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

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.connectbot.data.entity.Host
import org.connectbot.transport.CommandOutput
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import solutions.ormus.logos.herd.HerdAttention.Notice
import solutions.ormus.logos.herd.HerdrAgentStatus.BLOCKED
import solutions.ormus.logos.herd.HerdrAgentStatus.DONE
import solutions.ormus.logos.herd.HerdrAgentStatus.IDLE
import solutions.ormus.logos.herd.HerdrAgentStatus.WORKING

/**
 * The notification rules for a Herdr session (Menu atom herd-notify), with a
 * fake clock and scripted snapshots.
 */
class HerdAttentionTest {
    private var now = 0L
    private val attention = HerdAttention(clock = { now })

    /** A snapshot of one workspace (~Demo) with agents in tabs 1 and 2; [focused] has focus. */
    private fun snapshot(vararg agents: Pair<String, HerdrAgentStatus>, focused: String = "w1:p9"): HerdrSessionSnapshot = HerdrSessionSnapshot(
        workspaces = listOf(HerdrWorkspaceInfo(workspaceId = "w1", label = "~Demo", number = 1)),
        tabs = listOf(
            HerdrTabInfo(tabId = "w1:t1", workspaceId = "w1", label = "1", number = 1),
            HerdrTabInfo(tabId = "w1:t2", workspaceId = "w1", label = "2", number = 2),
        ),
        agents = agents.map { (pane, status) ->
            HerdrAgentInfo(
                paneId = pane,
                workspaceId = "w1",
                tabId = if (pane == "w1:p1") "w1:t1" else "w1:t2",
                agent = "claude",
                agentStatus = status,
                terminalTitleStripped = "Docs site build - claude",
            )
        },
        focusedWorkspaceId = "w1",
        focusedTabId = "w1:t1",
        focusedPaneId = focused,
    )

    /** Feed one poll at [atMs]. */
    private fun poll(atMs: Long, snapshot: HerdrSessionSnapshot, onScreen: Boolean = false): List<Notice> {
        now = atMs
        return attention.onSnapshot(snapshot, onScreen)
    }

    @Test
    fun firstSnapshotAfterConnect_postsNothing() {
        assertTrue(poll(0, snapshot("w1:p1" to BLOCKED, "w1:p2" to DONE)).isEmpty())
        // Still blocked long after: it never changed, so it is not news.
        assertTrue(poll(10_000, snapshot("w1:p1" to BLOCKED, "w1:p2" to DONE)).isEmpty())

        // A reconnect starts over.
        poll(20_000, snapshot("w1:p1" to WORKING))
        attention.reset()
        assertTrue(poll(30_000, snapshot("w1:p1" to DONE)).isEmpty())
    }

    @Test
    fun workingToBlockedHeldFiveSeconds_postsOneNeedsYouForThatPane() {
        poll(0, snapshot("w1:p1" to WORKING))

        assertTrue("just blocked", poll(4_000, snapshot("w1:p1" to BLOCKED)).isEmpty())
        assertTrue("4 s in", poll(8_000, snapshot("w1:p1" to BLOCKED)).isEmpty())
        val notices = poll(9_000, snapshot("w1:p1" to BLOCKED))

        val needsYou = notices.single() as Notice.NeedsYou
        assertEquals("w1:p1", needsYou.agent.paneId)
        assertEquals("claude", needsYou.agent.agent)
        assertEquals("Docs site build - claude", needsYou.agent.title())
        assertEquals("~Demo", needsYou.workspaceLabel)
        assertEquals("1", needsYou.tabLabel)
        // One, not one per poll.
        assertTrue(poll(13_000, snapshot("w1:p1" to BLOCKED)).isEmpty())
        assertTrue(poll(70_000, snapshot("w1:p1" to BLOCKED)).isEmpty())
    }

    @Test
    fun momentaryBlocked_postsNothing() {
        poll(0, snapshot("w1:p1" to WORKING))
        poll(4_000, snapshot("w1:p1" to BLOCKED))

        assertTrue(poll(8_000, snapshot("w1:p1" to WORKING)).isEmpty())
        assertTrue(poll(30_000, snapshot("w1:p1" to WORKING)).isEmpty())
    }

    @Test
    fun blockedToDone_postsOneFinished() {
        poll(0, snapshot("w1:p1" to BLOCKED))

        val finished = poll(4_000, snapshot("w1:p1" to DONE)).single() as Notice.Finished
        assertEquals("w1:p1", finished.agent.paneId)
        assertTrue(poll(8_000, snapshot("w1:p1" to DONE)).isEmpty())
    }

    @Test
    fun workingToDone_alsoPostsFinished_idleToDoneDoesNot() {
        poll(0, snapshot("w1:p1" to WORKING, "w1:p2" to IDLE))

        val notices = poll(4_000, snapshot("w1:p1" to DONE, "w1:p2" to DONE))
        assertEquals(listOf("w1:p1"), notices.map { it.agent.paneId })
    }

    @Test
    fun secondBlockedWithinSixtySeconds_postsNothing() {
        poll(0, snapshot("w1:p1" to WORKING))
        poll(1_000, snapshot("w1:p1" to BLOCKED))
        assertEquals(1, poll(6_000, snapshot("w1:p1" to BLOCKED)).size) // the first "Needs you", at 6 s

        poll(10_000, snapshot("w1:p1" to WORKING))
        poll(20_000, snapshot("w1:p1" to BLOCKED))
        assertTrue("second block, 24 s after the first notice", poll(30_000, snapshot("w1:p1" to BLOCKED)).isEmpty())

        // Past the window, a new block is news again.
        poll(70_000, snapshot("w1:p1" to WORKING))
        poll(80_000, snapshot("w1:p1" to BLOCKED))
        assertEquals(1, poll(86_000, snapshot("w1:p1" to BLOCKED)).size)
    }

    @Test
    fun answeredFromTheNotification_nextBlockIsNewsInsideTheWindow() {
        poll(0, snapshot("w1:p1" to WORKING))
        poll(1_000, snapshot("w1:p1" to BLOCKED))
        assertEquals(1, poll(6_000, snapshot("w1:p1" to BLOCKED)).size)

        // Approve sent Enter; the agent runs, then asks again.
        attention.answered("w1:p1")
        poll(10_000, snapshot("w1:p1" to WORKING))
        poll(20_000, snapshot("w1:p1" to BLOCKED))
        assertEquals(1, poll(26_000, snapshot("w1:p1" to BLOCKED)).size)
    }

    @Test
    fun otherPanesAreIndependent() {
        poll(0, snapshot("w1:p1" to WORKING, "w1:p2" to WORKING))
        poll(1_000, snapshot("w1:p1" to BLOCKED, "w1:p2" to BLOCKED))

        val notices = poll(6_000, snapshot("w1:p1" to BLOCKED, "w1:p2" to BLOCKED))
        assertEquals(listOf("w1:p1", "w1:p2"), notices.map { it.agent.paneId })
        assertEquals("2", notices[1].tabLabel)
    }

    @Test
    fun agentAlreadyOnScreen_postsNothing() {
        poll(0, snapshot("w1:p1" to WORKING, focused = "w1:p1"))
        poll(1_000, snapshot("w1:p1" to BLOCKED, focused = "w1:p1"), onScreen = true)
        assertTrue(poll(6_000, snapshot("w1:p1" to BLOCKED, focused = "w1:p1"), onScreen = true).isEmpty())
        assertTrue(poll(9_000, snapshot("w1:p1" to DONE, focused = "w1:p1"), onScreen = true).isEmpty())
    }

    @Test
    fun sessionOnScreenButAnotherPaneFocused_stillPosts() {
        poll(0, snapshot("w1:p1" to WORKING, focused = "w1:p2"))
        poll(1_000, snapshot("w1:p1" to BLOCKED, focused = "w1:p2"), onScreen = true)
        assertEquals(1, poll(6_000, snapshot("w1:p1" to BLOCKED, focused = "w1:p2"), onScreen = true).size)
    }

    @Test
    fun bellAndIdle_postNothingForAHerdrSession_butStillForOtherHosts() {
        val herdrHost = Host(nickname = "Sun", postLogin = "herdr\n")
        val plainHost = Host(nickname = "NAS", postLogin = null)

        assertFalse(HerdAttention.bellMayNotify(herdrHost, away = true))
        assertFalse(HerdAttention.bellMayNotify(herdrHost, away = false))
        assertTrue(HerdAttention.bellMayNotify(plainHost, away = true))
        assertFalse(HerdAttention.bellMayNotify(plainHost, away = false))
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun session_postsFromPolledSnapshots() = runTest {
        // The same rules wired through HerdSession's poller on virtual time.
        val script = ArrayDeque(
            listOf(
                snapshot("w1:p1" to WORKING),
                // Four polls 2.5 s +/- 20% apart: blocked for at least 6 s.
                snapshot("w1:p1" to BLOCKED),
                snapshot("w1:p1" to BLOCKED),
                snapshot("w1:p1" to BLOCKED),
                snapshot("w1:p1" to BLOCKED),
                snapshot("w1:p1" to DONE),
            ),
        )
        var last = script.first()
        val posted = mutableListOf<Notice>()
        val session = HerdSession(
            hostName = "Sun",
            runner = { command ->
                // start() reads the prefix and lists the sessions and machines too: not scripted polls.
                if (command == HerdrPrefix.readCommand || command == HerdrSessions.listCommand || command == HerdrMachines.listCommand) {
                    CommandOutput("", "", 0)
                } else if ("pane read" in command) {
                    CommandOutput("Do you want to proceed?\n\u276F 1. Yes\n  2. No\n", "", 0)
                } else {
                    last = script.removeFirstOrNull() ?: last
                    CommandOutput(snapshotJson(last), "", 0)
                }
            },
            sendKeys = {},
            scope = backgroundScope,
            io = coroutineContext[kotlinx.coroutines.CoroutineDispatcher]!!,
            pollIntervalMs = 2_500,
            clock = { testScheduler.currentTime },
            onNotices = { posted += it },
        )
        session.start()
        testScheduler.advanceTimeBy(20_000)
        session.stop()

        assertEquals(listOf("NeedsYou", "Finished"), posted.map { it::class.simpleName })
        // The "Needs you" carries the end of the pane: what Approve would answer.
        assertEquals("Do you want to proceed?\n\u276F 1. Yes\n  2. No", (posted[0] as Notice.NeedsYou).paneTail)
    }

    private fun snapshotJson(s: HerdrSessionSnapshot): String {
        val agents = s.agents.joinToString(",") {
            """{"pane_id":"${it.paneId}","workspace_id":"w1","tab_id":"${it.tabId}","agent":"claude","agent_status":"${it.agentStatus.name.lowercase()}"}"""
        }
        return """{"id":"cli:api:snapshot","result":{"snapshot":{"agents":[$agents],"workspaces":[{"workspace_id":"w1","label":"~Demo"}],""" +
            """"tabs":[{"tab_id":"w1:t1","label":"1"}],"focused_pane_id":"w1:p9"}}}"""
    }
}
