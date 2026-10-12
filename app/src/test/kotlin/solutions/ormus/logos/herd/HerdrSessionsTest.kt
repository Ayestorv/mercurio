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
import kotlinx.coroutines.test.runTest
import org.connectbot.transport.CommandOutput
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Finding a host's Herdr sessions and acting in one. session-list.json has the shape of
 * `herdr session list --json` on Sun (herdr 0.9.0), with generic names and paths.
 */
class HerdrSessionsTest {
    @Test
    fun parse_runningSessionsOnly_defaultFirst() {
        assertEquals(listOf("default", "work"), HerdrSessions.parse(herdrFixture("session-list.json")))
        assertNull("not the list", HerdrSessions.parse("error: unknown flag --json"))
        assertEquals(emptyList<String>(), HerdrSessions.parse("""{"sessions":[]}"""))
        // A name the shell could misread is dropped, not run.
        assertEquals(listOf("default"), HerdrSessions.parse("""{"sessions":[{"name":"default","running":true},{"name":"a b","running":true}]}"""))
    }

    @Test
    fun commands_reachANamedSessionWithTheSessionFlag() {
        assertEquals(Herd.snapshotCommand, HerdrSessions.snapshotCommand("default"))
        assertEquals("bash -lc 'herdr --session '\\''work'\\'' api snapshot'", HerdrSessions.snapshotCommand("work"))
        assertEquals(
            HerdrInvocation.Cli("bash -lc 'herdr --session '\\''work'\\'' tab focus '\\''w1:t2'\\'''"),
            HerdrSessions.commands("work").build(HerdrAction.FocusTab("w1:t2"), null),
        )
        assertEquals(HerdrCommands().build(HerdrAction.FocusTab("w1:t2"), null), HerdrSessions.commands("default").build(HerdrAction.FocusTab("w1:t2"), null))
        assertTrue(runCatching { HerdrSessions.snapshotCommand("x'; rm -rf ~") }.isFailure)
    }

    // A host with the default session (sun-mixed) and "work" (picker-test); every command is recorded.
    private val ran = mutableListOf<String>()

    private fun run(command: String): CommandOutput {
        ran += command
        return when (command) {
            HerdrSessions.listCommand -> CommandOutput(herdrFixture("session-list.json"), "", 0)
            Herd.snapshotCommand -> CommandOutput(herdrFixture("snapshot-sun-mixed.json"), "", 0)
            HerdrSessions.snapshotCommand("work") -> CommandOutput(herdrFixture("snapshot-picker-test.json"), "", 0)
            else -> CommandOutput("""{"id":"cli","result":{}}""", "", 0)
        }
    }

    @Test
    fun session_listsSessions_andActsInTheOneAsked() = runTest {
        val herd = HerdSession("Sun", ::run, sendKeys = {}, scope = backgroundScope, io = coroutineContext[CoroutineDispatcher]!!)
        assertEquals(listOf("default"), herd.sessions.value)

        herd.refreshSessions()
        assertEquals(listOf("default", "work"), herd.sessions.value)
        herd.refresh("work")
        assertEquals(listOf("tmp", "beta", "gamma"), herd.snapshotOf("work")?.workspaces?.map { it.label })
        assertNull("the default's snapshot is the monitor's", herd.snapshotOf("default"))

        // A focused-id action in "work" targets work's focused tab, with work's flag.
        herd.perform(HerdrAction.CloseTab, session = "work")
        testScheduler.runCurrent()
        val close = ran.single { "tab close" in it }
        assertEquals(HerdrSessions.commands("work").build(HerdrAction.CloseTab, herd.snapshotOf("work")), HerdrInvocation.Cli(close))
        assertEquals("success refreshes that session", HerdrSessions.snapshotCommand("work"), ran.last())
    }

    @Test
    fun session_attachingAnotherSessionPollsItWithTheDefault() = runTest {
        val herd = HerdSession(
            "Sun",
            ::run,
            sendKeys = {},
            scope = backgroundScope,
            io = coroutineContext[CoroutineDispatcher]!!,
            pollIntervalMs = 1_000,
        )
        herd.attach("work")
        assertEquals("work", herd.chosen.value)
        herd.start()
        testScheduler.advanceTimeBy(500)
        herd.stop()

        assertEquals(listOf("tmp", "beta", "gamma"), herd.snapshotOf("work")?.workspaces?.map { it.label })
        assertEquals(4, herd.snapshot.value?.workspaces?.size)
    }

    @Test
    fun session_aChosenSessionThatStopsFallsBackToTheDefault() = runTest {
        val herd = HerdSession("Sun", ::run, sendKeys = {}, scope = backgroundScope, io = coroutineContext[CoroutineDispatcher]!!)
        herd.choose("gone")
        herd.refreshSessions()
        assertEquals("default", herd.chosen.value)
        herd.choose("work")
        herd.refreshSessions()
        assertEquals("work", herd.chosen.value)
    }
}
