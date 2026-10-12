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

package solutions.ormus.logos.devtools

import org.connectbot.transport.CommandOutput
import solutions.ormus.logos.herd.HerdrApi

/** Why a dev tool (diff, files) could not read the host. */
enum class HostProblem { NOT_CONNECTED, NO_DIR, NOT_A_REPO, NO_GIT, FAILED }

/** What a dev tool read from the host: the parsed [value], or why not. */
sealed interface HostRead<out T> {
    /** [truncated] when the output passed the size cap and was cut. */
    data class Ok<T>(val value: T, val truncated: Boolean = false) : HostRead<T>

    data class Problem(val reason: HostProblem, val detail: String = "") : HostRead<Nothing>
}

/**
 * The shell side of the dev tools. Each read is one `sh -c` script over an exec channel
 * beside the PTY (nothing is installed on the host): it moves into the directory, checks
 * for git when it needs it, and caps its output with `head -c`, so a huge diff or file
 * never crosses the connection whole. An end marker after the output tells a cut from
 * a whole read.
 */
object HostScript {
    const val END = "#mercurio-end"

    private const val EXIT_NO_DIR = 2
    private const val EXIT_NOT_A_REPO = 3
    private const val EXIT_NO_GIT = 4

    /** Run [script] under sh, whatever the login shell is. */
    fun wrap(script: String): String = "sh -c ${HerdrApi.shQuote(script)}"

    /** Move into [dir]; null stays in the exec channel's start directory (home). */
    fun cd(dir: String?): String = if (dir == null) "" else "cd -- ${HerdrApi.shQuote(dir)} 2>/dev/null || exit $EXIT_NO_DIR\n"

    /** Stop unless git is there and this is a work tree; then move to its top level. */
    const val GIT_ROOT = "command -v git >/dev/null 2>&1 || exit $EXIT_NO_GIT\n" +
        "top=\$(git rev-parse --show-toplevel 2>/dev/null) || exit $EXIT_NOT_A_REPO\n" +
        "cd -- \"\$top\" || exit $EXIT_NOT_A_REPO\n" +
        // Reading must not take the index lock an agent's git may want.
        "export GIT_OPTIONAL_LOCKS=0\n"

    /** [body]'s output, then the end marker, cut at [cap] bytes. */
    fun capped(body: String, cap: Int): String = "{ $body\nprintf '\\n%s\\n' '$END'; } | head -c $cap"

    /** Turn what the script printed into [HostRead]: its exit code, then [parse] on the text before the marker. */
    fun <T> read(out: CommandOutput?, parse: (text: String, truncated: Boolean) -> T): HostRead<T> {
        if (out == null) return HostRead.Problem(HostProblem.NOT_CONNECTED)
        return when (out.exitCode) {
            null, 0 -> {
                val suffix = "\n$END\n"
                val whole = out.stdout.endsWith(suffix)
                val text = if (whole) out.stdout.removeSuffix(suffix) else out.stdout
                HostRead.Ok(parse(text, !whole), truncated = !whole)
            }

            EXIT_NO_DIR -> HostRead.Problem(HostProblem.NO_DIR)

            EXIT_NOT_A_REPO -> HostRead.Problem(HostProblem.NOT_A_REPO)

            EXIT_NO_GIT -> HostRead.Problem(HostProblem.NO_GIT)

            else -> HostRead.Problem(HostProblem.FAILED, firstLine(out.stderr).ifEmpty { "exit ${out.exitCode}" })
        }
    }

    /** Run [command] with [runner], a dropped connection or a timeout read as a failure. */
    fun run(runner: (String) -> CommandOutput?, command: String): CommandOutput? = try {
        runner(command)
    } catch (e: Exception) {
        CommandOutput(stdout = "", stderr = e.message ?: e.toString(), exitCode = -1)
    }

    private fun firstLine(text: String): String = text.lineSequence().firstOrNull { it.isNotBlank() }?.trim()?.take(160).orEmpty()
}
