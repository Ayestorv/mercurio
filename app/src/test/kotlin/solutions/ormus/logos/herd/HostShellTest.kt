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

import org.connectbot.data.TailscaleStatus
import org.connectbot.transport.CommandOutput
import org.connectbot.ui.screens.chat.AgentChat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Exec commands on a Windows host: cmd.exe found from its answer, and the commands rewritten for it. */
class HostShellTest {

    // What cmd.exe printed on the first outside tester's laptop (Spanish Windows).
    private val noBashSpanish = CommandOutput(
        stdout = "",
        stderr = "\"bash\" no se reconoce como un comando interno o externo,\r\nprograma o archivo por lotes ejecutable.\r\n",
        exitCode = 1,
    )
    private val noBashEnglish = CommandOutput("", "'bash' is not recognized as an internal or external command,\r\noperable program or batch file.\r\n", 9009)
    private val ok = CommandOutput("{}", "", 0)

    @Test
    fun cmdSayingItHasNoBash_marksTheConnection_andTheCommandRunsAgainRewritten() {
        val shell = HostShell()
        val ran = mutableListOf<String>()
        val first = shell.run(HerdrApi.loginShell(HerdrApi.SNAPSHOT_CMD)) {
            ran += it
            if (it.startsWith("bash")) noBashSpanish else ok
        }

        assertEquals(ok, first)
        assertEquals(listOf("bash -lc 'herdr api snapshot'", "herdr api snapshot"), ran)
        assertTrue(shell.isCmd)

        // From then on, rewritten before the first try.
        ran.clear()
        shell.run(HerdrApi.loginShell("herdr tab focus ${HerdrApi.shQuote("w1:t2")}")) {
            ran += it
            ok
        }
        assertEquals(listOf("herdr tab focus w1:t2"), ran)
    }

    @Test
    fun theEnglishMessage_andCmdsNotFoundExitCode_areSeenToo() {
        assertTrue(HostShell.isCmdWithoutBash(noBashEnglish))
        assertTrue(HostShell.isCmdWithoutBash(CommandOutput("", "", 9009)))
    }

    @Test
    fun aPosixHost_staysAsItIs() {
        val shell = HostShell()
        // bash ran, herdr was missing: bash's own error, no quoted 'bash'.
        val missingHerdr = CommandOutput("", "bash: line 1: herdr: command not found\n", 127)
        val ran = mutableListOf<String>()
        assertEquals(
            missingHerdr,
            shell.run(HerdrApi.loginShell(HerdrApi.SNAPSHOT_CMD)) {
                ran += it
                missingHerdr
            },
        )
        assertEquals(1, ran.size)
        assertFalse(shell.isCmd)
        // Herdr's own failure, JSON on stderr.
        assertFalse(HostShell.isCmdWithoutBash(CommandOutput("", """{"error":{"code":"agent_not_found"}}""", 1)))
        // PowerShell quotes 'bash' too, but its quoting is not cmd.exe's.
        assertFalse(HostShell.isCmdWithoutBash(CommandOutput("", "bash : The term 'bash' is not recognized as the name of a cmdlet, function, script file", 1)))
    }

    @Test
    fun herdrsCommands_areRewrittenForCmd() {
        assertEquals("herdr api snapshot", HostShell.forCmd(HerdrApi.loginShell(HerdrApi.SNAPSHOT_CMD)))
        assertEquals(
            "herdr pane read w1:p1 --source recent-unwrapped --lines 1000",
            HostShell.forCmd(Herd.readCommand("w1:p1")),
        )
        assertEquals(
            "herdr --session work tab focus w1:t2 && herdr --session work agent focus w1:p3",
            HostShell.forCmd(HerdrApi.loginShell("herdr --session ${HerdrApi.shQuote("work")} tab focus ${HerdrApi.shQuote("w1:t2")} && herdr --session ${HerdrApi.shQuote("work")} agent focus ${HerdrApi.shQuote("w1:p3")}")),
        )
        assertEquals("tailscale status --json", HostShell.forCmd(TailscaleStatus.COMMAND))
    }

    @Test
    fun aChatReply_keepsItsText_andLosesThePauseCmdHasNoCommandFor() {
        assertEquals(
            "herdr pane send-text w1:p1 \"say \"\"hi\"\" to 100\"^%\"\" && herdr pane send-keys w1:p1 enter",
            HostShell.forCmd(AgentChat.sendCommand("w1:p1", "say \"hi\" to 100%")),
        )
        // A line break cannot ride on a cmd.exe command line.
        assertNull(HostShell.forCmd(AgentChat.sendCommand("w1:p1", "one\ntwo")))
    }

    @Test
    fun aCommandThatNeedsAPosixShell_isNotRewritten_andFailsSayingWhy() {
        assertNull(HostShell.forCmd(HerdrPrefix.readCommand))
        assertNull(HostShell.forCmd("\$HOME/.local/bin/herdr api snapshot"))
        assertNull(HostShell.forCmd("bash -lc 'herdr api snapshot | head'"))

        val shell = HostShell()
        shell.run(HerdrApi.loginShell(HerdrApi.SNAPSHOT_CMD)) { if (it.startsWith("bash")) noBashEnglish else ok }
        val ran = mutableListOf<String>()
        val out = shell.run(HerdrPrefix.readCommand) {
            ran += it
            ok
        }
        assertEquals(emptyList<String>(), ran)
        assertEquals(HostShell.UNSUPPORTED_EXIT, out.exitCode)
        assertEquals(HostShell.UNSUPPORTED, out.stderr)
    }

    @Test
    fun eachWord_readsBackWhole_throughCmdAndTheArgumentParser() {
        val words = listOf(
            "plain", "w1:p1", "", "two words", "say \"hi\"", "100%", "%PATH%", "50% off, 20% more",
            "a & b | c > d < e ^ f", "C:\\dir\\", "back\\\"slash", "trail\\", "\\\\server\\share", "!bang!",
            "it's", "tab\there", "ñandú «comillas»",
        )
        for (word in words) {
            val line = "herdr agent prompt w1:p1 " + HostShell.cmdWord(word)!!
            assertCmdSafe(word, line)
            assertEquals(word, listOf("herdr", "agent", "prompt", "w1:p1", word), argv(cmdLine(line)))
        }
    }

    /**
     * cmd.exe would run [line] as one command: every `& | < > ^` is inside quotes, and every
     * percent sign is caret-escaped outside them, so none can start a variable.
     */
    private fun assertCmdSafe(word: String, line: String) {
        var quoted = false
        line.forEachIndexed { i, c ->
            when {
                c == '"' -> quoted = !quoted
                c == '%' -> assertTrue("$word: % at $i in $line", !quoted && i > 0 && line[i - 1] == '^')
                c in "&|<>" -> assertTrue("$word: $c at $i outside quotes in $line", quoted)
            }
        }
        assertFalse("$word: quotes left open in $line", quoted)
    }

    // cmd.exe hands a program its command line with each caret outside double quotes taken
    // off and the character after it kept; inside them every character stays.
    private fun cmdLine(line: String): String {
        val out = StringBuilder()
        var quoted = false
        var i = 0
        while (i < line.length) {
            val c = line[i]
            when {
                c == '"' -> {
                    quoted = !quoted
                    out.append(c)
                }

                c == '^' && !quoted && i + 1 < line.length -> out.append(line[++i])

                else -> out.append(c)
            }
            i++
        }
        return out.toString()
    }

    // The Microsoft C runtime's argument parser, which Rust's (Herdr's) follows: backslashes
    // count only before a quote, and "" inside quotes is one quote.
    private fun argv(line: String): List<String> {
        val args = mutableListOf<String>()
        val cur = StringBuilder()
        var inArg = false
        var quoted = false
        var i = 0
        while (i < line.length) {
            val c = line[i]
            when {
                c == '\\' -> {
                    var n = 0
                    while (i < line.length && line[i] == '\\') {
                        n++
                        i++
                    }
                    if (i < line.length && line[i] == '"') {
                        cur.append("\\".repeat(n / 2))
                        if (n % 2 == 1) {
                            cur.append('"')
                            i++
                        }
                    } else {
                        cur.append("\\".repeat(n))
                    }
                    inArg = true
                    continue
                }

                c == '"' -> {
                    if (quoted && i + 1 < line.length && line[i + 1] == '"') {
                        cur.append('"')
                        i++
                    } else {
                        quoted = !quoted
                    }
                    inArg = true
                }

                (c == ' ' || c == '\t') && !quoted -> if (inArg) {
                    args += cur.toString()
                    cur.setLength(0)
                    inArg = false
                }

                else -> {
                    cur.append(c)
                    inArg = true
                }
            }
            i++
        }
        if (inArg) args += cur.toString()
        return args
    }
}
