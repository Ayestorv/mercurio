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

import android.annotation.SuppressLint
import android.webkit.CookieManager
import android.webkit.WebStorage
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.connectbot.R
import org.connectbot.service.TerminalBridge
import org.connectbot.transport.SSH
import org.connectbot.ui.screens.reader.RecentOutput
import org.connectbot.ui.theme.OrmusMono
import org.connectbot.usage.LocalUsageLog
import org.connectbot.usage.UsageActions
import solutions.ormus.logos.devtools.DevPorts
import solutions.ormus.logos.devtools.LoopbackForward
import solutions.ormus.logos.devtools.PreviewTarget

/** Test tag on each dev server row. */
const val PREVIEW_TARGET_TAG = "preview_target"

/** The open preview: what it shows, and the forward carrying it (null until it is up). */
private class OpenPreview(val target: PreviewTarget, val forward: LoopbackForward)

/**
 * Full-screen preview of a dev server on [bridge]'s host: pick a listening port (or a
 * URL the agent printed), Mercurio forwards a phone loopback port to it through the SSH
 * connection and opens it in an in-app WebView. Closing tears the forward down.
 */
@Composable
fun PreviewDialog(bridge: TerminalBridge, onDismiss: () -> Unit) {
    val usage = LocalUsageLog.current
    var listing by remember { mutableStateOf<DevPorts.Listening?>(null) }
    var reloads by remember { mutableIntStateOf(0) }
    var open by remember { mutableStateOf<OpenPreview?>(null) }
    var forwardError by remember { mutableStateOf<String?>(null) }
    var picked by remember { mutableStateOf<PreviewTarget?>(null) }
    val ssh = (bridge.transport as? SSH)?.takeIf { it.isConnected() }

    LaunchedEffect(reloads) {
        listing = null
        listing = withContext(Dispatchers.IO) {
            val out = try {
                ssh?.execDetailed(DevPorts.listenCommand)
            } catch (e: Exception) {
                return@withContext DevPorts.Listening.Failed(e.message ?: e.toString())
            }
            when (val read = DevPorts.readListening(out)) {
                is DevPorts.Listening.Ports -> DevPorts.Listening.Ports(DevPorts.targets(DevPorts.printed(RecentOutput.read(bridge).text), read.targets))
                else -> read
            }
        }
    }
    // Forward the picked server; the forward closes when the pick changes or the dialog goes.
    LaunchedEffect(picked) {
        forwardError = null
        val target = picked ?: return@LaunchedEffect
        val conn = ssh ?: return@LaunchedEffect
        val forward = withContext(Dispatchers.IO) {
            try {
                conn.forwardLoopback(target.host, target.port)
            } catch (e: Exception) {
                forwardError = e.message ?: e.toString()
                null
            }
        }
        open = forward?.let { OpenPreview(target, it) }
    }
    DisposableEffect(open) {
        val current = open
        onDispose { current?.forward?.close() }
    }

    fun close() {
        usage.log(UsageActions.PREVIEW_CLOSE)
        onDismiss()
    }

    Dialog(onDismissRequest = ::close, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surfaceContainerLowest) {
            val current = open
            if (current == null) {
                PreviewPicker(
                    listing = listing,
                    forwardError = forwardError,
                    onPick = {
                        usage.log(UsageActions.PREVIEW_PICK)
                        picked = it
                    },
                    onRefresh = {
                        usage.log(UsageActions.PREVIEW_REFRESH)
                        reloads++
                    },
                    onClose = ::close,
                )
            } else {
                PreviewPage(
                    preview = current,
                    onLeave = {
                        usage.log(UsageActions.PREVIEW_BACK)
                        picked = null
                        open = null
                    },
                    onReload = { usage.log(UsageActions.PREVIEW_RELOAD) },
                    onClose = ::close,
                )
            }
        }
    }
}

@Composable
private fun Header(
    title: String,
    subtitle: String?,
    onBack: () -> Unit,
    onRefresh: () -> Unit,
    onClose: () -> Unit,
    refreshLabel: Int,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onBack) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.preview_back))
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            subtitle?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = OrmusMono),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        IconButton(onClick = onRefresh) {
            Icon(Icons.Default.Refresh, contentDescription = stringResource(refreshLabel))
        }
        IconButton(onClick = onClose) {
            Icon(Icons.Default.Close, contentDescription = stringResource(R.string.preview_close))
        }
    }
}

/** The dev servers on the host to pick from: printed URLs first, then listening ports. */
@Composable
fun PreviewPicker(
    listing: DevPorts.Listening?,
    forwardError: String?,
    onPick: (PreviewTarget) -> Unit,
    onRefresh: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxSize()) {
        Header(stringResource(R.string.preview_title), null, onClose, onRefresh, onClose, R.string.preview_refresh)
        forwardError?.let { Note(stringResource(R.string.preview_forward_failed, it)) }
        when (listing) {
            null -> LinearProgressIndicator(modifier = Modifier.fillMaxWidth())

            DevPorts.Listening.NotConnected -> Note(stringResource(R.string.preview_not_connected))

            DevPorts.Listening.NoTool -> Note(stringResource(R.string.preview_no_tool))

            is DevPorts.Listening.Failed -> Note(stringResource(R.string.preview_failed, listing.message))

            is DevPorts.Listening.Ports -> if (listing.targets.isEmpty()) {
                Note(stringResource(R.string.preview_empty))
            } else {
                LazyColumn(contentPadding = PaddingValues(vertical = 8.dp), modifier = Modifier.fillMaxSize()) {
                    items(listing.targets, key = { it.port }) { target -> TargetRow(target, onPick) }
                }
            }
        }
    }
}

@Composable
private fun TargetRow(target: PreviewTarget, onPick: (PreviewTarget) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onPick(target) }
            .padding(horizontal = 16.dp, vertical = 12.dp)
            .testTag(PREVIEW_TARGET_TAG),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(target.port.toString(), fontFamily = OrmusMono, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
        Text(
            listOfNotNull(target.process, target.path.takeIf { it != "/" }).joinToString("  "),
            fontFamily = OrmusMono,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun Note(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
    )
}

/**
 * The forwarded server in a WebView. Its cookies and storage are cleared as it opens and
 * again as it closes, so previews never share a session (every forward is 127.0.0.1,
 * and cookies ignore the port) and nothing lingers after the preview.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun PreviewPage(preview: OpenPreview, onLeave: () -> Unit, onReload: () -> Unit, onClose: () -> Unit) {
    var webView by remember { mutableStateOf<WebView?>(null) }
    val url = "http://127.0.0.1:${preview.forward.port}${preview.target.path}"
    fun back() {
        val web = webView
        if (web != null && web.canGoBack()) web.goBack() else onLeave()
    }
    BackHandler(onBack = ::back)
    Column(modifier = Modifier.fillMaxSize()) {
        Header(
            title = listOfNotNull(preview.target.process, preview.target.port.toString()).joinToString(" "),
            subtitle = url,
            onBack = ::back,
            onRefresh = {
                onReload()
                webView?.reload()
            },
            onClose = onClose,
            refreshLabel = R.string.preview_reload,
        )
        AndroidView(
            factory = { context ->
                clearWebData()
                WebView(context).apply {
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    settings.allowFileAccess = false
                    settings.allowContentAccess = false
                    // Links stay in the preview, never the phone's browser.
                    webViewClient = WebViewClient()
                    loadUrl(url)
                    webView = this
                }
            },
            onRelease = { web ->
                web.stopLoading()
                web.clearCache(true)
                web.destroy()
                clearWebData()
            },
            modifier = Modifier
                .fillMaxSize()
                .testTag("preview_web"),
        )
    }
}

private fun clearWebData() {
    CookieManager.getInstance().removeAllCookies(null)
    WebStorage.getInstance().deleteAllData()
}
