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

package org.connectbot.ui.screens.chat

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.connectbot.ui.theme.ConnectBotTheme
import org.connectbot.usage.LocalUsageLog
import org.connectbot.usage.UsageActions
import org.connectbot.usage.UsageLog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import solutions.ormus.logos.herd.HerdAnswer
import solutions.ormus.logos.herd.HerdrAgentStatus

/** The chat drawn from a synthetic payload: bubbles, tool rows, the waiting marker, the reply box. */
@RunWith(AndroidJUnit4::class)
class ChatContentTest {

    @get:Rule
    val composeTestRule = createAndroidComposeRule<ComponentActivity>()

    private val items = listOf(
        ChatItem(ChatItem.USER, text = "Add a dark mode toggle."),
        ChatItem(ChatItem.AGENT, text = "Looking at **settings** first:\n- the toggle\n- the theme"),
        ChatItem(ChatItem.TOOL, line = "Edit src/Settings.kt", detail = "~/app/src/Settings.kt", state = "ok"),
        ChatItem(ChatItem.TOOL, line = "Bash: ./gradlew test", detail = "./gradlew test", state = "run"),
    )

    private fun state(status: HerdrAgentStatus, agent: String = "claude") = ChatUiState(
        target = ChatTarget(paneId = "w1:p1", agent = agent, sessionId = "s", status = status, title = "Dark mode", place = "Demo / app"),
        items = items,
    )

    private fun show(
        state: ChatUiState,
        logged: MutableList<String> = mutableListOf(),
        sent: MutableList<String> = mutableListOf(),
        answers: MutableList<HerdAnswer> = mutableListOf(),
        windowInsets: WindowInsets = WindowInsets(0),
    ) {
        composeTestRule.setContent {
            ConnectBotTheme {
                CompositionLocalProvider(LocalUsageLog provides UsageLog { logged += it }) {
                    ChatContent(
                        state = state,
                        fallbackTitle = "Demo",
                        onClose = {},
                        onRefresh = {},
                        onSend = { sent += it },
                        onAnswer = { answers += it },
                        onOpenReader = {},
                        windowInsets = windowInsets,
                    )
                }
            }
        }
    }

    @Test
    fun conversation_userOnTheRight_agentWithMarkdown_toolsOnOneLine() {
        show(state(HerdrAgentStatus.WORKING))

        composeTestRule.onNode(hasTestTag(CHAT_USER_TAG) and hasAnyDescendant(hasText("Add a dark mode toggle."))).assertExists()
        // The markers are gone; the words stay.
        composeTestRule.onNodeWithText("Looking at settings first:").assertExists()
        composeTestRule.onNodeWithText("the toggle").assertExists()
        composeTestRule.onAllNodesWithTag(CHAT_TOOL_TAG).assertCountEquals(2)
        composeTestRule.onNodeWithText("▸ Edit src/Settings.kt").assertExists()
        composeTestRule.onNodeWithText("running").assertExists()
        composeTestRule.onNodeWithText("/ WORKING").assertExists()
        composeTestRule.onNodeWithText("claude · Demo / app").assertExists()
        composeTestRule.onAllNodesWithTag(CHAT_WAITING_TAG).assertCountEquals(0)
    }

    @Test
    fun header_startsBelowTheStatusBar() {
        val statusBar = 48.dp
        show(state(HerdrAgentStatus.WORKING), windowInsets = WindowInsets(top = statusBar))

        val title = composeTestRule.onNodeWithText("claude · Demo / app").getUnclippedBoundsInRoot()
        assertTrue("title top ${title.top} is under the status bar", title.top >= statusBar)
    }

    @Test
    fun toolRow_opensOnATap_andLogsIt() {
        val logged = mutableListOf<String>()
        show(state(HerdrAgentStatus.IDLE), logged)

        composeTestRule.onAllNodesWithText("~/app/src/Settings.kt").assertCountEquals(0)
        composeTestRule.onNodeWithText("▸ Edit src/Settings.kt").performClick()

        composeTestRule.onNodeWithText("~/app/src/Settings.kt").assertExists()
        composeTestRule.onNodeWithText("▾ Edit src/Settings.kt").assertExists()
        assertEquals(listOf(UsageActions.CHAT_TOOL), logged)
    }

    @Test
    fun blocked_showsWaitingWithThePendingCall_andApproveDeny() {
        val logged = mutableListOf<String>()
        val answers = mutableListOf<HerdAnswer>()
        show(state(HerdrAgentStatus.BLOCKED), logged, answers = answers)

        composeTestRule.onNode(
            hasTestTag(CHAT_WAITING_TAG) and
                hasAnyDescendant(hasText("/ WAITING FOR YOU")) and
                hasAnyDescendant(hasText("Bash: ./gradlew test")),
            useUnmergedTree = true,
        ).assertExists()
        composeTestRule.onNodeWithText("Approve").performClick()
        composeTestRule.onNodeWithText("Deny").performClick()

        assertEquals(listOf(HerdAnswer.APPROVE, HerdAnswer.DENY), answers)
        assertEquals(listOf(UsageActions.CHAT_APPROVE, UsageActions.CHAT_DENY), logged)
    }

    @Test
    fun blocked_pinsThePromptOnTop_withTheReplyRightUnderIt() {
        val screen = "Do you want to proceed?\n❯ 1. Yes\n  2. No, and tell Claude what to do differently (esc)"
        show(state(HerdrAgentStatus.BLOCKED).copy(screen = screen))

        composeTestRule.onNode(
            hasTestTag(CHAT_WAITING_TAG) and
                hasAnyDescendant(hasText("Do you want to proceed?")) and
                hasAnyDescendant(hasText("1. Yes")) and
                hasAnyDescendant(hasText("2. No, and tell Claude what to do differently (esc)")),
            useUnmergedTree = true,
        ).assertExists()
        val card = composeTestRule.onNodeWithTag(CHAT_WAITING_TAG).getUnclippedBoundsInRoot()
        val reply = composeTestRule.onNodeWithTag(CHAT_REPLY_TAG).getUnclippedBoundsInRoot()
        val list = composeTestRule.onNodeWithTag(CHAT_LIST_TAG).getUnclippedBoundsInRoot()
        assertTrue("reply ${reply.top} under the card ${card.bottom}", reply.top >= card.bottom)
        assertTrue("reply ${reply.bottom} above the conversation ${list.top}", reply.bottom <= list.top)
    }

    @Test
    fun working_pinsTheRunningCall_andTheReplyStaysAtTheBottom() {
        show(state(HerdrAgentStatus.WORKING))

        composeTestRule.onNode(hasTestTag(CHAT_ASK_TAG) and hasAnyDescendant(hasText("Working: Bash: ./gradlew test")), useUnmergedTree = true).assertExists()
        val reply = composeTestRule.onNodeWithTag(CHAT_REPLY_TAG).getUnclippedBoundsInRoot()
        val list = composeTestRule.onNodeWithTag(CHAT_LIST_TAG).getUnclippedBoundsInRoot()
        assertTrue("reply ${reply.top} under the conversation ${list.bottom}", reply.top >= list.bottom)
    }

    @Test
    fun done_pinsTheLastReply_andATapScrollsToIt() {
        val logged = mutableListOf<String>()
        val long = (1..40).map { ChatItem(ChatItem.USER, text = "message $it") }
        val items = listOf(ChatItem(ChatItem.AGENT, text = "Settled on the system setting.\nTests pass.")) + long
        show(state(HerdrAgentStatus.DONE).copy(items = items), logged)

        composeTestRule.onNode(hasTestTag(CHAT_ASK_TAG) and hasAnyDescendant(hasText("/ DONE")), useUnmergedTree = true).assertExists()
        // The conversation follows its newest message: the reply is off screen until the card is tapped.
        composeTestRule.onAllNodesWithTag(CHAT_AGENT_TAG).assertCountEquals(0)
        composeTestRule.onNodeWithTag(CHAT_ASK_TAG).performClick()
        composeTestRule.waitForIdle()

        composeTestRule.onAllNodesWithTag(CHAT_AGENT_TAG).assertCountEquals(1)
        assertEquals(listOf(UsageActions.CHAT_ASK), logged)
    }

    @Test
    fun blocked_unprovenAgent_waitsWithoutAnswers() {
        show(state(HerdrAgentStatus.BLOCKED).let { it.copy(target = it.target?.copy(agent = "pi")) })

        composeTestRule.onNodeWithTag(CHAT_WAITING_TAG).assertExists()
        composeTestRule.onAllNodesWithText("Approve").assertCountEquals(0)
    }

    @Test
    fun reply_sendsTheTextAndClearsTheBox_logsOnlyTheId() {
        val logged = mutableListOf<String>()
        val sent = mutableListOf<String>()
        show(state(HerdrAgentStatus.IDLE), logged, sent)

        composeTestRule.onNodeWithText("Send").assertIsNotEnabled()
        composeTestRule.onNodeWithTag(CHAT_REPLY_TAG).performTextInput("use the system setting")
        composeTestRule.onNodeWithText("Send").performClick()

        assertEquals(listOf("use the system setting"), sent)
        assertEquals(listOf(UsageActions.CHAT_SEND), logged)
        composeTestRule.onNodeWithText("Send").assertIsNotEnabled()
    }

    @Test
    fun notAnAgent_saysSo_andTheReplyBoxIsOff() {
        show(ChatUiState(problem = ChatProblem.NotAgent))

        composeTestRule.onNodeWithText("The focused pane is not a Claude Code or Codex agent", substring = true).assertExists()
        composeTestRule.onNodeWithTag(CHAT_REPLY_TAG).assertIsNotEnabled()
        composeTestRule.onNodeWithText("Demo").assertExists()
    }

    @Test
    fun truncated_saysEarlierMessagesAreNotShown() {
        show(state(HerdrAgentStatus.IDLE).copy(truncated = true))

        composeTestRule.onNodeWithTag(CHAT_LIST_TAG).performScrollToIndex(0)
        composeTestRule.onAllNodesWithText("/ EARLIER MESSAGES NOT SHOWN").assertCountEquals(1)
    }
}
