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

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.connectbot.di.CoroutineDispatchers
import org.connectbot.transport.CommandOutput
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import solutions.ormus.logos.herd.HerdAnswer
import solutions.ormus.logos.herd.HerdAnswerOutcome
import solutions.ormus.logos.herd.HerdrAgentInfo
import solutions.ormus.logos.herd.HerdrAgentSession
import solutions.ormus.logos.herd.HerdrAgentStatus
import solutions.ormus.logos.herd.HerdrPaneInfo
import solutions.ormus.logos.herd.HerdrSessionSnapshot

@OptIn(ExperimentalCoroutinesApi::class)
class ChatViewModelTest {

    private val testDispatcher = StandardTestDispatcher()
    private val dispatchers = CoroutineDispatchers(default = testDispatcher, io = testDispatcher, main = testDispatcher)

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    /** Two claude panes, w1:p1 and w1:p2; [focused] has Herdr's focus. */
    private class FakeHost(
        var status: HerdrAgentStatus = HerdrAgentStatus.WORKING,
        override val session: String = "default",
    ) : ChatHost {
        var agent: String? = "claude"
        var focused = "w1:p1"
        var agentSession = "11111111-2222-3333-4444-555555555555"
        val commands = mutableListOf<String>()
        var reply = CommandOutput("""{"v":1,"size":10,"items":[{"k":"user","text":"hi"}]}""", "", 0)

        override suspend fun snapshot(fresh: Boolean) = HerdrSessionSnapshot(
            panes = listOf(
                HerdrPaneInfo(paneId = "w1:p1", agent = agent, agentSession = HerdrAgentSession("claude", "id", agentSession)),
                HerdrPaneInfo(paneId = "w1:p2", agent = "claude", agentSession = HerdrAgentSession("claude", "id", "22222222-2222-3333-4444-555555555555")),
            ),
            agents = listOf(
                HerdrAgentInfo(paneId = "w1:p1", agent = agent, agentStatus = status),
                HerdrAgentInfo(paneId = "w1:p2", agent = "claude", agentStatus = status),
            ),
            focusedPaneId = focused,
        )

        override fun exec(command: String): CommandOutput {
            commands += command
            return if ("send-text" in command) CommandOutput("", "", 0) else reply
        }

        val sends: List<String> get() = commands.filter { "send-text" in it }
        val reads: List<String> get() = commands.filter { "MERCURIO_CHAT" in it }

        override suspend fun answer(paneId: String, answer: HerdAnswer) = HerdAnswerOutcome.NotBlocked

        var screenText: String? = "Do you want to proceed?\n❯ 1. Yes\n  2. No"
        val screens = mutableListOf<String>()

        override fun screen(paneId: String): String? {
            screens += paneId
            return screenText
        }
    }

    @Test
    fun load_showsTheItems_thenAsksOnlyWhetherTheSizeChanged() = runTest(testDispatcher) {
        val host = FakeHost()
        val viewModel = ChatViewModel(dispatchers)
        viewModel.open()

        assertTrue(viewModel.load(host, fresh = true))
        assertEquals(listOf("hi"), viewModel.uiState.value.items.map { it.text })
        assertTrue(host.commands[0].startsWith("python3 - 'claude' '11111111-2222-3333-4444-555555555555' '-1'"))

        host.reply = CommandOutput("""{"v":1,"size":10,"same":true}""", "", 0)
        assertEquals(false, viewModel.load(host, fresh = false))
        assertTrue(host.commands[1].startsWith("python3 - 'claude' '11111111-2222-3333-4444-555555555555' '10'"))
        assertEquals(listOf("hi"), viewModel.uiState.value.items.map { it.text })
    }

    @Test
    fun load_namesWhyThereIsNoChat() = runTest(testDispatcher) {
        val host = FakeHost()
        val viewModel = ChatViewModel(dispatchers)
        viewModel.open()

        host.reply = CommandOutput("", "", AgentChat.EXIT_NO_TRANSCRIPT)
        viewModel.load(host, fresh = true)
        assertEquals(ChatProblem.NoTranscript, viewModel.uiState.value.problem)

        host.agent = null
        viewModel.open()
        viewModel.load(host, fresh = true)
        assertEquals(ChatProblem.NotAgent, viewModel.uiState.value.problem)
        assertNull(viewModel.uiState.value.target)
    }

    @Test
    fun send_goesToTheChatsPane_evenAfterFocusMoved_andReadsAgainAtOnce() = runTest(testDispatcher) {
        val host = FakeHost(HerdrAgentStatus.IDLE)
        val viewModel = ChatViewModel(dispatchers)
        viewModel.open()
        val follow = launch { viewModel.follow(host) }
        runCurrent()
        assertEquals(1, host.reads.size)

        // Focus moves to the other agent (another device, a swipe): the chat stays on w1:p1.
        host.focused = "w1:p2"
        viewModel.send(host, "ship it")
        runCurrent()

        assertEquals(listOf(AgentChat.sendCommand("w1:p1", "ship it")), host.sends)
        assertTrue(host.reads.size >= 2)
        assertTrue(host.reads.all { "11111111-2222-3333-4444-555555555555" in it })
        assertNull(viewModel.uiState.value.unsent)
        follow.cancel()
    }

    @Test
    fun open_onAGivenPane_readsAndAnswersThatPane_notTheFocusedOne() = runTest(testDispatcher) {
        val host = FakeHost(HerdrAgentStatus.IDLE, session = "work")
        val viewModel = ChatViewModel(dispatchers)
        // An Agents board card: w1:p2 in work, while Herdr's focus is on w1:p1.
        viewModel.open("w1:p2")
        viewModel.load(host, fresh = true)

        assertEquals("w1:p2", viewModel.uiState.value.target?.paneId)
        assertTrue(host.reads.single(), "22222222-2222-3333-4444-555555555555" in host.reads.single())
        viewModel.send(host, "ship it")
        runCurrent()
        assertEquals(listOf(AgentChat.sendCommand("w1:p2", "ship it", "work")), host.sends)
    }

    @Test
    fun send_inAnotherHerdrSession_goesToThatSessionsPane() = runTest(testDispatcher) {
        val host = FakeHost(HerdrAgentStatus.IDLE, session = "work")
        val viewModel = ChatViewModel(dispatchers)
        viewModel.open()
        viewModel.load(host, fresh = true)
        assertEquals("work", viewModel.uiState.value.target?.session)

        viewModel.send(host, "ship it")
        runCurrent()

        // Pane ids repeat across Herdr sessions: the reply names the chat's session.
        assertEquals(listOf(AgentChat.sendCommand("w1:p1", "ship it", "work")), host.sends)
        assertTrue(host.sends.single(), "herdr --session" in host.sends.single())
    }

    @Test
    fun send_paneNowRunsAnotherSession_keepsTheTextAndSaysWhy() = runTest(testDispatcher) {
        val host = FakeHost(HerdrAgentStatus.IDLE)
        val viewModel = ChatViewModel(dispatchers)
        viewModel.open()
        viewModel.load(host, fresh = true)

        host.agentSession = "33333333-2222-3333-4444-555555555555"
        viewModel.send(host, "ship it")
        advanceUntilIdle()

        assertTrue(host.sends.isEmpty())
        val unsent = checkNotNull(viewModel.uiState.value.unsent)
        assertEquals("ship it", unsent.text)
        assertEquals(ChatProblem.PaneGone, unsent.reason)
    }

    @Test
    fun send_agentExited_keepsTheText() = runTest(testDispatcher) {
        val host = FakeHost(HerdrAgentStatus.IDLE)
        val viewModel = ChatViewModel(dispatchers)
        viewModel.open()
        viewModel.load(host, fresh = true)

        host.agent = null
        viewModel.send(host, "ship it")
        advanceUntilIdle()

        assertTrue(host.sends.isEmpty())
        assertEquals("ship it", viewModel.uiState.value.unsent?.text)
        // The next read keeps the conversation but turns the reply box off.
        viewModel.load(host, fresh = false)
        assertEquals(ChatProblem.PaneGone, viewModel.uiState.value.problem)
        assertNull(viewModel.uiState.value.target)
        assertEquals(listOf("hi"), viewModel.uiState.value.items.map { it.text })
    }

    @Test
    fun load_readsTheScreenOnlyWhileBlocked_andTimesTheState() = runTest(testDispatcher) {
        val host = FakeHost(HerdrAgentStatus.WORKING)
        val viewModel = ChatViewModel(dispatchers)
        var now = 1_000L
        viewModel.clock = { now }
        viewModel.open()

        viewModel.load(host, fresh = true)
        assertEquals(emptyList<String>(), host.screens)
        assertNull(viewModel.uiState.value.screen)
        assertEquals(1_000L, viewModel.uiState.value.since)

        // Still working later: the time it started stays.
        now = 5_000L
        viewModel.load(host, fresh = false)
        assertEquals(1_000L, viewModel.uiState.value.since)

        host.status = HerdrAgentStatus.BLOCKED
        viewModel.load(host, fresh = false)
        assertEquals(listOf("w1:p1"), host.screens)
        assertEquals(5_000L, viewModel.uiState.value.since)
        assertEquals(ChatAsk.Blocked("Do you want to proceed?", listOf("1. Yes", "2. No"), 0), viewModel.uiState.value.ask)
    }

    @Test
    fun follow_pollsQuicklyWhileWorking() = runTest(testDispatcher) {
        val host = FakeHost(HerdrAgentStatus.WORKING)
        val viewModel = ChatViewModel(dispatchers)
        val follow = launch { viewModel.follow(host) }
        runCurrent()

        advanceTimeBy(ChatPolling.FAST_MS * 3 + 1)

        assertEquals(4, host.commands.size)
        follow.cancel()
        advanceUntilIdle()
    }

    @Test
    fun polling_backsOffWhileIdle_andStaysQuickWhileWorking() {
        assertEquals(ChatPolling.FAST_MS, ChatPolling.next(HerdrAgentStatus.WORKING, changed = false, previous = ChatPolling.IDLE_MAX_MS))
        assertEquals(ChatPolling.BLOCKED_MS, ChatPolling.next(HerdrAgentStatus.BLOCKED, changed = false, previous = ChatPolling.FAST_MS))
        assertEquals(ChatPolling.FAST_MS, ChatPolling.next(HerdrAgentStatus.IDLE, changed = true, previous = 16_000))
        assertEquals(8_000L, ChatPolling.next(HerdrAgentStatus.IDLE, changed = false, previous = 4_000))
        assertEquals(ChatPolling.IDLE_MAX_MS, ChatPolling.next(HerdrAgentStatus.DONE, changed = false, previous = ChatPolling.IDLE_MAX_MS))
    }
}
