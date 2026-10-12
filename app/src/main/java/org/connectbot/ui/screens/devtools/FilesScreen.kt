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

import android.content.ClipData
import androidx.activity.compose.BackHandler
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
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.toClipEntry
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.connectbot.R
import org.connectbot.service.TerminalBridge
import org.connectbot.ui.components.OrmusKey
import org.connectbot.ui.components.WORD_KEY_PADDING
import org.connectbot.ui.theme.OrmusMono
import org.connectbot.usage.LocalUsageLog
import org.connectbot.usage.UsageActions
import solutions.ormus.logos.devtools.Highlighter
import solutions.ormus.logos.devtools.HostRead
import solutions.ormus.logos.devtools.HostScript
import solutions.ormus.logos.devtools.RepoEntry
import solutions.ormus.logos.devtools.RepoFile
import solutions.ormus.logos.devtools.RepoFiles
import solutions.ormus.logos.devtools.RepoHome
import solutions.ormus.logos.devtools.Token
import solutions.ormus.logos.devtools.TokenKind
import solutions.ormus.logos.devtools.WorkDir

/** Test tag on each directory entry row. */
const val FILES_ENTRY_TAG = "files_entry"

/** Test tag on each line of an open file. */
const val FILES_LINE_TAG = "files_line"

private const val FILE_TEXT_SP = 13

/** An open file's lines and, for a known language, each line's tokens. */
class FileLines(val lines: List<String>, val tokens: List<List<Token>>?)

/**
 * Full-screen file browser over [bridge]'s SSH connection: the repository tree at HEAD
 * (or a recent commit) from the focused Herdr pane's directory, or a plain directory
 * with ls and cat when it is not a git work tree.
 */
@Composable
fun FilesDialog(bridge: TerminalBridge, onDismiss: () -> Unit) {
    val usage = LocalUsageLog.current
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    val dirs = rememberWorkDirs(bridge)
    // The focused pane's directory until one is picked.
    var picked by remember { mutableStateOf<String?>(null) }
    val dir = picked ?: dirs.firstOrNull()?.path
    var home by remember { mutableStateOf<HostRead<RepoHome>?>(null) }
    var rev by remember { mutableStateOf(HEAD) }
    var path by remember { mutableStateOf("") }
    var entries by remember { mutableStateOf<HostRead<List<RepoEntry>>?>(null) }
    var file by remember { mutableStateOf<String?>(null) }
    var content by remember { mutableStateOf<HostRead<RepoFile>?>(null) }
    var lines by remember { mutableStateOf<FileLines?>(null) }
    var reloads by remember { mutableIntStateOf(0) }
    val runner = remember(bridge) { hostRunner(bridge) }

    // The directory [home] was read for: a new one starts over at its prefix and HEAD,
    // a refresh only reads the recent commits again.
    var homeDir by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(dir, reloads) {
        val fresh = home == null || dir != homeDir
        if (dir != homeDir) home = null
        val read = withContext(Dispatchers.IO) { RepoFiles.readHome(HostScript.run(runner, RepoFiles.homeCommand(dir))) }
        if (fresh) {
            path = (read as? HostRead.Ok)?.value?.prefix?.trimEnd('/').orEmpty()
            rev = HEAD
            file = null
            homeDir = dir
        }
        home = read
    }
    LaunchedEffect(home, rev, path, reloads) {
        entries = null
        val at = (home as? HostRead.Ok)?.value ?: return@LaunchedEffect
        entries = withContext(Dispatchers.IO) { RepoFiles.readList(at, HostScript.run(runner, RepoFiles.listCommand(at, rev, path))) }
    }
    LaunchedEffect(file, rev, reloads) {
        content = null
        lines = null
        val at = (home as? HostRead.Ok)?.value ?: return@LaunchedEffect
        val name = file ?: return@LaunchedEffect
        val read = withContext(Dispatchers.IO) { RepoFiles.readFile(HostScript.run(runner, RepoFiles.fileCommand(at, rev, name))) }
        lines = (read as? HostRead.Ok)?.value?.text?.let { text ->
            withContext(Dispatchers.Default) {
                val split = text.removeSuffix("\n").split('\n')
                FileLines(split, Highlighter.syntaxFor(name)?.let { Highlighter.tokens(split, it) })
            }
        }
        content = read
    }

    fun copy(label: String, text: String) {
        scope.launch { clipboard.setClipEntry(ClipData.newPlainText(label, text).toClipEntry()) }
    }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        FilesScreen(
            dir = dir,
            dirs = dirs,
            home = home,
            rev = rev,
            path = path,
            entries = entries,
            file = file,
            content = content,
            lines = lines,
            onDir = {
                usage.log(UsageActions.FILES_DIR)
                picked = it
            },
            onRev = {
                usage.log(UsageActions.FILES_REV)
                file = null
                rev = it
            },
            onEntry = { entry ->
                val child = RepoFiles.join(path, entry.name).removePrefix("/")
                if (entry.dir) {
                    usage.log(UsageActions.FILES_FOLDER)
                    path = child
                } else {
                    usage.log(UsageActions.FILES_FILE)
                    file = child
                }
            },
            onUp = {
                usage.log(UsageActions.FILES_UP)
                path = path.substringBeforeLast('/', "")
            },
            onCopyPath = {
                usage.log(UsageActions.FILES_COPY_PATH)
                (home as? HostRead.Ok)?.value?.let { at -> file?.let { copy("path", RepoFiles.join(at.root, it)) } }
            },
            onCopyText = {
                usage.log(UsageActions.FILES_COPY_TEXT)
                (content as? HostRead.Ok)?.value?.text?.let { copy("file", it) }
            },
            onRefresh = {
                usage.log(UsageActions.FILES_REFRESH)
                reloads++
            },
            onBack = {
                if (file != null) {
                    usage.log(UsageActions.FILES_BACK)
                    file = null
                } else {
                    usage.log(UsageActions.FILES_CLOSE)
                    onDismiss()
                }
            },
        )
    }
}

private const val HEAD = "HEAD"

/**
 * The browser: a directory's entries (folders first) with ".." above them, or an open
 * file's highlighted lines with copy keys. [home], [entries] and [content] are null while
 * they load.
 */
@Composable
fun FilesScreen(
    dir: String?,
    dirs: List<WorkDir>,
    home: HostRead<RepoHome>?,
    rev: String,
    path: String,
    entries: HostRead<List<RepoEntry>>?,
    file: String?,
    content: HostRead<RepoFile>?,
    lines: FileLines?,
    onDir: (String) -> Unit,
    onRev: (String) -> Unit,
    onEntry: (RepoEntry) -> Unit,
    onUp: () -> Unit,
    onCopyPath: () -> Unit,
    onCopyText: () -> Unit,
    onRefresh: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    BackHandler(enabled = file != null, onBack = onBack)
    val at = (home as? HostRead.Ok)?.value
    Surface(modifier = modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surfaceContainerLowest) {
        Column(modifier = Modifier.fillMaxSize()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 4.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.files_close))
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        file ?: path.ifEmpty { stringResource(R.string.files_title) },
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.StartEllipsis,
                    )
                    WorkDirPicker(dir = at?.root ?: dir, dirs = dirs, onPick = onDir)
                }
                if (at?.git == true) RevPicker(rev, at, onRev)
                IconButton(onClick = onRefresh) {
                    Icon(Icons.Default.Refresh, contentDescription = stringResource(R.string.files_refresh))
                }
            }
            if (home !is HostRead.Ok) {
                DevRead(home) {}
                return@Column
            }
            if (file == null) {
                DevRead(entries) { Entries(it, showUp = path.isNotEmpty(), onEntry = onEntry, onUp = onUp) }
            } else {
                DevRead(content) { read -> OpenFile(read, lines, onCopyPath, onCopyText) }
            }
        }
    }
}

@Composable
private fun RevPicker(rev: String, home: RepoHome, onRev: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        Text(
            rev,
            fontFamily = OrmusMono,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier
                .clickable { open = true }
                .padding(horizontal = 8.dp, vertical = 12.dp),
        )
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(text = { Text(HEAD, fontFamily = OrmusMono) }, onClick = {
                open = false
                onRev(HEAD)
            })
            home.commits.forEach { commit ->
                DropdownMenuItem(
                    text = { Text("${commit.id} ${commit.subject}", fontFamily = OrmusMono, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    onClick = {
                        open = false
                        onRev(commit.id)
                    },
                )
            }
        }
    }
}

@Composable
private fun Entries(entries: List<RepoEntry>, showUp: Boolean, onEntry: (RepoEntry) -> Unit, onUp: () -> Unit) {
    LazyColumn(contentPadding = PaddingValues(vertical = 8.dp), modifier = Modifier.fillMaxSize()) {
        if (showUp) item { EntryRow("..", detail = null, onClick = onUp) }
        items(entries, key = { it.name }) { entry ->
            EntryRow(
                name = if (entry.dir) entry.name + "/" else entry.name,
                detail = entry.size?.let { bytes(it) },
                onClick = { onEntry(entry) },
            )
        }
        if (entries.isEmpty() && !showUp) item { DevNote(stringResource(R.string.files_empty)) }
    }
}

@Composable
private fun EntryRow(name: String, detail: String?, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp)
            .testTag(FILES_ENTRY_TAG),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            name,
            fontFamily = OrmusMono,
            color = if (name.endsWith("/") || name == "..") MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.MiddleEllipsis,
            modifier = Modifier.weight(1f),
        )
        detail?.let { Text(it, fontFamily = OrmusMono, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
}

private fun bytes(size: Long): String = when {
    size < 1024 -> "$size B"
    size < 1024 * 1024 -> "${size / 1024} KB"
    else -> "${size / (1024 * 1024)} MB"
}

@Composable
private fun OpenFile(file: RepoFile, lines: FileLines?, onCopyPath: () -> Unit, onCopyText: () -> Unit) {
    val styles = TokenStyles(
        keyword = SpanStyle(color = MaterialTheme.colorScheme.primary),
        string = SpanStyle(color = MaterialTheme.colorScheme.tertiary),
        comment = SpanStyle(color = MaterialTheme.colorScheme.onSurfaceVariant, fontStyle = FontStyle.Italic),
        number = SpanStyle(color = MaterialTheme.colorScheme.tertiary),
    )
    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OrmusKey(label = stringResource(R.string.files_copy_path), onClick = onCopyPath, contentPadding = WORD_KEY_PADDING)
            OrmusKey(label = stringResource(R.string.files_copy_text), onClick = onCopyText, enabled = file.text != null, contentPadding = WORD_KEY_PADDING)
        }
        if (file.text == null || lines == null) {
            DevNote(stringResource(R.string.files_binary, file.size ?: 0L))
            return@Column
        }
        LazyColumn(contentPadding = PaddingValues(vertical = 8.dp), modifier = Modifier.fillMaxSize()) {
            items(lines.lines.size) { index ->
                Text(
                    highlighted(lines.lines[index], lines.tokens?.getOrNull(index).orEmpty(), styles),
                    fontFamily = OrmusMono,
                    fontSize = FILE_TEXT_SP.sp,
                    lineHeight = (FILE_TEXT_SP * 1.4f).sp,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier
                        .padding(horizontal = 12.dp, vertical = 1.dp)
                        .testTag(FILES_LINE_TAG),
                )
            }
        }
    }
}

/** The brand's syntax colors: gold for keywords, the brighter gold for literals, muted italic comments. */
private class TokenStyles(val keyword: SpanStyle, val string: SpanStyle, val comment: SpanStyle, val number: SpanStyle)

private fun highlighted(line: String, tokens: List<Token>, styles: TokenStyles): AnnotatedString = buildAnnotatedString {
    append(line.ifEmpty { " " })
    tokens.forEach { token ->
        val style = when (token.kind) {
            TokenKind.KEYWORD -> styles.keyword
            TokenKind.STRING -> styles.string
            TokenKind.COMMENT -> styles.comment
            TokenKind.NUMBER -> styles.number
        }
        addStyle(style, token.start, token.end)
    }
}
