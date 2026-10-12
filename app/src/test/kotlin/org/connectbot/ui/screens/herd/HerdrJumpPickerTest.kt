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

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import solutions.ormus.logos.herd.HerdrJump
import solutions.ormus.logos.herd.JumpTarget
import solutions.ormus.logos.herd.parseHerdrSnapshot

/** Jump to: the rows in urgency order, the search, and a tap. */
@RunWith(AndroidJUnit4::class)
class HerdrJumpPickerTest {
    @get:Rule
    val composeTestRule = createAndroidComposeRule<ComponentActivity>()

    private fun fixture(name: String) = checkNotNull(parseHerdrSnapshot(checkNotNull(javaClass.getResourceAsStream("/herdr/$name")).readBytes().decodeToString()))

    private val targets = HerdrJump.targets(mapOf("default" to fixture("snapshot-sun-mixed.json"), "work" to fixture("snapshot-picker-test.json")))
    private val picked = mutableListOf<JumpTarget>()

    private fun rows() = composeTestRule.onAllNodes(hasClickAction() and hasAnyAncestor(hasTestTag(HERDR_JUMP_LIST_TAG)))

    private fun show() {
        composeTestRule.setContent { HerdrJumpContent(targets, onPick = { picked += it }) }
        composeTestRule.waitForIdle()
    }

    @Test
    fun rows_needsYouFirst_withWorkspaceSessionAndAgent() {
        show()

        composeTestRule.onNodeWithTag(HERDR_JUMP_SEARCH_TAG).assertIsDisplayed()
        // Tab 3 of ~Demo has a blocked grok: first row.
        rows()[0].assertIsDisplayed()
        composeTestRule.onAllNodesWithText("GROK · NEEDS YOU").assertCountEquals(2)
        composeTestRule.onAllNodesWithText("~Demo · default").fetchSemanticsNodes().let { assert(it.isNotEmpty()) }
    }

    @Test
    fun search_narrowsTheList_andATapPicksThatTab() {
        show()

        composeTestRule.onNodeWithTag(HERDR_JUMP_SEARCH_TAG).performTextInput("gamma")
        composeTestRule.waitForIdle()
        rows().assertCountEquals(1)
        composeTestRule.onNodeWithText("gamma · work").assertIsDisplayed()
        rows()[0].performClick()

        assertEquals(listOf("work" to "w3:t1"), picked.map { it.session to it.tabId })
    }

    @Test
    fun search_withNoMatch_saysSo() {
        show()

        composeTestRule.onNodeWithTag(HERDR_JUMP_SEARCH_TAG).performTextInput("nothing-like-it")
        composeTestRule.waitForIdle()
        rows().assertCountEquals(0)
        composeTestRule.onNodeWithText("No tab matches").assertIsDisplayed()
    }
}
