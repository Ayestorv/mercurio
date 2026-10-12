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
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.preference.PreferenceManager
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.connectbot.BuildConfig
import org.connectbot.R
import org.connectbot.service.FixPrompt
import org.connectbot.ui.components.KeyTone
import org.connectbot.ui.components.OrmusKey
import org.connectbot.ui.components.WORD_KEY_PADDING
import org.connectbot.ui.components.copyFixPrompt
import org.connectbot.ui.screens.chat.AgentChat
import org.connectbot.ui.theme.Ormus
import org.connectbot.ui.theme.OrmusCornerShape
import org.connectbot.ui.theme.OrmusMono
import org.connectbot.usage.LocalUsageLog
import org.connectbot.usage.UsageActions
import solutions.ormus.logos.herd.HerdResult
import solutions.ormus.logos.herd.HerdSession
import solutions.ormus.logos.herd.HerdrAction
import solutions.ormus.logos.herd.HerdrCommands
import solutions.ormus.logos.herd.HerdrDirection
import solutions.ormus.logos.herd.HerdrFailure
import solutions.ormus.logos.herd.HerdrKey
import solutions.ormus.logos.herd.HerdrKeys
import solutions.ormus.logos.herd.HerdrPrefix
import solutions.ormus.logos.herd.HerdrSessionSnapshot
import solutions.ormus.logos.herd.HerdrSessions
import solutions.ormus.logos.herd.HerdrTabInfo

/** Test tag on the card shown while Herdr is not answering. */
const val HERDR_OFFLINE_TAG = "herdr_offline"

private const val COPIED_MS = 2000L

/** The longest label a key of the user's takes, so it stays a key. */
private const val MAX_KEY_LABEL = 16

/** Test tag on the "/ PREFIX ..." label: the prefix Mercurio sends on this host. */
const val HERDR_PREFIX_TAG = "herdr_prefix"

/** Test tag on the one-line result of the last action. */
const val HERDR_RESULT_TAG = "herdr_result"

/** Test tag on the row of Herdr session keys at the top of the panel. */
const val HERDR_SESSION_ROW_TAG = "herdr_session_row"

/** Test tag on the row of numbered tab keys at the top of the panel. */
const val HERDR_TAB_ROW_TAG = "herdr_tab_row"

/** The tab row numbers the first nine tabs, as Herdr's own tab keys do; the rest are under Tabs. */
private const val TAB_ROW_MAX = 9

/** Arrow keys in the Panes group, and the width of their "/ FOCUS" or "/ SWAP" label. */
private val ARROW_KEY_WIDTH = 52.dp
private val ARROW_LABEL_WIDTH = 72.dp

/** The tallest the Herdr panel's action area gets before it scrolls, so the terminal stays in view. */
private val PANEL_BODY_MAX_HEIGHT = 220.dp

/** The panel shows one group of Herdr actions at a time. */
enum class HerdrSection(val label: Int) {
    AGENTS(R.string.herdr_group_agents),
    PANES(R.string.herdr_group_panes),
    TABS(R.string.herdr_group_tabs),

    // "Spaces" so all five group tabs fit a phone's width; the buttons keep "workspace".
    WORKSPACES(R.string.herdr_section_workspaces),
    SESSION(R.string.herdr_group_session),
}

/**
 * Every Herdr action in a compact panel, one group ([section]) at a time: a row of group
 * tabs, then that group's actions, one tap each. Rename actions ask for one name first;
 * close and stop actions ask once. Live from [session]; the last result clears when the
 * panel closes.
 */
@Composable
fun HerdrCommandPanel(
    session: HerdSession,
    section: HerdrSection,
    onSection: (HerdrSection) -> Unit,
    modifier: Modifier = Modifier,
    /** The host as a fix prompt names it: nickname (user@hostname:port). */
    host: String = "",
    /** The user's hidden, changed and added keys, one layout for the app. */
    store: HerdrPanelStore = rememberHerdrPanelStore(),
    /** Opens the focused agent's chat; the Chat key is off where it has none. */
    onChat: (() -> Unit)? = null,
    /** Open Jump to (every tab of every session); no key without it. */
    onJumpTo: (() -> Unit)? = null,
    /** Open the Agents board (every agent of every session); no key without it. */
    onBoard: (() -> Unit)? = null,
    /** Open the diff of the focused pane's work tree; no Diff key when null. */
    onDiff: (() -> Unit)? = null,
) {
    val usage = LocalUsageLog.current
    val layout by store.layout.collectAsState()
    val sessions by session.sessions.collectAsState()
    val chosen by session.chosen.collectAsState()
    val defaultSnapshot by session.snapshot.collectAsState()
    val others by session.others.collectAsState()
    // The panel acts on the chosen Herdr session; the default one is polled, the others fetched.
    val snapshot = if (HerdrSessions.isDefault(chosen)) defaultSnapshot else others[chosen]
    val result by session.result.collectAsState()
    val failure by session.failure.collectAsState()
    val hostKeys by session.keys.collectAsState()
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    // Act on what has focus now, not on the last poll, and on the sessions running now.
    LaunchedEffect(session) {
        session.refresh()
        session.refreshSessions()
    }
    LaunchedEffect(session, chosen) { if (!HerdrSessions.isDefault(chosen)) session.refresh(chosen) }
    // Closing the panel without using a prefix the Herdr key sent leaves Herdr's prefix mode.
    DisposableEffect(session) {
        onDispose {
            session.cancelPrefix()
            session.clearResult()
        }
    }
    HerdrCommandContent(
        snapshot = snapshot,
        result = result,
        failure = failure,
        hostKeys = hostKeys,
        section = section,
        onSection = onSection,
        onJumpTo = onJumpTo,
        sessions = sessions,
        chosenSession = chosen,
        onChooseSession = { name ->
            usage.log(UsageActions.HERDR_SHEET_SESSION)
            session.choose(name)
        },
        // A prompt to paste into an agent: what failed on this host, the command, its output.
        onCopyFix = { reason ->
            usage.log(UsageActions.HERDR_SHEET_FIX_PROMPT)
            val f = failure
            val prompt = FixPrompt.herdr(
                host = host.ifBlank { "this host" },
                appVersion = BuildConfig.VERSION_NAME,
                command = f?.command,
                exitCode = f?.exitCode,
                stderr = f?.stderr,
                stdout = f?.stdout,
                reason = reason,
            )
            scope.launch { clipboard.copyFixPrompt(prompt) }
        },
        // The prefix fell back to Herdr's default, or a client key is off: the read command and why.
        onCopyPrefixFix = { reason ->
            usage.log(UsageActions.HERDR_SHEET_FIX_PROMPT)
            val prompt = FixPrompt.herdr(
                host = host.ifBlank { "this host" },
                appVersion = BuildConfig.VERSION_NAME,
                command = HerdrPrefix.readCommand,
                exitCode = null,
                stderr = null,
                stdout = null,
                reason = reason,
            )
            scope.launch { clipboard.copyFixPrompt(prompt) }
        },
        onRetry = {
            usage.log(UsageActions.HERDR_SHEET_RETRY)
            scope.launch { session.refresh(chosen) }
        },
        onRun = { action, text ->
            // The usage log records which action ran, never the name typed for it.
            usage.log(herdrSheetUsageId(action))
            session.perform(action, text, chosen)
        },
        onTabKey = { action ->
            usage.log(UsageActions.HERDR_SHEET_TAB_KEY)
            session.perform(action, session = chosen)
        },
        layout = layout,
        // The usage log records what changed, never the label or key typed.
        onEdit = { edit ->
            usage.log(edit.usageId)
            when (edit) {
                is HerdrPanelEdit.Hide -> store.hide(edit.id)
                is HerdrPanelEdit.EditDefault -> store.edit(edit.id, edit.key)
                is HerdrPanelEdit.Add -> store.add(edit.key)
                is HerdrPanelEdit.EditCustom -> store.replace(edit.index, edit.key)
                is HerdrPanelEdit.Remove -> store.remove(edit.index)
                HerdrPanelEdit.Restore -> store.restoreDefaults()
                HerdrPanelEdit.OpenMenu -> Unit
            }
        },
        onChat = onChat,
        onBoard = onBoard?.let { open ->
            {
                usage.log(UsageActions.BOARD_OPEN)
                open()
            }
        },
        needsYou = boardNeedsYou(defaultSnapshot, others, sessions),
        onDiff = onDiff?.let {
            {
                usage.log(UsageActions.HERDR_SHEET_DIFF)
                it()
            }
        },
        modifier = modifier,
    )
}

/** The app's [HerdrPanelStore], over its default preferences. */
@Composable
private fun rememberHerdrPanelStore(): HerdrPanelStore {
    val context = LocalContext.current
    return remember { HerdrPanelStore(PreferenceManager.getDefaultSharedPreferences(context)) }
}

/** A change to the panel's layout, from a long press, Add key or Restore defaults. */
sealed interface HerdrPanelEdit {
    val usageId: String

    /** A long press opened a key's menu; the layout is unchanged. */
    data object OpenMenu : HerdrPanelEdit {
        override val usageId = UsageActions.HERDR_SHEET_KEY_MENU
    }

    data class Hide(val id: String) : HerdrPanelEdit {
        override val usageId = UsageActions.HERDR_SHEET_KEY_HIDE
    }

    data class EditDefault(val id: String, val key: HerdrPanelKey) : HerdrPanelEdit {
        override val usageId = UsageActions.HERDR_SHEET_KEY_EDIT
    }

    data class Add(val key: HerdrPanelKey) : HerdrPanelEdit {
        override val usageId = UsageActions.HERDR_SHEET_KEY_ADD
    }

    data class EditCustom(val index: Int, val key: HerdrPanelKey) : HerdrPanelEdit {
        override val usageId = UsageActions.HERDR_SHEET_KEY_EDIT
    }

    data class Remove(val index: Int) : HerdrPanelEdit {
        override val usageId = UsageActions.HERDR_SHEET_KEY_REMOVE
    }

    data object Restore : HerdrPanelEdit {
        override val usageId = UsageActions.HERDR_SHEET_RESTORE
    }
}

/** The key a long press opened a menu on: a default key (by its usage id) or custom key [index]. */
private sealed interface KeyTarget {
    data class Default(val action: HerdrAction, val id: String) : KeyTarget

    data class Custom(val index: Int, val key: HerdrPanelKey) : KeyTarget
}

/** The panel's own prompts: a key's menu, the key form (a null target adds one), Restore defaults. */
private sealed interface PanelPrompt {
    data class Menu(val target: KeyTarget) : PanelPrompt

    data class Form(val target: KeyTarget?) : PanelPrompt

    data object Restore : PanelPrompt
}

/** What the action keys need from the panel: the user's layout, the host's Herdr keys, a tap, a long press. */
private class PanelKeys(
    val layout: HerdrPanelLayout,
    val hostKeys: HerdrKeys,
    val tap: (HerdrAction) -> Unit,
    val longPress: (KeyTarget) -> Unit,
)

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun HerdrCommandContent(
    snapshot: HerdrSessionSnapshot?,
    result: HerdResult?,
    section: HerdrSection,
    onSection: (HerdrSection) -> Unit,
    onRun: (HerdrAction, String?) -> Unit,
    modifier: Modifier = Modifier,
    failure: HerdrFailure? = null,
    /** Herdr's keys on the host: the prefix, shown with the client keys, and each client key's binding. */
    hostKeys: HerdrKeys = HerdrKeys.DEFAULT,
    /** Copy a fix prompt for the given one-line reason. */
    onCopyFix: (String) -> Unit = {},
    /** Copy a fix prompt for why the prefix fell back to Herdr's default or a client key is off. */
    onCopyPrefixFix: (String) -> Unit = {},
    onRetry: () -> Unit = {},
    /** A numbered key in the tab row: focus that tab. */
    onTabKey: (HerdrAction.FocusTab) -> Unit = { onRun(it, null) },
    /** The user's hidden, changed and added keys. */
    layout: HerdrPanelLayout = HerdrPanelLayout(),
    /** Change [layout]: hide, edit, add, remove a key, or restore the defaults. */
    onEdit: (HerdrPanelEdit) -> Unit = {},
    /** Opens the focused agent's chat (Agents group); no Chat key when null. */
    onChat: (() -> Unit)? = null,
    /** The host's running Herdr sessions; a switcher shows when there is more than one. */
    sessions: List<String> = emptyList(),
    /** The session [snapshot] is from and the actions run in. */
    chosenSession: String = HerdrCommands.DEFAULT_SESSION,
    onChooseSession: (String) -> Unit = {},
    /** Open Jump to; the Tabs group leads with its key when set. */
    onJumpTo: (() -> Unit)? = null,
    /** Open the Agents board; the Agents group leads with its key when set. */
    onBoard: (() -> Unit)? = null,
    /** Agents waiting for the user in every session, counted on the board key. */
    needsYou: Int = 0,
    /** Open the diff viewer; no Diff key when null. */
    onDiff: (() -> Unit)? = null,
) {
    // An action waiting for its name or its confirmation (the second tap).
    var pending by remember { mutableStateOf<HerdrAction?>(null) }

    // A key's menu, the key form, or Restore defaults, open in place of the actions.
    var prompt by remember { mutableStateOf<PanelPrompt?>(null) }

    fun tap(action: HerdrAction) {
        if (action.needsText || action.confirm) pending = action else onRun(action, null)
    }

    val keys = PanelKeys(layout, hostKeys, ::tap) { target ->
        onEdit(HerdrPanelEdit.OpenMenu)
        prompt = PanelPrompt.Menu(target)
    }

    // The user's keystroke keys run as plain ClientKeys; the result line still names them.
    val keyLabels: Map<HerdrAction, String> = (layout.edited.values + layout.custom)
        .mapNotNull { key -> key.action()?.let { it to key.label } }
        .toMap()

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        // Which Herdr session the panel acts on, when the host runs more than one.
        SessionRow(sessions, chosenSession) { name ->
            pending = null
            prompt = null
            onChooseSession(name)
        }
        // The focused workspace's tabs by number, whatever group is open: one tap from the Herdr key.
        TabKeyRow(snapshot) { action ->
            pending = null
            prompt = null
            onTabKey(action)
        }
        // Herdr not answering yet: why, a prompt to fix it, and another try.
        if (snapshot == null) {
            OfflineCard(failure = failure, onCopyFix = onCopyFix, onRetry = onRetry)
        }
        result?.let { r ->
            ResultLine(r, keyLabels)
            if (r is HerdResult.Failed || r is HerdResult.Unavailable) {
                val reason = resultText(r, keyLabels)
                FixPromptKey(onCopy = { onCopyFix(reason) })
            }
        }
        // Group tabs: brand keys, the open group gold-tinted, all five on one row.
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            HerdrSection.entries.forEach { s ->
                OrmusKey(
                    label = stringResource(s.label),
                    onClick = {
                        pending = null
                        prompt = null
                        onSection(s)
                    },
                    modifier = Modifier.weight(1f),
                    tone = if (s == section) KeyTone.ACCENT else KeyTone.PLAIN,
                )
            }
        }
        val body = Modifier
            .fillMaxWidth()
            .heightIn(max = PANEL_BODY_MAX_HEIGHT)
            .verticalScroll(rememberScrollState())
        prompt?.let { p ->
            Column(modifier = body) {
                when (p) {
                    is PanelPrompt.Menu -> KeyMenu(
                        target = p.target,
                        label = targetLabel(p.target, layout),
                        onEdit = { prompt = PanelPrompt.Form(p.target) },
                        onHide = {
                            prompt = null
                            onEdit(
                                when (val t = p.target) {
                                    is KeyTarget.Default -> HerdrPanelEdit.Hide(t.id)
                                    is KeyTarget.Custom -> HerdrPanelEdit.Remove(t.index)
                                },
                            )
                        },
                        onCancel = { prompt = null },
                    )

                    is PanelPrompt.Form -> {
                        val target = p.target
                        KeyForm(
                            target = target,
                            initial = when (target) {
                                null -> HerdrPanelKey("", "")

                                is KeyTarget.Custom -> target.key

                                // Herdr's key for a default is the host's binding after the prefix.
                                is KeyTarget.Default -> layout.edited[target.id]
                                    ?: HerdrPanelKey(targetLabel(target, layout), hostSpec(target.action, hostKeys))
                            },
                            onSave = { key ->
                                prompt = null
                                onEdit(
                                    when (target) {
                                        null -> HerdrPanelEdit.Add(key)
                                        is KeyTarget.Default -> HerdrPanelEdit.EditDefault(target.id, key)
                                        is KeyTarget.Custom -> HerdrPanelEdit.EditCustom(target.index, key)
                                    },
                                )
                            },
                            onCancel = { prompt = null },
                        )
                    }

                    PanelPrompt.Restore -> RestorePrompt(
                        onConfirm = {
                            prompt = null
                            onEdit(HerdrPanelEdit.Restore)
                        },
                        onCancel = { prompt = null },
                    )
                }
            }
            return@Column
        }
        val current = pending
        if (current != null) {
            Column(modifier = body) {
                if (current.needsText) {
                    NamePrompt(
                        action = current,
                        onRun = { name ->
                            pending = null
                            onRun(current, name)
                        },
                        onCancel = { pending = null },
                    )
                } else {
                    ConfirmPrompt(
                        action = current,
                        onConfirm = {
                            pending = null
                            onRun(current, null)
                        },
                        onCancel = { pending = null },
                    )
                }
            }
            return@Column
        }
        Column(modifier = body, verticalArrangement = Arrangement.spacedBy(6.dp)) {
            when (section) {
                HerdrSection.AGENTS -> Actions {
                    onBoard?.let { BoardKey(needsYou, it) }
                    ActionButton(HerdrAction.FocusNextBlocked, keys, primary = true)
                    if (onChat != null) {
                        OrmusKey(
                            label = stringResource(R.string.chat_open),
                            onClick = onChat,
                            enabled = AgentChat.target(snapshot) != null,
                            contentPadding = WORD_KEY_PADDING,
                        )
                    }
                    HerdrAction.START_KINDS.forEach { ActionButton(HerdrAction.StartAgent(it), keys) }
                    ActionButton(HerdrAction.ExplainDetection, keys)
                    onDiff?.let { OrmusKey(label = stringResource(R.string.diff_title), onClick = it, contentPadding = WORD_KEY_PADDING) }
                }

                HerdrSection.PANES -> {
                    Actions {
                        ActionButton(HerdrAction.SplitRight, keys)
                        ActionButton(HerdrAction.SplitDown, keys)
                        ActionButton(HerdrAction.ZoomPane, keys)
                        ActionButton(HerdrAction.RenamePane, keys)
                        ActionButton(HerdrAction.ClosePane, keys)
                    }
                    ArrowRow(R.string.herdr_action_focus) { tap(HerdrAction.FocusPane(it)) }
                    ArrowRow(R.string.herdr_action_swap) { tap(HerdrAction.SwapPane(it)) }
                }

                HerdrSection.TABS -> {
                    // Every tab of every session, the ones that need you first: the group's main key.
                    onJumpTo?.let { open ->
                        OrmusKey(
                            label = stringResource(R.string.herdr_jump_open),
                            onClick = open,
                            tone = KeyTone.ON,
                            contentPadding = WORD_KEY_PADDING,
                        )
                    }
                    val tabs = workspaceTabs(snapshot)
                    if (tabs.isNotEmpty()) {
                        JumpLabel(R.string.herdr_jump_tabs)
                        Actions {
                            tabs.forEach { tab ->
                                OrmusKey(
                                    label = tab.label.ifBlank { tab.number.toString() },
                                    onClick = { tap(HerdrAction.FocusTab(tab.tabId)) },
                                    tone = if (tab.tabId == snapshot?.focusedTabId) KeyTone.ACCENT else KeyTone.PLAIN,
                                    contentPadding = WORD_KEY_PADDING,
                                )
                            }
                        }
                    }
                    Actions {
                        ActionButton(HerdrAction.NewTab, keys)
                        ActionButton(HerdrAction.RenameTab, keys)
                        ActionButton(HerdrAction.CloseTab, keys)
                    }
                }

                HerdrSection.WORKSPACES -> {
                    val workspaces = snapshot?.workspaces.orEmpty().sortedBy { it.number }
                    if (workspaces.isNotEmpty()) {
                        JumpLabel(R.string.herdr_jump_workspaces)
                        Actions {
                            workspaces.forEach { workspace ->
                                OrmusKey(
                                    label = stringResource(R.string.herdr_workspace_entry, workspace.number, workspace.label),
                                    onClick = { tap(HerdrAction.FocusWorkspace(workspace.workspaceId)) },
                                    tone = if (workspace.workspaceId == snapshot?.focusedWorkspaceId) KeyTone.ACCENT else KeyTone.PLAIN,
                                    contentPadding = WORD_KEY_PADDING,
                                )
                            }
                        }
                    }
                    Actions {
                        ActionButton(HerdrAction.NAVIGATE_WORKSPACES, keys)
                        ActionButton(HerdrAction.NewWorkspace, keys)
                        ActionButton(HerdrAction.RenameWorkspace, keys)
                        ActionButton(HerdrAction.CloseWorkspace, keys)
                    }
                }

                HerdrSection.SESSION -> {
                    PrefixLine(hostKeys, layout, onCopyFix = onCopyPrefixFix)
                    Actions {
                        ActionButton(HerdrAction.TOGGLE_SIDEBAR, keys)
                        ActionButton(HerdrAction.COPY_MODE, keys)
                        ActionButton(HerdrAction.GOTO, keys)
                        ActionButton(HerdrAction.OPEN_NOTIFICATION, keys)
                        ActionButton(HerdrAction.DETACH, keys)
                        ActionButton(HerdrAction.RELOAD_CONFIG, keys)
                        ActionButton(HerdrAction.HELP, keys)
                        ActionButton(HerdrAction.StopSession, keys)
                        // The user's own keys, then the keys that change the panel.
                        layout.custom.forEachIndexed { index, key ->
                            key.action()?.let { action ->
                                OrmusKey(
                                    label = key.label,
                                    onClick = { tap(action) },
                                    onLongClick = { keys.longPress(KeyTarget.Custom(index, key)) },
                                    contentPadding = WORD_KEY_PADDING,
                                )
                            }
                        }
                        OrmusKey(
                            label = stringResource(R.string.herdr_key_add),
                            onClick = { prompt = PanelPrompt.Form(null) },
                            contentPadding = WORD_KEY_PADDING,
                        )
                        if (!layout.isDefault) {
                            OrmusKey(
                                label = stringResource(R.string.herdr_key_restore),
                                onClick = { prompt = PanelPrompt.Restore },
                                contentPadding = WORD_KEY_PADDING,
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * The prefix Mercurio sends ahead of the client keys, why when it is a fallback, and why
 * each client key the host binds to nothing Mercurio can send is off (unless the user hid
 * it or gave it a key of their own).
 */
@Composable
private fun PrefixLine(keys: HerdrKeys, layout: HerdrPanelLayout, onCopyFix: (String) -> Unit) {
    val problems = listOfNotNull(keys.prefix.problem) + HerdrKey.entries.mapNotNull { key ->
        val id = herdrSheetUsageId(HerdrAction.BoundKey(key))
        keys.problem(key)?.takeIf { id !in layout.hidden && id !in layout.edited }
    }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        MicroLabel(stringResource(R.string.herdr_prefix_label, keys.prefix.spec), modifier = Modifier.testTag(HERDR_PREFIX_TAG))
        problems.forEach { problem ->
            Text(problem, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
        }
        if (problems.isNotEmpty()) FixPromptKey(onCopy = { onCopyFix(problems.joinToString("\n")) })
    }
}

/** The key a default keystroke key sends after the prefix on this host ("d"); empty when it is not a prefix binding. */
private fun hostSpec(action: HerdrAction, keys: HerdrKeys): String {
    val spec = (action as? HerdrAction.BoundKey)?.let { keys.bindings[it.key]?.spec }.orEmpty()
    return if (spec.startsWith(PREFIX_PLUS, ignoreCase = true)) spec.substring(PREFIX_PLUS.length) else ""
}

private const val PREFIX_PLUS = "prefix+"

/** The focused workspace's tabs, by Herdr's tab number. */
private fun workspaceTabs(snapshot: HerdrSessionSnapshot?): List<HerdrTabInfo> = snapshot?.tabs.orEmpty()
    .filter { it.workspaceId == snapshot?.focusedWorkspaceId }
    .sortedBy { it.number }

/**
 * One key per tab of the focused workspace, numbered 1 to 9 by position and named by the
 * tab's label for TalkBack; the focused tab is gold-tinted. The keys share one row's width,
 * so nine fit a phone. Hidden while there is no snapshot or only one tab.
 */
@Composable
private fun TabKeyRow(snapshot: HerdrSessionSnapshot?, onTap: (HerdrAction.FocusTab) -> Unit) {
    val tabs = workspaceTabs(snapshot).take(TAB_ROW_MAX)
    if (tabs.size < 2) return
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .testTag(HERDR_TAB_ROW_TAG),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        tabs.forEachIndexed { index, tab ->
            val focused = tab.tabId == snapshot?.focusedTabId
            val number = (index + 1).toString()
            OrmusKey(
                label = number,
                onClick = { onTap(HerdrAction.FocusTab(tab.tabId)) },
                modifier = Modifier
                    .weight(1f)
                    .semantics { selected = focused },
                tone = if (focused) KeyTone.ACCENT else KeyTone.PLAIN,
                description = tab.label.ifBlank { number },
            )
        }
    }
}

/**
 * One key per running Herdr session, the chosen one gold-tinted, in a row that scrolls
 * sideways when the names do not fit. Hidden while the host runs one session.
 */
@Composable
private fun SessionRow(sessions: List<String>, chosen: String, onChoose: (String) -> Unit) {
    if (sessions.size < 2) return
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .testTag(HERDR_SESSION_ROW_TAG),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        MicroLabel(stringResource(R.string.herdr_session_label))
        sessions.forEach { name ->
            val picked = name == chosen
            OrmusKey(
                label = name,
                onClick = { onChoose(name) },
                modifier = Modifier.semantics { selected = picked },
                tone = if (picked) KeyTone.ACCENT else KeyTone.PLAIN,
                contentPadding = WORD_KEY_PADDING,
            )
        }
    }
}

/** One group's buttons, wrapped onto as many lines as they need. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Actions(content: @Composable () -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        content()
    }
}

/** The usage-log id of a sheet action (see [UsageActions]). */
fun herdrSheetUsageId(action: HerdrAction): String = when (action) {
    HerdrAction.FocusNextBlocked -> UsageActions.HERDR_SHEET_NEXT_BLOCKED

    is HerdrAction.StartAgent -> when (action.kind) {
        "claude" -> UsageActions.HERDR_SHEET_START_CLAUDE
        "codex" -> UsageActions.HERDR_SHEET_START_CODEX
        else -> UsageActions.HERDR_SHEET_START_GROK
    }

    HerdrAction.ExplainDetection -> UsageActions.HERDR_SHEET_EXPLAIN

    is HerdrAction.FocusAgent -> UsageActions.HERD_CARD

    HerdrAction.SplitRight -> UsageActions.HERDR_SHEET_SPLIT_RIGHT

    HerdrAction.SplitDown -> UsageActions.HERDR_SHEET_SPLIT_DOWN

    HerdrAction.ZoomPane -> UsageActions.HERDR_SHEET_ZOOM

    is HerdrAction.FocusPane -> when (action.direction) {
        HerdrDirection.LEFT -> UsageActions.HERDR_SHEET_FOCUS_LEFT
        HerdrDirection.RIGHT -> UsageActions.HERDR_SHEET_FOCUS_RIGHT
        HerdrDirection.UP -> UsageActions.HERDR_SHEET_FOCUS_UP
        HerdrDirection.DOWN -> UsageActions.HERDR_SHEET_FOCUS_DOWN
    }

    is HerdrAction.SwapPane -> when (action.direction) {
        HerdrDirection.LEFT -> UsageActions.HERDR_SHEET_SWAP_LEFT
        HerdrDirection.RIGHT -> UsageActions.HERDR_SHEET_SWAP_RIGHT
        HerdrDirection.UP -> UsageActions.HERDR_SHEET_SWAP_UP
        HerdrDirection.DOWN -> UsageActions.HERDR_SHEET_SWAP_DOWN
    }

    HerdrAction.RenamePane -> UsageActions.HERDR_SHEET_RENAME_PANE

    HerdrAction.ClosePane -> UsageActions.HERDR_SHEET_CLOSE_PANE

    HerdrAction.NewTab -> UsageActions.HERDR_SHEET_NEW_TAB

    HerdrAction.RenameTab -> UsageActions.HERDR_SHEET_RENAME_TAB

    HerdrAction.CloseTab -> UsageActions.HERDR_SHEET_CLOSE_TAB

    is HerdrAction.FocusTab -> UsageActions.HERDR_SHEET_JUMP_TAB

    HerdrAction.NewWorkspace -> UsageActions.HERDR_SHEET_NEW_WORKSPACE

    HerdrAction.RenameWorkspace -> UsageActions.HERDR_SHEET_RENAME_WORKSPACE

    HerdrAction.CloseWorkspace -> UsageActions.HERDR_SHEET_CLOSE_WORKSPACE

    is HerdrAction.FocusWorkspace -> UsageActions.HERDR_SHEET_JUMP_WORKSPACE

    HerdrAction.COPY_MODE -> UsageActions.HERDR_SHEET_COPY_MODE

    HerdrAction.GOTO -> UsageActions.HERDR_SHEET_GOTO

    HerdrAction.OPEN_NOTIFICATION -> UsageActions.HERDR_SHEET_OPEN_NOTIFICATION

    HerdrAction.DETACH -> UsageActions.HERDR_SHEET_DETACH

    HerdrAction.RELOAD_CONFIG -> UsageActions.HERDR_SHEET_RELOAD_CONFIG

    HerdrAction.HELP -> UsageActions.HERDR_SHEET_HELP

    HerdrAction.TOGGLE_SIDEBAR -> UsageActions.HERDR_SHEET_SIDEBAR

    HerdrAction.NAVIGATE_WORKSPACES -> UsageActions.HERDR_SHEET_NAVIGATE_WORKSPACES

    is HerdrAction.ClientKey -> UsageActions.KEY_OTHER

    is HerdrAction.BoundKey -> UsageActions.KEY_OTHER

    HerdrAction.StopSession -> UsageActions.HERDR_SHEET_STOP_SESSION
}

/** What an action is called on its button and in the result line. */
@Composable
fun herdrActionLabel(action: HerdrAction): String = when (action) {
    HerdrAction.FocusNextBlocked -> stringResource(R.string.herdr_action_next_blocked)

    is HerdrAction.StartAgent -> stringResource(R.string.herdr_action_start, action.kind)

    HerdrAction.ExplainDetection -> stringResource(R.string.herdr_action_explain)

    is HerdrAction.FocusAgent -> stringResource(R.string.herdr_action_focus)

    HerdrAction.SplitRight -> stringResource(R.string.herdr_action_split_right)

    HerdrAction.SplitDown -> stringResource(R.string.herdr_action_split_down)

    HerdrAction.ZoomPane -> stringResource(R.string.herdr_action_zoom)

    is HerdrAction.FocusPane ->
        stringResource(R.string.herdr_direction_action, stringResource(R.string.herdr_action_focus), directionWord(action.direction))

    is HerdrAction.SwapPane ->
        stringResource(R.string.herdr_direction_action, stringResource(R.string.herdr_action_swap), directionWord(action.direction))

    HerdrAction.RenamePane -> stringResource(R.string.herdr_action_rename_pane)

    HerdrAction.ClosePane -> stringResource(R.string.herdr_action_close_pane)

    HerdrAction.NewTab -> stringResource(R.string.herdr_action_new_tab)

    HerdrAction.RenameTab -> stringResource(R.string.herdr_action_rename_tab)

    HerdrAction.CloseTab -> stringResource(R.string.herdr_action_close_tab)

    is HerdrAction.FocusTab -> stringResource(R.string.herdr_jump_tabs)

    HerdrAction.NewWorkspace -> stringResource(R.string.herdr_action_new_workspace)

    HerdrAction.RenameWorkspace -> stringResource(R.string.herdr_action_rename_workspace)

    HerdrAction.CloseWorkspace -> stringResource(R.string.herdr_action_close_workspace)

    is HerdrAction.FocusWorkspace -> stringResource(R.string.herdr_jump_workspaces)

    HerdrAction.COPY_MODE -> stringResource(R.string.herdr_action_copy_mode)

    HerdrAction.GOTO -> stringResource(R.string.herdr_action_goto)

    HerdrAction.OPEN_NOTIFICATION -> stringResource(R.string.herdr_action_open_notification)

    HerdrAction.DETACH -> stringResource(R.string.herdr_action_detach)

    HerdrAction.RELOAD_CONFIG -> stringResource(R.string.herdr_action_reload_config)

    HerdrAction.HELP -> stringResource(R.string.herdr_action_help)

    HerdrAction.TOGGLE_SIDEBAR -> stringResource(R.string.herdr_action_toggle_sidebar)

    HerdrAction.NAVIGATE_WORKSPACES -> stringResource(R.string.herdr_action_navigate_workspaces)

    is HerdrAction.ClientKey -> action.key

    is HerdrAction.BoundKey -> action.key.config

    HerdrAction.StopSession -> stringResource(R.string.herdr_action_stop_session)
}

@Composable
private fun directionWord(direction: HerdrDirection): String = stringResource(
    when (direction) {
        HerdrDirection.LEFT -> R.string.herdr_direction_left
        HerdrDirection.RIGHT -> R.string.herdr_direction_right
        HerdrDirection.UP -> R.string.herdr_direction_up
        HerdrDirection.DOWN -> R.string.herdr_direction_down
    },
)

/** The brand's micro-label: JetBrains Mono, uppercase, tracked, prefixed with "/ ". */
@Composable
private fun MicroLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        "/ " + text.uppercase(),
        style = MaterialTheme.typography.labelSmall,
        fontFamily = OrmusMono,
        letterSpacing = 0.08.em,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier,
    )
}

@Composable
private fun JumpLabel(label: Int) {
    MicroLabel(stringResource(label))
}

/**
 * One Herdr action as a brand key: the gold fill for the panel's main action, hairline
 * keys for the rest. Close and stop are plain keys too (gold is the only accent, never
 * red); their confirmation is the second tap. Gone when the user hid it; the user's label
 * and key when they edited it, which wins over the host's binding; a long press opens its
 * menu. A client key the host binds to nothing Mercurio can send shows off (PrefixLine says
 * why) but still takes its long press.
 */
@Composable
private fun ActionButton(
    action: HerdrAction,
    keys: PanelKeys,
    primary: Boolean = false,
) {
    val id = herdrSheetUsageId(action)
    if (id in keys.layout.hidden) return
    val edited = keys.layout.edited[id]
    val run = edited?.action() ?: action
    OrmusKey(
        label = edited?.label ?: herdrActionLabel(action),
        onClick = { keys.tap(run) },
        enabled = run !is HerdrAction.BoundKey || keys.hostKeys.bytes(run.key) != null,
        onLongClick = { keys.longPress(KeyTarget.Default(action, id)) },
        tone = if (primary) KeyTone.ON else KeyTone.PLAIN,
        contentPadding = WORD_KEY_PADDING,
    )
}

/** What the menu and the form call the key: the user's label, else the action's. */
@Composable
private fun targetLabel(target: KeyTarget, layout: HerdrPanelLayout): String = when (target) {
    is KeyTarget.Custom -> target.key.label
    is KeyTarget.Default -> layout.edited[target.id]?.label ?: herdrActionLabel(target.action)
}

@Composable
private fun ArrowRow(label: Int, onTap: (HerdrDirection) -> Unit) {
    val name = stringResource(label)
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        MicroLabel(name, modifier = Modifier.width(ARROW_LABEL_WIDTH))
        listOf(
            HerdrDirection.LEFT to "←",
            HerdrDirection.DOWN to "↓",
            HerdrDirection.UP to "↑",
            HerdrDirection.RIGHT to "→",
        ).forEach { (direction, glyph) ->
            OrmusKey(
                label = glyph,
                onClick = { onTap(direction) },
                modifier = Modifier.width(ARROW_KEY_WIDTH),
                symbolSize = 20.sp,
                description = stringResource(R.string.herdr_direction_action, name, directionWord(direction)),
            )
        }
    }
}

/** What the result line says for [result]; [keyLabels] names the user's keystroke keys. */
@Composable
private fun resultText(result: HerdResult, keyLabels: Map<HerdrAction, String> = emptyMap()): String {
    val label = keyLabels[result.action] ?: herdrActionLabel(result.action)
    return when (result) {
        is HerdResult.Running -> stringResource(R.string.herdr_result_running, label)
        is HerdResult.Succeeded -> stringResource(R.string.herdr_result_ok, label)
        is HerdResult.Unavailable -> stringResource(R.string.herdr_result_unavailable, label)
        is HerdResult.Failed -> stringResource(R.string.herdr_result_failed, label, result.message)
    }
}

@Composable
private fun ResultLine(result: HerdResult, keyLabels: Map<HerdrAction, String>) {
    val text = resultText(result, keyLabels)
    val isError = result is HerdResult.Unavailable || result is HerdResult.Failed
    Column(modifier = Modifier.testTag(HERDR_RESULT_TAG).semantics(mergeDescendants = true) {}) {
        Text(
            text,
            style = MaterialTheme.typography.bodyMedium,
            // Gold is the only raised voice; a failure reads in it, never in red.
            color = if (isError) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        (result as? HerdResult.Succeeded)?.output?.let { output ->
            Surface(color = MaterialTheme.colorScheme.surfaceContainerHighest, shape = OrmusCornerShape) {
                Text(
                    output,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(8.dp),
                )
            }
        }
    }
}

@Composable
private fun NamePrompt(
    action: HerdrAction,
    onRun: (String) -> Unit,
    onCancel: () -> Unit,
) {
    var name by remember(action) { mutableStateOf("") }
    val focus = remember { FocusRequester() }
    // Focus the field so the dictation keyboard comes straight up.
    LaunchedEffect(action) { focus.requestFocus() }
    PromptCard {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            MicroLabel(herdrActionLabel(action))
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                singleLine = true,
                label = { Text(stringResource(R.string.herdr_name_label)) },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { if (name.isNotBlank()) onRun(name) }),
                shape = OrmusCornerShape,
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(focus),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OrmusKey(
                    label = stringResource(R.string.herdr_name_run),
                    onClick = { onRun(name) },
                    enabled = name.isNotBlank(),
                    tone = KeyTone.ON,
                    contentPadding = WORD_KEY_PADDING,
                )
                OrmusKey(label = stringResource(R.string.herdr_cancel), onClick = onCancel, contentPadding = WORD_KEY_PADDING)
            }
        }
    }
}

@Composable
private fun ConfirmPrompt(
    action: HerdrAction,
    onConfirm: () -> Unit,
    onCancel: () -> Unit,
) {
    val question = when (action) {
        HerdrAction.ClosePane -> R.string.herdr_confirm_close_pane
        HerdrAction.CloseTab -> R.string.herdr_confirm_close_tab
        HerdrAction.CloseWorkspace -> R.string.herdr_confirm_close_workspace
        else -> R.string.herdr_confirm_stop_session
    }
    PromptCard {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(question), style = MaterialTheme.typography.bodyLarge)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OrmusKey(
                    label = stringResource(R.string.herdr_confirm),
                    onClick = onConfirm,
                    tone = KeyTone.ON,
                    contentPadding = WORD_KEY_PADDING,
                )
                OrmusKey(label = stringResource(R.string.herdr_cancel), onClick = onCancel, contentPadding = WORD_KEY_PADDING)
            }
        }
    }
}

/** A key's long-press menu: Edit key (keystroke keys only), Hide (Remove for the user's own), Cancel. */
@Composable
private fun KeyMenu(
    target: KeyTarget,
    label: String,
    onEdit: () -> Unit,
    onHide: () -> Unit,
    onCancel: () -> Unit,
) {
    val keystroke = target is KeyTarget.Custom || (target as KeyTarget.Default).action is HerdrAction.BoundKey
    PromptCard {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            MicroLabel(label)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (keystroke) {
                    OrmusKey(label = stringResource(R.string.herdr_key_edit), onClick = onEdit, contentPadding = WORD_KEY_PADDING)
                }
                OrmusKey(
                    label = stringResource(if (target is KeyTarget.Custom) R.string.herdr_key_remove else R.string.herdr_key_hide),
                    onClick = onHide,
                    contentPadding = WORD_KEY_PADDING,
                )
                OrmusKey(label = stringResource(R.string.herdr_cancel), onClick = onCancel, contentPadding = WORD_KEY_PADDING)
            }
        }
    }
}

/**
 * A keystroke key's label and its Herdr key (sent after the prefix), for a new key
 * ([target] null) or an edit. Save waits for a label and a key a terminal can send.
 */
@Composable
private fun KeyForm(
    target: KeyTarget?,
    initial: HerdrPanelKey,
    onSave: (HerdrPanelKey) -> Unit,
    onCancel: () -> Unit,
) {
    var label by remember(target) { mutableStateOf(initial.label) }
    var spec by remember(target) { mutableStateOf(initial.spec) }
    val key = HerdrPanelKey(label, spec)
    val unsendable = spec.isNotBlank() && key.action() == null
    val focus = remember { FocusRequester() }
    LaunchedEffect(target) { focus.requestFocus() }
    PromptCard {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            MicroLabel(stringResource(if (target == null) R.string.herdr_key_add else R.string.herdr_key_edit))
            // Side by side, so Save stays in view under the panel's height cap.
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = label,
                    onValueChange = { label = it.take(MAX_KEY_LABEL) },
                    singleLine = true,
                    label = { Text(stringResource(R.string.herdr_key_label)) },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                    shape = OrmusCornerShape,
                    modifier = Modifier
                        .weight(1f)
                        .focusRequester(focus),
                )
                OutlinedTextField(
                    value = spec,
                    onValueChange = { spec = it },
                    singleLine = true,
                    label = { Text(stringResource(R.string.herdr_key_spec)) },
                    // Gold, not red, when the key cannot be sent.
                    supportingText = {
                        Text(
                            if (unsendable) stringResource(R.string.herdr_key_cannot_send, spec.trim()) else stringResource(R.string.herdr_key_spec_hint),
                            color = if (unsendable) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    },
                    keyboardOptions = KeyboardOptions(
                        capitalization = KeyboardCapitalization.None,
                        autoCorrectEnabled = false,
                        keyboardType = KeyboardType.Ascii,
                        imeAction = ImeAction.Done,
                    ),
                    keyboardActions = KeyboardActions(onDone = { if (key.isValid) onSave(key) }),
                    shape = OrmusCornerShape,
                    modifier = Modifier.weight(1.4f),
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OrmusKey(
                    label = stringResource(R.string.herdr_key_save),
                    onClick = { onSave(key) },
                    enabled = key.isValid,
                    tone = KeyTone.ON,
                    contentPadding = WORD_KEY_PADDING,
                )
                OrmusKey(label = stringResource(R.string.herdr_cancel), onClick = onCancel, contentPadding = WORD_KEY_PADDING)
            }
        }
    }
}

/** Asks once before Restore defaults brings hidden keys back and drops the user's own. */
@Composable
private fun RestorePrompt(onConfirm: () -> Unit, onCancel: () -> Unit) {
    PromptCard {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.herdr_confirm_restore), style = MaterialTheme.typography.bodyLarge)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OrmusKey(
                    label = stringResource(R.string.herdr_confirm),
                    onClick = onConfirm,
                    tone = KeyTone.ON,
                    contentPadding = WORD_KEY_PADDING,
                )
                OrmusKey(label = stringResource(R.string.herdr_cancel), onClick = onCancel, contentPadding = WORD_KEY_PADDING)
            }
        }
    }
}

/** Herdr not answering: the reason, Copy fix prompt (once something failed), and Retry. */
@Composable
private fun OfflineCard(
    failure: HerdrFailure?,
    onCopyFix: (String) -> Unit,
    onRetry: () -> Unit,
) {
    val reason = FixPrompt.herdrReason(failure)
    PromptCard {
        Column(
            modifier = Modifier
                .padding(12.dp)
                .testTag(HERDR_OFFLINE_TAG),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            MicroLabel(stringResource(if (failure == null) R.string.herdr_waiting else R.string.herdr_not_answering))
            Text(reason, style = MaterialTheme.typography.bodyMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (failure != null) FixPromptKey(onCopy = { onCopyFix(reason) })
                OrmusKey(label = stringResource(R.string.herdr_retry), onClick = onRetry, contentPadding = WORD_KEY_PADDING)
            }
        }
    }
}

/** "Copy fix prompt", the gold fill, reading "Copied" for a moment after a tap. */
@Composable
private fun FixPromptKey(onCopy: () -> Unit) {
    var copied by remember { mutableStateOf(false) }
    LaunchedEffect(copied) {
        if (copied) {
            delay(COPIED_MS)
            copied = false
        }
    }
    OrmusKey(
        label = stringResource(if (copied) R.string.copy_details_copied else R.string.copy_details),
        onClick = {
            onCopy()
            copied = true
        },
        tone = KeyTone.ON,
        contentPadding = WORD_KEY_PADDING,
    )
}

/** A prompt inside the panel: the brand card, a 3px corner and a gold hairline. */
@Composable
private fun PromptCard(content: @Composable () -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerHighest,
        shape = OrmusCornerShape,
        border = BorderStroke(1.dp, Ormus.extras.cardAccent),
    ) { content() }
}
