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

import android.content.Context
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.core.content.edit
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.connectbot.transport.CommandOutput
import org.connectbot.ui.components.NeedsYouKey
import org.connectbot.ui.screens.chat.ASK_OPTION_TAG
import org.connectbot.ui.screens.console.HerdrSwipeIntro
import org.connectbot.ui.theme.ConnectBotTheme
import org.connectbot.usage.UsageActions
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import solutions.ormus.logos.herd.AgentsBoard
import solutions.ormus.logos.herd.Herd
import solutions.ormus.logos.herd.HerdSession
import solutions.ormus.logos.herd.HerdrAgentInfo
import solutions.ormus.logos.herd.HerdrAgentStatus
import solutions.ormus.logos.herd.HerdrJump
import solutions.ormus.logos.herd.HerdrSessionSnapshot
import solutions.ormus.logos.herd.HerdrSessions
import solutions.ormus.logos.herd.HerdrTabInfo
import solutions.ormus.logos.herd.HerdrWorkspaceInfo
import solutions.ormus.logos.herd.parseHerdrSnapshot

/**
 * Renders the Herd screen and the Herdr command sheet on a device from the
 * snapshot recorded on Sun, and saves a screenshot of each to
 * /data/local/tmp/herd-*.png (pull with adb) as evidence of how they look.
 */
@RunWith(AndroidJUnit4::class)
class HerdScreenshotTest {

    @get:Rule
    val composeTestRule = createAndroidComposeRule<ComponentActivity>()

    private val fixture = checkNotNull(javaClass.getResourceAsStream("/herdr/snapshot-sun-mixed.json")).readBytes().decodeToString()

    private fun screencap(name: String) {
        composeTestRule.waitForIdle()
        // Let the first frame reach the display before capturing it.
        Thread.sleep(SCREENCAP_SETTLE_MS)
        InstrumentationRegistry.getInstrumentation().uiAutomation
            .executeShellCommand("screencap -p /data/local/tmp/$name.png")
            .close()
        // screencap runs asynchronously in the shell; give it a moment to write.
        Thread.sleep(SCREENCAP_SETTLE_MS)
    }

    @Test
    fun herdScreen() {
        val snapshot = checkNotNull(parseHerdrSnapshot(fixture))
        composeTestRule.setContent {
            ConnectBotTheme {
                // The test activity draws edge to edge; keep clear of the bars as the app's dialog does.
                Box(Modifier.safeDrawingPadding()) {
                    HerdScreen(snapshot = snapshot, onOpenAgent = {}, onRefresh = {}, onOpenCommands = {}, onClose = {})
                }
            }
        }

        composeTestRule.onAllNodesWithTag(HERD_CARD_TAG).assertCountEquals(6)
        screencap("herd-screen")
    }

    @Test
    fun jumpPicker() {
        val work = checkNotNull(javaClass.getResourceAsStream("/herdr/snapshot-picker-test.json")).readBytes().decodeToString()
        val targets = HerdrJump.targets(mapOf("default" to checkNotNull(parseHerdrSnapshot(fixture)), "work" to checkNotNull(parseHerdrSnapshot(work))))
        composeTestRule.setContent {
            ConnectBotTheme {
                Surface(color = MaterialTheme.colorScheme.surfaceContainer) {
                    Box(Modifier.fillMaxSize().safeDrawingPadding()) { HerdrJumpContent(targets, onPick = {}) }
                }
            }
        }

        screencap("herd-jump")
        composeTestRule.onNodeWithTag(HERDR_JUMP_SEARCH_TAG).performTextInput("work")
        screencap("herd-jump-search")
    }

    @Test
    fun panelWithTwoSessions() {
        val session = HerdSession(
            hostName = "Sun",
            runner = { command ->
                val out = when (command) {
                    Herd.snapshotCommand -> fixture
                    HerdrSessions.listCommand -> """{"sessions":[{"name":"default","running":true},{"name":"work","running":true}]}"""
                    else -> """{"result":{}}"""
                }
                CommandOutput(out, "", 0)
            },
            sendKeys = {},
            scope = CoroutineScope(Dispatchers.Unconfined),
            io = Dispatchers.Unconfined,
        )
        composeTestRule.setContent {
            ConnectBotTheme {
                Surface(color = MaterialTheme.colorScheme.surfaceContainerLow) {
                    Box(Modifier.fillMaxSize().safeDrawingPadding()) {
                        HerdrCommandPanel(session = session, section = HerdrSection.TABS, onSection = {}, onJumpTo = {})
                    }
                }
            }
        }

        screencap("herd-sheet-sessions")
    }

    @Test
    fun commandSheet() {
        val session = HerdSession(
            hostName = "Sun",
            runner = { command ->
                CommandOutput(if (command == Herd.snapshotCommand) fixture else """{"result":{}}""", "", 0)
            },
            sendKeys = {},
            scope = CoroutineScope(Dispatchers.Unconfined),
            io = Dispatchers.Unconfined,
        )
        composeTestRule.setContent {
            ConnectBotTheme {
                Surface(color = MaterialTheme.colorScheme.surfaceContainerLow) {
                    Box(Modifier.fillMaxSize().safeDrawingPadding()) { HerdrCommandPanel(session = session, section = HerdrSection.PANES, onSection = {}) }
                }
            }
        }

        screencap("herd-sheet-top")
        composeTestRule.onNodeWithText("Close pane").performClick()
        screencap("herd-sheet-confirm")
    }

    @Test
    fun editablePanel() {
        val session = HerdSession(
            hostName = "Sun",
            runner = { command ->
                CommandOutput(if (command == Herd.snapshotCommand) fixture else """{"result":{}}""", "", 0)
            },
            sendKeys = {},
            scope = CoroutineScope(Dispatchers.Unconfined),
            io = Dispatchers.Unconfined,
        )
        // Its own preferences file, so the app's real panel layout is never touched.
        val prefs = InstrumentationRegistry.getInstrumentation().targetContext
            .getSharedPreferences("herdr-panel-screenshot", Context.MODE_PRIVATE)
        prefs.edit { clear() }
        val store = HerdrPanelStore(prefs)
        store.hide(UsageActions.HERDR_SHEET_RELOAD_CONFIG)
        store.add(HerdrPanelKey("Redraw", "shift+r"))
        composeTestRule.setContent {
            ConnectBotTheme {
                Surface(color = MaterialTheme.colorScheme.surfaceContainerLow) {
                    Box(Modifier.fillMaxSize().safeDrawingPadding()) {
                        HerdrCommandPanel(session = session, section = HerdrSection.SESSION, onSection = {}, store = store)
                    }
                }
            }
        }

        screencap("herd-panel-session")
        composeTestRule.onNodeWithText("Goto").performTouchInput { longClick() }
        screencap("herd-panel-menu")
        composeTestRule.onNodeWithText("Cancel").performClick()
        composeTestRule.onNodeWithText("Add key").performClick()
        composeTestRule.onNodeWithText("Label").performTextInput("Find")
        composeTestRule.onNodeWithText("Key").performTextInput("super+f")
        screencap("herd-panel-add")
        prefs.edit { clear() }
    }

    @Test
    fun agentsBoard() {
        fun agent(pane: String, kind: String, state: HerdrAgentStatus, title: String) = HerdrAgentInfo(
            paneId = pane,
            workspaceId = pane.substringBefore(':'),
            tabId = pane.replace(":p", ":t"),
            agent = kind,
            agentStatus = state,
            terminalTitleStripped = title,
        )
        fun snapshot(workspaces: List<String>, agents: List<HerdrAgentInfo>) = HerdrSessionSnapshot(
            workspaces = workspaces.mapIndexed { i, label -> HerdrWorkspaceInfo(workspaceId = "w${i + 1}", label = label, number = i + 1) },
            tabs = agents.mapIndexed { i, a -> HerdrTabInfo(tabId = a.tabId, workspaceId = a.workspaceId, label = "tab ${i + 1}", number = i + 1) },
            agents = agents,
        )
        val minute = 60_000L
        val now = 10 * 60 * minute
        val since = mapOf("default/w1:p1" to now - 3 * minute, "work/w1:p1" to now - 12 * minute, "default/w2:p1" to now - 40 * minute)
        val cards = AgentsBoard.cards(
            mapOf(
                "default" to snapshot(
                    listOf("api", "site"),
                    listOf(
                        agent("w1:p1", "claude", HerdrAgentStatus.BLOCKED, "Migrate the billing table"),
                        agent("w1:p2", "codex", HerdrAgentStatus.WORKING, "Flaky parser tests"),
                        agent("w2:p1", "claude", HerdrAgentStatus.DONE, "Docs site broken links"),
                        agent("w2:p2", "grok", HerdrAgentStatus.IDLE, "Theme pass"),
                    ),
                ),
                "work" to snapshot(
                    listOf("ops"),
                    listOf(
                        agent("w1:p1", "codex", HerdrAgentStatus.BLOCKED, "Rotate the staging certs"),
                        agent("w1:p2", "claude", HerdrAgentStatus.WORKING, "Release notes draft"),
                    ),
                ),
            ),
        ) { session, pane -> since["$session/$pane"] }
        // In the board's own dialog: edge to edge, as the app shows it.
        composeTestRule.setContent {
            ConnectBotTheme {
                Dialog(onDismissRequest = {}, properties = BOARD_DIALOG_PROPERTIES) {
                    AgentsBoardContent(
                        cards = cards,
                        loading = false,
                        now = now,
                        canChat = { it.agent != "grok" },
                        onChat = {},
                        onJump = {},
                        onAnswer = { _, _ -> },
                        onPick = { _, _ -> },
                        onReply = { _, _ -> },
                        readScreen = { "Run the migration on staging?\n> 1. Yes\n  2. Yes, and run it on prod after\n  3. No\n\nOpus  ~/src/api  main  ctx 68% (137k/200k)" },
                        onRefresh = {},
                        onClose = {},
                    )
                }
            }
        }
        composeTestRule.waitForIdle()
        val bars = checkNotNull(ViewCompat.getRootWindowInsets(composeTestRule.activity.window.decorView))
        val statusBar = bars.getInsets(WindowInsetsCompat.Type.statusBars()).top
        val navigationBarTop = composeTestRule.activity.window.decorView.height - bars.getInsets(WindowInsetsCompat.Type.navigationBars()).bottom
        assertTrue("status bar $statusBar px", statusBar > 0)

        val header = composeTestRule.onNodeWithText("Needs you · 2", substring = true).fetchSemanticsNode().positionOnScreen.y
        assertTrue("first header top $header px is under the status bar ($statusBar px)", header >= statusBar)
        composeTestRule.onAllNodesWithText("Run the migration on staging?")[0].assertExists()
        // claude's three options as keys; codex's stay text, with Approve and Deny.
        composeTestRule.onAllNodesWithTag(ASK_OPTION_TAG).assertCountEquals(3)
        screencap("herd-board")

        composeTestRule.onNode(hasScrollAction()).performScrollToNode(hasText("Theme pass"))
        composeTestRule.waitForIdle()
        val last = composeTestRule.onAllNodesWithTag(BOARD_CARD_TAG).fetchSemanticsNodes().maxBy { it.positionOnScreen.y }
        val lastBottom = last.positionOnScreen.y + last.size.height
        assertTrue("last card bottom $lastBottom px is under the navigation bar ($navigationBarTop px)", lastBottom <= navigationBarTop)
        screencap("herd-board-end")

        composeTestRule.onNode(hasScrollAction()).performScrollToNode(hasText("Migrate the billing table"))
        composeTestRule.onAllNodesWithTag(BOARD_REPLY_TAG)[0].performTextInput("2")
        screencap("herd-board-reply")
    }

    @Test
    fun panelBoardKey() {
        composeTestRule.setContent {
            ConnectBotTheme {
                Surface(color = MaterialTheme.colorScheme.surfaceContainerLow) {
                    Box(Modifier.fillMaxSize().safeDrawingPadding()) {
                        HerdrCommandContent(
                            snapshot = checkNotNull(parseHerdrSnapshot(fixture)),
                            result = null,
                            section = HerdrSection.AGENTS,
                            onSection = {},
                            onRun = { _, _ -> },
                            onChat = {},
                            onBoard = {},
                            needsYou = 2,
                        )
                    }
                }
            }
        }

        screencap("herd-panel-board-key")
    }

    @Test
    fun swipeIntro() {
        composeTestRule.setContent {
            ConnectBotTheme {
                Surface(color = MaterialTheme.colorScheme.background) {
                    Box(Modifier.fillMaxSize().safeDrawingPadding()) {
                        HerdrSwipeIntro(onDismiss = {}, modifier = Modifier.align(Alignment.TopCenter))
                    }
                }
            }
        }

        screencap("herd-swipe-intro")
    }

    @Test
    fun needsYouKey() {
        composeTestRule.setContent {
            ConnectBotTheme {
                Surface(color = MaterialTheme.colorScheme.surfaceContainer) {
                    // Row 1's share for the key on a 411dp phone (two of 6.4 weights), in each state.
                    Column(Modifier.fillMaxSize().safeDrawingPadding().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        NeedsYouKey(waiting = 0, onClick = {}, onLongClick = {}, enabled = true, modifier = Modifier.width(118.dp))
                        NeedsYouKey(waiting = 3, onClick = {}, onLongClick = {}, enabled = true, modifier = Modifier.width(118.dp))
                        NeedsYouKey(waiting = 12, onClick = {}, onLongClick = {}, enabled = true, modifier = Modifier.width(118.dp))
                        NeedsYouKey(waiting = 0, onClick = {}, onLongClick = {}, enabled = false, modifier = Modifier.width(118.dp))
                    }
                }
            }
        }

        screencap("herd-needs-you-key")
    }

    private companion object {
        const val SCREENCAP_SETTLE_MS = 1_500L
    }
}
