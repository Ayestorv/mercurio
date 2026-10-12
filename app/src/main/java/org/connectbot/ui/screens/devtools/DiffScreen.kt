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

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.connectbot.R
import org.connectbot.service.TerminalBridge
import org.connectbot.ui.theme.OrmusMono
import org.connectbot.usage.LocalUsageLog
import org.connectbot.usage.UsageActions
import solutions.ormus.logos.devtools.ChangedFile
import solutions.ormus.logos.devtools.DiffArea
import solutions.ormus.logos.devtools.DiffLine
import solutions.ormus.logos.devtools.DiffLineKind
import solutions.ormus.logos.devtools.DiffListing
import solutions.ormus.logos.devtools.GitDiff
import solutions.ormus.logos.devtools.HostRead
import solutions.ormus.logos.devtools.HostScript
import solutions.ormus.logos.devtools.WorkDir

/** Test tag on each changed-file row. */
const val DIFF_FILE_TAG = "diff_file"

/** Test tag on each line of an open diff. */
const val DIFF_LINE_TAG = "diff_line"

private const val DIFF_TEXT_SP = 13

/**
 * Full-screen diff of the work tree an agent is changing: the focused Herdr pane's
 * directory (or home without Herdr), read over [bridge]'s SSH connection with git.
 */
@Composable
fun DiffDialog(bridge: TerminalBridge, onDismiss: () -> Unit) {
    val usage = LocalUsageLog.current
    val dirs = rememberWorkDirs(bridge)
    // The focused pane's directory until one is picked.
    var picked by remember { mutableStateOf<String?>(null) }
    val dir = picked ?: dirs.firstOrNull()?.path
    var reloads by remember { mutableIntStateOf(0) }
    var listing by remember { mutableStateOf<HostRead<DiffListing>?>(null) }
    var open by remember { mutableStateOf<ChangedFile?>(null) }
    var lines by remember { mutableStateOf<HostRead<List<DiffLine>>?>(null) }
    val runner = remember(bridge) { hostRunner(bridge) }

    LaunchedEffect(dir, reloads) {
        listing = null
        listing = withContext(Dispatchers.IO) { GitDiff.readList(HostScript.run(runner, GitDiff.listCommand(dir))) }
    }
    LaunchedEffect(open, reloads) {
        lines = null
        val file = open ?: return@LaunchedEffect
        lines = withContext(Dispatchers.IO) { GitDiff.readFile(HostScript.run(runner, GitDiff.fileCommand(dir, file))) }
    }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        DiffScreen(
            dir = dir,
            dirs = dirs,
            listing = listing,
            open = open,
            lines = lines,
            onDir = {
                usage.log(UsageActions.DIFF_DIR)
                open = null
                picked = it
            },
            onOpen = {
                usage.log(UsageActions.DIFF_FILE)
                open = it
            },
            onRefresh = {
                usage.log(UsageActions.DIFF_REFRESH)
                reloads++
            },
            onBack = {
                if (open != null) {
                    usage.log(UsageActions.DIFF_BACK)
                    open = null
                } else {
                    usage.log(UsageActions.DIFF_CLOSE)
                    onDismiss()
                }
            },
        )
    }
}

/**
 * The changed files by area (staged, unstaged, untracked) with their line counts; a tap
 * opens that file's unified diff. [listing] and [lines] are null while they load.
 */
@Composable
fun DiffScreen(
    dir: String?,
    dirs: List<WorkDir>,
    listing: HostRead<DiffListing>?,
    open: ChangedFile?,
    lines: HostRead<List<DiffLine>>?,
    onDir: (String) -> Unit,
    onOpen: (ChangedFile) -> Unit,
    onRefresh: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    BackHandler(enabled = open != null, onBack = onBack)
    Surface(modifier = modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surfaceContainerLowest) {
        Column(modifier = Modifier.fillMaxSize()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 4.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.diff_close))
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        open?.path ?: stringResource(R.string.diff_title),
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.StartEllipsis,
                    )
                    val root = (listing as? HostRead.Ok)?.value?.root
                    WorkDirPicker(dir = root ?: dir, dirs = dirs, onPick = onDir)
                }
                IconButton(onClick = onRefresh) {
                    Icon(Icons.Default.Refresh, contentDescription = stringResource(R.string.diff_refresh))
                }
            }
            if (open == null) {
                DevRead(listing) { FileList(it.files, onOpen) }
            } else {
                DevRead(lines) { DiffLines(it) }
            }
        }
    }
}

@Composable
private fun FileList(files: List<ChangedFile>, onOpen: (ChangedFile) -> Unit) {
    if (files.isEmpty()) {
        DevNote(stringResource(R.string.diff_clean))
        return
    }
    val byArea = files.groupBy { it.area }
    LazyColumn(contentPadding = PaddingValues(vertical = 8.dp), modifier = Modifier.fillMaxSize()) {
        DiffArea.entries.forEach { area ->
            val inArea = byArea[area].orEmpty()
            if (inArea.isEmpty()) return@forEach
            item(key = area.name) {
                DevLabel(
                    "${stringResource(areaLabel(area))} ${inArea.size}",
                    modifier = Modifier.padding(start = 16.dp, top = 12.dp, bottom = 4.dp),
                )
            }
            items(inArea, key = { "${area.name}:${it.path}" }) { file -> FileRow(file, onOpen) }
        }
    }
}

private fun areaLabel(area: DiffArea): Int = when (area) {
    DiffArea.STAGED -> R.string.diff_staged
    DiffArea.UNSTAGED -> R.string.diff_unstaged
    DiffArea.UNTRACKED -> R.string.diff_untracked
}

@Composable
private fun FileRow(file: ChangedFile, onOpen: (ChangedFile) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onOpen(file) }
            .padding(horizontal = 16.dp, vertical = 10.dp)
            .testTag(DIFF_FILE_TAG),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(file.status.toString(), fontFamily = OrmusMono, color = MaterialTheme.colorScheme.primary)
        Text(
            file.path,
            fontFamily = OrmusMono,
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 1,
            overflow = TextOverflow.StartEllipsis,
            modifier = Modifier.weight(1f),
        )
        when {
            file.binary -> Text(stringResource(R.string.diff_binary), fontFamily = OrmusMono, color = MaterialTheme.colorScheme.onSurfaceVariant)

            file.area == DiffArea.UNTRACKED -> Text(stringResource(R.string.diff_new), fontFamily = OrmusMono, color = MaterialTheme.colorScheme.onSurfaceVariant)

            else -> {
                Text("+${file.added ?: 0}", fontFamily = OrmusMono, color = MaterialTheme.colorScheme.primary)
                Text("-${file.removed ?: 0}", fontFamily = OrmusMono, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

/**
 * The unified diff, one mono line per row, wrapped. Added lines carry a gold tint and
 * removed lines a neutral one (gold is the only accent, never red); the +/- stays in the
 * text so the kind never rests on color alone.
 */
@Composable
private fun DiffLines(lines: List<DiffLine>) {
    if (lines.isEmpty()) {
        DevNote(stringResource(R.string.diff_clean))
        return
    }
    val added = MaterialTheme.colorScheme.primary.copy(alpha = 0.16f)
    val removed = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.07f)
    LazyColumn(contentPadding = PaddingValues(vertical = 8.dp), modifier = Modifier.fillMaxSize()) {
        items(lines.size) { index ->
            val line = lines[index]
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(
                        when (line.kind) {
                            DiffLineKind.ADDED -> added
                            DiffLineKind.REMOVED -> removed
                            else -> Color.Transparent
                        },
                    )
                    .padding(horizontal = 12.dp, vertical = 1.dp),
            ) {
                Text(
                    line.text.ifEmpty { " " },
                    fontFamily = OrmusMono,
                    fontSize = DIFF_TEXT_SP.sp,
                    lineHeight = (DIFF_TEXT_SP * 1.4f).sp,
                    color = when (line.kind) {
                        DiffLineKind.HUNK -> MaterialTheme.colorScheme.primary
                        DiffLineKind.META, DiffLineKind.REMOVED -> MaterialTheme.colorScheme.onSurfaceVariant
                        else -> MaterialTheme.colorScheme.onSurface
                    },
                    modifier = Modifier.testTag(DIFF_LINE_TAG),
                )
            }
        }
    }
}
