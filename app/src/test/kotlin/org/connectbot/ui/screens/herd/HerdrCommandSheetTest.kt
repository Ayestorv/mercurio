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

package org.connectbot.ui.screens.herd

import androidx.activity.ComponentActivity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.preference.PreferenceManager
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.connectbot.transport.CommandOutput
import org.connectbot.usage.LocalUsageLog
import org.connectbot.usage.UsageActions
import org.connectbot.usage.UsageLog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import solutions.ormus.logos.herd.Herd
import solutions.ormus.logos.herd.HerdSession
import solutions.ormus.logos.herd.HerdrAction
import solutions.ormus.logos.herd.HerdrDirection
import solutions.ormus.logos.herd.HerdrPrefix
import solutions.ormus.logos.herd.HerdrSessionSnapshot
import solutions.ormus.logos.herd.HerdrSessions
import solutions.ormus.logos.herd.HerdrTabInfo

/**
 * The Herdr command sheet against a fake runner that answers the snapshot with
 * the recording from Sun (focus: pane w1:p1, tab w1:t1, workspace w1) and
 * records every other command.
 */
@RunWith(AndroidJUnit4::class)
class HerdrCommandSheetTest {

    @get:Rule
    val composeTestRule = createAndroidComposeRule<ComponentActivity>()

    private val fixture = checkNotNull(javaClass.getResourceAsStream("/herdr/snapshot-sun-mixed.json")).readBytes().decodeToString()

    // Sun's real Herdr config: prefix Ctrl+Space (NUL), detach d, reload config q.
    private val sunConfig = checkNotNull(javaClass.getResourceAsStream("/herdr/config-sun.toml")).readBytes().decodeToString()
    private val ran = mutableListOf<String>()
    private val keys = mutableListOf<String>()
    private var answer: (String) -> CommandOutput = { CommandOutput("""{"id":"cli","result":{}}""", "", 0) }

    // The host's running Herdr sessions; a test adds "work" (three workspaces, one tab each).
    private var sessionList = """{"sessions":[{"name":"default","running":true}]}"""
    private val work = checkNotNull(javaClass.getResourceAsStream("/herdr/snapshot-picker-test.json")).readBytes().decodeToString()

    private val session = HerdSession(
        hostName = "Sun",
        runner = { command ->
            if (command == Herd.snapshotCommand) {
                CommandOutput(fixture, "", 0)
            } else if (command == HerdrPrefix.readCommand) {
                CommandOutput(sunConfig, "", 0)
            } else if (command == HerdrSessions.listCommand) {
                CommandOutput(sessionList, "", 0)
            } else if (command == HerdrSessions.snapshotCommand("work")) {
                CommandOutput(work, "", 0)
            } else {
                ran += command
                answer(command)
            }
        },
        sendKeys = { keys += it },
        scope = CoroutineScope(Dispatchers.Unconfined),
        io = Dispatchers.Unconfined,
    ).also { runBlocking { it.readKeys() } }

    // The panel's layout over the app's default preferences, which start empty in each test.
    private val prefs = PreferenceManager.getDefaultSharedPreferences(ApplicationProvider.getApplicationContext())
    private val store = HerdrPanelStore(prefs)

    /** The panel with its group held here, the way the compose bar holds it. */
    @Composable
    private fun Panel() {
        var section by remember { mutableStateOf(HerdrSection.AGENTS) }
        HerdrCommandPanel(session = session, section = section, onSection = { section = it }, store = store)
    }

    private fun show() {
        composeTestRule.setContent { Panel() }
        composeTestRule.waitForIdle()
    }

    // The group each action button lives in. A label not listed (a prompt's buttons) is
    // tapped where it is.
    private val groupOf = mapOf(
        "Next waiting agent" to "Agents", "Start claude" to "Agents", "Start codex" to "Agents",
        "Start grok" to "Agents", "Explain detection" to "Agents",
        "Split right" to "Panes", "Split down" to "Panes", "Zoom" to "Panes",
        "Rename pane" to "Panes", "Close pane" to "Panes",
        "New tab" to "Tabs", "Rename tab" to "Tabs", "Close tab" to "Tabs", "3" to "Tabs", "7" to "Tabs",
        "New workspace" to "Spaces", "Rename workspace" to "Spaces", "Close workspace" to "Spaces",
        "1 · ~Demo" to "Spaces", "2 · ~" to "Spaces", "4 · ~" to "Spaces",
        "Copy mode" to "Session", "Goto" to "Session", "Open notification" to "Session", "Detach" to "Session",
        "Reload config" to "Session", "Help" to "Session", "Stop session" to "Session",
        "Sidebar" to "Session", "Navigate" to "Spaces",
    )

    private fun group(name: String) {
        composeTestRule.onNodeWithText(name).performClick()
        composeTestRule.waitForIdle()
    }

    /** A key in the open group, not its namesake in the tab row on top. */
    private fun inGroup(text: String) = composeTestRule.onNode(hasText(text) and !hasAnyAncestor(hasTestTag(HERDR_TAB_ROW_TAG)))

    private fun tap(text: String) {
        groupOf[text]?.let { group(it) }
        inGroup(text).performScrollTo().performClick()
        composeTestRule.waitForIdle()
    }

    /** The tab row's keys, in order. */
    private fun tabKeys() = composeTestRule.onAllNodes(hasAnyAncestor(hasTestTag(HERDR_TAB_ROW_TAG)) and hasClickAction())
    private fun longPress(text: String) {
        groupOf[text]?.let { group(it) }
        composeTestRule.onNodeWithText(text).performScrollTo().performTouchInput { longClick() }
        composeTestRule.waitForIdle()
    }

    /** What the remote shell runs once bash -lc unwraps it. */
    private fun lastRun(): String = ran.last().removePrefix("bash -lc ").removeSurrounding("'").replace("'\\''", "'")

    @Test
    fun showsEveryGroupTab_andOneGroupAtATime() {
        show()

        for (name in listOf("Agents", "Panes", "Tabs", "Spaces", "Session")) {
            composeTestRule.onNodeWithText(name).assertIsDisplayed()
        }
        // Agents first; the other groups' buttons wait for their tab.
        composeTestRule.onNodeWithText("Next waiting agent").assertIsDisplayed()
        composeTestRule.onAllNodesWithText("Split right").assertCountEquals(0)

        // The current workspace's tabs and every workspace, to jump to.
        group("Tabs")
        composeTestRule.onAllNodesWithText("Next waiting agent").assertCountEquals(0)
        composeTestRule.onNodeWithText("/ JUMP TO TAB").performScrollTo().assertIsDisplayed()
        inGroup("7").performScrollTo().assertIsDisplayed()
        group("Spaces")
        composeTestRule.onNodeWithText("1 · ~Demo").performScrollTo().assertIsDisplayed()
        composeTestRule.onNodeWithText("4 · ~").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun tabRow_numbersTheFocusedWorkspacesTabs_inEveryGroup() {
        show()

        for (name in listOf("Agents", "Panes", "Tabs", "Spaces", "Session")) {
            group(name)
            composeTestRule.onNodeWithTag(HERDR_TAB_ROW_TAG).assertIsDisplayed()
            // Workspace w1 has seven tabs; w3, w4 and w5 add none.
            tabKeys().assertCountEquals(7)
        }
        for (i in 0 until 7) tabKeys()[i].assertTextEquals((i + 1).toString())
    }

    @Test
    fun sessionRow_hiddenWhileTheHostRunsOneSession() {
        show()

        composeTestRule.onAllNodesWithTag(HERDR_SESSION_ROW_TAG).assertCountEquals(0)
    }

    @Test
    fun sessionRow_pickingASessionRunsTheActionsThere() {
        sessionList = """{"sessions":[{"name":"default","running":true},{"name":"work","running":true},{"name":"old","running":false}]}"""
        val logged = mutableListOf<String>()
        composeTestRule.setContent {
            CompositionLocalProvider(LocalUsageLog provides UsageLog { logged += it }) {
                Panel()
            }
        }
        composeTestRule.waitForIdle()

        val sessionKey = { name: String -> composeTestRule.onNode(hasText(name) and hasAnyAncestor(hasTestTag(HERDR_SESSION_ROW_TAG))) }
        sessionKey("default").assertIsSelected()
        sessionKey("work").assertIsNotSelected()
        composeTestRule.onAllNodesWithText("old").assertCountEquals(0)

        sessionKey("work").performClick()
        composeTestRule.waitForIdle()
        sessionKey("work").assertIsSelected()
        // Work's focused workspace has one tab, so no tab row; its workspaces are the jump list.
        composeTestRule.onAllNodesWithTag(HERDR_TAB_ROW_TAG).assertCountEquals(0)
        tap("New tab")
        assertEquals("herdr --session 'work' tab create --workspace 'w1' --focus", lastRun())
        group("Spaces")
        composeTestRule.onNodeWithText("3 · gamma").performScrollTo().performClick()
        composeTestRule.waitForIdle()
        assertEquals("herdr --session 'work' workspace focus 'w3'", lastRun())

        assertEquals(UsageActions.HERDR_SHEET_SESSION, logged.first())
        assertEquals(listOf(UsageActions.HERDR_SHEET_NEW_TAB, UsageActions.HERDR_SHEET_JUMP_WORKSPACE), logged.drop(1))
    }

    @Test
    fun tabsGroup_leadsWithJumpTo_whenThePanelCanOpenIt() {
        var opened = 0
        composeTestRule.setContent {
            var section by remember { mutableStateOf(HerdrSection.TABS) }
            HerdrCommandPanel(session = session, section = section, onSection = { section = it }, store = store, onJumpTo = { opened++ })
        }
        composeTestRule.waitForIdle()

        inGroup("Jump to…").performScrollTo().performClick()
        assertEquals(1, opened)
        assertTrue("opening Jump to runs nothing on the host", ran.isEmpty())
    }

    @Test
    fun tabsGroup_noJumpToKeyWithoutIt() {
        show()

        group("Tabs")
        composeTestRule.onAllNodesWithText("Jump to…").assertCountEquals(0)
    }

    @Test
    fun tabRow_marksTheFocusedTab() {
        show()

        // Focus is on w1:t1, the first tab.
        tabKeys()[0].assertIsSelected()
        for (i in 1 until 7) tabKeys()[i].assertIsNotSelected()
    }

    @Test
    fun tabRow_aKeyFocusesItsTab_andLogsTheTabKeyId() {
        val logged = mutableListOf<String>()
        composeTestRule.setContent {
            CompositionLocalProvider(LocalUsageLog provides UsageLog { logged += it }) {
                Panel()
            }
        }
        composeTestRule.waitForIdle()

        tabKeys()[2].performClick()
        composeTestRule.waitForIdle()
        assertEquals("herdr tab focus 'w1:tC'", lastRun())
        tabKeys()[6].performClick()
        composeTestRule.waitForIdle()
        assertEquals("herdr tab focus 'w1:tK'", lastRun())

        assertEquals(listOf(UsageActions.HERDR_SHEET_TAB_KEY, UsageActions.HERDR_SHEET_TAB_KEY), logged)
    }

    @Test
    fun tabRow_aKeyDropsAHalfAskedAction() {
        show()

        tap("Rename tab")
        tabKeys()[1].performClick()
        composeTestRule.waitForIdle()

        composeTestRule.onAllNodesWithText("New name").assertCountEquals(0)
        assertEquals("herdr tab focus 'w1:tB'", lastRun())
    }

    /** The content alone, on [snapshot], recording the tab keys it runs. */
    private fun showContent(snapshot: HerdrSessionSnapshot?, focused: MutableList<String> = mutableListOf()) {
        composeTestRule.setContent {
            HerdrCommandContent(
                snapshot = snapshot,
                result = null,
                section = HerdrSection.AGENTS,
                onSection = {},
                onRun = { _, _ -> },
                onTabKey = { focused += it.tabId },
            )
        }
        composeTestRule.waitForIdle()
    }

    private fun tabs(vararg labels: String) = HerdrSessionSnapshot(
        tabs = labels.mapIndexed { i, label -> HerdrTabInfo(tabId = "w1:t$i", workspaceId = "w1", label = label, number = i + 1) },
        focusedWorkspaceId = "w1",
        focusedTabId = "w1:t1",
    )

    @Test
    fun tabRow_namesEachKeyByItsTabsLabel() {
        val focused = mutableListOf<String>()
        showContent(tabs("Mercurio", "Sal", ""), focused)

        // A tab with no label is named by its number.
        for ((i, name) in listOf("Mercurio", "Sal", "3").withIndex()) {
            tabKeys()[i].assertContentDescriptionEquals(name)
        }
        composeTestRule.onNodeWithContentDescription("Sal").performClick()
        assertEquals(listOf("w1:t1"), focused)
    }

    @Test
    fun tabRow_isHidden_withoutASnapshot() {
        showContent(null)
        composeTestRule.onAllNodesWithTag(HERDR_TAB_ROW_TAG).assertCountEquals(0)
    }

    @Test
    fun tabRow_isHidden_withOneTab() {
        showContent(tabs("only"))
        composeTestRule.onAllNodesWithTag(HERDR_TAB_ROW_TAG).assertCountEquals(0)
    }

    @Test
    fun tabRow_stopsAtNineKeys_onOneRow() {
        showContent(tabs(*Array(12) { "tab $it" }))

        tabKeys().assertCountEquals(9)
        tabKeys()[8].assertTextEquals("9")
        // All nine share the row's width: none is pushed past its end.
        val row = composeTestRule.onNodeWithTag(HERDR_TAB_ROW_TAG).getBoundsInRoot()
        val last = tabKeys()[8].getBoundsInRoot()
        assertEquals(row.right.value, last.right.value, 1f)
    }

    @Test
    fun switchingGroup_dropsAHalfAskedAction() {
        show()

        tap("Close pane")
        composeTestRule.onNodeWithText("Close this pane? What runs in it stops.").assertIsDisplayed()
        group("Tabs")

        composeTestRule.onAllNodesWithText("Close this pane? What runs in it stops.").assertCountEquals(0)
        assertTrue(ran.isEmpty())
    }

    @Test
    fun oneTapActions_runTheirCommandOnTheFocusedIds() {
        show()

        val expected = linkedMapOf(
            "Next waiting agent" to "herdr tab focus 'w1:tJ' && herdr agent focus 'w1:pJ'",
            "Start claude" to "herdr agent start 'claude-w1-p1' --kind 'claude' --pane 'w1:p1' --timeout 12000",
            "Start codex" to "herdr agent start 'codex-w1-p1' --kind 'codex' --pane 'w1:p1' --timeout 12000",
            "Start grok" to "herdr agent start 'grok-w1-p1' --kind 'grok' --pane 'w1:p1' --timeout 12000",
            "Explain detection" to "herdr agent explain 'w1:p1'",
            "Split right" to "herdr pane split 'w1:p1' --direction right --focus",
            "Split down" to "herdr pane split 'w1:p1' --direction down --focus",
            "Zoom" to "herdr pane zoom 'w1:p1' --toggle",
            "New tab" to "herdr tab create --workspace 'w1' --focus",
            "3" to "herdr tab focus 'w1:tC'",
            "New workspace" to "herdr workspace create --focus",
            "2 · ~" to "herdr workspace focus 'w3'",
        )
        for ((label, command) in expected) {
            tap(label)
            assertEquals(label, command, lastRun())
        }
    }

    @Test
    fun arrows_focusAndSwapTheFocusedPane() {
        show()
        group("Panes")

        composeTestRule.onNodeWithContentDescription("Focus left").performScrollTo().performClick()
        assertEquals("herdr pane focus --pane 'w1:p1' --direction left", lastRun())
        composeTestRule.onNodeWithContentDescription("Swap down").performScrollTo().performClick()
        assertEquals("herdr pane swap --pane 'w1:p1' --direction down", lastRun())
    }

    @Test
    fun rename_asksForANameThenRuns() {
        show()

        tap("Rename tab")
        assertTrue(ran.isEmpty())
        composeTestRule.onNodeWithText("New name").performTextInput("logs and tests")
        tap("Rename")

        assertEquals("herdr tab rename 'w1:t1' 'logs and tests'", lastRun())
    }

    @Test
    fun close_asksOnceThenRuns() {
        show()

        tap("Close pane")
        composeTestRule.onNodeWithText("Close this pane? What runs in it stops.").assertIsDisplayed()
        assertTrue(ran.isEmpty())
        tap("Yes, do it")

        assertEquals("herdr pane close 'w1:p1'", lastRun())
    }

    @Test
    fun close_cancelRunsNothing() {
        show()

        tap("Stop session")
        tap("Cancel")

        assertTrue(ran.isEmpty())
    }

    @Test
    fun stopSession_afterConfirm() {
        show()

        tap("Stop session")
        composeTestRule.onNodeWithText("Stop the Herdr session? Every agent and pane in it stops.").assertIsDisplayed()
        tap("Yes, do it")

        assertEquals("herdr session stop 'default'", lastRun())
    }

    @Test
    fun sessionKeys_sendPrefixNulThenKey() {
        show()
        val nul = 0.toChar().toString()

        val expected = linkedMapOf(
            "Copy mode" to "[",
            "Goto" to "g",
            "Open notification" to "o",
            "Detach" to "d",
            "Reload config" to "q",
            "Help" to "?",
            // Herdr's defaults for its side panel and for workspace navigation (Sun leaves them).
            "Sidebar" to "b",
            "Navigate" to "w",
        )
        for ((label, key) in expected) {
            tap(label)
            assertEquals(label, nul + key, keys.last())
        }
        assertTrue("keystrokes run no command", ran.isEmpty())
    }

    @Test
    fun sessionGroup_showsTheHostsPrefix() {
        show()
        group("Session")

        composeTestRule.onNodeWithTag(HERDR_PREFIX_TAG).assertTextContains("/ PREFIX CTRL+SPACE")
    }

    @Test
    fun anUnsendablePrefix_showsCtrlBAndWhy_andCopiesAFixPrompt() {
        val odd = HerdSession(
            hostName = "lab",
            runner = { command ->
                when (command) {
                    HerdrPrefix.readCommand -> CommandOutput("[keys]\nprefix = \"super+b\"\n", "", 0)
                    else -> CommandOutput(fixture, "", 0)
                }
            },
            sendKeys = {},
            scope = CoroutineScope(Dispatchers.Unconfined),
            io = Dispatchers.Unconfined,
        )
        runBlocking { odd.readKeys() }
        composeTestRule.setContent {
            HerdrCommandPanel(session = odd, section = HerdrSection.SESSION, onSection = {}, host = "lab")
        }
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag(HERDR_PREFIX_TAG).assertTextContains("/ PREFIX CTRL+B")
        composeTestRule.onNodeWithText("prefix = \"super+b\"", substring = true).assertIsDisplayed()
        composeTestRule.onNodeWithText("Copy fix prompt").performClick()
        composeTestRule.waitForIdle()
        val prompt = copiedText()
        assertTrue(prompt, prompt.contains("prefix = \"super+b\""))
        assertTrue(prompt, prompt.contains("herdr/config.toml"))
    }

    @Test
    fun aClientKeyTheHostBindsToNoTerminalKey_isOff_andSaysWhy() {
        val sent = mutableListOf<String>()
        val stock = HerdSession(
            hostName = "stock",
            runner = { command ->
                when (command) {
                    HerdrPrefix.readCommand -> CommandOutput("[keys]\ndetach = \"cmd+d\"\n", "", 0)
                    else -> CommandOutput(fixture, "", 0)
                }
            },
            sendKeys = { sent += it },
            scope = CoroutineScope(Dispatchers.Unconfined),
            io = Dispatchers.Unconfined,
        )
        runBlocking { stock.readKeys() }
        composeTestRule.setContent {
            HerdrCommandPanel(session = stock, section = HerdrSection.SESSION, onSection = {}, host = "stock")
        }
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithText("Detach").assertIsNotEnabled()
        composeTestRule.onNodeWithText("detach to \"cmd+d\"", substring = true).assertIsDisplayed()
        // The others keep Herdr's defaults: reload config is prefix, then shift+r.
        composeTestRule.onNodeWithText("Reload config").performScrollTo().performClick()
        composeTestRule.waitForIdle()
        assertEquals(listOf(2.toChar().toString() + "R"), sent)

        // An off key still takes its long press, and the user's own key wins over the host's binding.
        longPress("Detach")
        tap("Edit key")
        composeTestRule.onNodeWithText("Key").performTextInput("x")
        tap("Save")
        tap("Detach")
        assertEquals(2.toChar().toString() + "x", sent.last())
        composeTestRule.onAllNodesWithText("detach to \"cmd+d\"", substring = true).assertCountEquals(0)
    }

    @Test
    fun editKey_startsFromTheHostsBinding_andAnUneditedKeyKeepsIt() {
        val nul = 0.toChar().toString()
        show()

        // Sun binds detach to prefix+d: the form starts from d.
        longPress("Detach")
        tap("Edit key")
        composeTestRule.onNodeWithText("Key").assertTextContains("d")
        tap("Cancel")
        tap("Detach")
        assertEquals(nul + "d", keys.last())
    }

    // The Herdr key sends Herdr's prefix as the panel opens (armPrefix). Herdr stays in
    // prefix mode until a key arrives, so the prefix is used or cancelled with Esc.

    @Test
    fun armedPrefix_aPanelKeySendsOnlyItsLetter() {
        val nul = 0.toChar().toString()
        session.armPrefix()
        show()

        tap("Goto")
        tap("Sidebar")

        // The first key rides the armed prefix; the next one sends its own.
        assertEquals(listOf(nul, "g", nul + "b"), keys)
    }

    @Test
    fun armedPrefix_aCommandCancelsItFirst() {
        session.armPrefix()
        show()

        tap("Zoom")

        assertEquals(listOf(0.toChar().toString(), 27.toChar().toString()), keys)
        assertEquals("herdr pane zoom 'w1:p1' --toggle", lastRun())
    }

    @Test
    fun armedPrefix_closingThePanelUnusedCancelsIt() {
        var open by mutableStateOf(true)
        session.armPrefix()
        composeTestRule.setContent { if (open) Panel() }
        composeTestRule.waitForIdle()

        open = false
        composeTestRule.waitForIdle()

        assertEquals(listOf(0.toChar().toString(), 27.toChar().toString()), keys)
    }

    @Test
    fun keystroke_withoutAnArmedPrefix_isUnchanged_andCancelSendsNothing() {
        val nul = 0.toChar().toString()

        assertEquals(nul + "p", session.keystroke(nul + "p"))
        session.cancelPrefix()
        assertTrue(keys.isEmpty())

        session.armPrefix()
        session.armPrefix()
        // A second arm sends nothing more; a non-Herdr key cancels before it goes.
        assertEquals("x", session.keystroke("x"))
        assertEquals(listOf(nul, 27.toChar().toString()), keys)
    }

    private fun copiedText(): String {
        val clipboard = composeTestRule.activity.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
        return clipboard.primaryClip!!.getItemAt(0).text.toString()
    }

    @Test
    fun herdrNotAnswering_showsWhy_andCopiesAFixPrompt() {
        // A host where herdr is missing: every command exits 127.
        val broken = HerdSession(
            hostName = "lab",
            runner = { CommandOutput("", "bash: line 1: herdr: command not found", 127) },
            sendKeys = {},
            scope = CoroutineScope(Dispatchers.Unconfined),
            io = Dispatchers.Unconfined,
        )
        composeTestRule.setContent {
            HerdrCommandPanel(session = broken, section = HerdrSection.AGENTS, onSection = {}, host = "lab (bob@lab:22)")
        }
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag(HERDR_OFFLINE_TAG).assertIsDisplayed()
        composeTestRule.onNodeWithText("/ HERDR NOT ANSWERING").assertIsDisplayed()
        composeTestRule.onNodeWithText("`herdr` was not found on the host (exit 127).").assertIsDisplayed()
        composeTestRule.onNodeWithText("Copy fix prompt").performClick()
        composeTestRule.waitForIdle()

        val prompt = copiedText()
        assertTrue(prompt.startsWith("Mercurio connects to lab (bob@lab:22), but its Herdr side fails"))
        assertTrue(prompt.contains("- Exit code: 127"))
        assertTrue(prompt.contains("herdr: command not found"))
        assertTrue(prompt.contains("herdr api snapshot"))
    }

    @Test
    fun aFailedAction_offersAFixPromptBesideIt() {
        answer = {
            CommandOutput("", """{"error":{"code":"pane_not_found","message":"pane w1:p1 not found"},"id":"cli:pane:zoom"}""", 1)
        }
        show()

        tap("Zoom")
        composeTestRule.onNodeWithText("Copy fix prompt").performClick()
        composeTestRule.waitForIdle()

        val prompt = copiedText()
        assertTrue(prompt.contains("Zoom failed: pane w1:p1 not found"))
        assertTrue(prompt.contains("- Exit code: 1"))
        assertTrue(prompt.contains("herdr pane zoom"))
    }

    @Test
    fun failure_showsHerdrErrorInOneLine() {
        answer = {
            CommandOutput("", """{"error":{"code":"pane_not_found","message":"pane w1:p1 not found"},"id":"cli:pane:zoom"}""", 1)
        }
        show()

        tap("Zoom")

        composeTestRule.onNodeWithTag(HERDR_RESULT_TAG).assertTextContains("Zoom failed: pane w1:p1 not found")
    }

    @Test
    fun success_saysDone() {
        show()

        tap("Split down")

        composeTestRule.onNodeWithTag(HERDR_RESULT_TAG).assertTextContains("Split down: done")
    }

    @Test
    fun actions_logTheirUsageIdNeverTheName() {
        val logged = mutableListOf<String>()
        composeTestRule.setContent {
            CompositionLocalProvider(LocalUsageLog provides UsageLog { logged += it }) {
                Panel()
            }
        }

        tap("Split right")
        tap("Rename pane")
        composeTestRule.onNodeWithText("New name").performTextInput("secret plan")
        tap("Rename")
        tap("Close tab")
        tap("Yes, do it")
        tap("Goto")

        assertEquals(
            listOf(
                UsageActions.HERDR_SHEET_SPLIT_RIGHT,
                UsageActions.HERDR_SHEET_RENAME_PANE,
                UsageActions.HERDR_SHEET_CLOSE_TAB,
                UsageActions.HERDR_SHEET_GOTO,
            ),
            logged,
        )
    }

    @Test
    fun everySheetAction_hasItsOwnListedUsageId() {
        val actions = listOf(
            HerdrAction.FocusNextBlocked, HerdrAction.ExplainDetection,
            HerdrAction.SplitRight, HerdrAction.SplitDown, HerdrAction.ZoomPane,
            HerdrAction.RenamePane, HerdrAction.ClosePane,
            HerdrAction.NewTab, HerdrAction.RenameTab, HerdrAction.CloseTab, HerdrAction.FocusTab("w1:t1"),
            HerdrAction.NewWorkspace, HerdrAction.RenameWorkspace, HerdrAction.CloseWorkspace, HerdrAction.FocusWorkspace("w1"),
            HerdrAction.COPY_MODE, HerdrAction.GOTO, HerdrAction.OPEN_NOTIFICATION, HerdrAction.DETACH,
            HerdrAction.RELOAD_CONFIG, HerdrAction.HELP, HerdrAction.StopSession,
            HerdrAction.TOGGLE_SIDEBAR, HerdrAction.NAVIGATE_WORKSPACES,
        ) + HerdrAction.START_KINDS.map { HerdrAction.StartAgent(it) } +
            HerdrDirection.entries.flatMap { listOf(HerdrAction.FocusPane(it), HerdrAction.SwapPane(it)) }

        val ids = actions.map { herdrSheetUsageId(it) }
        assertTrue(ids.all { UsageActions.isKnown(it) && it.startsWith("herdr-sheet:") })
        assertEquals("one id per action", actions.size, ids.toSet().size)
    }

    // The panel's layout: long press a key to hide or edit it, Add key, Restore defaults.

    @Test
    fun longPress_hide_takesTheKeyOffThePanel_andKeepsItOff() {
        val logged = mutableListOf<String>()
        composeTestRule.setContent {
            CompositionLocalProvider(LocalUsageLog provides UsageLog { logged += it }) { Panel() }
        }

        longPress("Split right")
        // A command key has no key to edit; nothing ran.
        composeTestRule.onAllNodesWithText("Edit key").assertCountEquals(0)
        tap("Hide")

        composeTestRule.onAllNodesWithText("Split right").assertCountEquals(0)
        composeTestRule.onNodeWithText("Split down").assertIsDisplayed()
        assertTrue(ran.isEmpty())
        // Stored for the app: a new store over the same preferences reads it back.
        assertEquals(setOf(UsageActions.HERDR_SHEET_SPLIT_RIGHT), HerdrPanelStore(prefs).layout.value.hidden)
        assertEquals(listOf(UsageActions.HERDR_SHEET_KEY_MENU, UsageActions.HERDR_SHEET_KEY_HIDE), logged)
    }

    @Test
    fun addKey_sendsThePrefixThenTheKey_throughTheSession() {
        val nul = 0.toChar().toString()
        val logged = mutableListOf<String>()
        session.armPrefix()
        composeTestRule.setContent {
            CompositionLocalProvider(LocalUsageLog provides UsageLog { logged += it }) { Panel() }
        }
        group("Session")

        tap("Add key")
        composeTestRule.onNodeWithText("Label").performTextInput("Redraw")
        composeTestRule.onNodeWithText("Key").performTextInput("shift+r")
        tap("Save")
        tap("Redraw")
        tap("Redraw")

        // The first rides the prefix the Herdr key armed, the second sends its own.
        assertEquals(listOf(nul, "R", nul + "R"), keys)
        assertTrue("keystrokes run no command", ran.isEmpty())
        composeTestRule.onNodeWithTag(HERDR_RESULT_TAG).assertTextContains("Redraw: done")
        assertEquals(listOf(HerdrPanelKey("Redraw", "shift+r")), store.layout.value.custom)
        // Ids only: never the label or the key typed.
        assertEquals(
            listOf(UsageActions.HERDR_SHEET_KEY_ADD, UsageActions.KEY_OTHER, UsageActions.KEY_OTHER),
            logged,
        )
    }

    @Test
    fun addKey_refusesAKeyATerminalCannotSend() {
        show()
        group("Session")

        tap("Add key")
        composeTestRule.onNodeWithText("Label").performTextInput("Find")
        composeTestRule.onNodeWithText("Key").performTextInput("super+f")

        composeTestRule.onNodeWithText("A terminal cannot send super+f").assertIsDisplayed()
        composeTestRule.onNodeWithText("Save").assertIsNotEnabled()
    }

    @Test
    fun editKey_aSessionKeySendsItsNewKey() {
        val nul = 0.toChar().toString()
        show()

        longPress("Goto")
        tap("Edit key")
        composeTestRule.onNodeWithText("Key").performTextClearance()
        composeTestRule.onNodeWithText("Key").performTextInput("ctrl+g")
        tap("Save")
        tap("Goto")

        assertEquals(nul + 7.toChar(), keys.last())
    }

    @Test
    fun customKey_longPress_remove() {
        store.add(HerdrPanelKey("Redraw", "shift+r"))
        show()
        group("Session")

        longPress("Redraw")
        tap("Remove")

        composeTestRule.onAllNodesWithText("Redraw").assertCountEquals(0)
        assertTrue(store.layout.value.isDefault)
    }

    @Test
    fun restoreDefaults_asksFirst_thenBringsKeysBackAndDropsCustomOnes() {
        store.hide(UsageActions.HERDR_SHEET_COPY_MODE)
        store.add(HerdrPanelKey("Redraw", "shift+r"))
        show()
        group("Session")
        composeTestRule.onAllNodesWithText("Copy mode").assertCountEquals(0)

        tap("Restore defaults")
        tap("Cancel")
        composeTestRule.onAllNodesWithText("Copy mode").assertCountEquals(0)

        tap("Restore defaults")
        tap("Yes, do it")

        composeTestRule.onNodeWithText("Copy mode").assertExists()
        composeTestRule.onAllNodesWithText("Redraw").assertCountEquals(0)
        composeTestRule.onAllNodesWithText("Restore defaults").assertCountEquals(0)
        assertFalse(prefs.contains(HerdrPanelStore.PREF_KEY))
    }
}
