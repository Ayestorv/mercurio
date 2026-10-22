/*
 * ConnectBot: simple, powerful, open-source SSH client for Android
 * Copyright 2007-2026 Kenny Root
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

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Link
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonColors
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.connectbot.R
import org.connectbot.service.TerminalBridge
import org.connectbot.ui.components.ormusPanelTop
import org.connectbot.ui.screens.herd.HerdrCommandPanel
import org.connectbot.ui.screens.herd.HerdrJumpPicker
import org.connectbot.ui.screens.herd.HerdrSection
import org.connectbot.ui.screens.herd.boardNeedsYou
import org.connectbot.ui.screens.herd.boardSnapshots
import org.connectbot.ui.screens.reader.LastReply
import org.connectbot.ui.screens.reader.RecentOutput
import org.connectbot.ui.theme.Ormus
import org.connectbot.ui.theme.OrmusCornerShape
import org.connectbot.ui.theme.OrmusMono
import org.connectbot.usage.LocalUsageLog
import org.connectbot.usage.UsageActions
import org.connectbot.util.LatestUrl
import solutions.ormus.logos.herd.AgentsBoard
import solutions.ormus.logos.herd.Herd
import solutions.ormus.logos.herd.HerdrKey
import solutions.ormus.logos.herd.HerdrKeys
import solutions.ormus.logos.herd.HerdrSessionSnapshot
import solutions.ormus.logos.terminal.BracketedPaste

// Control bytes built from code points so no raw control chars live in the source.
private val ESC: String = 27.toChar().toString() // 0x1b
private val CR: String = 13.toChar().toString() // 0x0d carriage return
private val TAB: String = 9.toChar().toString() // 0x09 tab
private val CLEAR_LINE: String = 21.toChar().toString() // 0x15 Ctrl+U: wipe the typed line

// CSI sequences for the control keys.
private val MODE: String = ESC + "[Z" // Shift+Tab: cycle Claude Code plan / auto-accept
private val UP: String = ESC + "[A"
private val DOWN: String = ESC + "[B"
private val RIGHT: String = ESC + "[C"
private val LEFT: String = ESC + "[D"

// Row 1's ↑ and ↓ page: the full-screen agent TUIs scroll on Page Up and Page Down.
private val PAGE_UP: String = ESC + "[5~"
private val PAGE_DOWN: String = ESC + "[6~"

// Jump to bottom: this many Page Downs in one burst. Paging stops at the bottom, so the
// burst lands there in anything that pages, the way holding ↓ does (holding ↓ was half
// of all taps in Ormus's usage log).
private const val BOTTOM_PAGES = 40

// Delay between sending the dictated text and the Enter that submits it, so the
// remote TUI sees a discrete Enter keypress instead of a newline folded into the
// pasted burst.
private const val SEND_ENTER_DELAY_MS = 50L

/**
 * Usage-log action id for a key's sequence, with [herdrKeys] the host's Herdr keys;
 * one hook in [TerminalComposeBar]'s key handler.
 */
private fun usageIdFor(sequence: String, herdrKeys: HerdrKeys): String = when (sequence) {
    MODE -> UsageActions.KEY_MODE
    ESC -> UsageActions.KEY_ESC
    "y" -> UsageActions.KEY_Y
    "n" -> UsageActions.KEY_N
    TAB -> UsageActions.KEY_TAB
    CLEAR_LINE -> UsageActions.KEY_CLEAR_LINE
    UP -> UsageActions.KEY_UP
    DOWN -> UsageActions.KEY_DOWN
    LEFT -> UsageActions.KEY_LEFT
    RIGHT -> UsageActions.KEY_RIGHT
    CR -> UsageActions.KEY_ENTER
    herdrKeys.bytes(HerdrKey.PREVIOUS_TAB) -> UsageActions.HERDR_PREV_TAB
    herdrKeys.bytes(HerdrKey.NEXT_TAB) -> UsageActions.HERDR_NEXT_TAB
    herdrKeys.bytes(HerdrKey.TOGGLE_SIDEBAR) -> UsageActions.HERDR_SIDEBAR
    PAGE_UP -> UsageActions.HERDR_PGUP
    PAGE_DOWN -> UsageActions.HERDR_PGDN
    else -> UsageActions.KEY_OTHER
}

// Hold-to-repeat for the arrow keys: the first press sends at once, then after
// REPEAT_START_MS it repeats every REPEAT_EVERY_MS until released.
private const val REPEAT_START_MS = 400L
private const val REPEAT_EVERY_MS = 60L

// The ⏎ key at the end of row 2: a little wider than a key, as the bar's main action.
private val ENTER_KEY_WIDTH = 56.dp

// Gap between keys in a row. Small enough that eight tray keys stay 44dp wide or
// more on a 411dp phone.
private val KEY_GAP = Ormus.spacing.xs

// Esc and Tab before the field: the 44dp minimum, so the field keeps as much of row 2
// as it can.
private val ROW2_KEY_WIDTH = 44.dp

// A vertical drag at least this long on the bar opens (up) or closes (down) the tray.
private val TRAY_SWIPE_DISTANCE = Ormus.spacing.xl

/**
 * One control key on the surface: writes a raw sequence to the PTY immediately,
 * for driving an interactive TUI such as Claude Code, Codex, or Grok.
 */
@Composable
private fun ControlKey(
    label: String,
    sequence: String,
    onSend: (String) -> Unit,
    modifier: Modifier = Modifier,
    emphasized: Boolean = false,
    enabled: Boolean = true,
    symbolSize: TextUnit = TextUnit.Unspecified,
    description: String? = null,
) {
    val tone = if (emphasized) KeyTone.ACCENT else KeyTone.PLAIN
    Button(
        onClick = { onSend(sequence) },
        modifier = modifier
            .height(KEY_HEIGHT)
            .then(if (description != null) Modifier.semantics { contentDescription = description } else Modifier),
        enabled = enabled,
        shape = KEY_SHAPE,
        colors = keyColors(tone),
        border = keyBorder(tone),
        contentPadding = KEY_PADDING,
    ) { KeyLabel(label, symbolSize) }
}

/**
 * A [ControlKey] that keeps sending while held, for paging and the arrows: a tap
 * sends once, a hold repeats. It sends on press, not on release. [description] names
 * the key when its glyph alone would not (row 1's ↑ and ↓ page).
 */
@Composable
private fun RepeatingControlKey(
    label: String,
    sequence: String,
    onSend: (String) -> Unit,
    modifier: Modifier = Modifier,
    symbolSize: TextUnit = TextUnit.Unspecified,
    description: String? = null,
) {
    val interactions = remember { MutableInteractionSource() }
    val send by rememberUpdatedState(onSend)
    LaunchedEffect(interactions, sequence) {
        var repeating: Job? = null
        interactions.interactions.collect { interaction ->
            when (interaction) {
                is PressInteraction.Press -> {
                    send(sequence)
                    repeating?.cancel()
                    repeating = launch {
                        delay(REPEAT_START_MS)
                        while (isActive) {
                            send(sequence)
                            delay(REPEAT_EVERY_MS)
                        }
                    }
                }

                is PressInteraction.Release, is PressInteraction.Cancel -> repeating?.cancel()
            }
        }
    }
    Button(
        onClick = {},
        modifier = modifier
            .height(KEY_HEIGHT)
            .then(if (description != null) Modifier.semantics { contentDescription = description } else Modifier),
        shape = KEY_SHAPE,
        colors = keyColors(KeyTone.PLAIN),
        border = keyBorder(KeyTone.PLAIN),
        contentPadding = KEY_PADDING,
        interactionSource = interactions,
    ) { KeyLabel(label, symbolSize) }
}

/**
 * The one ⏎ key, at the end of row 2. With text in the field it sends that text, then
 * Enter; with an empty field it is a bare Enter for the terminal. It reads as the gold
 * accent key, and its description says which of the two a tap does.
 */
@Composable
private fun EnterKey(
    sendsText: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val description = stringResource(if (sendsText) R.string.button_send else R.string.compose_bar_enter)
    Button(
        onClick = onClick,
        modifier = modifier
            .height(KEY_HEIGHT)
            .semantics { contentDescription = description },
        shape = KEY_SHAPE,
        colors = keyColors(KeyTone.ACCENT),
        border = keyBorder(KeyTone.ACCENT),
        contentPadding = KEY_PADDING,
    ) { KeyLabel("⏎", 24.sp) }
}

/** The ⋯ key that opens and closes the tray. Gold-tinted while the tray is open. */
@Composable
private fun MoreKey(
    open: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val tone = if (open) KeyTone.ACCENT else KeyTone.PLAIN
    val description = stringResource(R.string.compose_bar_more_keys)
    Button(
        onClick = onClick,
        modifier = modifier
            .height(KEY_HEIGHT)
            .semantics { contentDescription = description },
        shape = KEY_SHAPE,
        colors = keyColors(tone),
        border = keyBorder(tone),
        contentPadding = KEY_PADDING,
    ) { KeyLabel("⋯", 20.sp) }
}

/** A key that is just an icon, named by [description]. */
@Composable
private fun IconKey(
    icon: ImageVector,
    description: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Button(
        onClick = onClick,
        modifier = modifier.height(KEY_HEIGHT),
        shape = KEY_SHAPE,
        colors = keyColors(KeyTone.PLAIN),
        border = keyBorder(KeyTone.PLAIN),
        contentPadding = KEY_PADDING,
    ) { Icon(icon, contentDescription = description, modifier = Modifier.size(20.dp)) }
}

/** Tray key that uploads the phone's latest screenshot and adds its path to the field. */
@Composable
private fun PasteScreenshotKey(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Button(
        onClick = onClick,
        modifier = modifier.height(KEY_HEIGHT),
        shape = KEY_SHAPE,
        colors = keyColors(KeyTone.PLAIN),
        border = keyBorder(KeyTone.PLAIN),
        contentPadding = KEY_PADDING,
    ) {
        Icon(
            Icons.Default.Image,
            contentDescription = stringResource(R.string.compose_bar_paste_screenshot),
            modifier = Modifier.size(18.dp),
        )
        Spacer(Modifier.width(Ormus.spacing.xs))
        KeyLabel(stringResource(R.string.compose_bar_paste))
    }
}

/** Tray key that opens the full-screen output reader. */
@Composable
private fun ReadKey(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Button(
        onClick = onClick,
        modifier = modifier.height(KEY_HEIGHT),
        shape = KEY_SHAPE,
        colors = keyColors(KeyTone.PLAIN),
        border = keyBorder(KeyTone.PLAIN),
        contentPadding = KEY_PADDING,
    ) {
        Icon(
            Icons.AutoMirrored.Filled.MenuBook,
            contentDescription = stringResource(R.string.reader_open),
            modifier = Modifier.size(18.dp),
        )
        Spacer(Modifier.width(Ormus.spacing.xs))
        KeyLabel(stringResource(R.string.compose_bar_read))
    }
}

/**
 * Persistent, dictation-first control surface docked at the bottom of the console.
 *
 * The terminal view uses a TYPE_NULL input connection to capture raw keys, which
 * is why on-device IME features (voice dictation, swipe typing, autocorrect) work
 * poorly when typing straight into it. This bar is a real Compose [TextField] with
 * a full InputConnection, so dictation engines compose cleanly here; the whole line
 * is then written to the PTY as one burst, decoupling the editable dictation buffer
 * from the raw-key terminal stream. Send appends a carriage return so a dictated
 * command executes.
 *
 * The same layout on every host. The Herdr keys show dimmed until the session runs
 * Herdr: its post-login command launches it, or a Herdr snapshot answers on the host.
 *
 * Two rows, always visible:
 * 1. ↑ and ↓, which page (Page Up and Page Down, hold to repeat), Herdr, ⋯, Chat, then Needs you
 *    above ⏎: a tap jumps to the next agent waiting for the user, in any Herdr session on
 *    the host, cycling; with none waiting, or on a long press, it opens the Agents board
 *    ([onBoard]). Gold while an agent waits.
 * 2. Esc, Tab, the dictation field and ⏎. The field takes whatever width is left. ⏎
 *    is Send and Enter in one key: it sends the field's text, then Enter, or a bare
 *    Enter when the field is empty. Dictation comes from the phone's own overlay
 *    (Wispr), so the bar has no mic.
 *
 * ⋯ or a swipe up on the bar opens the tray above it, in two rows:
 * 1. The arrow keys ←, ↑, ↓ and → (for menus that pick with the arrows), ⤓ (jump to the
 *    latest output), Mode, then Clear (Ctrl+U, wipes the line typed at the prompt).
 * 2. Read ([onRead], the full-screen output reader), copy the latest URL, copy the
 *    agent's last reply, paste screenshot, Sidebar (shows
 *    or hides Herdr's side panel), then / ([onSlashMenu], the agent slash-command
 *    menu, shown only when set).
 * Herdr's own Needs you key, Goto, copy mode and Jump to live in the Herdr panel.
 *
 * The tray takes its own height above the bar, so the terminal moves up while it is
 * open instead of sitting under it. Every tray key is two taps away. The tray stays
 * open while its keys are pressed and closes on ⋯, a swipe down or back.
 *
 * Herdr opens the Herdr panel in the same place: every Herdr action, one group at a
 * time ([HerdrCommandPanel]). The console owns whether it is open ([herdrPanelOpen]),
 * so the Herd screen and the palette can open it too; [onHerdrPanelChange] enables the
 * Herdr key once the session's Herdr side is up. The panel and the tray close each
 * other; the panel also closes on Herdr or back.
 *
 * [draft] holds the dictation field, so the slash menu can put a command in it.
 */
@Composable
fun TerminalComposeBar(
    bridge: TerminalBridge,
    modifier: Modifier = Modifier,
    onSend: () -> Unit = {},
    onPasteScreenshot: (suspend () -> String?)? = null,
    onRead: (() -> Unit)? = null,
    herdrPanelOpen: Boolean = false,
    onHerdrPanelChange: ((Boolean) -> Unit)? = null,
    onSlashMenu: (() -> Unit)? = null,
    /** Opens the agent chat from the Herdr panel's Chat key. */
    onChat: (() -> Unit)? = null,
    /** Opens the Agents board, from the Herdr panel's Board key or the Needs you key. */
    onBoard: (() -> Unit)? = null,
    /** Whether the Agents board shows, reading the other sessions itself. */
    boardOpen: Boolean = false,
    /** The Herdr panel's Diff key: open the diff viewer. */
    onDiff: (() -> Unit)? = null,
    draft: DictationDraft = remember { DictationDraft() },
    /** The session's recent output as plain text, for the URL key (blocking work off main). */
    readRecentOutput: suspend () -> String = { withContext(Dispatchers.IO) { RecentOutput.read(bridge).text } },
    /** The agent's last reply, for the copy key; null when there is none. */
    readLastReply: suspend () -> String? = { withContext(Dispatchers.IO) { LastReply.read(bridge) } },
) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val clipboard = LocalClipboard.current
    val usage = LocalUsageLog.current
    val focusManager = LocalFocusManager.current
    val keyboardController = LocalSoftwareKeyboardController.current
    val dictationFocus = remember { FocusRequester() }
    var trayOpen by remember(bridge) { mutableStateOf(false) }

    // Something outside the bar (the slash menu) put text in the field: focus it
    // and raise the keyboard so the rest can be dictated.
    LaunchedEffect(draft.focusRequests) {
        if (draft.focusRequests > 0) {
            dictationFocus.requestFocus()
            keyboardController?.show()
        }
    }

    fun send(actionId: String = UsageActions.SEND) {
        if (draft.text.isEmpty()) return
        usage.log(actionId)
        val payload = draft.text
        draft.value = TextFieldValue()
        bridge.herd?.cancelPrefix()
        // Line breaks go as one paste, so a multi-line draft is one message, not one per line.
        bridge.injectString(BracketedPaste.wrap(payload, bridge.bracketedPaste))
        // Submit with a separate, slightly-delayed Enter so the remote TUI
        // (e.g. Claude Code) registers a discrete Enter and runs the command,
        // rather than folding a trailing newline into the pasted text.
        scope.launch {
            delay(SEND_ENTER_DELAY_MS)
            bridge.injectString(CR)
        }
        // Dictation-first: once the command is sent the user is reading agent
        // output, not typing. Drop focus and collapse the soft keyboard so the
        // terminal reclaims the full viewport until the field is tapped again.
        focusManager.clearFocus()
        keyboardController?.hide()
        onSend()
    }

    // The draft view asked to send: the same path as ⏎, so text, then Enter.
    LaunchedEffect(draft.sendRequests) {
        if (draft.sendRequests > 0) send(UsageActions.DRAFT_SEND)
    }

    // Herdr's keys on this host (HerdSession.keys, read from its config); Herdr's defaults
    // where there is no Herdr side (telnet).
    val herdrKeys by (bridge.herd?.keys ?: DEFAULT_HERDR_KEYS).collectAsState()

    // Keys go through the Herdr side's prefix state: with Herdr's prefix sent ahead (the
    // Herdr key), a Herdr keystroke sends just its key and any other key cancels the
    // prefix first.
    val sendKey: (String) -> Unit = { seq ->
        usage.log(usageIdFor(seq, herdrKeys))
        bridge.injectString(bridge.herd?.keystroke(seq) ?: seq)
        onSend()
    }

    // Jump to the latest output: one burst of Page Downs, logged once.
    fun jumpToBottom() {
        usage.log(UsageActions.KEY_BOTTOM)
        val burst = PAGE_DOWN.repeat(BOTTOM_PAGES)
        bridge.injectString(bridge.herd?.keystroke(burst) ?: burst)
        onSend()
    }

    // ⏎: Send when the field holds text, a bare Enter when it is empty. Each logs its own id.
    fun enter() {
        if (draft.text.isEmpty()) sendKey(CR) else send()
    }

    // Herdr keys work once the session runs Herdr: its post-login command launches it, or
    // a Herdr snapshot has answered on the host (a host where Herdr runs without a
    // post-login command). Until then they show dimmed, so every host has the same layout.
    val herdrLive by (bridge.herd?.snapshot ?: NO_HERDR_SNAPSHOT).collectAsState()
    val herdrSession = Herd.runsHerdr(bridge.host) || herdrLive != null
    // Usage log: stamp console events with whether this session runs Herdr.
    LaunchedEffect(herdrSession) { usage.setHerdr(herdrSession) }

    // The group the Herdr panel shows: the last one picked in this session.
    var herdrSection by rememberSaveable(bridge) { mutableStateOf(HerdrSection.AGENTS) }
    val herdrPanel = herdrPanelOpen && bridge.herd != null && herdrSession

    // Needs you: the agents waiting for the user in every session the Herdr side holds a
    // snapshot of (the Agents board's count). The default and the attached session are polled;
    // the others are read by the board and Jump to, and every so often while both are closed.
    val herdrSessions by (bridge.herd?.sessions ?: NO_HERDR_SESSIONS).collectAsState()
    val herdrOthers by (bridge.herd?.others ?: NO_HERDR_OTHERS).collectAsState()
    val needsYou = boardNeedsYou(herdrLive, herdrOthers, herdrSessions)

    fun openBoard() {
        if (bridge.herd == null || !herdrSession || onBoard == null) return
        usage.log(UsageActions.NEEDS_YOU_BOARD)
        onBoard()
    }

    // The next waiting agent after the attached session's focus, by the board's jump path
    // (focus its pane, attach its session when it is another); the board when none waits.
    fun jumpToNeedsYou() {
        val herd = bridge.herd ?: return
        if (!herdrSession) return
        val target = AgentsBoard.nextNeedsYou(boardSnapshots(herd.snapshot.value, herd.others.value, herd.sessions.value), herd.attached.value)
        if (target == null) {
            openBoard()
            return
        }
        usage.log(UsageActions.NEEDS_YOU_JUMP)
        bridge.jumpToHerdrTab(target.jumpTarget(), target.paneId)?.let { why -> Toast.makeText(context, why, Toast.LENGTH_LONG).show() }
    }

    // Jump to (every tab of every Herdr session) sits where the panel and the tray do.
    var herdrJumpOpen by remember(bridge) { mutableStateOf(false) }
    val herdrJump = herdrJumpOpen && bridge.herd != null && herdrSession

    // With the board and Jump to closed (each reads the other sessions itself), the other
    // sessions are read every so often while the app is in the foreground, so the Needs you
    // count drops when an agent there stops waiting.
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(bridge.herd, herdrSession, boardOpen, herdrJump) {
        val herd = bridge.herd ?: return@LaunchedEffect
        if (herdrSession && !boardOpen && !herdrJump) herd.pollOthers(lifecycle.currentStateFlow.map { it.isAtLeast(Lifecycle.State.STARTED) })
    }

    fun openTray(actionId: String) {
        if (trayOpen) return
        usage.log(actionId)
        if (herdrPanel) onHerdrPanelChange?.invoke(false)
        herdrJumpOpen = false
        trayOpen = true
    }

    fun openHerdrJump(actionId: String) {
        if (bridge.herd == null || !herdrSession) return
        usage.log(actionId)
        trayOpen = false
        if (herdrPanel) onHerdrPanelChange?.invoke(false)
        herdrJumpOpen = true
    }

    // Herdr sends Herdr's prefix at once and opens the panel, so a panel key
    // then needs only its letter. Opening it puts the tray away: one panel at a time.
    // Closing the panel without using the prefix cancels it (HerdrCommandPanel).
    fun toggleHerdrPanel() {
        if (herdrPanel) {
            onHerdrPanelChange?.invoke(false)
            return
        }
        usage.log(UsageActions.HERDR_SHEET_OPEN)
        trayOpen = false
        herdrJumpOpen = false
        bridge.herd?.armPrefix()
        onHerdrPanelChange?.invoke(true)
    }

    fun closeTray() {
        if (!trayOpen) return
        usage.log(UsageActions.BAR_TRAY_CLOSE)
        trayOpen = false
    }

    BackHandler(enabled = trayOpen) { closeTray() }
    BackHandler(enabled = herdrPanel) { onHerdrPanelChange?.invoke(false) }
    BackHandler(enabled = herdrJump) { herdrJumpOpen = false }

    // Swipe up on the bar opens the tray, swipe down on the bar or tray closes it.
    // Only the drag's total distance counts, so a tap on a key is never a swipe.
    val trayDrag = Modifier.pointerInput(Unit) {
        val distance = TRAY_SWIPE_DISTANCE.toPx()
        var total = 0f
        detectVerticalDragGestures(
            onDragStart = { total = 0f },
            onDragEnd = {
                when {
                    total <= -distance -> openTray(UsageActions.BAR_TRAY_SWIPE)
                    total >= distance -> closeTray()
                }
            },
        ) { change, dragAmount ->
            change.consume()
            total += dragAmount
        }
    }

    fun onTextChange(newField: TextFieldValue) {
        draft.value = newField
    }

    // The tray sits in the column above the bar, so its height counts: the console
    // pads the terminal by this whole height and the terminal moves up while the tray
    // is open. A fade only, no slide or expand, so the PTY resizes once each way
    // instead of on every animation frame.
    Column(modifier = modifier.fillMaxWidth()) {
        AnimatedVisibility(
            visible = trayOpen,
            enter = fadeIn(tween(Ormus.motion.FAST_MS)),
            exit = fadeOut(tween(Ormus.motion.FAST_MS)),
        ) {
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .ormusPanelTop()
                    .then(trayDrag)
                    .testTag(TRAY_TEST_TAG),
                color = MaterialTheme.colorScheme.surfaceContainer,
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = Ormus.spacing.sm, vertical = 6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    // Row 1: the arrow keys (menus that pick with them) and Mode.
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(KEY_GAP),
                    ) {
                        ControlKey(
                            "←",
                            LEFT,
                            sendKey,
                            Modifier.weight(1f),
                            symbolSize = 20.sp,
                            description = stringResource(R.string.compose_bar_left),
                        )
                        RepeatingControlKey(
                            "↑",
                            UP,
                            sendKey,
                            Modifier.weight(1f),
                            symbolSize = 20.sp,
                            description = stringResource(R.string.compose_bar_up),
                        )
                        RepeatingControlKey(
                            "↓",
                            DOWN,
                            sendKey,
                            Modifier.weight(1f),
                            symbolSize = 20.sp,
                            description = stringResource(R.string.compose_bar_down),
                        )
                        ControlKey(
                            "→",
                            RIGHT,
                            sendKey,
                            Modifier.weight(1f),
                            symbolSize = 20.sp,
                            description = stringResource(R.string.compose_bar_right),
                        )
                        ControlKey(
                            "⤓",
                            "",
                            { jumpToBottom() },
                            Modifier.weight(1f),
                            symbolSize = 20.sp,
                            description = stringResource(R.string.compose_bar_bottom),
                        )
                        ControlKey("Mode", MODE, sendKey, Modifier.weight(1.3f), emphasized = true)
                        // Ctrl+U wipes what is typed at the prompt: for a dictation that went wrong.
                        ControlKey(
                            stringResource(R.string.compose_bar_clear_line),
                            CLEAR_LINE,
                            sendKey,
                            Modifier.weight(1.3f),
                        )
                    }
                    // Row 2: reading and sharing: Read, paste screenshot, Herdr's side panel, /.
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(KEY_GAP),
                    ) {
                        if (onRead != null) {
                            ReadKey(
                                onClick = {
                                    usage.log(UsageActions.READER_OPEN)
                                    onRead()
                                },
                                modifier = Modifier.weight(1.4f),
                            )
                        } else {
                            Spacer(Modifier.weight(1.4f))
                        }
                        // The latest URL the chat printed, onto this phone's clipboard (not the
                        // host's), with the URL flashed as confirmation.
                        IconKey(
                            icon = Icons.Default.Link,
                            description = stringResource(R.string.compose_bar_copy_url),
                            onClick = {
                                usage.log(UsageActions.BAR_URL_COPY)
                                scope.launch {
                                    val url = LatestUrl.find(readRecentOutput())
                                    if (url != null) clipboard.copyText(url)
                                    Toast.makeText(
                                        context,
                                        if (url != null) context.getString(R.string.compose_bar_url_copied, url) else context.getString(R.string.compose_bar_no_url),
                                        Toast.LENGTH_SHORT,
                                    ).show()
                                }
                            },
                            modifier = Modifier.weight(1f),
                        )
                        // The agent's last reply, onto this phone's clipboard.
                        IconKey(
                            icon = Icons.Default.ContentCopy,
                            description = stringResource(R.string.compose_bar_copy_reply),
                            onClick = {
                                usage.log(UsageActions.BAR_REPLY_COPY)
                                scope.launch {
                                    val reply = readLastReply()
                                    if (reply != null) clipboard.copyText(reply)
                                    Toast.makeText(
                                        context,
                                        if (reply != null) context.getString(R.string.compose_bar_reply_copied, reply.length) else context.getString(R.string.compose_bar_no_reply),
                                        Toast.LENGTH_SHORT,
                                    ).show()
                                }
                            },
                            modifier = Modifier.weight(1f),
                        )
                        if (onPasteScreenshot != null) {
                            PasteScreenshotKey(
                                onClick = {
                                    usage.log(UsageActions.PASTE_SCREENSHOT)
                                    scope.launch {
                                        val path = onPasteScreenshot()
                                        if (!path.isNullOrEmpty()) {
                                            val text = draft.text
                                            val withShot = if (text.isBlank()) {
                                                "read $path "
                                            } else {
                                                text.trimEnd() + " read $path "
                                            }
                                            draft.value = TextFieldValue(withShot, TextRange(withShot.length))
                                        }
                                    }
                                },
                                modifier = Modifier.weight(1.5f),
                            )
                        } else {
                            Spacer(Modifier.weight(1.5f))
                        }
                        // Herdr's side panel, shown or hidden. The tray stays open, so one more
                        // tap puts it back.
                        val sidebar = herdrKeys.bytes(HerdrKey.TOGGLE_SIDEBAR)
                        ControlKey(
                            stringResource(R.string.herdr_action_toggle_sidebar),
                            sidebar.orEmpty(),
                            sendKey,
                            Modifier.weight(1.5f),
                            enabled = herdrSession && sidebar != null,
                        )
                        if (onSlashMenu != null) {
                            ControlKey(
                                "/",
                                "",
                                {
                                    usage.log(UsageActions.BAR_SLASH_MENU)
                                    onSlashMenu()
                                },
                                Modifier.weight(1.1f),
                                symbolSize = 20.sp,
                            )
                        } else {
                            Spacer(Modifier.weight(1.1f))
                        }
                    }
                }
            }
        }
        // The Herdr panel sits where the tray does and pushes the terminal up the same way.
        val herd = bridge.herd
        AnimatedVisibility(
            visible = herdrPanel && herd != null,
            enter = fadeIn(tween(Ormus.motion.FAST_MS)),
            exit = fadeOut(tween(Ormus.motion.FAST_MS)),
        ) {
            if (herd != null) {
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .ormusPanelTop()
                        .testTag(HERDR_PANEL_TEST_TAG),
                    color = MaterialTheme.colorScheme.surfaceContainer,
                ) {
                    HerdrCommandPanel(
                        session = herd,
                        section = herdrSection,
                        onSection = { herdrSection = it },
                        host = bridge.host.let { "${it.nickname} (${it.username}@${it.hostname}:${it.port})" },
                        onChat = onChat,
                        onJumpTo = { openHerdrJump(UsageActions.HERDR_JUMP_OPEN) },
                        onBoard = onBoard,
                        onDiff = onDiff,
                    )
                }
            }
        }
        AnimatedVisibility(
            visible = herdrJump && herd != null,
            enter = fadeIn(tween(Ormus.motion.FAST_MS)),
            exit = fadeOut(tween(Ormus.motion.FAST_MS)),
        ) {
            if (herd != null) {
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .ormusPanelTop()
                        .testTag(HERDR_JUMP_TEST_TAG),
                    color = MaterialTheme.colorScheme.surfaceContainer,
                ) {
                    HerdrJumpPicker(
                        session = herd,
                        onPick = { target ->
                            usage.log(UsageActions.HERDR_JUMP_PICK)
                            herdrJumpOpen = false
                            // The row's pane too, when an agent runs in it (Herdr focuses agents by pane id).
                            bridge.jumpToHerdrTab(target, target.paneId?.takeIf { target.agent != null })?.let { why -> Toast.makeText(context, why, Toast.LENGTH_LONG).show() }
                        },
                    )
                }
            }
        }
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .ormusPanelTop()
                .then(trayDrag),
            // Midnight bar over the code-panel terminal, with the brand's gold top hairline.
            color = MaterialTheme.colorScheme.surfaceContainer,
            tonalElevation = 8.dp,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = Ormus.spacing.sm, vertical = 6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                // Row 1: the page keys, Herdr, ⋯, Chat, then Needs you above ⏎.
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(KEY_GAP),
                ) {
                    // ↑ and ↓ page: Page Up and Page Down, the way he scrolls. The arrow keys
                    // themselves are in the tray.
                    RepeatingControlKey(
                        "↑",
                        PAGE_UP,
                        sendKey,
                        Modifier.weight(1f),
                        symbolSize = 20.sp,
                        description = stringResource(R.string.herdr_key_page_up),
                    )
                    RepeatingControlKey(
                        "↓",
                        PAGE_DOWN,
                        sendKey,
                        Modifier.weight(1f),
                        symbolSize = 20.sp,
                        description = stringResource(R.string.herdr_key_page_down),
                    )
                    // On every host. Dimmed, not hidden, until Herdr answers, so the row never
                    // shifts under the thumb. Gold-tinted while the panel is open. Held, it opens
                    // the pane search (Jump to) with the keyboard up: the panel's own Jump to key
                    // was never tapped in 5,000 console actions, so the search gets a gesture here.
                    OrmusKey(
                        label = stringResource(R.string.herdr_key_commands),
                        onClick = { toggleHerdrPanel() },
                        modifier = Modifier.weight(1.2f),
                        tone = if (herdrPanel) KeyTone.ACCENT else KeyTone.PLAIN,
                        enabled = herdrSession && onHerdrPanelChange != null,
                        description = stringResource(R.string.herdr_key_commands_description),
                        onLongClick = { openHerdrJump(UsageActions.HERDR_JUMP_KEY) },
                    )
                    MoreKey(
                        open = trayOpen,
                        onClick = { if (trayOpen) closeTray() else openTray(UsageActions.BAR_TRAY_OPEN) },
                        modifier = Modifier.weight(1f),
                    )
                    // Chat: the pane under the thumb as a conversation (an agent pane opens Chat
                    // View on it; any other pane its output reader), one tap from Needs you. Chat
                    // View was reached only through the Agents board before (board:chat 16,
                    // chat:open 0 in about 5,000 console actions). Dimmed, like Herdr, until the
                    // session runs Herdr, so the row never shifts.
                    OrmusKey(
                        label = stringResource(R.string.chat_key),
                        onClick = {
                            usage.log(UsageActions.CHAT_KEY)
                            onRead?.invoke()
                        },
                        modifier = Modifier.weight(1f),
                        enabled = herdrSession && onRead != null,
                        description = stringResource(R.string.chat_key_description),
                    )
                    // Where the tab arrows were: swipes switch tabs now. Dimmed, like Herdr,
                    // until the session runs Herdr, so the row never shifts.
                    NeedsYouKey(
                        waiting = needsYou,
                        onClick = { jumpToNeedsYou() },
                        onLongClick = { openBoard() },
                        enabled = herdrSession && bridge.herd != null,
                        modifier = Modifier.weight(2f),
                    )
                }

                // Row 2: Esc, Tab, the dictation field and ⏎ (Send or Enter).
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(KEY_GAP),
                ) {
                    ControlKey("Esc", ESC, sendKey, Modifier.width(ROW2_KEY_WIDTH))
                    ControlKey("Tab", TAB, sendKey, Modifier.width(ROW2_KEY_WIDTH))
                    TextField(
                        value = draft.value,
                        onValueChange = { onTextChange(it) },
                        placeholder = {
                            Text(stringResource(R.string.terminal_text_input_dialog_label), maxLines = 1)
                        },
                        maxLines = 4,
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.Text,
                            imeAction = ImeAction.Send,
                        ),
                        keyboardActions = KeyboardActions(onSend = { send(UsageActions.SEND_IME) }),
                        colors = TextFieldDefaults.colors(
                            focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                            unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                            focusedIndicatorColor = Color.Transparent,
                            unfocusedIndicatorColor = Color.Transparent,
                            cursorColor = MaterialTheme.colorScheme.primary,
                        ),
                        shape = OrmusCornerShape,
                        modifier = Modifier
                            .weight(1f)
                            .focusRequester(dictationFocus),
                    )

                    EnterKey(
                        sendsText = draft.text.isNotEmpty(),
                        onClick = { enter() },
                        modifier = Modifier.width(ENTER_KEY_WIDTH),
                    )
                }
            }
        }
    }
}

/** Stands in for a missing Herdr side: no snapshot, ever. */
private val NO_HERDR_SNAPSHOT: StateFlow<HerdrSessionSnapshot?> = MutableStateFlow(null)

/** Stand in for a missing Herdr side: no session listed, no other session's snapshot. */
private val NO_HERDR_SESSIONS: StateFlow<List<String>> = MutableStateFlow(emptyList())
private val NO_HERDR_OTHERS: StateFlow<Map<String, HerdrSessionSnapshot>> = MutableStateFlow(emptyMap())

/**
 * The compose bar's Needs you key: "Needs you", and "Needs you · N" in gold while [waiting]
 * agents wait for the user; a hairline ghost when none does.
 */
@Composable
internal fun NeedsYouKey(waiting: Int, onClick: () -> Unit, onLongClick: () -> Unit, enabled: Boolean, modifier: Modifier = Modifier) {
    OrmusKey(
        label = if (waiting > 0) stringResource(R.string.needs_you_key_count, waiting) else stringResource(R.string.needs_you_key),
        onClick = onClick,
        modifier = modifier,
        tone = if (waiting > 0 && enabled) KeyTone.ACCENT else KeyTone.PLAIN,
        enabled = enabled,
        // Under the other keys' 14sp, so "Needs you · 12" fits row 1 on a 360dp phone.
        symbolSize = 12.sp,
        description = stringResource(R.string.needs_you_key_description, waiting),
        onLongClick = onLongClick,
    )
}

/** Stands in for a missing Herdr side's keys: Herdr's defaults. */
private val DEFAULT_HERDR_KEYS: StateFlow<HerdrKeys> = MutableStateFlow(HerdrKeys.DEFAULT)

/** Test tag on the open Jump to list. */
internal const val HERDR_JUMP_TEST_TAG = "composeBarHerdrJump"

/** Test tag on the open Herdr panel. */
internal const val HERDR_PANEL_TEST_TAG = "composeBarHerdrPanel"

/** Test tag on the open tray, for tests that check it is there or swipe it closed. */
internal const val TRAY_TEST_TAG = "composeBarTray"
