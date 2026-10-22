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

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.connectbot.ui.theme.ConnectBotTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** The draft view: the whole dictation draft, large, sent the way ⏎ sends it. */
@RunWith(AndroidJUnit4::class)
class DraftDialogTest {

    @get:Rule
    val composeTestRule = createAndroidComposeRule<ComponentActivity>()

    private var dismissed = false

    private fun show(draft: DictationDraft) {
        composeTestRule.setContent { ConnectBotTheme { DraftDialog(draft = draft, onDismiss = { dismissed = true }) } }
        composeTestRule.waitForIdle()
    }

    @Test
    fun showsTheWholeDraft_andEditsIt() {
        val note = "refactor the reader so it follows the tail"
        // The cursor at the end, where dictation leaves it.
        val draft = DictationDraft().apply { value = TextFieldValue(note, TextRange(note.length)) }
        show(draft)

        composeTestRule.onNodeWithTag(DRAFT_FIELD_TAG).assertTextContains("refactor the reader so it follows the tail")
        composeTestRule.onNodeWithText("42 characters").assertExists()
        composeTestRule.onNodeWithTag(DRAFT_FIELD_TAG).performTextInput(" now")
        assertTrue(draft.text.endsWith(" now"))
    }

    @Test
    fun anEmptyDraft_saysWhatThePageIsFor() {
        show(DictationDraft())

        composeTestRule.onNodeWithText("Write or dictate a long message here, and read it over before Send sends it.").assertExists()
    }

    @Test
    fun send_asksTheBarToSend_andCloses() {
        val draft = DictationDraft().apply { value = TextFieldValue("ship it") }
        show(draft)

        composeTestRule.onNodeWithText("Send").performClick()

        assertEquals(1, draft.sendRequests)
        assertTrue(dismissed)
    }

    @Test
    fun close_keepsTheDraft_clearEmptiesIt_sendWaitsForText() {
        val draft = DictationDraft().apply { value = TextFieldValue("keep me") }
        show(draft)

        composeTestRule.onNodeWithText("Clear").performClick()
        assertEquals("", draft.text)
        composeTestRule.onNodeWithText("Send").assertIsNotEnabled()
        composeTestRule.onNodeWithText("Close").performClick()
        assertTrue(dismissed)
        assertEquals(0, draft.sendRequests)
    }
}
