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

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.connectbot.di.CoroutineDispatchers
import org.connectbot.service.TerminalBridge
import org.connectbot.transport.CommandOutput
import org.connectbot.transport.SSH
import solutions.ormus.logos.herd.HerdAnswer
import solutions.ormus.logos.herd.HerdAnswerOutcome
import solutions.ormus.logos.herd.HerdrAgentStatus
import solutions.ormus.logos.herd.HerdrApi
import solutions.ormus.logos.herd.HerdrCommands
import solutions.ormus.logos.herd.HerdrSessionSnapshot
import solutions.ormus.logos.herd.HerdrSessions
import solutions.ormus.logos.herd.parseHerdrSnapshot
import javax.inject.Inject

/** Why the chat has no conversation to show. */
sealed interface ChatProblem {
    /** The focused pane is not a Claude Code or Codex agent Herdr names a session for. */
    data object NotAgent : ChatProblem

    /** The agent's transcript file is not on the host. */
    data object NoTranscript : ChatProblem

    /** The pane the chat shows is gone, or runs another agent session now. */
    data object PaneGone : ChatProblem

    /** The read failed; [reason] is one line. */
    data class ReadFailed(val reason: String) : ChatProblem
}

/** What the chat view shows. */
data class ChatUiState(
    val loading: Boolean = false,
    val target: ChatTarget? = null,
    val items: List<ChatItem> = emptyList(),
    val truncated: Boolean = false,
    val problem: ChatProblem? = null,
    /** The last Approve or Deny that did not reach a waiting agent. */
    val answer: HerdAnswerOutcome? = null,
    /** The last reply that was not sent, to put back in the reply box. */
    val unsent: ChatUnsent? = null,
) {
    val blocked: Boolean get() = target?.status == HerdrAgentStatus.BLOCKED
    val working: Boolean get() = target?.status == HerdrAgentStatus.WORKING
}

/** A reply that was not sent: its [text], why, and a [seq] so the same text failing twice still shows. */
data class ChatUnsent(val text: String, val reason: ChatProblem, val seq: Int)

/** What the chat needs from a session; [BridgeChatHost] for a live one, a fake in tests. */
interface ChatHost {
    /** Herdr's snapshot; [fresh] asks the host now instead of taking the last poll. */
    suspend fun snapshot(fresh: Boolean): HerdrSessionSnapshot?

    /** Run [command] over an exec channel. Blocking; null when it never ran. */
    fun exec(command: String): CommandOutput?

    /** Approve or deny the blocked agent in [paneId]. */
    suspend fun answer(paneId: String, answer: HerdAnswer): HerdAnswerOutcome

    /** The Herdr session the chat's pane is in; [snapshot] and [answer] are that session's. */
    val session: String get() = HerdrCommands.DEFAULT_SESSION
}

/**
 * Follows one agent pane's conversation while the chat is on screen: the pane focused
 * when it opened, kept even when Herdr's focus moves, so a reply never lands in another
 * agent. One read on open, then a poll that is quick while the agent works and backs off
 * while it is idle. A poll that finds the transcript the same size it was sends back one line.
 */
@HiltViewModel
class ChatViewModel @Inject constructor(
    private val dispatchers: CoroutineDispatchers,
) : ViewModel() {
    private val _uiState = MutableStateFlow(ChatUiState())
    val uiState: StateFlow<ChatUiState> = _uiState.asStateFlow()

    // The pane this chat shows (set by the first read after [open]), the transcript the
    // items came from, and its size then.
    private var pinnedPane: String? = null
    private var knownSession: String? = null
    private var knownSize = -1L
    private var unsentSeq = 0

    private val reading = Mutex()
    private val wake = Channel<Unit>(Channel.CONFLATED)

    /** The chat opened: show [pane], else the pane focused now, from a clean slate. */
    fun open(pane: String? = null) {
        pinnedPane = pane
        knownSession = null
        knownSize = -1
        _uiState.value = ChatUiState(loading = true)
    }

    /** Read now and keep reading until cancelled (the chat closed or left the screen). */
    suspend fun follow(host: ChatHost) {
        var wait = ChatPolling.FAST_MS
        var fresh = true
        while (true) {
            val changed = load(host, fresh)
            wait = ChatPolling.next(_uiState.value.target?.status, changed, wait)
            // A refresh, a reply or an answer cuts the wait short and starts over quick.
            fresh = withTimeoutOrNull(wait) { wake.receive() } != null
            if (fresh) wait = ChatPolling.FAST_MS
        }
    }

    /** Read again now (pull to refresh, the refresh key). */
    fun refresh() {
        _uiState.update { it.copy(loading = true) }
        wake.trySend(Unit)
    }

    /**
     * Send [text] to the agent pane the chat shows, then read again. The pane must still
     * run the same agent session (a fresh snapshot says); otherwise, or when the send
     * fails, the text comes back in [ChatUiState.unsent] with the reason.
     */
    fun send(host: ChatHost, text: String) {
        if (text.isBlank()) return
        val target = _uiState.value.target
        viewModelScope.launch {
            val problem = withContext(dispatchers.io) { deliver(host, target, text) }
            _uiState.update { it.copy(unsent = problem?.let { p -> ChatUnsent(text, p, ++unsentSeq) }) }
            wake.trySend(Unit)
        }
    }

    private suspend fun deliver(host: ChatHost, target: ChatTarget?, text: String): ChatProblem? {
        if (target == null || !AgentChat.stillThere(host.snapshot(fresh = true), target)) return ChatProblem.PaneGone
        val out = try {
            host.exec(AgentChat.sendCommand(target.paneId, text, target.session))
        } catch (e: Exception) {
            return ChatProblem.ReadFailed(e.message ?: e.javaClass.simpleName)
        }
        val error = HerdrApi.parseCommandError(out) ?: return null
        return ChatProblem.ReadFailed(error.message.ifBlank { error.code }.lineSequence().first())
    }

    /** Approve or deny the waiting agent, then read again. */
    fun answer(host: ChatHost, answer: HerdAnswer) {
        val paneId = _uiState.value.target?.paneId ?: return
        viewModelScope.launch {
            val outcome = host.answer(paneId, answer)
            _uiState.update { it.copy(answer = outcome.takeIf { o -> o !is HerdAnswerOutcome.Sent }) }
            wake.trySend(Unit)
        }
    }

    /** One read; true when what the chat shows changed. */
    internal suspend fun load(host: ChatHost, fresh: Boolean): Boolean = reading.withLock {
        val before = _uiState.value
        val after = withContext(dispatchers.io) { read(host, fresh, before) }
        _uiState.value = after
        after != before
    }

    private suspend fun read(host: ChatHost, fresh: Boolean, state: ChatUiState): ChatUiState {
        val done = state.copy(loading = false)
        val pinned = pinnedPane
        val target = AgentChat.target(host.snapshot(fresh), pane = pinned, herdrSession = host.session)
            ?: return if (pinned == null) {
                done.copy(target = null, items = emptyList(), truncated = false, problem = ChatProblem.NotAgent)
            } else {
                // Keep what was read; the reply box is off until the chat is opened again.
                done.copy(target = null, problem = ChatProblem.PaneGone)
            }
        pinnedPane = target.paneId
        val session = target.agent + ":" + target.sessionId
        if (session != knownSession) {
            knownSession = session
            knownSize = -1
        }
        val sameSession = state.target?.let { it.agent + ":" + it.sessionId } == session
        val base = if (sameSession) done.copy(target = target) else done.copy(target = target, items = emptyList(), truncated = false)
        val command = AgentChat.command(target.agent, target.sessionId, knownSize) ?: return base.copy(problem = ChatProblem.NotAgent)
        val out = try {
            host.exec(command)
        } catch (e: Exception) {
            return base.copy(problem = ChatProblem.ReadFailed(e.message ?: e.javaClass.simpleName))
        }
        val problem = when {
            out == null -> ChatProblem.ReadFailed("not connected")
            out.exitCode == AgentChat.EXIT_NO_TRANSCRIPT -> ChatProblem.NoTranscript
            out.exitCode != null && out.exitCode != 0 -> ChatProblem.ReadFailed("exit ${out.exitCode}: " + out.stderr.trim().lineSequence().lastOrNull().orEmpty())
            else -> null
        }
        if (problem != null) return base.copy(problem = problem)
        val payload = out?.let { AgentChat.parse(it.stdout) } ?: return base.copy(problem = ChatProblem.ReadFailed("unreadable reply"))
        if (payload.same) return base.copy(problem = null)
        knownSize = payload.size
        // An answer note stays only until the agent moves on.
        return base.copy(items = payload.items, truncated = payload.truncated, problem = null, answer = null)
    }
}

/** How long the chat waits between reads. */
object ChatPolling {
    const val FAST_MS = 2_000L
    const val BLOCKED_MS = 4_000L
    const val IDLE_MAX_MS = 30_000L

    /**
     * The next wait after a read: quick while the agent works or the conversation just
     * changed, steady while it waits for you, and doubling up to [IDLE_MAX_MS] while
     * nothing happens.
     */
    fun next(status: HerdrAgentStatus?, changed: Boolean, previous: Long): Long = when {
        status == HerdrAgentStatus.WORKING -> FAST_MS
        status == HerdrAgentStatus.BLOCKED -> BLOCKED_MS
        changed -> FAST_MS
        else -> (previous * 2).coerceIn(FAST_MS, IDLE_MAX_MS)
    }
}

/** A live session as the chat's host: Herdr [session]'s snapshot and the exec channel. */
class BridgeChatHost(private val bridge: TerminalBridge, override val session: String = HerdrCommands.DEFAULT_SESSION) : ChatHost {
    override suspend fun snapshot(fresh: Boolean): HerdrSessionSnapshot? {
        val herd = bridge.herd
        if (herd != null) {
            if (fresh || herd.snapshotOf(session) == null) herd.refresh(session)
            return herd.snapshotOf(session)
        }
        val out = exec(HerdrSessions.snapshotCommand(session))?.takeIf { it.exitCode == null || it.exitCode == 0 }
        return out?.let { parseHerdrSnapshot(it.stdout) }
    }

    override fun exec(command: String): CommandOutput? = (bridge.transport as? SSH)?.execDetailed(command)

    override suspend fun answer(paneId: String, answer: HerdAnswer): HerdAnswerOutcome = bridge.herd?.answer(paneId, answer, session) ?: HerdAnswerOutcome.Failed("Herdr is not up in this session")
}
