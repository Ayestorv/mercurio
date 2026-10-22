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

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import org.connectbot.R
import org.connectbot.ui.theme.Ormus
import org.connectbot.ui.theme.OrmusCornerShape
import org.connectbot.ui.theme.OrmusMono

/** Test tag on the draft view's text field. */
const val DRAFT_FIELD_TAG = "draft_field"

/**
 * The whole dictation draft, large enough to read a long voice note before it goes out.
 * It edits the same [draft] as the compose bar's field. Send asks the bar to send it
 * ([DictationDraft.requestSend]), so it goes out exactly as ⏎ would send it; Close keeps
 * the draft; Clear empties it.
 */
@Composable
fun DraftDialog(
    draft: DictationDraft,
    onDismiss: () -> Unit,
) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            color = MaterialTheme.colorScheme.surfaceContainer,
            shape = OrmusCornerShape,
            border = BorderStroke(1.dp, Ormus.extras.cardAccent),
        ) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    "/ " + stringResource(R.string.draft_title).uppercase(),
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = OrmusMono,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    value = draft.value,
                    onValueChange = { draft.value = it },
                    placeholder = { Text(stringResource(R.string.draft_hint)) },
                    textStyle = MaterialTheme.typography.bodyLarge.copy(fontSize = 18.sp, lineHeight = 26.sp),
                    shape = OrmusCornerShape,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = DRAFT_MIN_HEIGHT, max = DRAFT_MAX_HEIGHT)
                        .testTag(DRAFT_FIELD_TAG),
                )
                Text(
                    stringResource(R.string.draft_count, draft.text.length),
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = OrmusMono,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    OrmusKey(
                        label = stringResource(R.string.draft_clear),
                        onClick = { draft.value = TextFieldValue() },
                        enabled = draft.text.isNotEmpty(),
                        contentPadding = WORD_KEY_PADDING,
                    )
                    Spacer(Modifier.weight(1f))
                    OrmusKey(label = stringResource(R.string.button_close), onClick = onDismiss, contentPadding = WORD_KEY_PADDING)
                    OrmusKey(
                        label = stringResource(R.string.button_send),
                        onClick = {
                            draft.requestSend()
                            onDismiss()
                        },
                        enabled = draft.text.isNotBlank(),
                        tone = KeyTone.ON,
                        contentPadding = WORD_KEY_PADDING,
                    )
                }
            }
        }
    }
}

private val DRAFT_MIN_HEIGHT = 240.dp
private val DRAFT_MAX_HEIGHT = 480.dp
