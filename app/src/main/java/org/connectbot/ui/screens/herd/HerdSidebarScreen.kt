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

import android.app.Presentation
import android.hardware.display.DisplayManager
import android.os.Bundle
import android.view.Display
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import org.connectbot.R
import org.connectbot.data.entity.Host
import org.connectbot.service.HerdFleet
import org.connectbot.service.TerminalBridge
import org.connectbot.service.TerminalManager
import org.connectbot.ui.theme.AppTypography
import org.connectbot.ui.theme.LocalOrmusExtras
import org.connectbot.ui.theme.Ormus
import org.connectbot.ui.theme.OrmusDarkColorScheme
import org.connectbot.ui.theme.OrmusDarkExtras
import org.connectbot.ui.theme.OrmusShapes
import org.connectbot.usage.UsageActions
import org.connectbot.usage.UsageLog
import solutions.ormus.logos.herd.HerdrAction
import solutions.ormus.logos.herd.HerdrAgentInfo
import solutions.ormus.logos.herd.HerdrAgentStatus
import solutions.ormus.logos.herd.HerdrWorkspaceInfo
import solutions.ormus.logos.pad.PadControl

/**
 * Herdr's sidebar on a second screen (the Ayn Thor's lower display): the attached
 * session's workspaces with their agents, as Herdr's own sidebar lists them, while the
 * terminal keeps the whole top screen. A tap focuses a workspace or an agent's pane.
 *
 * [start] in the activity's onStart and [stop] in onStop: the sidebar shows only while
 * the app does, and follows displays as they come and go.
 */
class HerdSidebarDisplay(private val activity: ComponentActivity, private val links: SidebarLinks) {
    private val displays = activity.getSystemService(DisplayManager::class.java)
    private var presentation: Presentation? = null

    private val listener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) = update()
        override fun onDisplayRemoved(displayId: Int) = update()
        override fun onDisplayChanged(displayId: Int) = Unit
    }

    fun start() {
        displays?.registerDisplayListener(listener, null)
        update()
    }

    fun stop() {
        displays?.unregisterDisplayListener(listener)
        presentation?.dismiss()
        presentation = null
    }

    @Suppress("DEPRECATION")
    private fun update() {
        val own = activity.windowManager.defaultDisplay.displayId
        val display = displays?.getDisplays(DisplayManager.DISPLAY_CATEGORY_PRESENTATION)
            ?.firstOrNull { it.displayId != own && it.state != Display.STATE_OFF }
        if (presentation?.display?.displayId == display?.displayId) return
        presentation?.dismiss()
        presentation = display?.let { show(it) }
    }

    private fun show(display: Display): Presentation? = runCatching {
        SidebarPresentation(activity, display, links).also { it.show() }
    }.getOrNull()
}

private class SidebarPresentation(
    private val activity: ComponentActivity,
    display: Display,
    private val links: SidebarLinks,
) : Presentation(activity, display) {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Touch works on the lower screen without taking key focus from the terminal above.
        window?.addFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE)
        val view = ComposeView(context).apply {
            setViewTreeLifecycleOwner(activity)
            setViewTreeViewModelStoreOwner(activity)
            setViewTreeSavedStateRegistryOwner(activity)
            setContent {
                CompositionLocalProvider(LocalOrmusExtras provides OrmusDarkExtras) {
                    MaterialTheme(colorScheme = OrmusDarkColorScheme, typography = AppTypography, shapes = OrmusShapes) {
                        HerdSidebarScreen(links = links)
                    }
                }
            }
        }
        setContentView(view)
    }

    // Should the window take focus anyway, the pad keeps driving the terminal.
    override fun dispatchKeyEvent(event: KeyEvent): Boolean = PadControl.gamepad.onKey(event) || super.dispatchKeyEvent(event)

    override fun dispatchGenericMotionEvent(event: MotionEvent): Boolean = PadControl.gamepad.onMotion(event) || super.dispatchGenericMotionEvent(event)
}

/** What the sidebar needs from the app: the usage log, the terminal service, and a way to open a host's console. */
class SidebarLinks(
    val usage: () -> UsageLog,
    val manager: () -> TerminalManager?,
    /** Bring [Host]'s console up on the top screen, focusing the pane when one is given. */
    val open: (host: Host, paneId: String?) -> Unit,
)

/** The sidebar itself: every Herdr host's workspaces and agents, the one on top marked. */
@Composable
fun HerdSidebarScreen(links: SidebarLinks, modifier: Modifier = Modifier) {
    val usage = remember(links) { links.usage() }
    val target by PadControl.target.collectAsState()
    val enabled by PadControl.enabled.collectAsState()
    val connected by PadControl.connected.collectAsState()
    val last by PadControl.last.collectAsState()
    var showControls by remember { mutableStateOf(false) }

    // The service binds after the activity starts: look until it is there.
    var manager by remember { mutableStateOf(links.manager()) }
    LaunchedEffect(links) {
        while (manager == null) {
            delay(HERD_WAIT_MS)
            manager = links.manager()
        }
    }
    val fleet = manager?.fleet
    DisposableEffect(fleet) {
        val stop = fleet?.watch()
        onDispose { stop?.invoke() }
    }
    val members by (fleet?.members ?: remember { MutableStateFlow(emptyList<HerdFleet.Member>()) }).collectAsState()

    // This screen is the sidebar now: fold Herdr's own away in the terminal on top, once its client has drawn.
    LaunchedEffect(target) {
        val shown = target ?: return@LaunchedEffect
        while (true) {
            delay(COLLAPSE_CHECK_MS)
            PadControl.collapseHerdrSidebar(shown)
        }
    }

    Surface(modifier = modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(Modifier.fillMaxSize().padding(Ormus.spacing.md)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = stringResource(R.string.sidebar_title),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onBackground,
                    modifier = Modifier.weight(1f),
                )
                if (connected) {
                    Text(
                        text = stringResource(if (enabled) R.string.sidebar_pad_on else R.string.sidebar_pad_off),
                        style = MaterialTheme.typography.labelMedium,
                        color = if (enabled) Ormus.extras.status.blocked else Ormus.extras.status.idle,
                        modifier = Modifier
                            .clickable { showControls = !showControls }
                            .padding(Ormus.spacing.xs),
                    )
                }
            }
            Spacer(Modifier.height(Ormus.spacing.sm))
            HorizontalDivider(color = Ormus.extras.hairline)

            Box(Modifier.weight(1f).fillMaxWidth()) {
                when {
                    showControls -> ControlsLegend()
                    members.isEmpty() -> Hint(stringResource(R.string.sidebar_loading))
                    else -> FleetList(members, target, fleet, links, usage)
                }
            }

            if (connected && enabled) {
                HorizontalDivider(color = Ormus.extras.hairline)
                Text(
                    text = last?.label ?: stringResource(R.string.sidebar_pad_hint),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = Ormus.spacing.xs),
                    maxLines = 1,
                )
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FleetList(members: List<HerdFleet.Member>, target: TerminalBridge?, fleet: HerdFleet?, links: SidebarLinks, usage: UsageLog) {
    val scope = rememberCoroutineScope()
    // Hosts folded to their header (tap a host's name); every host starts open.
    val folded = remember { mutableStateMapOf<Long, Boolean>() }
    LazyColumn(Modifier.fillMaxSize().padding(top = Ormus.spacing.sm), verticalArrangement = Arrangement.spacedBy(Ormus.spacing.xxs)) {
        members.forEach { member ->
            val host = member.host
            val onTop = target?.host?.id == host.id
            val topHerd = if (onTop) target?.herd else null
            val snapshot = member.snapshot
            val isFolded = folded[host.id] == true
            stickyHeader(key = "host-${host.id}") {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.background)
                        .clickable { folded[host.id] = !isFolded }
                        .padding(top = Ormus.spacing.md, bottom = Ormus.spacing.xs, start = Ormus.spacing.xs, end = Ormus.spacing.xs),
                ) {
                    Text(
                        text = if (isFolded) "▸ " else "▾ ",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    val agents = snapshot?.agents.orEmpty()
                    val blocked = agents.count { it.agentStatus == HerdrAgentStatus.BLOCKED }
                    val working = agents.count { it.agentStatus == HerdrAgentStatus.WORKING }
                    Text(
                        text = host.nickname.uppercase() + if (snapshot != null) "  ${snapshot.workspaces.size}ws · ${agents.size}" else "",
                        style = MaterialTheme.typography.labelLarge,
                        color = if (onTop) Ormus.extras.status.blocked else MaterialTheme.colorScheme.onSurfaceVariant,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.weight(1f),
                    )
                    val note = when {
                        onTop -> stringResource(R.string.sidebar_host_on_top)
                        member.problem != null && snapshot == null -> member.problem
                        else -> null
                    }
                    if (blocked > 0) {
                        Text(text = "$blocked ●  ", style = MaterialTheme.typography.labelMedium, color = Ormus.extras.status.blocked)
                    }
                    if (working > 0) {
                        Text(text = "$working ◐  ", style = MaterialTheme.typography.labelMedium, color = Ormus.extras.status.working)
                    }
                    if (note != null) {
                        Text(text = note, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
            if (snapshot == null || isFolded) return@forEach
            val focusedWorkspace = snapshot.focusedWorkspaceId ?: snapshot.workspaces.firstOrNull { ws -> snapshot.panes.any { it.focused && it.workspaceId == ws.workspaceId } }?.workspaceId
            val focusedPane = snapshot.focusedPaneId ?: snapshot.panes.firstOrNull { it.focused }?.paneId
            val tabs = snapshot.tabs.associateBy { it.tabId }
            snapshot.workspaces.sortedBy { it.number }.forEach { ws ->
                val focused = ws.workspaceId == focusedWorkspace
                item(key = "ws-${host.id}-${ws.workspaceId}") {
                    WorkspaceRow(ws, focused && onTop) {
                        usage.log(UsageActions.SIDEBAR_WORKSPACE)
                        val action = HerdrAction.FocusWorkspace(ws.workspaceId)
                        if (topHerd != null) {
                            topHerd.perform(action, session = topHerd.attached.value)
                        } else {
                            scope.launch {
                                fleet?.perform(host, action)
                                links.open(host, null)
                            }
                        }
                    }
                }
                val agents = snapshot.agents.filter { it.workspaceId == ws.workspaceId }
                items(agents, key = { "agent-${host.id}-${it.paneId}" }) { agent ->
                    AgentRow(agent, here = onTop && agent.paneId == focusedPane, tabLabel = tabs[agent.tabId]?.label) {
                        usage.log(UsageActions.SIDEBAR_AGENT)
                        if (topHerd != null) {
                            topHerd.perform(HerdrAction.FocusAgent(agent.paneId), session = topHerd.attached.value)
                        } else {
                            links.open(host, agent.paneId)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun WorkspaceRow(ws: HerdrWorkspaceInfo, focused: Boolean, onClick: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .background(if (focused) MaterialTheme.colorScheme.surfaceContainerHigh else Color.Transparent, MaterialTheme.shapes.small)
            .clickable(onClick = onClick)
            .padding(horizontal = Ormus.spacing.sm, vertical = Ormus.spacing.sm),
    ) {
        Box(Modifier.width(3.dp).height(20.dp).background(if (focused) Ormus.extras.status.blocked else Color.Transparent))
        Spacer(Modifier.width(Ormus.spacing.sm))
        Text(text = "${ws.number}", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.width(Ormus.spacing.sm))
        Text(
            text = ws.label.ifBlank { ws.workspaceId },
            style = MaterialTheme.typography.titleSmall,
            fontWeight = if (focused) FontWeight.Bold else FontWeight.Normal,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        StatusDot(ws.agentStatus)
    }
}

@Composable
private fun AgentRow(agent: HerdrAgentInfo, here: Boolean, tabLabel: String?, onClick: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(start = Ormus.spacing.xl, end = Ormus.spacing.sm, top = Ormus.spacing.xs, bottom = Ormus.spacing.xs),
    ) {
        StatusDot(agent.agentStatus)
        Spacer(Modifier.width(Ormus.spacing.sm))
        Column(Modifier.weight(1f)) {
            Text(
                text = agent.displayAgent ?: agent.agent ?: agent.paneId,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = if (here) FontWeight.Bold else FontWeight.Normal,
                color = if (here) Ormus.extras.status.blocked else MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            val detail = listOfNotNull(tabLabel?.takeIf { it.isNotBlank() }, agent.terminalTitleStripped?.takeIf { it.isNotBlank() }).joinToString(" · ")
            if (detail.isNotEmpty()) {
                Text(text = detail, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        Text(text = statusLabel(agent.agentStatus), style = MaterialTheme.typography.labelSmall, color = statusColor(agent.agentStatus))
    }
}

// Shape and color together, so state never rests on color alone: filled for blocked and working, a ring otherwise.
@Composable
private fun StatusDot(status: HerdrAgentStatus) {
    val color = statusColor(status)
    val filled = status == HerdrAgentStatus.BLOCKED || status == HerdrAgentStatus.WORKING
    Box(
        Modifier
            .size(10.dp)
            .then(if (filled) Modifier.background(color, CircleShape) else Modifier.border(1.5.dp, color, CircleShape)),
    )
}

@Composable
private fun statusColor(status: HerdrAgentStatus): Color = when (status) {
    HerdrAgentStatus.BLOCKED -> Ormus.extras.status.blocked
    HerdrAgentStatus.DONE -> Ormus.extras.status.done
    HerdrAgentStatus.WORKING -> Ormus.extras.status.working
    else -> Ormus.extras.status.idle
}

@Composable
private fun statusLabel(status: HerdrAgentStatus): String = stringResource(
    when (status) {
        HerdrAgentStatus.BLOCKED -> R.string.sidebar_status_blocked
        HerdrAgentStatus.DONE -> R.string.sidebar_status_done
        HerdrAgentStatus.WORKING -> R.string.sidebar_status_working
        HerdrAgentStatus.IDLE -> R.string.sidebar_status_idle
        HerdrAgentStatus.UNKNOWN -> R.string.sidebar_status_unknown
    },
)

@Composable
private fun Hint(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = Ormus.spacing.lg),
    )
}

/** The controller map, one line per control (tap "Controller on" to open and close it). */
@Composable
private fun ControlsLegend() {
    val rows = listOf(
        "D-pad" to "Focus pane ←↓↑→",
        "LB / RB" to "Previous / next tab",
        "LT / RT" to "Previous / next workspace",
        "Right stick ↑↓" to "Workspace picker, then move",
        "A" to "Enter (picks in the picker)",
        "B" to "Esc · hold: Backspace",
        "X" to "Agent that needs you · hold: close pane",
        "Y" to "Shift+Tab · hold: Space (talk)",
        "L3 / R3" to "Zoom pane / next agent",
        "Left stick" to "Arrow keys",
        "View" to "Split right · hold: new tab",
        "Menu" to "Split down",
        "View + Menu" to "Controller on/off (Guide on Xbox)",
    )
    LazyColumn(Modifier.fillMaxSize().padding(top = Ormus.spacing.sm)) {
        items(rows) { (control, action) ->
            Row(Modifier.fillMaxWidth().padding(vertical = Ormus.spacing.xxs)) {
                Text(
                    text = control,
                    style = MaterialTheme.typography.labelLarge,
                    color = Ormus.extras.status.blocked,
                    modifier = Modifier.width(120.dp),
                )
                Text(text = action, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface)
            }
        }
    }
}

private const val HERD_WAIT_MS = 1_000L
private const val COLLAPSE_CHECK_MS = 1_000L
