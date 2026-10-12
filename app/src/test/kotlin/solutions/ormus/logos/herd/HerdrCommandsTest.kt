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
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The command each sheet action runs, against the snapshot recorded on Sun
 * (focus: pane w1:p1, tab w1:t1, workspace w1). Every argument is single-quoted
 * inside a login shell, so ids and names cannot break out of it.
 */
class HerdrCommandsTest {
    private val snapshot = checkNotNull(parseHerdrSnapshot(herdrFixture("snapshot-sun-mixed.json")))
    private val commands = HerdrCommands()

    private fun cli(action: HerdrAction, text: String? = null): String = (commands.build(action, snapshot, text) as HerdrInvocation.Cli).command

    /** What the remote shell runs once bash -lc unwraps its one argument. */
    private fun inner(command: String): String {
        val prefix = "bash -lc "
        check(command.startsWith(prefix))
        return command.removePrefix(prefix).removeSurrounding("'").replace("'\\''", "'")
    }

    private fun assertRuns(expected: String, action: HerdrAction, text: String? = null) {
        assertEquals(expected, inner(cli(action, text)))
    }

    @Test
    fun agents() {
        // Newest blocked agent first (w1:pJ, seq 10), skipping the focused pane.
        assertRuns("herdr tab focus 'w1:tJ' && herdr agent focus 'w1:pJ'", HerdrAction.FocusNextBlocked)
        assertRuns("herdr agent start 'claude-w1-p1' --kind 'claude' --pane 'w1:p1' --timeout 12000", HerdrAction.StartAgent("claude"))
        assertRuns("herdr agent start 'codex-w1-p1' --kind 'codex' --pane 'w1:p1' --timeout 12000", HerdrAction.StartAgent("codex"))
        assertRuns("herdr agent start 'grok-w1-p1' --kind 'grok' --pane 'w1:p1' --timeout 12000", HerdrAction.StartAgent("grok"))
        assertRuns("herdr agent explain 'w1:p1'", HerdrAction.ExplainDetection)
        assertRuns("herdr tab focus 'w1:tH' && herdr agent focus 'w1:pH'", HerdrAction.FocusAgent("w1:pH"))
    }

    @Test
    fun focusAgent_focusesItsTabFirst_soAnAttachedClientFollows() {
        // In another session the same pair runs there.
        val work = (HerdrSessions.commands("work").build(HerdrAction.FocusAgent("w1:pC"), snapshot) as HerdrInvocation.Cli).command
        assertEquals("herdr --session 'work' tab focus 'w1:tC' && herdr --session 'work' agent focus 'w1:pC'", inner(work))
        // A pane the snapshot does not know: the agent focus alone.
        assertRuns("herdr agent focus 'w9:p9'", HerdrAction.FocusAgent("w9:p9"))
        val none = (commands.build(HerdrAction.FocusAgent("w1:pC"), null) as HerdrInvocation.Cli).command
        assertEquals("herdr agent focus 'w1:pC'", inner(none))
    }

    @Test
    fun focusNextBlocked_walksToTheOtherBlockedAgent() {
        val onFirst = snapshot.copy(focusedPaneId = "w1:pJ")
        val command = (commands.build(HerdrAction.FocusNextBlocked, onFirst) as HerdrInvocation.Cli).command
        assertEquals("herdr tab focus 'w1:tC' && herdr agent focus 'w1:pC'", inner(command))
    }

    @Test
    fun focusNextBlocked_nothingWhenNoneBlocked() {
        val calm = snapshot.copy(agents = snapshot.agents.filter { it.agentStatus != HerdrAgentStatus.BLOCKED })
        assertNull(commands.build(HerdrAction.FocusNextBlocked, calm))
    }

    @Test
    fun panes() {
        assertRuns("herdr pane split 'w1:p1' --direction right --focus", HerdrAction.SplitRight)
        assertRuns("herdr pane split 'w1:p1' --direction down --focus", HerdrAction.SplitDown)
        assertRuns("herdr pane zoom 'w1:p1' --toggle", HerdrAction.ZoomPane)
        for (direction in HerdrDirection.entries) {
            assertRuns(
                "herdr pane focus --pane 'w1:p1' --direction ${direction.cli}",
                HerdrAction.FocusPane(direction),
            )
            assertRuns(
                "herdr pane swap --pane 'w1:p1' --direction ${direction.cli}",
                HerdrAction.SwapPane(direction),
            )
        }
        assertRuns("herdr pane rename 'w1:p1' 'review bot'", HerdrAction.RenamePane, "  review bot ")
        assertRuns("herdr pane close 'w1:p1'", HerdrAction.ClosePane)
    }

    @Test
    fun tabs() {
        assertRuns("herdr tab create --workspace 'w1' --focus", HerdrAction.NewTab)
        assertRuns("herdr tab rename 'w1:t1' 'logs'", HerdrAction.RenameTab, "logs")
        assertRuns("herdr tab close 'w1:t1'", HerdrAction.CloseTab)
        assertRuns("herdr tab focus 'w1:tC'", HerdrAction.FocusTab("w1:tC"))
    }

    @Test
    fun workspaces() {
        assertRuns("herdr workspace create --focus", HerdrAction.NewWorkspace)
        assertRuns("herdr workspace rename 'w1' 'Demo ops'", HerdrAction.RenameWorkspace, "Demo ops")
        assertRuns("herdr workspace close 'w1'", HerdrAction.CloseWorkspace)
        assertRuns("herdr workspace focus 'w3'", HerdrAction.FocusWorkspace("w3"))
    }

    @Test
    fun session_stopTargetsTheAttachedSession() {
        assertRuns("herdr session stop 'default'", HerdrAction.StopSession)
    }

    @Test
    fun clientKeys_sendTheHostsBindings() {
        val nul = 0.toChar().toString()
        // Sun's bindings: prefix Ctrl+Space (NUL), detach d, reload q, help ?, copy mode [.
        val sun = HerdrKeys.fromConfig(
            "[keys]\nprefix = \"ctrl+space\"\nreload_config = \"prefix+q\"\nhelp = \"prefix+?\"\n" +
                "detach = \"prefix+d\"\ncopy_mode = \"prefix+[\"\n",
        )
        val keys = mapOf(
            HerdrAction.COPY_MODE to "[",
            HerdrAction.GOTO to "g",
            HerdrAction.OPEN_NOTIFICATION to "o",
            HerdrAction.DETACH to "d",
            HerdrAction.RELOAD_CONFIG to "q",
            HerdrAction.HELP to "?",
            HerdrAction.TOGGLE_SIDEBAR to "b",
            HerdrAction.NAVIGATE_WORKSPACES to "w",
        )
        for ((action, key) in keys) {
            assertEquals(HerdrInvocation.Keys(nul + key), commands.build(action, snapshot, keys = sun))
        }
    }

    @Test
    fun clientKeys_withoutAConfig_sendHerdrsDefaults() {
        val ctrlB = 2.toChar().toString()
        assertEquals(HerdrInvocation.Keys(ctrlB + "g"), commands.build(HerdrAction.GOTO, snapshot))
        // Stock Herdr: detach is q, reload config shift+r.
        assertEquals(HerdrInvocation.Keys(ctrlB + "q"), commands.build(HerdrAction.DETACH, snapshot))
        assertEquals(HerdrInvocation.Keys(ctrlB + "R"), commands.build(HerdrAction.RELOAD_CONFIG, snapshot))
    }

    @Test
    fun loginShell_wrapsTheWholeCommandAsOneWord() {
        val command = cli(HerdrAction.RenamePane, "it's")
        // One bash -lc argument whose quotes are all escaped the POSIX way...
        assertEquals(true, command.startsWith("bash -lc '") && command.endsWith("'"))
        // ...that unwraps to a herdr command with the name as one word.
        assertEquals("herdr pane rename 'w1:p1' 'it'\\''s'", inner(command))
    }

    @Test
    fun hostileNamesStayOneArgument() {
        assertRuns("herdr tab rename 'w1:t1' '\$(rm -rf ~); echo'", HerdrAction.RenameTab, "\$(rm -rf ~); echo")
    }

    @Test
    fun renameWithoutANameOrFocusDoesNothing() {
        assertNull(commands.build(HerdrAction.RenamePane, snapshot, "   "))
        assertNull(commands.build(HerdrAction.ClosePane, snapshot.copy(focusedPaneId = null)))
        assertNull(commands.build(HerdrAction.ClosePane, null))
        assertNull(commands.build(HerdrAction.ClosePane, snapshot.copy(focusedPaneId = "w1:p1; reboot")))
    }

    @Test
    fun namedSession_goesBeforeTheGroup() {
        val throwaway = HerdrCommands(session = "mtest")
        val split = throwaway.build(HerdrAction.SplitRight, snapshot) as HerdrInvocation.Cli
        assertEquals("herdr --session 'mtest' pane split 'w1:p1' --direction right --focus", inner(split.command))
        val stop = throwaway.build(HerdrAction.StopSession, snapshot) as HerdrInvocation.Cli
        assertEquals("herdr --session 'mtest' session stop 'mtest'", inner(stop.command))
    }
}
