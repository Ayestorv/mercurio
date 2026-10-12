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

package org.connectbot.ui.screens.devtools

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import org.connectbot.R
import org.connectbot.service.TerminalBridge
import org.connectbot.transport.CommandOutput
import org.connectbot.transport.SSH
import org.connectbot.ui.theme.OrmusMono
import solutions.ormus.logos.devtools.HostProblem
import solutions.ormus.logos.devtools.HostRead
import solutions.ormus.logos.devtools.WorkDir
import solutions.ormus.logos.devtools.WorkDirs
import solutions.ormus.logos.herd.HerdrSessions

/** Runs one command on [bridge]'s host over an exec channel; null when it is not a connected SSH session. */
fun hostRunner(bridge: TerminalBridge): (String) -> CommandOutput? = { command ->
    (bridge.transport as? SSH)?.takeIf { it.isConnected() }?.execDetailed(command)
}

/**
 * The working directories of the Herdr session the panel acts on ([HerdSession.chosen]),
 * the focused pane's first; empty for a session without Herdr. Fetches that session's
 * snapshot as the tool opens, so a session other than the polled default is current.
 */
@Composable
fun rememberWorkDirs(bridge: TerminalBridge): List<WorkDir> {
    val herd = bridge.herd ?: return emptyList()
    val chosen by herd.chosen.collectAsState()
    val default by herd.snapshot.collectAsState()
    val others by herd.others.collectAsState()
    LaunchedEffect(herd, chosen) { herd.refresh(chosen) }
    val snapshot = if (HerdrSessions.isDefault(chosen)) default else others[chosen]
    return remember(snapshot) { WorkDirs.from(snapshot) }
}

/** One line on why a dev tool could not read the host. */
@Composable
fun hostProblemText(reason: HostProblem, detail: String): String = when (reason) {
    HostProblem.NOT_CONNECTED -> stringResource(R.string.devtools_not_connected)
    HostProblem.NO_DIR -> stringResource(R.string.devtools_no_dir)
    HostProblem.NOT_A_REPO -> stringResource(R.string.devtools_not_a_repo)
    HostProblem.NO_GIT -> stringResource(R.string.devtools_no_git)
    HostProblem.FAILED -> stringResource(R.string.devtools_failed, detail)
}

/** One quiet line: why a read failed, an empty list, a cut. */
@Composable
fun DevNote(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier.padding(horizontal = 16.dp, vertical = 8.dp),
    )
}

/** A progress bar while [read] loads, why it failed, or [content] (under a note when it was cut). */
@Composable
fun <T> DevRead(read: HostRead<T>?, modifier: Modifier = Modifier, content: @Composable (T) -> Unit) {
    when (read) {
        null -> LinearProgressIndicator(modifier = modifier.fillMaxWidth())

        is HostRead.Problem -> DevNote(hostProblemText(read.reason, read.detail), modifier)

        is HostRead.Ok -> {
            if (read.truncated) DevNote(stringResource(R.string.devtools_truncated), modifier)
            content(read.value)
        }
    }
}

/** The brand's micro-label: JetBrains Mono, uppercase, tracked, prefixed with "/ ". */
@Composable
fun DevLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        "/ " + text.uppercase(),
        style = MaterialTheme.typography.labelSmall,
        fontFamily = OrmusMono,
        letterSpacing = 0.08.em,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier,
    )
}

/**
 * The directory a dev tool reads, in mono; a tap lists the other Herdr panes' directories
 * to pick from. [dir] null is the home directory (a session without Herdr).
 */
@Composable
fun WorkDirPicker(
    dir: String?,
    dirs: List<WorkDir>,
    onPick: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var open by remember { mutableStateOf(false) }
    Box(modifier = modifier) {
        Text(
            dir ?: stringResource(R.string.devtools_home),
            style = MaterialTheme.typography.bodySmall.copy(fontFamily = OrmusMono),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.StartEllipsis,
            modifier = Modifier.clickable(enabled = dirs.isNotEmpty()) { open = true },
        )
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            dirs.forEach { workDir ->
                DropdownMenuItem(
                    text = {
                        Column {
                            Text(workDir.label, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(
                                workDir.path,
                                style = MaterialTheme.typography.bodySmall.copy(fontFamily = OrmusMono),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.StartEllipsis,
                            )
                        }
                    },
                    onClick = {
                        open = false
                        onPick(workDir.path)
                    },
                )
            }
        }
    }
}
