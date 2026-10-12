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

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import org.connectbot.R
import org.connectbot.service.TerminalBridge
import org.connectbot.ui.components.KeyTone
import org.connectbot.ui.components.OrmusKey
import org.connectbot.ui.components.WORD_KEY_PADDING
import org.connectbot.ui.theme.Ormus
import org.connectbot.ui.theme.OrmusCornerShape
import org.connectbot.ui.theme.OrmusMono
import org.connectbot.usage.LocalUsageLog
import org.connectbot.usage.UsageActions
import solutions.ormus.logos.herd.HerdAnswer
import solutions.ormus.logos.herd.HerdAnswerOutcome
import solutions.ormus.logos.herd.HerdApproval
import solutions.ormus.logos.herd.HerdrAgentStatus
import solutions.ormus.logos.herd.HerdrCommands

/** Test tags on the chat's parts. */
const val CHAT_LIST_TAG = "chat_list"
const val CHAT_USER_TAG = "chat_user"
const val CHAT_AGENT_TAG = "chat_agent"
const val CHAT_TOOL_TAG = "chat_tool"
const val CHAT_REPLY_TAG = "chat_reply"
const val CHAT_WAITING_TAG = "chat_waiting"

/** Full screen, drawn edge to edge: [ChatContent] keeps its own parts clear of the bars. */
internal val CHAT_DIALOG_PROPERTIES = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)

// A user bubble leaves the left of the screen to the agent, like a messaging app.
private val BUBBLE_MAX_WIDTH = 320.dp

/**
 * The agent conversation in [pane], or the pane focused when it opened, as a phone chat: your messages on the right,
 * the agent's replies on the left, each tool call one line that opens on a tap. Reads
 * while it is on screen (quick while the agent works, slower while it idles); the reply
 * box types into that pane, wherever Herdr's focus has moved since. [onOpenReader] shows the pane's raw
 * terminal output instead.
 */
@Composable
fun ChatViewDialog(
    bridge: TerminalBridge,
    onDismiss: () -> Unit,
    onOpenReader: () -> Unit,
    /** The Herdr session whose focused pane the chat opens on. */
    session: String = HerdrCommands.DEFAULT_SESSION,
    /** The pane to open on instead of the focused one (an Agents board card). */
    pane: String? = null,
    viewModel: ChatViewModel = hiltViewModel(),
) {
    val host = remember(bridge, session) { BridgeChatHost(bridge, session) }
    val lifecycle = LocalLifecycleOwner.current
    // Reads only while the chat is on screen: leaving the app or closing the chat stops them.
    LaunchedEffect(host, pane) {
        viewModel.open(pane)
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) { viewModel.follow(host) }
    }
    val state by viewModel.uiState.collectAsState()
    Dialog(
        onDismissRequest = onDismiss,
        properties = CHAT_DIALOG_PROPERTIES,
    ) {
        ChatContent(
            state = state,
            fallbackTitle = bridge.host.nickname,
            onClose = onDismiss,
            onRefresh = viewModel::refresh,
            onSend = { viewModel.send(host, it) },
            onAnswer = { viewModel.answer(host, it) },
            onOpenReader = onOpenReader,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatContent(
    state: ChatUiState,
    fallbackTitle: String,
    onClose: () -> Unit,
    onRefresh: () -> Unit,
    onSend: (String) -> Unit,
    onAnswer: (HerdAnswer) -> Unit,
    onOpenReader: () -> Unit,
    modifier: Modifier = Modifier,
    /** The bars and keyboard to keep clear of: the dialog draws edge to edge, under the status bar. */
    windowInsets: WindowInsets = WindowInsets.safeDrawing,
) {
    val usage = LocalUsageLog.current
    val listState = rememberLazyListState()
    // The rows after the messages: the "earlier not shown" note before, the status after.
    val lead = if (state.truncated) 1 else 0
    val rows = lead + state.items.size + 1
    val atBottom by remember {
        derivedStateOf {
            val last = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
            last >= listState.layoutInfo.totalItemsCount - 2
        }
    }
    // Follow the newest message while you are at the bottom; reading further up stays put.
    LaunchedEffect(state.items, state.target?.status) {
        if (state.items.isNotEmpty() && (atBottom || listState.firstVisibleItemIndex == 0)) {
            listState.scrollToItem(rows - 1)
        }
    }

    Surface(modifier = modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(windowInsets),
        ) {
            ChatHeader(
                state = state,
                fallbackTitle = fallbackTitle,
                onClose = {
                    usage.log(UsageActions.CHAT_CLOSE)
                    onClose()
                },
                onRefresh = {
                    usage.log(UsageActions.CHAT_REFRESH)
                    onRefresh()
                },
                onOpenReader = {
                    usage.log(UsageActions.CHAT_TERMINAL)
                    onOpenReader()
                },
            )
            state.problem?.let { problem -> ProblemLine(problem) }
            state.answer?.let { outcome -> AnswerLine(outcome) }
            state.unsent?.let { unsent -> UnsentLine(unsent) }
            PullToRefreshBox(
                isRefreshing = state.loading,
                onRefresh = {
                    usage.log(UsageActions.CHAT_REFRESH)
                    onRefresh()
                },
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
            ) {
                LazyColumn(
                    state = listState,
                    contentPadding = PaddingValues(horizontal = Ormus.spacing.md, vertical = Ormus.spacing.sm),
                    verticalArrangement = Arrangement.spacedBy(Ormus.spacing.sm),
                    modifier = Modifier
                        .fillMaxSize()
                        .testTag(CHAT_LIST_TAG),
                ) {
                    if (state.truncated) {
                        item { MicroLabel(stringResource(R.string.chat_earlier)) }
                    }
                    itemsIndexed(state.items) { _, item ->
                        when (item.k) {
                            ChatItem.USER -> UserBubble(item.text)
                            ChatItem.TOOL -> ToolRow(item, onOpen = { usage.log(UsageActions.CHAT_TOOL) })
                            else -> AgentText(item.text)
                        }
                    }
                    item { StatusRow(state, onAnswer = onAnswer) }
                }
            }
            ReplyBar(
                unsent = state.unsent,
                agent = state.target?.agent,
                enabled = state.target != null,
                primary = !state.blocked,
                onSend = { text ->
                    usage.log(UsageActions.CHAT_SEND)
                    onSend(text)
                },
            )
        }
    }
}

@Composable
private fun ChatHeader(
    state: ChatUiState,
    fallbackTitle: String,
    onClose: () -> Unit,
    onRefresh: () -> Unit,
    onOpenReader: () -> Unit,
) {
    val target = state.target
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Ormus.spacing.xs, vertical = Ormus.spacing.xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onClose) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.chat_close))
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                listOfNotNull(target?.agent, target?.place).joinToString(" · ").ifEmpty { fallbackTitle },
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            target?.title?.let { title ->
                Text(
                    title,
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = OrmusMono),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        IconButton(onClick = onOpenReader) {
            Icon(Icons.Default.Terminal, contentDescription = stringResource(R.string.chat_terminal))
        }
        IconButton(onClick = onRefresh, enabled = !state.loading) {
            Icon(Icons.Default.Refresh, contentDescription = stringResource(R.string.chat_refresh))
        }
    }
}

/** The brand's micro-label: JetBrains Mono, uppercase, tracked, prefixed with "/ ". */
@Composable
private fun MicroLabel(text: String, modifier: Modifier = Modifier, color: Color = MaterialTheme.colorScheme.onSurfaceVariant) {
    Text(
        "/ " + text.uppercase(),
        style = MaterialTheme.typography.labelSmall,
        fontFamily = OrmusMono,
        letterSpacing = 0.08.em,
        color = color,
        modifier = modifier,
    )
}

@Composable
private fun UserBubble(text: String) {
    Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
        Surface(
            color = MaterialTheme.colorScheme.surfaceContainerHighest,
            shape = OrmusCornerShape,
            modifier = Modifier
                .widthIn(max = BUBBLE_MAX_WIDTH)
                .border(1.dp, Ormus.extras.hairline, OrmusCornerShape)
                .testTag(CHAT_USER_TAG),
        ) {
            SelectionContainer {
                Text(
                    text,
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.padding(horizontal = Ormus.spacing.md, vertical = Ormus.spacing.sm),
                )
            }
        }
    }
}

@Composable
private fun AgentText(text: String) {
    val blocks = remember(text) { ChatMarkdown.blocks(text) }
    val code = SpanStyle(fontFamily = OrmusMono, background = MaterialTheme.colorScheme.surfaceContainerHighest)
    SelectionContainer(modifier = Modifier.testTag(CHAT_AGENT_TAG)) {
        Column(verticalArrangement = Arrangement.spacedBy(Ormus.spacing.xs)) {
            blocks.forEach { block ->
                when (block) {
                    is ChatBlock.Code -> Surface(
                        color = MaterialTheme.colorScheme.surfaceContainerLowest,
                        shape = OrmusCornerShape,
                        modifier = Modifier
                            .fillMaxWidth()
                            .border(1.dp, Ormus.extras.hairline, OrmusCornerShape),
                    ) {
                        Text(
                            block.text,
                            fontFamily = OrmusMono,
                            style = MaterialTheme.typography.bodySmall,
                            softWrap = false,
                            modifier = Modifier
                                .horizontalScroll(rememberScrollState())
                                .padding(Ormus.spacing.sm),
                        )
                    }

                    is ChatBlock.Heading -> Text(
                        ChatMarkdown.inline(block.text, code),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                    )

                    is ChatBlock.Item -> Row(modifier = Modifier.padding(start = Ormus.spacing.md * block.depth)) {
                        Text(
                            block.marker,
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.widthIn(min = 20.dp),
                        )
                        Text(ChatMarkdown.inline(block.text, code), style = MaterialTheme.typography.bodyLarge)
                    }

                    is ChatBlock.Paragraph -> Text(ChatMarkdown.inline(block.text, code), style = MaterialTheme.typography.bodyLarge)
                }
            }
        }
    }
}

/** A tool call on one hairline row; a tap opens its full target. */
@Composable
private fun ToolRow(item: ChatItem, onOpen: () -> Unit) {
    var open by rememberSaveable(item.line, item.detail) { mutableStateOf(false) }
    val state = when (item.state) {
        "error" -> stringResource(R.string.chat_tool_failed)
        "run" -> stringResource(R.string.chat_tool_running)
        else -> null
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, Ormus.extras.hairline, OrmusCornerShape)
            .clickable(enabled = item.detail.isNotEmpty()) {
                if (!open) onOpen()
                open = !open
            }
            .padding(horizontal = Ormus.spacing.sm, vertical = Ormus.spacing.xs)
            .testTag(CHAT_TOOL_TAG),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                (if (open) "▾ " else "▸ ") + item.line,
                fontFamily = OrmusMono,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            state?.let {
                Text(
                    it,
                    fontFamily = OrmusMono,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.padding(start = Ormus.spacing.sm),
                )
            }
        }
        if (open) {
            SelectionContainer {
                Text(
                    item.detail,
                    fontFamily = OrmusMono,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = Ormus.spacing.xs),
                )
            }
        }
    }
}

/** The end of the conversation: the agent is working, waiting for you (with Approve and Deny), or quiet. */
@Composable
private fun StatusRow(state: ChatUiState, onAnswer: (HerdAnswer) -> Unit) {
    val usage = LocalUsageLog.current
    val target = state.target ?: return
    if (target.status == HerdrAgentStatus.BLOCKED) {
        val pending = state.items.lastOrNull()?.takeIf { it.isTool && it.state == "run" }
        Surface(
            color = MaterialTheme.colorScheme.surfaceContainer,
            shape = OrmusCornerShape,
            modifier = Modifier
                .fillMaxWidth()
                .border(1.dp, Ormus.extras.cardAccent, OrmusCornerShape)
                .testTag(CHAT_WAITING_TAG),
        ) {
            Column(modifier = Modifier.padding(Ormus.spacing.md), verticalArrangement = Arrangement.spacedBy(Ormus.spacing.sm)) {
                MicroLabel(stringResource(R.string.chat_waiting), color = Ormus.extras.status.blocked)
                pending?.let { Text(it.line, fontFamily = OrmusMono, style = MaterialTheme.typography.bodySmall) }
                if (target.agent in HerdApproval.PROVEN_KINDS) {
                    Row(horizontalArrangement = Arrangement.spacedBy(Ormus.spacing.sm)) {
                        OrmusKey(
                            label = stringResource(R.string.chat_approve),
                            onClick = {
                                usage.log(UsageActions.CHAT_APPROVE)
                                onAnswer(HerdAnswer.APPROVE)
                            },
                            tone = KeyTone.ON,
                            contentPadding = WORD_KEY_PADDING,
                        )
                        OrmusKey(
                            label = stringResource(R.string.chat_deny),
                            onClick = {
                                usage.log(UsageActions.CHAT_DENY)
                                onAnswer(HerdAnswer.DENY)
                            },
                            contentPadding = WORD_KEY_PADDING,
                        )
                    }
                }
            }
        }
        return
    }
    val label = when (target.status) {
        HerdrAgentStatus.WORKING -> R.string.chat_working
        HerdrAgentStatus.DONE -> R.string.chat_done
        else -> R.string.chat_idle
    }
    Column {
        if (state.items.isEmpty() && state.problem == null && !state.loading) {
            Text(stringResource(R.string.chat_empty), color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        MicroLabel(stringResource(label), modifier = Modifier.padding(vertical = Ormus.spacing.xs))
    }
}

@Composable
private fun ProblemLine(problem: ChatProblem) {
    val text = when (problem) {
        ChatProblem.NotAgent -> stringResource(R.string.chat_not_agent)
        ChatProblem.NoTranscript -> stringResource(R.string.chat_no_transcript)
        ChatProblem.PaneGone -> stringResource(R.string.chat_pane_gone)
        is ChatProblem.ReadFailed -> stringResource(R.string.chat_read_failed, problem.reason)
    }
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(horizontal = Ormus.spacing.lg, vertical = Ormus.spacing.xs),
    )
}

@Composable
private fun AnswerLine(outcome: HerdAnswerOutcome) {
    val text = when (outcome) {
        HerdAnswerOutcome.NotBlocked -> stringResource(R.string.chat_not_blocked)
        is HerdAnswerOutcome.Failed -> stringResource(R.string.chat_answer_failed, outcome.reason)
        HerdAnswerOutcome.Sent -> return
    }
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(horizontal = Ormus.spacing.lg, vertical = Ormus.spacing.xs),
    )
}

@Composable
private fun UnsentLine(unsent: ChatUnsent) {
    Text(
        when (val reason = unsent.reason) {
            is ChatProblem.ReadFailed -> stringResource(R.string.chat_not_sent_failed, reason.reason)
            else -> stringResource(R.string.chat_not_sent_gone)
        },
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(horizontal = Ormus.spacing.lg, vertical = Ormus.spacing.xs),
    )
}

/** The reply box: what you type goes into the agent's pane, then Enter. A reply that was not sent comes back. */
@Composable
private fun ReplyBar(unsent: ChatUnsent?, agent: String?, enabled: Boolean, primary: Boolean, onSend: (String) -> Unit) {
    var text by rememberSaveable { mutableStateOf("") }
    LaunchedEffect(unsent) {
        if (unsent != null && text.isBlank()) text = unsent.text
    }
    fun send() {
        if (text.isBlank()) return
        onSend(text)
        text = ""
    }
    Surface(color = MaterialTheme.colorScheme.surfaceContainer, modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.padding(horizontal = Ormus.spacing.sm, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Ormus.spacing.sm),
        ) {
            TextField(
                value = text,
                onValueChange = { text = it },
                enabled = enabled,
                placeholder = { Text(stringResource(R.string.chat_reply_hint, agent ?: ""), maxLines = 1) },
                maxLines = 5,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = { send() }),
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                    unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                    disabledContainerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                    focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent,
                    disabledIndicatorColor = Color.Transparent,
                    cursorColor = MaterialTheme.colorScheme.primary,
                ),
                shape = OrmusCornerShape,
                modifier = Modifier
                    .weight(1f)
                    .testTag(CHAT_REPLY_TAG),
            )
            OrmusKey(
                label = stringResource(R.string.chat_send),
                onClick = { send() },
                enabled = enabled && text.isNotBlank(),
                tone = if (primary) KeyTone.ON else KeyTone.PLAIN,
                contentPadding = WORD_KEY_PADDING,
            )
        }
    }
}
