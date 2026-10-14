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

package org.connectbot.ui.screens.chat

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import org.connectbot.ui.components.KeyTone
import org.connectbot.ui.components.OrmusKey
import org.connectbot.ui.components.WORD_KEY_PADDING
import org.connectbot.ui.theme.Ormus
import org.connectbot.ui.theme.OrmusMono

/** Test tag on each option key of a waiting agent's ask. */
const val ASK_OPTION_TAG = "ask_option"

/** A waiting agent's question, in the heading style and gold: the line on its card that asks. */
@Composable
internal fun AskQuestion(question: String) {
    Text(question, style = MaterialTheme.typography.titleMedium, color = Ormus.extras.status.blocked)
}

/**
 * A waiting agent's numbered [options], shared by the Agents board and the chat. With
 * [keys] (one per option, from [solutions.ormus.logos.herd.HerdApproval.optionKeys]) each
 * option is a key: the first, highlighted on screen, in gold, the rest ghosts; a tap hands
 * that option's keys to [onPick]. Without, the options read as text.
 */
@Composable
internal fun AskOptions(options: List<String>, keys: List<String>?, onPick: (String) -> Unit) {
    if (keys == null) {
        options.forEach { option -> Text(option, fontFamily = OrmusMono, style = MaterialTheme.typography.bodySmall) }
        return
    }
    options.forEachIndexed { i, option ->
        OrmusKey(
            label = option,
            onClick = { onPick(keys[i]) },
            modifier = Modifier.fillMaxWidth().testTag(ASK_OPTION_TAG),
            tone = if (i == 0) KeyTone.ON else KeyTone.PLAIN,
            contentPadding = WORD_KEY_PADDING,
        )
    }
}
