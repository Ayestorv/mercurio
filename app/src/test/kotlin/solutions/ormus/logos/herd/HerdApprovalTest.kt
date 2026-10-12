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

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.connectbot.transport.CommandOutput
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import solutions.ormus.logos.herd.HerdrAgentStatus.BLOCKED
import solutions.ormus.logos.herd.HerdrAgentStatus.IDLE
import solutions.ormus.logos.herd.HerdrAgentStatus.WORKING

/** Approve and Deny from a "Needs you": which agents get them, and the still-blocked check before a key is sent. */
class HerdApprovalTest {
    private fun agent(kind: String?, status: HerdrAgentStatus = BLOCKED, pane: String = "w1:p1") = HerdrAgentInfo(paneId = pane, agent = kind, agentStatus = status)

    private fun snapshot(vararg agents: HerdrAgentInfo) = HerdrSessionSnapshot(agents = agents.toList())

    @Test
    fun offersAnswers_onlyForBlockedAgentsOfProvenKinds() {
        assertTrue(HerdApproval.offersAnswers(agent("claude")))
        assertTrue(HerdApproval.offersAnswers(agent("codex")))
        // Not proven: Enter and Esc may mean something else there.
        assertFalse(HerdApproval.offersAnswers(agent("grok")))
        assertFalse(HerdApproval.offersAnswers(agent("gemini")))
        assertFalse(HerdApproval.offersAnswers(agent(null)))
        // Not waiting on anyone.
        assertFalse(HerdApproval.offersAnswers(agent("claude", WORKING)))
        assertFalse(HerdApproval.offersAnswers(agent("claude", IDLE)))
    }

    @Test
    fun stillBlocked_onlyWhenThatPaneIsStillAnAnswerableBlockedAgent() {
        assertTrue(HerdApproval.stillBlocked(snapshot(agent("claude")), "w1:p1"))
        assertFalse("answered elsewhere", HerdApproval.stillBlocked(snapshot(agent("claude", WORKING)), "w1:p1"))
        assertFalse("pane closed", HerdApproval.stillBlocked(snapshot(agent("claude", pane = "w1:p2")), "w1:p1"))
        assertFalse("another kind took the pane", HerdApproval.stillBlocked(snapshot(agent("grok")), "w1:p1"))
    }

    @Test
    fun sendKeys_quotesThePaneAndRefusesOddIds() {
        assertEquals("bash -lc 'herdr pane send-keys '\\''w1:p1'\\'' enter'", HerdApproval.sendKeysCommand("w1:p1", HerdAnswer.APPROVE))
        assertEquals("bash -lc 'herdr pane send-keys '\\''w1:p1'\\'' esc'", HerdApproval.sendKeysCommand("w1:p1", HerdAnswer.DENY))
        assertTrue(runCatching { HerdApproval.sendKeysCommand("w1:p1; rm -rf ~", HerdAnswer.APPROVE) }.isFailure)
        assertEquals(
            "bash -lc 'herdr --session '\\''work'\\'' pane send-keys '\\''w1:p1'\\'' enter'",
            HerdApproval.sendKeysCommand("w1:p1", HerdAnswer.APPROVE, "work"),
        )
    }

    @Test
    fun tail_keepsThePromptWithoutRulesOrBlankLines() {
        // Claude Code's permission prompt as `herdr pane read` printed it on Sun (path shortened).
        val read = """
            ● Creating empty file approve-yes.txt
              ⎿  ${'$'} touch approve-yes.txt

            ────────────────────────────────────────
             Bash command

             Create empty file approve-yes.txt
            ╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌
             touch approve-yes.txt
            ╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌
             Do you want to proceed?
             ❯ 1. Yes
               2. Yes, and always allow access to scratch/ from this project
               3. No

             Esc to cancel · Tab to amend
        """.trimIndent()

        assertEquals(
            listOf(
                " touch approve-yes.txt",
                " Do you want to proceed?",
                " ❯ 1. Yes",
                "   2. Yes, and always allow access to scratch/ from this project",
                "   3. No",
                " Esc to cancel · Tab to amend",
            ),
            HerdApproval.tail(read, lines = 6)?.lines(),
        )
        assertNull(HerdApproval.tail("\n  \n────\n"))
    }

    // HerdSession.answer against a scripted host: every command is recorded; the
    // snapshot command answers with [agents], send-keys with [sendKeys].
    private class Host(var agents: String, val sendKeys: CommandOutput? = CommandOutput("", "", 0)) {
        val ran = mutableListOf<String>()

        fun run(command: String): CommandOutput? {
            ran += command
            return when {
                "api snapshot" in command -> if (agents.isEmpty()) null else CommandOutput("""{"result":{"snapshot":{"agents":[$agents]}}}""", "", 0)
                "send-keys" in command -> sendKeys
                else -> CommandOutput("", "", 0)
            }
        }
    }

    private fun TestScope.session(host: Host) = HerdSession(
        hostName = "Sun",
        runner = host::run,
        sendKeys = {},
        scope = backgroundScope,
        io = coroutineContext[CoroutineDispatcher]!!,
    )

    private val blockedClaude = """{"pane_id":"w1:p1","agent":"claude","agent_status":"blocked"}"""

    @Test
    fun answer_stillBlocked_sendsTheKey() = runTest {
        val host = Host(blockedClaude)

        assertEquals(HerdAnswerOutcome.Sent, session(host).answer("w1:p1", HerdAnswer.APPROVE))
        assertEquals(1, host.ran.count { "api snapshot" in it })
        assertEquals(HerdApproval.sendKeysCommand("w1:p1", HerdAnswer.APPROVE), host.ran.last())
    }

    @Test
    fun answer_inAnotherSession_checksAndSendsThere() = runTest {
        val host = Host(blockedClaude)

        assertEquals(HerdAnswerOutcome.Sent, session(host).answer("w1:p1", HerdAnswer.APPROVE, "work"))
        assertEquals(listOf(HerdrSessions.snapshotCommand("work"), HerdApproval.sendKeysCommand("w1:p1", HerdAnswer.APPROVE, "work")), host.ran)
    }

    @Test
    fun answer_noLongerBlocked_sendsNothing() = runTest {
        val host = Host("""{"pane_id":"w1:p1","agent":"claude","agent_status":"working"}""")

        assertEquals(HerdAnswerOutcome.NotBlocked, session(host).answer("w1:p1", HerdAnswer.DENY))
        assertTrue(host.ran.none { "send-keys" in it })
    }

    @Test
    fun answer_unprovenKind_sendsNothing() = runTest {
        val host = Host("""{"pane_id":"w1:p1","agent":"grok","agent_status":"blocked"}""")

        assertEquals(HerdAnswerOutcome.NotBlocked, session(host).answer("w1:p1", HerdAnswer.APPROVE))
        assertTrue(host.ran.none { "send-keys" in it })
    }

    @Test
    fun answer_snapshotFails_sendsNothingAndSaysWhy() = runTest {
        val host = Host("")

        val outcome = session(host).answer("w1:p1", HerdAnswer.APPROVE)
        assertEquals(HerdAnswerOutcome.Failed("No SSH session is up, so Mercurio could not run any Herdr command."), outcome)
        assertTrue(host.ran.none { "send-keys" in it })
    }

    @Test
    fun answer_sendKeysFails_reportsHerdrsError() = runTest {
        val error = CommandOutput("", """{"error":{"code":"pane_not_found","message":"pane w1:p1 not found"}}""", 1)
        val host = Host(blockedClaude, sendKeys = error)

        assertEquals(HerdAnswerOutcome.Failed("pane w1:p1 not found"), session(host).answer("w1:p1", HerdAnswer.APPROVE))
    }
}
