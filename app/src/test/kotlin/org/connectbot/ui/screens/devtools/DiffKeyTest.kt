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

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.connectbot.ui.screens.herd.HerdrCommandContent
import org.connectbot.ui.screens.herd.HerdrSection
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** The Herdr panel's Diff key, in the Agents group. */
@RunWith(AndroidJUnit4::class)
class DiffKeyTest {
    @get:Rule
    val composeTestRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun diffKeyOpensTheViewer() {
        var opened = 0
        composeTestRule.setContent {
            HerdrCommandContent(snapshot = null, result = null, section = HerdrSection.AGENTS, onSection = {}, onRun = { _, _ -> }, onDiff = { opened++ })
        }

        composeTestRule.onNodeWithText("Diff").performClick()

        assertEquals(1, opened)
    }

    @Test
    fun noDiffKeyWithoutAViewer() {
        composeTestRule.setContent {
            HerdrCommandContent(snapshot = null, result = null, section = HerdrSection.AGENTS, onSection = {}, onRun = { _, _ -> })
        }

        composeTestRule.onNodeWithText("Diff").assertDoesNotExist()
    }
}
