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
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onChildAt
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.connectbot.usage.LocalUsageLog
import org.connectbot.usage.UsageActions
import org.connectbot.usage.UsageLog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import solutions.ormus.logos.herd.AgentsBoard
import solutions.ormus.logos.herd.BoardCard
import solutions.ormus.logos.herd.HerdAnswer
import solutions.ormus.logos.herd.HerdAnswerOutcome
import solutions.ormus.logos.herd.HerdrAgentStatus
import solutions.ormus.logos.herd.boardAgent
import solutions.ormus.logos.herd.boardSnapshot

/** The Agents board on a phone's width: its sections, its empty states, and where each card action goes. */
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "w400dp-h1200dp")
class AgentsBoardScreenTest {
    @get:Rule
    val composeTestRule = createAndroidComposeRule<ComponentActivity>()

    // Two sessions whose pane ids repeat: an action must name the session as well as the pane.
    private val cards = AgentsBoard.cards(
        mapOf(
            "default" to boardSnapshot(
                workspaces = listOf("w1" to "alpha"),
                tabs = listOf("w1:t1" to "build", "w1:t2" to "docs"),
                agents = listOf(
                    boardAgent("w1:p1", "w1:t1", "claude", HerdrAgentStatus.WORKING),
                    boardAgent("w1:p2", "w1:t2", "grok", HerdrAgentStatus.IDLE),
                ),
            ),
            "work" to boardSnapshot(
                workspaces = listOf("w1" to "gamma"),
                tabs = listOf("w1:t1" to "review"),
                agents = listOf(boardAgent("w1:p1", "w1:t1", "claude", HerdrAgentStatus.BLOCKED)),
            ),
        ),
    )

    private val chats = mutableListOf<String>()
    private val jumps = mutableListOf<String>()
    private val answers = mutableListOf<String>()
    private val replies = mutableListOf<String>()
    private val reads = mutableListOf<String>()
    private val logged = mutableListOf<String>()

    private fun BoardCard.id() = "$session/$paneId"

    private fun show(
        shown: List<BoardCard> = cards,
        loading: Boolean = false,
        answer: HerdAnswerOutcome? = null,
        screen: String = "Do you want to proceed?\n> 1. Yes\nOpus  ~/src  ctx 42% (84k/200k)",
        windowInsets: WindowInsets = WindowInsets(0),
    ) {
        composeTestRule.setContent {
            CompositionLocalProvider(LocalUsageLog provides UsageLog { logged += it }) {
                AgentsBoardContent(
                    cards = shown,
                    loading = loading,
                    now = 0,
                    // Grok has no transcript the chat reads.
                    canChat = { it.agent == "claude" },
                    onChat = { chats += it.id() },
                    onJump = { jumps += it.id() },
                    onAnswer = { card, reply -> answers += "${card.id()} $reply" },
                    onReply = { card, text -> replies += "${card.id()} $text" },
                    readScreen = { card ->
                        reads += card.id()
                        screen
                    },
                    onRefresh = {},
                    onClose = {},
                    answer = answer,
                    windowInsets = windowInsets,
                )
            }
        }
        composeTestRule.waitForIdle()
    }

    private fun card(title: String) = composeTestRule.onNodeWithText(title)

    @Test
    fun sections_inOrder_withCountsAndEmptyStates() {
        show()

        assertHeaders()
        composeTestRule.onNodeWithText("Nothing finished unseen").assertIsDisplayed()
        composeTestRule.onAllNodesWithTag(BOARD_CARD_TAG).assertCountEquals(3)
        // The card says where it lives: workspace, tab, session.
        composeTestRule.onNodeWithText("gamma · review · work").assertIsDisplayed()
    }

    @Test
    @Config(qualifiers = "w1000dp-h1200dp")
    fun wide_showsTheSameSectionsSideBySide() {
        show()

        assertHeaders()
        // Side by side: every header on one line.
        val tops = (0 until 4).map { composeTestRule.onAllNodesWithTag(BOARD_HEADER_TAG)[it].fetchSemanticsNode().boundsInRoot.top }
        assertEquals(1, tops.distinct().size)
    }

    private fun assertHeaders() {
        val headers = composeTestRule.onAllNodesWithTag(BOARD_HEADER_TAG)
        headers.assertCountEquals(4)
        // In screen order (a sticky header is composed apart from the list, so not tree order).
        val shown = (0 until 4).map { headers[it].onChildAt(0).fetchSemanticsNode() }
            .sortedWith(compareBy({ it.boundsInRoot.top }, { it.boundsInRoot.left }))
            .map { it.config[SemanticsProperties.Text].joinToString() }
        assertEquals(listOf("Needs you · 1", "Working · 1", "Done · 0", "Idle · 1"), shown)
    }

    @Test
    fun loading_saysSo() {
        show(loading = true)
        composeTestRule.onNodeWithText("Reading agents from Herdr…").assertIsDisplayed()
    }

    @Test
    fun noAgents_saysSo() {
        show(shown = emptyList())
        composeTestRule.onNodeWithText("No agents are running in any Herdr session.").assertIsDisplayed()
        composeTestRule.onAllNodesWithTag(BOARD_HEADER_TAG).assertCountEquals(0)
    }

    @Test
    fun tap_opensTheChatOfThatSessionsPane() {
        show()

        composeTestRule.onAllNodesWithText("task w1:p1")[0].performClick()

        // The blocked claude in work is first: Needs you leads.
        assertEquals(listOf("work/w1:p1"), chats)
        assertEquals(emptyList<String>(), jumps)
        assertEquals(listOf(UsageActions.BOARD_CHAT), logged)
    }

    @Test
    fun tap_onAnAgentWithoutAChat_jumpsToIt() {
        show()

        card("task w1:p2").performClick()

        assertEquals(listOf("default/w1:p2"), jumps)
        assertEquals(emptyList<String>(), chats)
        assertEquals(listOf(UsageActions.BOARD_JUMP), logged)
    }

    @Test
    fun longPress_jumpsToThatSessionsPane() {
        show()

        composeTestRule.onAllNodesWithText("task w1:p1")[1].performTouchInput { longClick() }

        assertEquals(listOf("default/w1:p1"), jumps)
        assertEquals(emptyList<String>(), chats)
    }

    @Test
    fun approveAndDeny_answerThatSessionsPane() {
        show()

        composeTestRule.onAllNodesWithText("Approve").assertCountEquals(1)
        composeTestRule.onNodeWithText("Approve").performClick()
        composeTestRule.onNodeWithText("Deny").performClick()

        assertEquals(listOf("work/w1:p1 ${HerdAnswer.APPROVE}", "work/w1:p1 ${HerdAnswer.DENY}"), answers)
        assertEquals(listOf(UsageActions.BOARD_APPROVE, UsageActions.BOARD_DENY), logged)
        assertEquals("an answer is not a tap on the card", emptyList<String>(), chats)
    }

    @Test
    fun anAnswerThatWentNowhere_saysSo() {
        show(answer = HerdAnswerOutcome.NotBlocked)
        composeTestRule.onNodeWithText("It no longer waits for an answer; nothing was sent.").assertIsDisplayed()
    }

    @Test
    fun theFoldKey_readsThatPaneOnce_andShowsItsContextUse() {
        show()

        composeTestRule.onAllNodesWithContentDescription("Show screen")[0].performClick()
        composeTestRule.waitForIdle()

        assertEquals(listOf("work/w1:p1"), reads)
        composeTestRule.onNodeWithText("ctx 42% · 84k/200k").assertIsDisplayed()
        composeTestRule.onAllNodes(hasText("Do you want to proceed?", substring = true)).assertCountEquals(1)
        assertEquals(listOf(UsageActions.BOARD_MORE), logged)
        assertEquals("opening a card is not a tap on it", emptyList<String>(), chats)
    }

    @Test
    fun content_staysClearOfTheStatusAndNavigationBars() {
        // Taller than the board's top bar, so a board that ignores the insets draws its first header under it.
        val bar = 100.dp
        show(windowInsets = WindowInsets(top = bar, bottom = bar))

        val headers = composeTestRule.onAllNodesWithTag(BOARD_HEADER_TAG)
        val firstHeader = (0 until 4).minOf { headers[it].getUnclippedBoundsInRoot().top }
        assertTrue("first header top $firstHeader is under the status bar", firstHeader >= bar)
        // The list's cards scroll inside it, so its end is where the last card can show.
        val list = composeTestRule.onNode(hasScrollAction()).getUnclippedBoundsInRoot()
        val navigationBarTop = composeTestRule.onRoot().getUnclippedBoundsInRoot().bottom - bar
        assertTrue("the list ends at ${list.bottom}, under the navigation bar at $navigationBarTop", list.bottom <= navigationBarTop)
    }

    @Test
    fun needsYouCard_showsTheAskOffItsScreen_notTheScreensTail() {
        show(
            screen = """
                ● Update(src/App.kt)
                ╭──────────────────────────────────────╮
                │ Do you want to make this edit to App.kt? │
                │ ❯ 1. Yes                              │
                │   2. Yes, allow all edits this session │
                │   3. No, and tell Claude what to do differently (esc) │
                ╰──────────────────────────────────────╯
                Opus  ~/src  ctx 42% (84k/200k)
            """.trimIndent(),
        )

        assertEquals("only the waiting agent's screen is read", listOf("work/w1:p1"), reads)
        composeTestRule.onNodeWithText("Do you want to make this edit to App.kt?").assertIsDisplayed()
        composeTestRule.onNodeWithText("1. Yes").assertIsDisplayed()
        composeTestRule.onNodeWithText("2. Yes, allow all edits this session").assertIsDisplayed()
        composeTestRule.onNodeWithText("3. No, and tell Claude what to do differently (esc)").assertIsDisplayed()
        composeTestRule.onAllNodes(hasText("ctx 42%", substring = true)).assertCountEquals(0)
        composeTestRule.onNodeWithText("Approve").assertIsDisplayed()
        composeTestRule.onNodeWithText("Deny").assertIsDisplayed()
    }

    @Test
    fun reply_typesIntoThatSessionsPane() {
        show()

        composeTestRule.onAllNodesWithTag(BOARD_REPLY_TAG).assertCountEquals(1)
        composeTestRule.onNodeWithTag(BOARD_REPLY_TAG).performTextInput("use the second option")
        composeTestRule.onNodeWithText("Send").performClick()

        assertEquals(listOf("work/w1:p1 use the second option"), replies)
        assertEquals(listOf(UsageActions.BOARD_REPLY), logged)
        assertEquals("a reply is not a tap on the card", emptyList<String>(), chats)
    }

    @Test
    fun boardKey_inThePanelsAgentsGroup_countsWhoNeedsYou() {
        var opened = 0
        composeTestRule.setContent {
            HerdrCommandContent(
                snapshot = null,
                result = null,
                section = HerdrSection.AGENTS,
                onSection = {},
                onRun = { _, _ -> },
                onBoard = { opened++ },
                needsYou = 2,
            )
        }
        composeTestRule.onNodeWithText("Board · 2").performClick()
        assertEquals(1, opened)
    }
}
