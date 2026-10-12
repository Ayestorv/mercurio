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
import solutions.ormus.logos.devtools.ChangedFile
import solutions.ormus.logos.devtools.DiffArea
import solutions.ormus.logos.devtools.DiffListing
import solutions.ormus.logos.devtools.GitDiff
import solutions.ormus.logos.devtools.HostProblem
import solutions.ormus.logos.devtools.HostRead

/** The diff viewer on a synthetic work tree. */
@RunWith(AndroidJUnit4::class)
class DiffScreenTest {
    @get:Rule
    val composeTestRule = createAndroidComposeRule<ComponentActivity>()

    private val listing = DiffListing(
        root = "/work/demo",
        files = listOf(
            ChangedFile("src/a.kt", DiffArea.STAGED, 'M', 4, 1),
            ChangedFile("src/b.kt", DiffArea.UNSTAGED, 'M', 2, 0),
            ChangedFile("logo.png", DiffArea.UNSTAGED, 'M', binary = true),
            ChangedFile("notes.md", DiffArea.UNTRACKED, '?'),
        ),
    )

    @Test
    fun listsFilesByAreaWithCounts_andATapOpensOne() {
        var opened: ChangedFile? = null
        composeTestRule.setContent {
            DiffScreen(
                dir = "/work/demo/src",
                dirs = emptyList(),
                listing = HostRead.Ok(listing),
                open = null,
                lines = null,
                onDir = {},
                onOpen = { opened = it },
                onRefresh = {},
                onBack = {},
            )
        }

        val rows = composeTestRule.onAllNodesWithTag(DIFF_FILE_TAG)
        assertEquals(4, rows.fetchSemanticsNodes().size)
        rows[0].assertTextContains("src/a.kt").assertTextContains("+4").assertTextContains("-1")
        rows[2].assertTextContains("binary")
        rows[3].assertTextContains("new")
        composeTestRule.onNodeWithText("/ STAGED 1").assertExists()
        composeTestRule.onNodeWithText("/ UNSTAGED 2").assertExists()
        composeTestRule.onNodeWithText("/work/demo").assertExists()

        rows[1].performClick()

        assertEquals(listing.files[1], opened)
    }

    @Test
    fun anOpenFileShowsItsLines() {
        val lines = GitDiff.parseDiff("diff --git a/b b/b\n@@ -1 +1 @@\n-old\n+new\n")
        composeTestRule.setContent {
            DiffScreen(
                dir = null,
                dirs = emptyList(),
                listing = HostRead.Ok(listing),
                open = listing.files[1],
                lines = HostRead.Ok(lines, truncated = true),
                onDir = {},
                onOpen = {},
                onRefresh = {},
                onBack = {},
            )
        }

        val shown = composeTestRule.onAllNodesWithTag(DIFF_LINE_TAG)
        assertEquals(4, shown.fetchSemanticsNodes().size)
        shown[2].assertTextContains("-old")
        shown[3].assertTextContains("+new")
        composeTestRule.onNodeWithText("Too big to show whole: cut at the size cap.").assertExists()
    }

    @Test
    fun aPlainDirectorySaysSo() {
        composeTestRule.setContent {
            DiffScreen(
                dir = null,
                dirs = emptyList(),
                listing = HostRead.Problem(HostProblem.NOT_A_REPO),
                open = null,
                lines = null,
                onDir = {},
                onOpen = {},
                onRefresh = {},
                onBack = {},
            )
        }

        composeTestRule.onNodeWithText("Not a git repository.").assertExists()
        composeTestRule.onNodeWithText("~ (home)").assertExists()
    }
}
