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

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.connectbot.R
import org.connectbot.ui.components.DotStyle
import org.connectbot.ui.components.KEY_HEIGHT
import org.connectbot.ui.components.KEY_SHAPE
import org.connectbot.ui.components.KeyTone
import org.connectbot.ui.components.StatusChip
import org.connectbot.ui.components.WORD_KEY_PADDING
import org.connectbot.ui.components.keyBorder
import org.connectbot.ui.components.keyColors
import org.connectbot.ui.theme.Ormus
import org.connectbot.ui.theme.OrmusCornerShape
import org.connectbot.ui.theme.OrmusMono
import solutions.ormus.logos.herd.HerdSession
import solutions.ormus.logos.herd.HerdrAgentStatus
import solutions.ormus.logos.herd.HerdrCommands
import solutions.ormus.logos.herd.HerdrJump
import solutions.ormus.logos.herd.JumpTarget

/** Test tag on the Jump to search field. */
const val HERDR_JUMP_SEARCH_TAG = "herdr_jump_search"

/** Test tag on the Jump to list. */
const val HERDR_JUMP_LIST_TAG = "herdr_jump_list"

/** The tallest the list gets before it scrolls, so the terminal stays in view. */
private val JUMP_LIST_MAX_HEIGHT = 300.dp

/**
 * Jump to: every tab of every running Herdr session, the ones that need the user first,
 * with a search field on top. Opening it lists the sessions and fetches each one's snapshot
 * again; a tap hands the tab to [onPick].
 */
@Composable
fun HerdrJumpPicker(session: HerdSession, onPick: (JumpTarget) -> Unit, modifier: Modifier = Modifier) {
    val sessions by session.sessions.collectAsState()
    val primary by session.snapshot.collectAsState()
    val others by session.others.collectAsState()
    LaunchedEffect(session) { session.refreshAll() }
    val snapshots = buildMap {
        primary?.let { put(HerdrCommands.DEFAULT_SESSION, it) }
        putAll(others.filterKeys { it in sessions })
    }
    HerdrJumpContent(HerdrJump.targets(snapshots), onPick, modifier)
}

@Composable
fun HerdrJumpContent(targets: List<JumpTarget>, onPick: (JumpTarget) -> Unit, modifier: Modifier = Modifier) {
    var query by rememberSaveable { mutableStateOf("") }
    val shown = remember(targets, query) { HerdrJump.filter(targets, query) }
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            placeholder = { Text(stringResource(R.string.herdr_jump_search), maxLines = 1) },
            singleLine = true,
            shape = OrmusCornerShape,
            textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = OrmusMono),
            modifier = Modifier
                .fillMaxWidth()
                .testTag(HERDR_JUMP_SEARCH_TAG),
        )
        if (shown.isEmpty()) {
            Text(stringResource(R.string.herdr_jump_none), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = JUMP_LIST_MAX_HEIGHT)
                .testTag(HERDR_JUMP_LIST_TAG),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            items(shown, key = { "${it.session}/${it.tabId}" }) { target -> JumpRow(target) { onPick(target) } }
        }
    }
}

/** One tab: its name, then its workspace and session, and its agent's kind and state on the right. */
@Composable
private fun JumpRow(target: JumpTarget, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = KEY_HEIGHT),
        shape = KEY_SHAPE,
        colors = keyColors(KeyTone.PLAIN),
        border = keyBorder(KeyTone.PLAIN),
        contentPadding = WORD_KEY_PADDING,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    target.tabLabel.ifBlank { target.tabNumber.toString() },
                    fontFamily = OrmusMono,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    stringResource(R.string.herdr_jump_place, target.workspaceLabel.ifBlank { target.workspaceNumber.toString() }, target.session),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            AgentChip(target.agent, target.state)
        }
    }
}

/** The tab's agent and its state as the Herd's chip; nothing for a tab without either. */
@Composable
private fun AgentChip(agent: String?, state: HerdrAgentStatus) {
    val status = Ormus.extras.status
    val (word, color, style) = when (state) {
        HerdrAgentStatus.BLOCKED -> Triple(stringResource(R.string.herd_status_blocked), status.blocked, DotStyle.FILLED)
        HerdrAgentStatus.WORKING -> Triple(stringResource(R.string.herd_status_working), status.working, DotStyle.RING)
        HerdrAgentStatus.DONE -> Triple(stringResource(R.string.herd_status_done), status.done, DotStyle.FILLED)
        HerdrAgentStatus.IDLE -> Triple(stringResource(R.string.herd_status_idle), status.idle, DotStyle.HOLLOW)
        HerdrAgentStatus.UNKNOWN -> if (agent == null) return else Triple(null, status.idle, DotStyle.HOLLOW)
    }
    StatusChip(listOfNotNull(agent, word).joinToString(" · "), color, style = style)
}
