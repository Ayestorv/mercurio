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

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.connectbot.R
import org.connectbot.ui.components.DotStyle
import org.connectbot.ui.components.KeyTone
import org.connectbot.ui.components.MonoLabel
import org.connectbot.ui.components.OrmusKey
import org.connectbot.ui.components.StatusDot
import org.connectbot.ui.components.WORD_KEY_PADDING
import org.connectbot.ui.screens.chat.AgentChat
import org.connectbot.ui.theme.Ormus
import org.connectbot.ui.theme.OrmusCornerShape
import org.connectbot.ui.theme.OrmusMono
import org.connectbot.usage.LocalUsageLog
import org.connectbot.usage.UsageActions
import solutions.ormus.logos.herd.AgentsBoard
import solutions.ormus.logos.herd.BoardCard
import solutions.ormus.logos.herd.BoardColumn
import solutions.ormus.logos.herd.HerdAnswer
import solutions.ormus.logos.herd.HerdAnswerOutcome
import solutions.ormus.logos.herd.HerdApproval
import solutions.ormus.logos.herd.HerdSession
import solutions.ormus.logos.herd.HerdrAgentStatus
import solutions.ormus.logos.herd.HerdrCommands
import solutions.ormus.logos.herd.HerdrSessionSnapshot
import solutions.ormus.logos.herd.HerdrSessions

/** Test tag on every agent card, in the order shown. */
const val BOARD_CARD_TAG = "board_card"

/** Test tag on every section header. */
const val BOARD_HEADER_TAG = "board_header"

/** The board lays its sections side by side from this width (a tablet, an open foldable). */
private val BOARD_COLUMNS_MIN_WIDTH = 720.dp

/** How often the sessions the Herdr side does not poll are read again while the board shows. */
private const val OTHERS_REFRESH_MS = 3 * HerdSession.POLL_INTERVAL_MS

/** How often the cards' "in this state for" ages move on. */
private const val AGE_TICK_MS = 30_000L

/** Lines of an open card's screen it shows. */
private const val SCREEN_LINES = 8

/**
 * The Agents board, full screen: every agent of every running Herdr session on the host,
 * in Needs you, Working, Done and Idle. Live from the snapshots the Herdr side already
 * polls (the default session and the attached one); the other sessions are read as it
 * opens and every few polls while it shows. A tap opens the agent's chat ([onChat]), or
 * jumps to it when it has none ([onJump], also a long press); Approve and Deny answer a
 * waiting agent in its own session.
 */
@Composable
fun AgentsBoardDialog(
    session: HerdSession,
    onChat: (BoardCard) -> Unit,
    onJump: (BoardCard) -> Unit,
    onDismiss: () -> Unit,
) {
    val sessions by session.sessions.collectAsState()
    val primary by session.snapshot.collectAsState()
    val others by session.others.collectAsState()
    val lifecycle = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()
    LaunchedEffect(session) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            session.refreshAll()
            while (true) {
                delay(OTHERS_REFRESH_MS)
                val polled = setOf(HerdrCommands.DEFAULT_SESSION, session.attached.value)
                session.sessions.value.filter { it !in polled }.forEach { session.refresh(it) }
            }
        }
    }
    val now by produceState(System.currentTimeMillis()) {
        while (true) {
            delay(AGE_TICK_MS)
            value = System.currentTimeMillis()
        }
    }
    val snapshots = boardSnapshots(primary, others, sessions)
    var answer by remember { mutableStateOf<HerdAnswerOutcome?>(null) }
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        AgentsBoardContent(
            cards = AgentsBoard.cards(snapshots, session.since::of),
            loading = primary == null,
            now = now,
            canChat = { card -> canChat(snapshots[card.session], card) },
            onChat = onChat,
            onJump = onJump,
            onAnswer = { card, reply ->
                scope.launch { answer = session.answer(card.paneId, reply, card.session).takeIf { it !is HerdAnswerOutcome.Sent } }
            },
            readScreen = { card -> session.readPane(card.paneId, card.session) },
            onRefresh = { scope.launch { session.refreshAll() } },
            onClose = onDismiss,
            answer = answer,
            modifier = Modifier.safeDrawingPadding(),
        )
    }
}

private fun canChat(snapshot: HerdrSessionSnapshot?, card: BoardCard): Boolean = AgentChat.target(snapshot, pane = card.paneId, herdrSession = card.session) != null

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun AgentsBoardContent(
    cards: List<BoardCard>,
    loading: Boolean,
    now: Long,
    canChat: (BoardCard) -> Boolean,
    onChat: (BoardCard) -> Unit,
    onJump: (BoardCard) -> Unit,
    onAnswer: (BoardCard, HerdAnswer) -> Unit,
    readScreen: suspend (BoardCard) -> String?,
    onRefresh: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    /** The last Approve or Deny that did not reach a waiting agent. */
    answer: HerdAnswerOutcome? = null,
) {
    val usage = LocalUsageLog.current
    val sections = AgentsBoard.sections(cards)
    // Open cards by session and pane; an open card shows its screen and context use.
    var open by rememberSaveable { mutableStateOf(setOf<String>()) }
    val actions = CardActions(
        now = now,
        isOpen = { "${it.session}/${it.paneId}" in open },
        toggle = { card ->
            val key = "${card.session}/${card.paneId}"
            if (key !in open) usage.log(UsageActions.BOARD_MORE)
            open = if (key in open) open - key else open + key
        },
        tap = { card ->
            if (canChat(card)) {
                usage.log(UsageActions.BOARD_CHAT)
                onChat(card)
            } else {
                usage.log(UsageActions.BOARD_JUMP)
                onJump(card)
            }
        },
        longPress = { card ->
            usage.log(UsageActions.BOARD_JUMP)
            onJump(card)
        },
        answer = { card, reply ->
            usage.log(if (reply == HerdAnswer.APPROVE) UsageActions.BOARD_APPROVE else UsageActions.BOARD_DENY)
            onAnswer(card, reply)
        },
        readScreen = readScreen,
    )
    Surface(modifier = modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(modifier = Modifier.fillMaxSize()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 4.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = {
                    usage.log(UsageActions.BOARD_CLOSE)
                    onClose()
                }) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.board_close))
                }
                Text(stringResource(R.string.board_title), style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                IconButton(onClick = {
                    usage.log(UsageActions.BOARD_REFRESH)
                    onRefresh()
                }) {
                    Icon(Icons.Default.Refresh, contentDescription = stringResource(R.string.board_refresh))
                }
            }
            answer?.let { outcome ->
                Text(
                    when (outcome) {
                        is HerdAnswerOutcome.Failed -> stringResource(R.string.board_answer_failed, outcome.reason)
                        else -> stringResource(R.string.board_answer_not_blocked)
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    // Gold is the only raised voice, never red.
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                )
            }
            when {
                loading -> Note(stringResource(R.string.board_loading))

                cards.isEmpty() -> Note(stringResource(R.string.board_empty))

                else -> BoxWithConstraints(modifier = Modifier.weight(1f).fillMaxWidth()) {
                    if (maxWidth >= BOARD_COLUMNS_MIN_WIDTH) {
                        // Wide: a kanban, one scrolling column per section.
                        Row(modifier = Modifier.fillMaxSize().padding(horizontal = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            sections.forEach { (column, inColumn) ->
                                LazyColumn(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    item { SectionHeader(column, inColumn.size) }
                                    section(column, inColumn, actions)
                                }
                            }
                        }
                    } else {
                        // A phone: the sections stacked, each header staying on top while its cards scroll.
                        LazyColumn(modifier = Modifier.fillMaxSize().padding(horizontal = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            sections.forEach { (column, inColumn) ->
                                stickyHeader(key = column.name) { SectionHeader(column, inColumn.size) }
                                section(column, inColumn, actions)
                            }
                        }
                    }
                }
            }
        }
    }
}

/** What a card does on a tap, a long press, its fold key and its answer keys. */
private class CardActions(
    val now: Long,
    val isOpen: (BoardCard) -> Boolean,
    val toggle: (BoardCard) -> Unit,
    val tap: (BoardCard) -> Unit,
    val longPress: (BoardCard) -> Unit,
    val answer: (BoardCard, HerdAnswer) -> Unit,
    val readScreen: suspend (BoardCard) -> String?,
)

private fun LazyListScope.section(column: BoardColumn, cards: List<BoardCard>, actions: CardActions) {
    if (cards.isEmpty()) {
        item(key = "${column.name}/empty") { EmptyLine(column) }
        return
    }
    items(cards, key = { "${it.session}/${it.paneId}" }) { card -> AgentCard(card, actions) }
}

@Composable
private fun Note(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
    )
}

private fun sectionName(column: BoardColumn): Int = when (column) {
    BoardColumn.NEEDS_YOU -> R.string.board_needs_you
    BoardColumn.WORKING -> R.string.board_working
    BoardColumn.DONE -> R.string.board_done
    BoardColumn.IDLE -> R.string.board_idle
}

/** A section's name and count; Needs you speaks in gold while anyone waits. */
@Composable
private fun SectionHeader(column: BoardColumn, count: Int) {
    val gold = column == BoardColumn.NEEDS_YOU && count > 0
    Surface(color = MaterialTheme.colorScheme.background, modifier = Modifier.fillMaxWidth().testTag(BOARD_HEADER_TAG)) {
        MonoLabel(
            stringResource(R.string.board_section, stringResource(sectionName(column)), count),
            color = if (gold) Ormus.extras.status.blocked else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(vertical = 8.dp),
        )
    }
}

@Composable
private fun EmptyLine(column: BoardColumn) {
    Text(
        stringResource(
            when (column) {
                BoardColumn.NEEDS_YOU -> R.string.board_needs_you_empty
                BoardColumn.WORKING -> R.string.board_working_empty
                BoardColumn.DONE -> R.string.board_done_empty
                BoardColumn.IDLE -> R.string.board_idle_empty
            },
        ),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 4.dp, bottom = 4.dp),
    )
}

/**
 * One agent: its kind and how long it has been in this state, its task (the terminal
 * title), where it lives; Approve and Deny when it waits on a prompt they answer. The fold
 * key reads its screen once, for the screen's last lines and the context use.
 */
@Composable
private fun AgentCard(card: BoardCard, actions: CardActions) {
    val status = Ormus.extras.status
    val (color, style) = when (card.state) {
        HerdrAgentStatus.BLOCKED -> status.blocked to DotStyle.FILLED
        HerdrAgentStatus.WORKING -> status.working to DotStyle.RING
        HerdrAgentStatus.DONE -> status.done to DotStyle.FILLED
        HerdrAgentStatus.IDLE, HerdrAgentStatus.UNKNOWN -> status.idle to DotStyle.HOLLOW
    }
    val isOpen = actions.isOpen(card)
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .testTag(BOARD_CARD_TAG)
            .combinedClickable(role = Role.Button, onClick = { actions.tap(card) }, onLongClick = { actions.longPress(card) }),
        shape = OrmusCornerShape,
        color = MaterialTheme.colorScheme.surfaceContainer,
        // A waiting agent's card carries the gold hairline; the rest the plain one.
        border = BorderStroke(1.dp, if (card.state == HerdrAgentStatus.BLOCKED) Ormus.extras.cardAccent else Ormus.extras.hairline),
    ) {
        Column(modifier = Modifier.padding(start = 12.dp, end = 4.dp, top = 4.dp, bottom = 10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                StatusDot(color = color, style = style, size = 8.dp)
                Spacer(Modifier.width(8.dp))
                Text(
                    (card.agent ?: stringResource(R.string.board_agent)).uppercase(),
                    style = MaterialTheme.typography.labelLarge,
                    fontFamily = OrmusMono,
                    color = if (card.state == HerdrAgentStatus.BLOCKED) status.blocked else MaterialTheme.colorScheme.onSurface,
                )
                card.since?.let { since ->
                    Text(
                        " · " + AgentsBoard.age(actions.now - since),
                        style = MaterialTheme.typography.labelLarge,
                        fontFamily = OrmusMono,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(Modifier.weight(1f))
                IconButton(onClick = { actions.toggle(card) }) {
                    Icon(
                        if (isOpen) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                        contentDescription = stringResource(if (isOpen) R.string.board_less else R.string.board_more),
                    )
                }
            }
            card.title?.let { title ->
                Text(
                    title,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(end = 8.dp),
                )
            }
            MonoLabel(
                listOf(
                    card.workspaceLabel.ifBlank { card.workspaceNumber.toString() },
                    card.tabLabel.ifBlank { card.tabNumber.toString() },
                    card.session,
                ).joinToString(" · "),
                modifier = Modifier.padding(top = 4.dp, end = 8.dp),
            )
            if (isOpen) Screen(card, actions.readScreen)
            if (card.answerable) {
                Row(modifier = Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OrmusKey(
                        label = stringResource(R.string.chat_approve),
                        onClick = { actions.answer(card, HerdAnswer.APPROVE) },
                        tone = KeyTone.ON,
                        contentPadding = WORD_KEY_PADDING,
                    )
                    OrmusKey(
                        label = stringResource(R.string.chat_deny),
                        onClick = { actions.answer(card, HerdAnswer.DENY) },
                        contentPadding = WORD_KEY_PADDING,
                    )
                }
            }
        }
    }
}

/**
 * An open card's screen: one read when it opens and when the agent changes state, never on
 * a timer; the context use a Claude Code status line shows, then the screen's last lines.
 */
@Composable
private fun Screen(card: BoardCard, readScreen: suspend (BoardCard) -> String?) {
    var screen by remember { mutableStateOf<String?>(null) }
    var read by remember { mutableStateOf(false) }
    val reader by rememberUpdatedState(readScreen)
    LaunchedEffect(card.session, card.paneId, card.state) {
        screen = reader(card)
        read = true
    }
    val text = screen
    Column(modifier = Modifier.padding(top = 6.dp, end = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        when {
            !read -> MonoLabel(stringResource(R.string.board_reading))

            text == null -> MonoLabel(stringResource(R.string.board_read_failed))

            else -> {
                AgentsBoard.contextUse(text)?.let { use ->
                    MonoLabel(
                        if (use.detail != null) stringResource(R.string.board_context_detail, use.percent, use.detail) else stringResource(R.string.board_context, use.percent),
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
                HerdApproval.tail(text, SCREEN_LINES)?.let { tail ->
                    Surface(color = MaterialTheme.colorScheme.surfaceContainerHighest, shape = OrmusCornerShape) {
                        Text(
                            tail,
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = OrmusMono,
                            modifier = Modifier.padding(8.dp).fillMaxWidth(),
                        )
                    }
                }
            }
        }
    }
}

/** The Herdr panel's key that opens the board, naming how many agents need the user in gold. */
@Composable
internal fun BoardKey(needsYou: Int, onClick: () -> Unit) {
    OrmusKey(
        label = if (needsYou > 0) stringResource(R.string.board_open_count, needsYou) else stringResource(R.string.board_open),
        onClick = onClick,
        tone = if (needsYou > 0) KeyTone.ACCENT else KeyTone.PLAIN,
        contentPadding = WORD_KEY_PADDING,
    )
}

/** The snapshots the board shows: [primary] (the default session's) and the [others] still running. */
internal fun boardSnapshots(primary: HerdrSessionSnapshot?, others: Map<String, HerdrSessionSnapshot>, sessions: List<String>): Map<String, HerdrSessionSnapshot> = buildMap {
    primary?.let { put(HerdrCommands.DEFAULT_SESSION, it) }
    putAll(others.filterKeys { it in sessions && !HerdrSessions.isDefault(it) })
}

/** How many agents wait for the user in every session the Herdr side holds a snapshot of. */
internal fun boardNeedsYou(primary: HerdrSessionSnapshot?, others: Map<String, HerdrSessionSnapshot>, sessions: List<String>): Int = AgentsBoard.needsYou(boardSnapshots(primary, others, sessions))
