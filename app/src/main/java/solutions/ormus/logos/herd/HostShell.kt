/*
 * ConnectBot: simple, powerful, open-source SSH client for Android
 * Copyright 2026 Kenny Root
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package solutions.ormus.logos.herd

import org.connectbot.transport.CommandOutput

/**
 * Runs exec-channel commands on a host whose shell may turn out to be Windows cmd.exe.
 *
 * Mercurio writes its commands for a POSIX shell: `bash -lc '<command>'` ([HerdrApi.loginShell])
 * with each value in single quotes ([HerdrApi.shQuote]). Windows OpenSSH runs an exec command
 * through cmd.exe, which has no bash and reads single quotes as text, so every Herdr action
 * failed there with "'bash' is not recognized as an internal or external command". The first
 * `bash -lc` that cmd.exe answers that way marks the connection, and from then on every command
 * is rewritten for cmd.exe ([forCmd]) before it runs. A command that needs a POSIX shell (a
 * script, a variable, a pipe) cannot be rewritten and fails with [UNSUPPORTED].
 *
 * One per connection: the shell is the host's, and a new connection may be to another host.
 */
class HostShell {
    @Volatile
    private var cmd = false

    /** Whether this connection's commands run through cmd.exe. */
    val isCmd: Boolean
        get() = cmd

    /** Run [command] through [exec], rewritten for cmd.exe once the host turned out to have it. */
    fun run(command: String, exec: (String) -> CommandOutput): CommandOutput {
        if (cmd) return runOnCmd(command, exec)
        val out = exec(command)
        if (!command.startsWith(LOGIN_SHELL) || !isCmdWithoutBash(out)) return out
        cmd = true
        return runOnCmd(command, exec)
    }

    private fun runOnCmd(command: String, exec: (String) -> CommandOutput): CommandOutput {
        val rewritten = forCmd(command) ?: return CommandOutput(stdout = "", stderr = UNSUPPORTED, exitCode = UNSUPPORTED_EXIT)
        return exec(rewritten)
    }

    companion object {
        private const val LOGIN_SHELL = "bash -lc "

        /** What a command gets on cmd.exe when it needs a POSIX shell. */
        const val UNSUPPORTED = "Mercurio: this command needs a POSIX shell (bash), and this host runs Windows cmd.exe"
        const val UNSUPPORTED_EXIT = 127

        // cmd.exe's exit code for a command it cannot find.
        private const val CMD_NOT_FOUND = 9009

        // "'bash' is not recognized" in English, "\"bash\" no se reconoce" in Spanish: the
        // word bash in quotes of some kind, which bash's own errors ("bash: line 1: ...") never have.
        private val QUOTED_BASH = Regex("""['"‘’“”«»]\s?bash\s?['"‘’“”«»]""")

        // A value cmd.exe passes as it is: no space, quote, percent, caret or redirection.
        private val PLAIN = Regex("""[A-Za-z0-9_.:/@+-]+""")

        /**
         * Whether [out] is cmd.exe saying it has no bash. PowerShell's message also quotes
         * 'bash' but names a cmdlet; its quoting differs from cmd.exe's, so it is left alone.
         */
        fun isCmdWithoutBash(out: CommandOutput): Boolean {
            val exit = out.exitCode ?: return false
            if (exit == 0 || out.stdout.isNotBlank() || out.stderr.contains("cmdlet", ignoreCase = true)) return false
            return exit == CMD_NOT_FOUND || QUOTED_BASH.containsMatchIn(out.stderr)
        }

        /**
         * [command] for cmd.exe, or null when it needs a POSIX shell. `bash -lc` comes off,
         * the words are split the POSIX way, `sleep` steps are dropped (cmd.exe has none, and
         * starting the next process takes longer than the pause did), and each word is quoted
         * for cmd.exe ([cmdWord]). `&&` means the same in both.
         */
        fun forCmd(command: String): String? {
            val inner = if (command.startsWith(LOGIN_SHELL)) {
                PosixWords.split(command.removePrefix(LOGIN_SHELL))?.singleOrNull() ?: return null
            } else {
                command
            }
            val words = PosixWords.split(inner) ?: return null
            val steps = mutableListOf(mutableListOf<String>())
            for (word in words) {
                if (word == PosixWords.AND) steps += mutableListOf<String>() else steps.last() += word
            }
            if (steps.any { it.isEmpty() }) return null
            val lines = mutableListOf<String>()
            for (step in steps.filterNot { it.first() == "sleep" }) {
                lines += step.map { cmdWord(it) ?: return null }.joinToString(" ")
            }
            return lines.joinToString(" && ")
        }

        /**
         * [word] as one argument on a cmd.exe command line, as a program's argument parser
         * (the Microsoft C runtime's rules, which Rust's follow) reads it back; null for a line
         * break, which a cmd.exe command line cannot hold.
         *
         * Inside double quotes cmd.exe leaves `& | < > ^` alone. A quote inside is doubled
         * (`""`), which keeps cmd.exe's own quote count even and reads back as one quote; a
         * percent sign is written `"^%"` (close the quotes, an escaped percent, reopen) so
         * cmd.exe cannot expand it as a variable. Backslashes count only before a quote, so
         * those are doubled.
         */
        fun cmdWord(word: String): String? {
            if (PLAIN.matches(word)) return word
            if (word.any { it == '\n' || it == '\r' }) return null
            val out = StringBuilder("\"")
            var backslashes = 0
            for (c in word) {
                when (c) {
                    '\\' -> {
                        backslashes++
                        continue
                    }

                    '"' -> out.append("\\".repeat(backslashes * 2)).append("\"\"")

                    '%' -> out.append("\\".repeat(backslashes * 2)).append("\"^%\"")

                    else -> out.append("\\".repeat(backslashes)).append(c)
                }
                backslashes = 0
            }
            return out.append("\\".repeat(backslashes * 2)).append('"').toString()
        }
    }
}

/**
 * Splits a command into words the way a POSIX shell does for the commands Mercurio writes:
 * single quotes, double quotes without expansions, backslash escapes, and `&&` between steps.
 * Anything that needs the shell itself (a variable, a substitution, a pipe, a redirection,
 * `;`, a glob) gives null.
 */
internal object PosixWords {
    /** The `&&` between two steps, as its own word. */
    const val AND = "\u0000&&"

    fun split(command: String): List<String>? {
        val words = mutableListOf<String>()
        val word = StringBuilder()
        var inWord = false
        var i = 0
        while (i < command.length) {
            val c = command[i]
            when {
                c == ' ' || c == '\t' -> {
                    if (inWord) words += word.toString()
                    word.setLength(0)
                    inWord = false
                }

                c == '\'' -> {
                    val end = command.indexOf('\'', i + 1)
                    if (end < 0) return null
                    word.append(command, i + 1, end)
                    inWord = true
                    i = end
                }

                c == '"' -> {
                    i++
                    while (i < command.length && command[i] != '"') {
                        val d = command[i]
                        if (d == '$' || d == '`') return null
                        if (d == '\\' && i + 1 < command.length && command[i + 1] in "\"\\$`") i++
                        word.append(command[i])
                        i++
                    }
                    if (i >= command.length) return null
                    inWord = true
                }

                c == '\\' -> {
                    if (i + 1 >= command.length) return null
                    word.append(command[++i])
                    inWord = true
                }

                c == '&' && command.startsWith("&&", i) && !inWord -> {
                    words += AND
                    i++
                }

                c in "\$`|;<>&()*?[]{}~#\n" -> return null

                else -> {
                    word.append(c)
                    inWord = true
                }
            }
            i++
        }
        if (inWord) words += word.toString()
        return words
    }
}
