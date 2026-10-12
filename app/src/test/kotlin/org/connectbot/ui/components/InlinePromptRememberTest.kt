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
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.connectbot.R
import org.connectbot.service.PromptRequest
import org.connectbot.service.PromptResponse
import org.connectbot.ui.theme.ConnectBotTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** The login password prompt offers "Remember password", ticked; other prompts do not. */
@RunWith(AndroidJUnit4::class)
class InlinePromptRememberTest {

    @get:Rule
    val composeTestRule = createAndroidComposeRule<ComponentActivity>()

    private var response: PromptResponse? = null

    private fun show(prompt: PromptRequest) {
        composeTestRule.setContent {
            ConnectBotTheme {
                InlinePrompt(promptRequest = prompt, onResponse = { response = it }, onCancel = {})
            }
        }
        composeTestRule.waitForIdle()
    }

    private val rememberLabel get() = composeTestRule.activity.getString(R.string.prompt_remember_password)
    private val ok get() = composeTestRule.activity.getString(R.string.button_ok)

    @Test
    fun loginPasswordPrompt_showsRememberTicked_andSubmitsIt() {
        show(PromptRequest.StringPrompt(null, "Password:", isPassword = true, offerRemember = true))

        composeTestRule.onNodeWithText(rememberLabel).assertIsOn()
        composeTestRule.onNode(hasSetTextAction()).performTextInput("s3cret")
        composeTestRule.onNodeWithText(ok).performClick()

        assertEquals(PromptResponse.StringResponse("s3cret", remember = true), response)
    }

    @Test
    fun untickingRemember_submitsFalse() {
        show(PromptRequest.StringPrompt(null, "Password:", isPassword = true, offerRemember = true))

        composeTestRule.onNodeWithText(rememberLabel).performClick().assertIsOff()
        composeTestRule.onNode(hasSetTextAction()).performTextInput("s3cret")
        composeTestRule.onNodeWithText(ok).performClick()

        assertEquals(PromptResponse.StringResponse("s3cret", remember = false), response)
    }

    @Test
    fun plainTextPrompt_hasNoRememberBox() {
        show(PromptRequest.StringPrompt(null, "Username:", isPassword = false))
        composeTestRule.onNodeWithText(rememberLabel).assertDoesNotExist()
    }

    @Test
    fun keyPassphrasePrompt_hasNoRememberBox() {
        show(PromptRequest.StringPrompt(null, "Password for key 'work'", isPassword = true))
        composeTestRule.onNodeWithText(rememberLabel).assertDoesNotExist()
    }
}
