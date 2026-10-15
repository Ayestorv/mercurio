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
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasAnyAncestor
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
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.test.swipeRight
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import solutions.ormus.logos.herd.BoardCard
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
        picks: MutableList<String> = mutableListOf(),
        windowInsets: WindowInsets = WindowInsets(0),
        agents: List<BoardCard> = emptyList(),
        switched: MutableList<BoardCard>? = null,
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
                        onPick = { picks += it },
                        onOpenReader = {},
                        windowInsets = windowInsets,
                        agents = agents,
                        currentSession = "default",
                        currentPane = "w1:p1",
                        onSwitch = switched?.let { list -> { card -> list += card } },
                    )
                }
            }
        }
    }

    @Test
    fun conversation_userOnTheRight_agentTurnLabelled_toolRunOnOneLine() {
        show(state(HerdrAgentStatus.WORKING))

        composeTestRule.onNode(hasTestTag(CHAT_USER_TAG) and hasAnyDescendant(hasText("Add a dark mode toggle."))).assertExists()
        // The markers are gone; the words stay.
        composeTestRule.onNodeWithText("Looking at settings first:").assertExists()
        composeTestRule.onNodeWithText("the toggle").assertExists()
        // The agent turn opens under a hairline with the agent's label.
        composeTestRule.onAllNodesWithTag(CHAT_TURN_TAG).assertCountEquals(1)
        composeTestRule.onNodeWithText("/ CLAUDE").assertExists()
        // The two calls fold into one row until it is opened.
        composeTestRule.onAllNodesWithTag(CHAT_TOOLS_TAG).assertCountEquals(1)
        composeTestRule.onAllNodesWithTag(CHAT_TOOL_TAG).assertCountEquals(0)
        composeTestRule.onNodeWithText("▸ 2 tool calls").assertExists()
        composeTestRule.onNode(hasTestTag(CHAT_TOOLS_TAG) and hasAnyDescendant(hasText("running")), useUnmergedTree = true).assertExists()
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
        composeTestRule.onNodeWithText("▸ 2 tool calls").performClick()
        composeTestRule.onAllNodesWithTag(CHAT_TOOL_TAG).assertCountEquals(2)
        composeTestRule.onNodeWithText("▸ Edit src/Settings.kt").performClick()

        composeTestRule.onNodeWithText("~/app/src/Settings.kt").assertExists()
        composeTestRule.onNodeWithText("▾ Edit src/Settings.kt").assertExists()
        composeTestRule.onNodeWithText("▾ 2 tool calls").assertExists()
        assertEquals(listOf(UsageActions.CHAT_TOOLS, UsageActions.CHAT_TOOL), logged)
    }

    @Test
    fun aSingleToolCall_staysItsOwnRow() {
        show(state(HerdrAgentStatus.IDLE).copy(items = items.take(3)))

        composeTestRule.onAllNodesWithTag(CHAT_TOOLS_TAG).assertCountEquals(0)
        composeTestRule.onNodeWithText("▸ Edit src/Settings.kt").assertExists()
    }

    @Test
    fun userTurn_onTheRight_onTheGoldTint() {
        show(state(HerdrAgentStatus.IDLE))

        val bubble = composeTestRule.onNodeWithTag(CHAT_USER_TAG).getUnclippedBoundsInRoot()
        val list = composeTestRule.onNodeWithTag(CHAT_LIST_TAG).getUnclippedBoundsInRoot()
        assertTrue("bubble ${bubble.right} ends at the list's right ${list.right}", list.right - bubble.right <= 16.dp)
        assertTrue("bubble ${bubble.left} leaves the left free", bubble.left - list.left >= 48.dp)
    }

    @Test
    fun heading_isBoldAtBodySize_neverLarger() {
        show(state(HerdrAgentStatus.IDLE).copy(items = listOf(ChatItem(ChatItem.AGENT, text = "## What changed\nThe toggle follows the system."))))

        val heading = textStyleOf("What changed")
        val body = textStyleOf("The toggle follows the system.")
        assertEquals(body.fontSize, heading.fontSize)
        assertEquals(16.sp, body.fontSize)
        assertEquals(24.sp, body.lineHeight)
        assertEquals(FontWeight.SemiBold, heading.fontWeight)
    }

    @Test
    fun longReply_showsItsStart_andShowMoreShowsAll() {
        val logged = mutableListOf<String>()
        val long = (1..30).joinToString("\n\n") { "Paragraph $it of a long reply." }
        show(state(HerdrAgentStatus.IDLE).copy(items = listOf(ChatItem(ChatItem.AGENT, text = long))), logged)

        val cut = composeTestRule.onNodeWithTag(CHAT_AGENT_TAG).getUnclippedBoundsInRoot().let { it.bottom - it.top }
        // Twelve 24sp lines and the key under them, not thirty paragraphs.
        assertTrue("cut reply is $cut", cut < 24.dp * 12 + 80.dp)
        composeTestRule.onNodeWithText("Show more").performClick()

        composeTestRule.onAllNodesWithText("Show more").assertCountEquals(0)
        val full = composeTestRule.onNodeWithTag(CHAT_AGENT_TAG).getUnclippedBoundsInRoot().let { it.bottom - it.top }
        assertTrue("full reply $full over the cut $cut", full > cut * 2)
        assertEquals(listOf(UsageActions.CHAT_MORE), logged)
    }

    @Test
    fun shortReply_hasNoShowMore() {
        show(state(HerdrAgentStatus.IDLE))

        composeTestRule.onAllNodesWithText("Show more").assertCountEquals(0)
    }

    /** On a wide screen the reply stops at a reading measure: 55 to 75 characters a line, measured with Inter. */
    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    @Config(qualifiers = "w900dp-h1200dp")
    fun reply_onAWideScreen_keepsAReadingMeasure() {
        val prose = "The settings screen reads the saved choice first and falls back to the system setting, " +
            "so a fresh install looks the way the phone does until you pick a theme yourself. " +
            "The failing test expected dark by default; it now checks both paths and the suite passes."
        show(state(HerdrAgentStatus.IDLE).copy(items = listOf(ChatItem(ChatItem.AGENT, text = prose))))

        val results = mutableListOf<TextLayoutResult>()
        // The reply in the conversation, not its start on the pinned card.
        composeTestRule.onNode(hasText(prose) and hasAnyAncestor(hasTestTag(CHAT_AGENT_TAG))).performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(results) }
        val layout = results.single()
        val full = (0 until layout.lineCount - 1).map { layout.getLineEnd(it) - layout.getLineStart(it) }
        assertTrue("lines $full", full.isNotEmpty() && full.all { it in 55..75 })
    }

    /** The text style [text] was laid out with. */
    private fun textStyleOf(text: String): TextStyle {
        val results = mutableListOf<TextLayoutResult>()
        composeTestRule.onNodeWithText(text).performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(results) }
        return results.single().layoutInput.style
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

    // The ask in the issue, with three options.
    private val threeOptions = "Run the migration on staging?\n❯ 1. Yes\n  2. Yes, and run it on prod after\n  3. No"

    @Test
    fun blocked_claudeWithOptions_aKeyPerOption_sendsItsNumber_withoutApproveDeny() {
        val logged = mutableListOf<String>()
        val picks = mutableListOf<String>()
        show(state(HerdrAgentStatus.BLOCKED).copy(screen = threeOptions), logged, picks = picks)

        composeTestRule.onAllNodesWithTag(ASK_OPTION_TAG).assertCountEquals(3)
        composeTestRule.onAllNodesWithText("Approve").assertCountEquals(0)
        composeTestRule.onNodeWithText("2. Yes, and run it on prod after").performClick()
        composeTestRule.onNodeWithText("3. No").performClick()

        assertEquals(listOf("2", "3"), picks)
        assertEquals(listOf(UsageActions.CHAT_OPTION, UsageActions.CHAT_OPTION), logged)
    }

    @Test
    fun blocked_codexWithOptions_readsThemAsText_andKeepsApproveDeny() {
        show(state(HerdrAgentStatus.BLOCKED, agent = "codex").copy(screen = threeOptions))

        composeTestRule.onAllNodesWithTag(ASK_OPTION_TAG).assertCountEquals(0)
        composeTestRule.onNodeWithText("Approve").assertExists()
        composeTestRule.onNodeWithText("Deny").assertExists()
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

    // Three agents the chat can move between: the one on screen (default w1:p1), a second in
    // the default session, and one in the "work" session with the same pane id as the first.
    private val agents = listOf(
        BoardCard("default", "w1:p1", "w1:t1", "claude", HerdrAgentStatus.WORKING, "Dark mode", "1 · app", 1, "Demo", 1),
        BoardCard("default", "w1:p2", "w1:t2", "codex", HerdrAgentStatus.BLOCKED, "Tests", "2 · tests", 2, "Demo", 1),
        BoardCard("work", "w1:p1", "w1:t1", "grok", HerdrAgentStatus.IDLE, "Review", "1 · review", 1, "Gamma", 1),
    )

    @Test
    fun scan_oneChipPerAgent_theCurrentOneDoesNothing_aTapMovesToThatAgent() {
        val switched = mutableListOf<BoardCard>()
        val logged = mutableListOf<String>()
        show(state(HerdrAgentStatus.WORKING), logged = logged, agents = agents, switched = switched)

        composeTestRule.onNodeWithTag(CHAT_SCAN_TAG).assertExists()
        composeTestRule.onNodeWithText("claude · 1").assertExists()
        composeTestRule.onNodeWithText("codex · 2").assertExists()
        composeTestRule.onNodeWithText("grok · work 1").assertExists()

        composeTestRule.onNodeWithText("claude · 1").performClick()
        composeTestRule.waitForIdle()
        assertEquals(emptyList<BoardCard>(), switched)

        composeTestRule.onNodeWithText("grok · work 1").performClick()
        composeTestRule.waitForIdle()
        assertEquals(listOf("work" to "w1:p1"), switched.map { it.session to it.paneId })
        assertEquals(listOf(UsageActions.CHAT_PANE), logged)
    }

    @Test
    fun scan_swipeLeftOnTheTranscript_movesToTheNextAgent_swipeRightAtTheStartDoesNothing() {
        val switched = mutableListOf<BoardCard>()
        val logged = mutableListOf<String>()
        show(state(HerdrAgentStatus.WORKING), logged = logged, agents = agents, switched = switched)

        composeTestRule.onNodeWithTag(CHAT_LIST_TAG).performTouchInput { swipeRight() }
        composeTestRule.waitForIdle()
        assertEquals(emptyList<BoardCard>(), switched)

        composeTestRule.onNodeWithTag(CHAT_LIST_TAG).performTouchInput { swipeLeft() }
        composeTestRule.waitForIdle()
        assertEquals(listOf("default" to "w1:p2"), switched.map { it.session to it.paneId })
        assertEquals(listOf(UsageActions.CHAT_SWIPE_PANE), logged)
    }

    @Test
    fun scan_withOneAgent_showsNoStrip() {
        show(state(HerdrAgentStatus.WORKING), agents = agents.take(1), switched = mutableListOf())

        composeTestRule.onAllNodesWithTag(CHAT_SCAN_TAG).assertCountEquals(0)
    }
}
