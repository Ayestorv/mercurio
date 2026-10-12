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

package org.connectbot.ui.components

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.connectbot.R
import org.connectbot.ui.theme.Ormus
import org.connectbot.ui.theme.OrmusMono
import solutions.ormus.logos.herd.HerdrAgentStatus
import solutions.ormus.logos.herd.WorkspaceChoice
import solutions.ormus.logos.herd.WorkspacePick

/**
 * The connect-time Herdr picker: one row per workspace of every running Herdr
 * session (a gold dot on the default session's focused one, its most urgent agent
 * state on the right), the gold key to attach where Herdr already is. A row focuses
 * its workspace first, and attaches its session. Back attaches as before.
 */
@Composable
internal fun HerdrWorkspacePromptContent(
    choices: List<WorkspaceChoice>,
    onPick: (WorkspacePick) -> Unit,
) {
    BackHandler { onPick(WorkspacePick.Dismiss) }
    val focused = choices.firstOrNull { it.focused }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(Ormus.extras.panelOverLive)
            .ormusPanelTop()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        MonoLabel(stringResource(R.string.herdr_picker_title))
        // The rows scroll in a short window (landscape) while the gold key stays on screen.
        Column(
            modifier = Modifier
                .weight(1f, fill = false)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            // With more than one Herdr session, each session's rows sit under its name.
            val grouped = choices.distinctBy { it.session }.size > 1
            choices.forEachIndexed { index, choice ->
                if (grouped && choices.getOrNull(index - 1)?.session != choice.session) MonoLabel(choice.session)
                WorkspaceRow(choice) { onPick(WorkspacePick.Focus(choice.workspaceId, choice.session)) }
            }
        }
        OrmusKey(
            label = focused?.let { stringResource(R.string.herdr_picker_attach_to, it.label) }
                ?: stringResource(R.string.herdr_picker_attach),
            onClick = { onPick(WorkspacePick.Attach) },
            tone = KeyTone.ON,
            contentPadding = WORD_KEY_PADDING,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun WorkspaceRow(choice: WorkspaceChoice, onClick: () -> Unit) {
    val tone = if (choice.focused) KeyTone.ACCENT else KeyTone.PLAIN
    Button(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .height(KEY_HEIGHT),
        shape = KEY_SHAPE,
        colors = keyColors(tone),
        border = keyBorder(tone),
        contentPadding = WORD_KEY_PADDING,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Box(modifier = Modifier.size(8.dp)) {
                if (choice.focused) StatusDot(color = Ormus.extras.status.blocked, size = 8.dp)
            }
            Text(
                stringResource(R.string.herdr_workspace_entry, choice.number, choice.label),
                fontFamily = OrmusMono,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            StateChip(choice.state)
        }
    }
}

/** The workspace's most urgent agent state as the Herd's chip; nothing when Herdr does not say. */
@Composable
private fun StateChip(state: HerdrAgentStatus) {
    val status = Ormus.extras.status
    when (state) {
        HerdrAgentStatus.BLOCKED -> StatusChip(stringResource(R.string.herd_status_blocked), status.blocked)
        HerdrAgentStatus.WORKING -> StatusChip(stringResource(R.string.herd_status_working), status.working, style = DotStyle.RING)
        HerdrAgentStatus.DONE -> StatusChip(stringResource(R.string.herd_status_done), status.done)
        HerdrAgentStatus.IDLE -> StatusChip(stringResource(R.string.herd_status_idle), status.idle, style = DotStyle.HOLLOW)
        HerdrAgentStatus.UNKNOWN -> Unit
    }
}
