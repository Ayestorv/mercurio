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

package org.connectbot.ui.components

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
import androidx.compose.ui.test.swipeUp
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.flow.MutableStateFlow
import org.connectbot.data.entity.Host
import org.connectbot.service.TerminalBridge
import org.connectbot.ui.theme.ConnectBotTheme
import org.connectbot.usage.LocalUsageLog
import org.connectbot.usage.UsageActions
import org.connectbot.usage.UsageLog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentCaptor
import org.mockito.Mockito.any
import org.mockito.Mockito.atLeast
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.times
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import solutions.ormus.logos.herd.HerdSession
import solutions.ormus.logos.herd.HerdrAgentInfo
import solutions.ormus.logos.herd.HerdrAgentStatus
import solutions.ormus.logos.herd.HerdrKeys
import solutions.ormus.logos.herd.HerdrSessionSnapshot
import solutions.ormus.logos.herd.HerdrTabInfo
import solutions.ormus.logos.herd.HerdrWorkspaceInfo
import solutions.ormus.logos.herd.JumpTarget
import solutions.ormus.logos.herd.parseHerdrSnapshot

/**
 * The two-row compose bar and its tray: what row 1 holds on Herdr and plain hosts,
 * how the tray opens and closes, that every tray key still sends the bytes it sent
 * before the tray existed, and that the dictation draft survives the tray.
 */
@RunWith(AndroidJUnit4::class)
class TerminalComposeBarTest {

    @get:Rule
    val composeTestRule = createAndroidComposeRule<ComponentActivity>()

    private val logged = mutableListOf<String>()

    private fun bridgeWithPostLogin(postLogin: String?): TerminalBridge {
        val bridge = mock(TerminalBridge::class.java)
        `when`(bridge.host).thenReturn(Host(nickname = "Sun", postLogin = postLogin))
        return bridge
    }

    /**
     * A Herdr bridge whose latest snapshot has the focused pane holding
     * [maxOffsetFromBottom] rows of scrollback (`herdr api snapshot`, panes[].scroll).
     */
    private fun herdrBridgeWithScrollback(maxOffsetFromBottom: Int, postLogin: String? = "herdr\n"): TerminalBridge {
        val bridge = bridgeWithPostLogin(postLogin)
        val snapshot = parseHerdrSnapshot(
            """{"id":"1","result":{"snapshot":{"focused_pane_id":"w1:p1","panes":[""" +
                """{"pane_id":"w1:p1","focused":true,"scroll":{"max_offset_from_bottom":$maxOffsetFromBottom,""" +
                """"offset_from_bottom":0,"viewport_rows":40}},""" +
                """{"pane_id":"w1:p2","focused":false,"scroll":{"max_offset_from_bottom":999,""" +
                """"offset_from_bottom":0,"viewport_rows":40}}]}}}""",
        )
        val herd = mock(HerdSession::class.java)
        `when`(herd.snapshot).thenReturn(MutableStateFlow(snapshot))
        `when`(herd.result).thenReturn(MutableStateFlow(null))
        `when`(herd.failure).thenReturn(MutableStateFlow(null))
        `when`(herd.sessions).thenReturn(MutableStateFlow(listOf("default")))
        `when`(herd.chosen).thenReturn(MutableStateFlow("default"))
        `when`(herd.others).thenReturn(MutableStateFlow(emptyMap()))
        `when`(herd.attached).thenReturn(MutableStateFlow("default"))
        // Sun's prefix, read from its Herdr config.
        `when`(herd.keys).thenReturn(MutableStateFlow(HerdrKeys.fromConfig("[keys]\nprefix = \"ctrl+space\"\n")))
        `when`(bridge.herd).thenReturn(herd)
        return bridge
    }

    /**
     * A Herdr bridge whose default session has a blocked claude in w1 and the focus on a
     * working one in w2, and whose "work" session (attached: [attached]) has a blocked grok.
     */
    private fun herdrBridgeWithWaitingAgents(blockedInDefault: Boolean = true, blockedInWork: Boolean = true, attached: String = "default"): TerminalBridge {
        val bridge = bridgeWithPostLogin("herdr\n")
        fun agent(pane: String, tab: String, kind: String, blocked: Boolean) = HerdrAgentInfo(paneId = pane, workspaceId = tab.substringBefore(':'), tabId = tab, agent = kind, agentStatus = if (blocked) HerdrAgentStatus.BLOCKED else HerdrAgentStatus.WORKING)
        val main = HerdrSessionSnapshot(
            workspaces = listOf(HerdrWorkspaceInfo("w1", label = "alpha", number = 1), HerdrWorkspaceInfo("w2", label = "beta", number = 2)),
            tabs = listOf(HerdrTabInfo("w1:t1", workspaceId = "w1", label = "build", number = 1), HerdrTabInfo("w2:t1", workspaceId = "w2", label = "tests", number = 1)),
            agents = listOf(agent("w1:p1", "w1:t1", "claude", blockedInDefault), agent("w2:p1", "w2:t1", "codex", false)),
            focusedWorkspaceId = "w2",
            focusedTabId = "w2:t1",
            focusedPaneId = "w2:p1",
        )
        val work = HerdrSessionSnapshot(
            workspaces = listOf(HerdrWorkspaceInfo("w1", label = "gamma", number = 1)),
            tabs = listOf(HerdrTabInfo("w1:t1", workspaceId = "w1", label = "review", number = 1)),
            agents = listOf(agent("w1:p1", "w1:t1", "grok", blockedInWork)),
            focusedWorkspaceId = "w1",
            focusedTabId = "w1:t1",
            focusedPaneId = "w1:p1",
        )
        val herd = mock(HerdSession::class.java)
        `when`(herd.snapshot).thenReturn(MutableStateFlow(main))
        `when`(herd.result).thenReturn(MutableStateFlow(null))
        `when`(herd.failure).thenReturn(MutableStateFlow(null))
        `when`(herd.sessions).thenReturn(MutableStateFlow(listOf("default", "work")))
        `when`(herd.chosen).thenReturn(MutableStateFlow(attached))
        `when`(herd.others).thenReturn(MutableStateFlow(mapOf("work" to work)))
        `when`(herd.attached).thenReturn(MutableStateFlow(attached))
        `when`(herd.keys).thenReturn(MutableStateFlow(HerdrKeys.fromConfig("[keys]\nprefix = \"ctrl+space\"\n")))
        `when`(bridge.herd).thenReturn(herd)
        return bridge
    }

    /** Every string the bar wrote to the PTY, in order. */
    private fun sentBytes(bridge: TerminalBridge): List<String> {
        val captor = ArgumentCaptor.forClass(String::class.java)
        verify(bridge, atLeast(0)).injectString(captor.capture())
        return captor.allValues
    }

    /** The bar on a 411dp-wide phone (a Pixel), docked at the bottom like the console. */
    private fun setBar(
        bridge: TerminalBridge,
        herdrPanel: Boolean = false,
        onRead: (() -> Unit)? = null,
        onPasteScreenshot: (suspend () -> String?)? = null,
        onSlashMenu: (() -> Unit)? = null,
        draft: DictationDraft = DictationDraft(),
        recentOutput: String = "",
        lastReply: String? = null,
        onBoard: (() -> Unit)? = null,
    ) {
        composeTestRule.setContent {
            ConnectBotTheme {
                CompositionLocalProvider(LocalUsageLog provides UsageLog { logged += it }) {
                    // The console owns whether the Herdr panel is open; this stands in for it.
                    var herdrOpen by remember { mutableStateOf(false) }
                    Box(Modifier.width(PHONE_WIDTH).fillMaxSize()) {
                        TerminalComposeBar(
                            bridge = bridge,
                            modifier = Modifier.align(Alignment.BottomCenter).testTag(BAR),
                            onRead = onRead,
                            herdrPanelOpen = herdrOpen,
                            onHerdrPanelChange = if (herdrPanel) {
                                { open -> herdrOpen = open }
                            } else {
                                null
                            },
                            onPasteScreenshot = onPasteScreenshot,
                            onSlashMenu = onSlashMenu,
                            onBoard = onBoard,
                            draft = draft,
                            readRecentOutput = { recentOutput },
                            readLastReply = { lastReply },
                        )
                    }
                }
            }
        }
        composeTestRule.waitForIdle()
    }

    private fun key(label: String): SemanticsNodeInteraction = composeTestRule.onNode(hasClickAction() and hasLabel(label))

    private fun hasLabel(label: String) = SemanticsMatcher("text or description is $label") { node ->
        val texts = node.config.getOrElse(SemanticsProperties.Text) { emptyList() }.map { it.text }
        val descriptions = node.config.getOrElse(SemanticsProperties.ContentDescription) { emptyList() }
        label in texts || label in descriptions
    }

    private fun more() = key(MORE)

    private fun openTray() {
        more().performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag(TRAY_TEST_TAG).assertIsDisplayed()
    }

    private fun assertTrayOpen() {
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag(TRAY_TEST_TAG).assertIsDisplayed()
    }

    private fun assertTrayClosed() {
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag(TRAY_TEST_TAG).assertDoesNotExist()
    }

    /** The labels sit left to right in this order, on one row. */
    private fun assertRowOrder(vararg labels: String) {
        val bounds = labels.map { key(it).assertIsDisplayed().fetchSemanticsNode().boundsInRoot }
        bounds.zipWithNext().forEachIndexed { i, (a, b) ->
            assertTrue("'${labels[i]}' before '${labels[i + 1]}'", a.right <= b.left + 0.5f)
            assertEquals("'${labels[i + 1]}' on the same row", a.center.y, b.center.y, 0.5f)
        }
    }

    /** The dictation field sits between [before] and [after] on row 2, and keeps a usable width. */
    private fun assertFieldBetween(before: String, after: String) {
        val field = composeTestRule.onNode(hasSetTextAction()).assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        val left = key(before).fetchSemanticsNode().boundsInRoot
        val right = key(after).fetchSemanticsNode().boundsInRoot
        assertTrue("field after '$before'", left.right <= field.left + 0.5f)
        assertTrue("field before '$after'", field.right <= right.left + 0.5f)
        val widthDp = field.width / composeTestRule.density.density
        assertTrue("field is $widthDp dp wide, needs 80", widthDp >= 80f)
    }

    private fun assertComfortable(label: String) {
        val node = key(label).fetchSemanticsNode()
        val density = composeTestRule.density.density
        val w = node.boundsInRoot.width / density
        val h = node.boundsInRoot.height / density
        assertTrue("'$label' is $w dp wide, needs 44", w >= 44f)
        assertTrue("'$label' is $h dp tall, needs 46", h >= 46f)
    }

    // Row 1.

    @Test
    fun herdrHost_row1_pageKeysMoreThenNeedsYou() {
        setBar(bridgeWithPostLogin("herdr\n"), herdrPanel = true, onSlashMenu = {})

        assertRowOrder("↑", "↓", "Herdr", MORE, NEEDS_YOU)
        // The tab arrows are gone: swipes switch tabs, Jump to sits in the Herdr panel.
        composeTestRule.onAllNodesWithContentDescription("Previous tab").assertCountEquals0()
        composeTestRule.onAllNodesWithContentDescription("Next tab").assertCountEquals0()
        assertComfortable(NEEDS_YOU)
        // ↑ and ↓ keep their glyphs but page.
        key("PgUp").assertIsDisplayed()
        key("PgDn").assertIsDisplayed()
        // / waits in the tray.
        composeTestRule.onAllNodesWithText("/").assertCountEquals0()
        composeTestRule.onAllNodesWithText("Ctrl").assertCountEquals0()
        composeTestRule.onAllNodesWithText("Goto").assertCountEquals0()
        composeTestRule.onAllNodesWithText("Mode").assertCountEquals0()
    }

    @Test
    fun plainHost_row1_sameLayout_herdrKeysDimmed() {
        setBar(bridgeWithPostLogin(null), herdrPanel = true)

        // The same row as on Sun; the Herdr keys wait, dimmed, for Herdr to answer.
        assertRowOrder("↑", "↓", "Herdr", MORE, NEEDS_YOU)
        key("Herdr").assertIsNotEnabled()
        key(NEEDS_YOU).assertIsNotEnabled()
        // Ctrl lives in the tray on every host.
        composeTestRule.onAllNodesWithText("Ctrl").assertCountEquals0()
    }

    @Test
    fun herdrFoundOnTheHost_withoutAPostLoginCommand_wakesTheHerdrKeys() {
        // lab: Herdr runs there, but its host entry has no post-login command. A Herdr
        // snapshot answering is enough.
        val bridge = herdrBridgeWithScrollback(0, postLogin = null)
        setBar(bridge, herdrPanel = true)

        key("Herdr").assertIsEnabled()
        key(NEEDS_YOU).assertIsEnabled()
        key("Herdr").performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag(HERDR_PANEL_TEST_TAG).assertIsDisplayed()
    }

    @Test
    fun plainHost_row2_escTabFieldEnter_noMicNoTabSwitchers() {
        setBar(bridgeWithPostLogin(null), onRead = {})

        assertRowOrder("Esc", "Tab", ENTER)
        assertFieldBetween("Tab", ENTER)
        // One ⏎ key: no separate Send button.
        composeTestRule.onAllNodesWithText("⏎").assertCountEquals(1)
        composeTestRule.onAllNodesWithContentDescription("Start dictation").assertCountEquals0()
        // Read lives in the tray now.
        composeTestRule.onAllNodesWithContentDescription(READ).assertCountEquals0()
    }

    @Test
    fun herdrHost_row2_escTabFieldEnter_needsYouSitsAboveEnter() {
        setBar(bridgeWithPostLogin("herdr\n"), onSlashMenu = {})

        assertRowOrder("Esc", "Tab", ENTER)
        assertFieldBetween("Tab", ENTER)
        val needsYou = key(NEEDS_YOU).fetchSemanticsNode().boundsInRoot
        val enter = key(ENTER).fetchSemanticsNode().boundsInRoot
        assertTrue("Needs you is above ⏎", needsYou.bottom <= enter.top + 0.5f)
        assertTrue("Needs you lines up over ⏎", needsYou.right > enter.left && needsYou.left < enter.right)
    }

    @Test
    fun needsYou_countsWaitingAgentsInEverySession_andGoesGold() {
        setBar(herdrBridgeWithWaitingAgents(), onBoard = {})

        key("Needs you, 2 waiting").assertIsEnabled().assertTextEquals("Needs you · 2")
    }

    @Test
    fun needsYou_tap_jumpsToTheNextWaitingAgentAfterTheFocus_andSendsNothing() {
        // The focus is on beta's working codex: the default's waiting claude is behind it, so
        // the next one is the grok waiting in work.
        val bridge = herdrBridgeWithWaitingAgents()
        var boards = 0
        setBar(bridge, onBoard = { boards++ })

        key("Needs you, 2 waiting").performClick()
        composeTestRule.waitForIdle()

        val target = ArgumentCaptor.forClass(JumpTarget::class.java)
        val pane = ArgumentCaptor.forClass(String::class.java)
        verify(bridge).jumpToHerdrTab(target.capture() ?: JUMP, pane.capture() ?: "")
        assertEquals("work", target.value.session)
        assertEquals("w1:t1", target.value.tabId)
        assertEquals("w1:p1", pane.value)
        assertEquals(0, boards)
        assertEquals(emptyList<String>(), sentBytes(bridge))
        assertEquals(listOf(UsageActions.NEEDS_YOU_JUMP), logged)
    }

    @Test
    fun needsYou_tapWithNoneWaiting_opensTheBoard() {
        val bridge = herdrBridgeWithWaitingAgents(blockedInDefault = false, blockedInWork = false)
        var boards = 0
        setBar(bridge, onBoard = { boards++ })

        key("Needs you, 0 waiting").assertTextEquals("Needs you").performClick()
        composeTestRule.waitForIdle()

        assertEquals(1, boards)
        verify(bridge, never()).jumpToHerdrTab(any(JumpTarget::class.java) ?: JUMP, any())
        assertEquals(listOf(UsageActions.NEEDS_YOU_BOARD), logged)
    }

    @Test
    fun needsYou_longPress_opensTheBoard_evenWithAgentsWaiting() {
        val bridge = herdrBridgeWithWaitingAgents()
        var boards = 0
        setBar(bridge, onBoard = { boards++ })

        key("Needs you, 2 waiting").performTouchInput { longClick() }
        composeTestRule.waitForIdle()

        assertEquals(1, boards)
        assertEquals(listOf(UsageActions.NEEDS_YOU_BOARD), logged)
    }

    @Test
    fun needsYou_onAPlainHost_takesNoTapAndNoLongPress() {
        var boards = 0
        setBar(bridgeWithPostLogin(null), onBoard = { boards++ })

        key(NEEDS_YOU).performClick()
        key(NEEDS_YOU).performTouchInput { longClick() }
        composeTestRule.waitForIdle()

        assertEquals(0, boards)
        assertEquals(emptyList<String>(), logged)
    }

    @Test
    fun slashKey_hiddenWithoutACallback() {
        setBar(bridgeWithPostLogin(null), onRead = {})
        openTray()

        composeTestRule.onAllNodesWithText("/").assertCountEquals0()
    }

    @Test
    fun traySlashKey_opensTheMenu() {
        var opened = false
        setBar(bridgeWithPostLogin(null), onRead = {}, onSlashMenu = { opened = true })
        composeTestRule.onAllNodesWithText("/").assertCountEquals0()
        openTray()
        assertComfortable("/")

        key("/").performClick()
        assertTrue(opened)
        assertEquals(listOf(UsageActions.BAR_TRAY_OPEN, UsageActions.BAR_SLASH_MENU), logged)
    }

    @Test
    fun herdrKey_onRow1_opensAndClosesTheHerdrPanel() {
        setBar(herdrBridgeWithScrollback(0), herdrPanel = true)
        composeTestRule.onNodeWithTag(HERDR_PANEL_TEST_TAG).assertDoesNotExist()

        key("Herdr").assertIsEnabled().performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag(HERDR_PANEL_TEST_TAG).assertIsDisplayed()
        // One group at a time, Agents first.
        composeTestRule.onNodeWithText("Next waiting agent").assertIsDisplayed()
        composeTestRule.onAllNodesWithText("Split right").assertCountEquals0()

        key("Herdr").performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag(HERDR_PANEL_TEST_TAG).assertDoesNotExist()
        assertEquals(listOf(UsageActions.HERDR_SHEET_OPEN), logged)
    }

    @Test
    fun herdrKey_sendsHerdrsPrefixAsThePanelOpens() {
        val bridge = herdrBridgeWithScrollback(0)
        setBar(bridge, herdrPanel = true)

        key("Herdr").performClick()
        composeTestRule.waitForIdle()

        verify(checkNotNull(bridge.herd)).armPrefix()
    }

    @Test
    fun herdrPanel_isCompact_andMovesTheTerminalUp() {
        setBar(herdrBridgeWithScrollback(0), herdrPanel = true)
        val closed = composeTestRule.onNodeWithTag(BAR).fetchSemanticsNode().boundsInRoot

        key("Herdr").performClick()
        composeTestRule.waitForIdle()
        val open = composeTestRule.onNodeWithTag(BAR).fetchSemanticsNode().boundsInRoot
        val panel = composeTestRule.onNodeWithTag(HERDR_PANEL_TEST_TAG).fetchSemanticsNode().boundsInRoot
        val density = composeTestRule.density.density

        // The panel adds its own height to the bar, so the terminal moves up, and it stays
        // well short of the screen: it is a panel, not a full-screen sheet.
        assertEquals(panel.height, open.height - closed.height, 1f)
        assertTrue("the panel is ${panel.height / density} dp tall", panel.height / density <= 330f)
    }

    @Test
    fun herdrPanel_andTray_closeEachOther() {
        setBar(herdrBridgeWithScrollback(0), herdrPanel = true)

        openTray()
        key("Herdr").performClick()
        composeTestRule.waitForIdle()
        assertTrayClosed()
        composeTestRule.onNodeWithTag(HERDR_PANEL_TEST_TAG).assertIsDisplayed()

        more().performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag(HERDR_PANEL_TEST_TAG).assertDoesNotExist()
        assertTrayOpen()
    }

    @Test
    fun herdrPanel_backCloses() {
        setBar(herdrBridgeWithScrollback(0), herdrPanel = true)
        key("Herdr").performClick()
        composeTestRule.waitForIdle()

        composeTestRule.runOnUiThread { composeTestRule.activity.onBackPressedDispatcher.onBackPressed() }

        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag(HERDR_PANEL_TEST_TAG).assertDoesNotExist()
    }

    @Test
    fun herdrKey_disabledUntilTheHerdrSideIsUp_rowDoesNotShift() {
        setBar(bridgeWithPostLogin("herdr\n"))

        key("Herdr").assertIsNotEnabled()
        assertRowOrder("↑", "↓", "Herdr", MORE, NEEDS_YOU)
        assertComfortable("Herdr")
    }

    @Test
    fun row1Up_tapPagesUpOnce() {
        val bridge = bridgeWithPostLogin(null)
        setBar(bridge)

        key("↑").performClick()
        composeTestRule.waitForIdle()

        verify(bridge, times(1)).injectString(ESC + "[5~")
        verify(bridge, never()).injectString(ESC + "[A")
    }

    @Test
    fun row1Up_holdRepeatsPageUp() {
        val bridge = bridgeWithPostLogin(null)
        setBar(bridge)

        key("↑").performTouchInput {
            down(center)
            advanceEventTime(1_000)
        }
        composeTestRule.mainClock.advanceTimeBy(1_000)
        key("↑").performTouchInput { up() }

        // One on press, then repeats every 60 ms after the first 400 ms.
        verify(bridge, atLeast(5)).injectString(ESC + "[5~")
    }

    @Test
    fun row1Down_holdRepeatsPageDown() {
        val bridge = bridgeWithPostLogin(null)
        setBar(bridge)

        key("↓").performTouchInput {
            down(center)
            advanceEventTime(1_000)
        }
        composeTestRule.mainClock.advanceTimeBy(1_000)
        key("↓").performTouchInput { up() }

        verify(bridge, atLeast(5)).injectString(ESC + "[6~")
    }

    @Test
    fun trayArrows_upAndDownSendTheArrowKeys() {
        val bridge = bridgeWithPostLogin(null)
        setBar(bridge)
        openTray()
        logged.clear()

        key("Up").performClick()
        key("Down").performClick()

        assertEquals(listOf(ESC + "[A", ESC + "[B"), sentBytes(bridge))
        assertEquals(listOf(UsageActions.KEY_UP, UsageActions.KEY_DOWN), logged)
    }

    @Test
    fun theDraftViewsSend_goesOutLikeEnter() {
        val bridge = bridgeWithPostLogin(null)
        val draft = DictationDraft()
        setBar(bridge, draft = draft)
        composeTestRule.onNode(hasSetTextAction()).performTextInput("a long voice note")

        composeTestRule.runOnUiThread { draft.requestSend() }
        composeTestRule.waitForIdle()
        composeTestRule.mainClock.advanceTimeBy(100)

        assertEquals(listOf("a long voice note", CR), sentBytes(bridge))
        assertEquals(listOf(UsageActions.DRAFT_SEND), logged)
        assertEquals("", draft.text)
    }

    @Test
    fun barKeys_emptyEnterEscAndTabSendTheirBytes() {
        val bridge = bridgeWithPostLogin(null)
        setBar(bridge)

        key(ENTER).performClick()
        key("Esc").performClick()
        key("Tab").performClick()

        verify(bridge).injectString(CR)
        verify(bridge).injectString(ESC)
        verify(bridge).injectString(TAB)
        assertEquals(listOf(UsageActions.KEY_ENTER, UsageActions.KEY_ESC, UsageActions.KEY_TAB), logged)
    }

    @Test
    fun enterKey_withText_isSend_thenGoesBackToEnter() {
        val bridge = bridgeWithPostLogin(null)
        setBar(bridge)
        composeTestRule.onNode(hasSetTextAction()).performTextInput("git status")

        // With text in the field the one ⏎ key reads as Send and sends the text, then Enter.
        composeTestRule.onAllNodesWithContentDescription(ENTER).assertCountEquals0()
        key(SEND).performClick()
        composeTestRule.mainClock.advanceTimeBy(100)

        assertEquals(listOf("git status", CR), sentBytes(bridge))
        assertEquals(listOf(UsageActions.SEND), logged)
        composeTestRule.onNode(hasSetTextAction()).assertTextContains("")
        key(ENTER).assertIsDisplayed()
    }

    // The tray.

    @Test
    fun tray_closedAtFirst_moreOpensAndCloses() {
        setBar(bridgeWithPostLogin("herdr\n"), onRead = {})
        assertTrayClosed()

        openTray()
        listOf("Left", "Up", "Down", "Right", "Mode", READ, "Sidebar").forEach {
            key(it).assertIsDisplayed()
        }
        // The keys Ormus never used are gone from the tray (Goto and copy mode live in the
        // Herdr panel; Needs you sits on row 1, outside the tray).
        listOf("Stop", "Ctrl", "y", "n", "Goto", "Scroll").forEach {
            composeTestRule.onAllNodesWithText(it).assertCountEquals0()
        }
        composeTestRule.onNodeWithTag(TRAY_TEST_TAG).assert(hasAnyDescendant(hasText("Needs you")).not())

        more().performClick()
        assertTrayClosed()
        assertEquals(listOf(UsageActions.BAR_TRAY_OPEN, UsageActions.BAR_TRAY_CLOSE), logged)
    }

    @Test
    fun tray_takesItsOwnHeight_soTheTerminalMovesUp() {
        setBar(bridgeWithPostLogin("herdr\n"), onRead = {})
        val closed = composeTestRule.onNodeWithTag(BAR).fetchSemanticsNode().boundsInRoot
        val terminalFloorClosed = closed.top

        openTray()
        val open = composeTestRule.onNodeWithTag(BAR).fetchSemanticsNode().boundsInRoot
        val tray = composeTestRule.onNodeWithTag(TRAY_TEST_TAG).fetchSemanticsNode().boundsInRoot

        // The console pads the terminal by the bar's height, so a taller bar moves the
        // terminal up by the tray's height instead of the tray covering it.
        assertEquals(tray.height, open.height - closed.height, 1f)
        assertEquals(open.top, tray.top, 0.5f)
        assertTrue("terminal floor moves up", open.top < terminalFloorClosed)
    }

    @Test
    fun trayRead_opensTheReader() {
        var opened = false
        setBar(bridgeWithPostLogin(null), onRead = { opened = true })
        openTray()

        key(READ).performClick()

        assertTrue(opened)
        assertEquals(listOf(UsageActions.BAR_TRAY_OPEN, UsageActions.READER_OPEN), logged)
    }

    @Test
    fun tray_swipeUpOnBarOpens_swipeDownCloses() {
        setBar(bridgeWithPostLogin(null))

        composeTestRule.onNodeWithTag(BAR).performTouchInput { swipeUp() }
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag(TRAY_TEST_TAG).assertIsDisplayed()

        composeTestRule.onNodeWithTag(TRAY_TEST_TAG).performTouchInput { swipeDown() }
        assertTrayClosed()

        composeTestRule.onNodeWithTag(BAR).performTouchInput { swipeUp() }
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag(BAR).performTouchInput { swipeDown() }
        assertTrayClosed()

        assertEquals(
            listOf(
                UsageActions.BAR_TRAY_SWIPE,
                UsageActions.BAR_TRAY_CLOSE,
                UsageActions.BAR_TRAY_SWIPE,
                UsageActions.BAR_TRAY_CLOSE,
            ),
            logged,
        )
    }

    @Test
    fun tray_backCloses() {
        setBar(bridgeWithPostLogin(null))
        openTray()

        composeTestRule.runOnUiThread { composeTestRule.activity.onBackPressedDispatcher.onBackPressed() }

        assertTrayClosed()
        assertEquals(listOf(UsageActions.BAR_TRAY_OPEN, UsageActions.BAR_TRAY_CLOSE), logged)
    }

    @Test
    fun trayKeys_sendTheSameBytesAsBefore_andKeepTheTrayOpen() {
        // Label, the bytes the key sent before the tray, and its unchanged usage id.
        val keys = listOf(
            Triple("Mode", ESC + "[Z", UsageActions.KEY_MODE),
            Triple("Left", ESC + "[D", UsageActions.KEY_LEFT),
            Triple("Up", ESC + "[A", UsageActions.KEY_UP),
            Triple("Down", ESC + "[B", UsageActions.KEY_DOWN),
            Triple("Right", ESC + "[C", UsageActions.KEY_RIGHT),
            Triple("Sidebar", CTRL_B + "b", UsageActions.HERDR_SIDEBAR),
            Triple("Clear", 21.toChar().toString(), UsageActions.KEY_CLEAR_LINE),
        )
        val bridge = bridgeWithPostLogin("herdr\n")
        setBar(bridge)
        openTray()

        // One open, then every key in a row: the tray stays up between presses.
        for ((label, bytes, id) in keys) {
            logged.clear()
            key(label).performClick()
            verify(bridge).injectString(bytes)
            assertTrayOpen()
            assertEquals("$label logs its own id", listOf(id), logged)
        }
    }

    @Test
    fun trayPaste_addsTheScreenshotPathToTheDraft() {
        setBar(bridgeWithPostLogin(null), onPasteScreenshot = { "/tmp/logos-pastes/shot-1.png" })
        composeTestRule.onNode(hasSetTextAction()).performTextInput("look at")
        openTray()

        key(PASTE).performClick()
        assertTrayOpen()

        composeTestRule.onNode(hasSetTextAction()).assertTextContains("look at read /tmp/logos-pastes/shot-1.png ")
        assertEquals(listOf(UsageActions.BAR_TRAY_OPEN, UsageActions.PASTE_SCREENSHOT), logged)
    }

    @Test
    fun plainHost_trayHerdrKeysDimmed() {
        setBar(bridgeWithPostLogin(null), onPasteScreenshot = { null })
        openTray()

        key(PASTE).assertIsEnabled()
        key("Sidebar").assertIsNotEnabled()
    }

    @Test
    fun trayBottom_sendsOneBurstOfPageDowns_loggedOnce() {
        val bridge = bridgeWithPostLogin("herdr\n")
        setBar(bridge)
        openTray()
        logged.clear()

        key("Jump to bottom").performClick()

        assertEquals(listOf((ESC + "[6~").repeat(40)), sentBytes(bridge))
        assertEquals(listOf(UsageActions.KEY_BOTTOM), logged)
        assertTrayOpen()
    }

    @Test
    fun trayReplyKey_copiesTheAgentsLastReplyToThePhone() {
        setBar(bridgeWithPostLogin("herdr\n"), lastReply = "Done: all green.")
        openTray()
        logged.clear()

        key("Copy last reply").performClick()
        composeTestRule.waitForIdle()

        val clipboard = composeTestRule.activity.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
        assertEquals("Done: all green.", clipboard.primaryClip!!.getItemAt(0).text.toString())
        assertEquals(listOf(UsageActions.BAR_REPLY_COPY), logged)
    }

    @Test
    fun trayUrlKey_copiesTheLatestUrlToThePhone() {
        setBar(bridgeWithPostLogin("herdr\n"), recentOutput = "PR: https://github.com/o/r/pull/7\nlive at https://app.example.com/x.")
        openTray()
        logged.clear()

        key("Copy latest URL").performClick()
        composeTestRule.waitForIdle()

        val clipboard = composeTestRule.activity.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
        assertEquals("https://app.example.com/x", clipboard.primaryClip!!.getItemAt(0).text.toString())
        assertEquals(listOf(UsageActions.BAR_URL_COPY), logged)
        assertTrayOpen()
    }

    @Test
    fun traySidebar_togglesHerdrsSidePanel_andKeepsTheTrayOpen() {
        val bridge = bridgeWithPostLogin("herdr\n")
        setBar(bridge)
        openTray()
        logged.clear()

        key("Sidebar").performClick()
        key("Sidebar").performClick()

        // Herdr's default prefix (no Herdr side here), then b: Herdr's own default for the side panel.
        assertEquals(listOf(CTRL_B + "b", CTRL_B + "b"), sentBytes(bridge))
        assertEquals(listOf(UsageActions.HERDR_SIDEBAR, UsageActions.HERDR_SIDEBAR), logged)
        assertTrayOpen()
    }

    @Test
    fun traySidebar_sendsTheHostsPrefix() {
        val bridge = herdrBridgeWithScrollback(0)
        setBar(bridge)
        openTray()
        logged.clear()

        key("Sidebar").performClick()

        // Sun's config sets prefix = "ctrl+space": NUL, then b.
        assertEquals(listOf(NUL + "b"), sentBytes(bridge))
        assertEquals(listOf(UsageActions.HERDR_SIDEBAR), logged)
    }

    @Test
    fun draft_survivesTrayUse() {
        val bridge = bridgeWithPostLogin("herdr\n")
        setBar(bridge)
        val field = composeTestRule.onNode(hasSetTextAction())
        field.performTextInput("run the four checks")

        openTray()
        key("Mode").performClick()
        composeTestRule.onNodeWithTag(TRAY_TEST_TAG).performTouchInput { swipeDown() }
        openTray()
        more().performClick()
        assertTrayClosed()

        field.assertTextContains("run the four checks")
        verify(bridge, never()).injectString("run the four checks")
        composeTestRule.onNodeWithContentDescription(SEND).performClick()
        verify(bridge).injectString("run the four checks")
    }

    @Test
    fun everyKey_isAtLeast44dpWideAnd46dpTall_onA411dpPhone() {
        setBar(
            bridgeWithPostLogin("herdr\n"),
            herdrPanel = true,
            onRead = {},
            onPasteScreenshot = { null },
            onSlashMenu = {},
        )
        listOf("↑", "↓", "Herdr", MORE, NEEDS_YOU, "Esc", "Tab", ENTER).forEach { assertComfortable(it) }
        openTray()
        listOf("Left", "Up", "Down", "Right", "Jump to bottom", "Mode", "Clear", READ, "Copy latest URL", "Copy last reply", PASTE, "Sidebar", "/")
            .forEach { assertComfortable(it) }
    }

    @Test
    fun plainHost_everyBarKey_isAtLeast44dpWide() {
        setBar(bridgeWithPostLogin(null))
        listOf("↑", "↓", "Herdr", MORE, NEEDS_YOU, "Esc", "Tab", ENTER).forEach { assertComfortable(it) }
    }

    private fun androidx.compose.ui.test.SemanticsNodeInteractionCollection.assertCountEquals0() {
        assertEquals(0, fetchSemanticsNodes().size)
    }

    private companion object {
        const val BAR = "composeBar"
        const val MORE = "More keys"
        const val READ = "Read output"
        const val SEND = "Send"
        const val ENTER = "Enter"
        const val PASTE = "Paste latest screenshot"
        val PHONE_WIDTH = 411.dp
        const val NEEDS_YOU = "Needs you"

        // Stands in for Mockito's matchers, which return null for Kotlin's non-null parameters.
        val JUMP = JumpTarget("", "", "", 0, "", 0, null, HerdrAgentStatus.UNKNOWN, false)

        val NUL: String = 0.toChar().toString()
        val CTRL_B: String = 2.toChar().toString()
        val ETX: String = 3.toChar().toString()
        val TAB: String = 9.toChar().toString()
        val CR: String = 13.toChar().toString()
        val ESC: String = 27.toChar().toString()
    }
}
