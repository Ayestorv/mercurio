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
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import solutions.ormus.logos.devtools.DevPorts
import solutions.ormus.logos.devtools.PreviewTarget

/** The dev preview's server list. */
@RunWith(AndroidJUnit4::class)
class PreviewPickerTest {
    @get:Rule
    val composeTestRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun listsServersAndATapPicksOne() {
        val targets = listOf(PreviewTarget("127.0.0.1", 5173, "/app/", "node"), PreviewTarget("127.0.0.1", 8000, process = "python3"))
        var picked: PreviewTarget? = null
        composeTestRule.setContent {
            PreviewPicker(listing = DevPorts.Listening.Ports(targets), forwardError = null, onPick = { picked = it }, onRefresh = {}, onClose = {})
        }

        val rows = composeTestRule.onAllNodesWithTag(PREVIEW_TARGET_TAG)
        assertEquals(2, rows.fetchSemanticsNodes().size)
        rows[0].assertTextContains("5173").assertTextContains("node  /app/")
        rows[1].assertTextContains("8000").assertTextContains("python3")

        rows[1].performClick()

        assertEquals(targets[1], picked)
    }

    @Test
    fun saysWhenNothingListens_andWhyAForwardFailed() {
        composeTestRule.setContent {
            PreviewPicker(listing = DevPorts.Listening.Ports(emptyList()), forwardError = "refused", onPick = {}, onRefresh = {}, onClose = {})
        }

        composeTestRule.onNodeWithText("No dev server is listening on the host.").assertExists()
        composeTestRule.onNodeWithText("Could not forward the port: refused").assertExists()
    }
}
