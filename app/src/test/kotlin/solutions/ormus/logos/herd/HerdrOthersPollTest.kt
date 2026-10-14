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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.connectbot.transport.CommandOutput
import org.connectbot.ui.screens.herd.boardNeedsYou
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The reads that keep the Needs you count fresh for the Herdr sessions the monitor does not
 * poll, on the test scheduler's clock: a host with the default session and two others, whose
 * snapshots the test scripts.
 */
class HerdrOthersPollTest {
    private val ran = mutableListOf<String>()

    // Each session's agents, as `herdr api snapshot` prints them; the test changes them between ticks.
    private val agents = mutableMapOf(
        "default" to "",
        "alpha" to """{"pane_id":"w1:p1","agent":"claude","agent_status":"blocked"}""",
        "beta" to """{"pane_id":"w1:p1","agent":"codex","agent_status":"working"}""",
    )

    private fun run(command: String): CommandOutput {
        ran += command
        if (command == HerdrSessions.listCommand) {
            val sessions = agents.keys.joinToString(",") { """{"name":"$it","running":true}""" }
            return CommandOutput("""{"sessions":[$sessions]}""", "", 0)
        }
        val session = agents.keys.firstOrNull { HerdrSessions.snapshotCommand(it) == command }
            ?: return CommandOutput("""{"id":"cli","result":{}}""", "", 0)
        return CommandOutput("""{"result":{"snapshot":{"agents":[${agents.getValue(session)}]}}}""", "", 0)
    }

    private fun reads(session: String): Int = ran.count { it == HerdrSessions.snapshotCommand(session) }

    private fun TestScope.herd(): HerdSession = HerdSession("host", ::run, sendKeys = {}, scope = backgroundScope, io = coroutineContext[CoroutineDispatcher]!!)

    private fun HerdSession.needsYou(): Int = boardNeedsYou(snapshot.value, others.value, sessions.value)

    private fun TestScope.tick(ms: Long = HerdSession.OTHERS_POLL_MS) {
        testScheduler.advanceTimeBy(ms)
        testScheduler.runCurrent()
    }

    @Test
    fun quietPoll_readsEveryOtherSessionOncePerTick() = runTest {
        val herd = herd()
        herd.refreshSessions()
        ran.clear()
        backgroundScope.launch { herd.pollOthers(MutableStateFlow(true)) }

        tick(HerdSession.OTHERS_POLL_MS - 1)
        assertEquals("nothing before the first tick", emptyList<String>(), ran)
        tick(1)
        assertEquals(1, reads("alpha"))
        assertEquals(1, reads("beta"))
        tick()
        tick()
        assertEquals(3, reads("alpha"))
        assertEquals(3, reads("beta"))
        assertEquals("one exec per session per tick, nothing else", 6, ran.size)
        assertEquals("the default session is the monitor's", 0, reads("default"))
    }

    @Test
    fun quietPoll_leavesTheAttachedSessionToTheMonitor() = runTest {
        val herd = herd()
        herd.refreshSessions()
        herd.attach("alpha")
        ran.clear()
        backgroundScope.launch { herd.pollOthers(MutableStateFlow(true)) }

        tick()
        assertEquals(0, reads("alpha"))
        assertEquals(listOf(HerdrSessions.snapshotCommand("beta")), ran)
    }

    @Test
    fun quietPoll_needsYouDropsWithinOneTickOfTheAgentLeavingBlocked() = runTest {
        val herd = herd()
        herd.refreshSessions()
        backgroundScope.launch { herd.pollOthers(MutableStateFlow(true)) }

        tick()
        assertEquals(1, herd.needsYou())
        agents["alpha"] = """{"pane_id":"w1:p1","agent":"claude","agent_status":"working"}"""
        tick(HerdSession.OTHERS_POLL_MS - 1)
        assertEquals("still the last read", 1, herd.needsYou())
        tick(1)
        assertEquals(0, herd.needsYou())
    }

    @Test
    fun quietPoll_readsNothingInTheBackground() = runTest {
        val herd = herd()
        herd.refreshSessions()
        ran.clear()
        val foreground = MutableStateFlow(false)
        backgroundScope.launch { herd.pollOthers(foreground) }

        tick(10 * HerdSession.OTHERS_POLL_MS)
        assertEquals(emptyList<String>(), ran)

        foreground.value = true
        tick()
        assertEquals(2, ran.size)
        foreground.value = false
        tick(10 * HerdSession.OTHERS_POLL_MS)
        assertEquals("stops as the app leaves", 2, ran.size)
    }

    @Test
    fun boardPoll_readsEverySessionAtOnce_thenTheUnpolledOnesEveryBoardTick() = runTest {
        val herd = herd()
        backgroundScope.launch { herd.pollBoard() }

        testScheduler.runCurrent()
        assertEquals(1, ran.count { it == HerdrSessions.listCommand })
        assertEquals(1, reads("default"))
        assertEquals(1, reads("alpha"))
        assertEquals(1, reads("beta"))
        assertEquals(1, herd.needsYou())

        agents["alpha"] = ""
        tick(HerdSession.BOARD_POLL_MS)
        assertEquals(2, reads("alpha"))
        assertEquals(2, reads("beta"))
        assertEquals("the default is the monitor's", 1, reads("default"))
        assertEquals(0, herd.needsYou())
        assertEquals(12_000L, HerdSession.BOARD_POLL_MS)
    }
}
