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

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The Jump to list over every session, its search, and the switch to another session. */
class HerdrJumpTest {
    private fun snapshot(name: String): HerdrSessionSnapshot = checkNotNull(parseHerdrSnapshot(herdrFixture(name)))

    // The default session (sun-mixed: seven tabs in w1, five more across w3 to w5) and "work"
    // (picker-test: three workspaces, one tab each, no agents).
    private val targets = HerdrJump.targets(mapOf("work" to snapshot("snapshot-picker-test.json"), "default" to snapshot("snapshot-sun-mixed.json")))

    @Test
    fun targets_everyTabOfEverySession_blockedThenWorkingThenDoneThenTheRest() {
        assertEquals(15, targets.size)
        assertEquals(
            listOf("w1:tC", "w1:tJ", "w1:tB", "w1:tK", "w1:tH"),
            targets.take(5).map { it.tabId },
        )
        assertEquals(
            listOf(HerdrAgentStatus.BLOCKED, HerdrAgentStatus.BLOCKED, HerdrAgentStatus.WORKING, HerdrAgentStatus.WORKING, HerdrAgentStatus.DONE),
            targets.take(5).map { it.state },
        )
        // The rest: the default session's tabs by workspace and tab number, then work's.
        assertEquals(listOf("default", "work", "work", "work"), targets.drop(11).map { it.session })
        assertEquals(listOf("TMPTAB", "BETATAB", "GAMMATAB"), targets.takeLast(3).map { it.tabLabel })
    }

    @Test
    fun targets_carryTheTabsAgentWorkspaceAndFocus() {
        val blocked = targets.first()
        assertEquals("grok", blocked.agent)
        assertEquals("~Demo", blocked.workspaceLabel)
        assertEquals("default", blocked.session)
        assertFalse(blocked.focused)
        assertEquals("w1:pC", blocked.paneId)
        assertEquals("Lint setup, two config files for the CI - grok", blocked.title)
        assertEquals("alice", blocked.folder)
        val shell = targets.single { it.session == "default" && it.tabId == "w1:t1" }
        assertNull("a tab without an agent", shell.agent)
        assertTrue(shell.focused)
        assertTrue(targets.single { it.session == "work" && it.tabId == "w1:t1" }.focused)
    }

    @Test
    fun filter_everyWordInTheTitleFolderTabWorkspaceSessionOrAgent_ignoringCase() {
        assertEquals(targets, HerdrJump.filter(targets, "  "))
        assertEquals(listOf("TMPTAB", "BETATAB", "GAMMATAB"), HerdrJump.filter(targets, "WORK").map { it.tabLabel })
        assertEquals(listOf("GAMMATAB"), HerdrJump.filter(targets, "work gam").map { it.tabLabel })
        assertEquals(6, HerdrJump.filter(targets, "grok").size)
        // The pane's title (what the agent works on) and its folder, which the tab label lacks here.
        assertEquals(listOf("w1:pC"), HerdrJump.filter(targets, "lint CI").map { it.paneId })
        assertEquals(listOf("w1:pJ"), HerdrJump.filter(targets, "flaky").map { it.paneId })
        assertEquals(3, HerdrJump.filter(targets, "tmp").size)
        assertEquals(emptyList<JumpTarget>(), HerdrJump.filter(targets, "nothing-like-it"))
    }

    @Test
    fun targets_aSplitTabIsOneRowPerPane_eachWithItsOwnAgentAndState() {
        val snapshot = snapshot("snapshot-sun-mixed.json")
        val second = snapshot.panes.single { it.paneId == "w1:pF" }.copy(paneId = "w1:pF2", tabId = "w1:tC", agent = "claude", agentStatus = HerdrAgentStatus.WORKING, terminalTitle = "Second pane", terminalTitleStripped = "Second pane", focused = false)
        val split = HerdrJump.targets(mapOf("default" to snapshot.copy(panes = snapshot.panes + second)))
        assertEquals(13, split.size)
        val rows = split.filter { it.tabId == "w1:tC" }
        assertEquals(listOf("w1:pC", "w1:pF2"), rows.map { it.paneId })
        assertEquals(listOf("grok" to HerdrAgentStatus.BLOCKED, "claude" to HerdrAgentStatus.WORKING), rows.map { it.agent to it.state })
        assertEquals("Second pane", rows[1].titleLine)
        assertNull("the label already carries the title", split.single { it.paneId == "w1:pC" }.copy(tabLabel = "3 · Lint setup, two config files for the CI - grok").titleLine)
    }

    @Test
    fun canReattach_notWhenThePostLoginExecsHerdr() {
        assertTrue(HerdrJump.canReattach("herdr\n"))
        assertTrue(HerdrJump.canReattach(null))
        assertFalse(HerdrJump.canReattach("exec herdr"))
        assertFalse(HerdrJump.canReattach("cd ~/work && exec ~/.local/bin/herdr\n"))
    }

    // What the client wrote as it detached, recorded in a pty (herdr 0.9.0), then the prompt.
    private val detached = "\u001b[?1003l\u001b[?1002l\u001b[?1000l\u001b[?1006l\u001b[?1015l\u001b[?1003l\u001b[?1002l\u001b[?1000l" +
        "\u001b[?1049l\u001b[?2031l\u001b[?25h\u001b[0 q\u001b[?2004hbash-5.3$ "

    @Test
    fun reattach_namesTheSessionOnlyOnceTheClientLeft_evenSplitAcrossReads() {
        val reattach = HerdrReattach()
        assertNull("nothing expected", reattach.onOutput(detached))

        reattach.expect("work")
        assertNull("still the client's frames", reattach.onOutput("\u001b[2J\u001b[1;1Hherdr frame"))
        val cut = detached.indexOf("\u001b[?1049l") + 4
        assertNull(reattach.onOutput(detached.substring(0, cut)))
        assertEquals("work", reattach.onOutput(detached.substring(cut)))
        assertNull("once", reattach.onOutput(detached))
    }

    @Test
    fun reattach_aGivenUpJumpTypesNothingLater() {
        val reattach = HerdrReattach()
        reattach.expect("work")
        reattach.giveUp("other")
        reattach.giveUp("work")
        assertNull(reattach.onOutput(detached))
    }

    @Test
    fun attachCommand_defaultIsHerdr_othersAttachByName() {
        assertEquals("herdr", HerdrSessions.attachCommand("default"))
        assertEquals("herdr session attach 'work'", HerdrSessions.attachCommand("work"))
    }
}
