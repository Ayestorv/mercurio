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
import androidx.compose.ui.test.assertIsNotEnabled
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
import solutions.ormus.logos.devtools.Highlighter
import solutions.ormus.logos.devtools.HostRead
import solutions.ormus.logos.devtools.RepoCommit
import solutions.ormus.logos.devtools.RepoEntry
import solutions.ormus.logos.devtools.RepoFile
import solutions.ormus.logos.devtools.RepoHome

/** The file browser on a synthetic repository. */
@RunWith(AndroidJUnit4::class)
class FilesScreenTest {
    @get:Rule
    val composeTestRule = createAndroidComposeRule<ComponentActivity>()

    private val home = RepoHome(git = true, root = "/work/demo", prefix = "src/", commits = listOf(RepoCommit("abc1234", "Add parser")))

    private fun show(
        path: String = "src",
        entries: HostRead<List<RepoEntry>>? = null,
        file: String? = null,
        content: HostRead<RepoFile>? = null,
        lines: FileLines? = null,
        onEntry: (RepoEntry) -> Unit = {},
        onUp: () -> Unit = {},
        onRev: (String) -> Unit = {},
    ) {
        composeTestRule.setContent {
            FilesScreen(
                dir = "/work/demo/src",
                dirs = emptyList(),
                home = HostRead.Ok(home),
                rev = "HEAD",
                path = path,
                entries = entries,
                file = file,
                content = content,
                lines = lines,
                onDir = {},
                onRev = onRev,
                onEntry = onEntry,
                onUp = onUp,
                onCopyPath = {},
                onCopyText = {},
                onRefresh = {},
                onBack = {},
            )
        }
    }

    @Test
    fun aDirectoryListsUpThenFoldersThenFiles_andATapOpens() {
        val tapped = mutableListOf<String>()
        show(
            entries = HostRead.Ok(listOf(RepoEntry("util", true), RepoEntry("main.kt", false, 2048))),
            onEntry = { tapped += it.name },
            onUp = { tapped += ".." },
        )

        val rows = composeTestRule.onAllNodesWithTag(FILES_ENTRY_TAG)
        assertEquals(3, rows.fetchSemanticsNodes().size)
        rows[0].assertTextContains("..")
        rows[1].assertTextContains("util/")
        rows[2].assertTextContains("main.kt").assertTextContains("2 KB")

        rows[0].performClick()
        rows[1].performClick()
        rows[2].performClick()

        assertEquals(listOf("..", "util", "main.kt"), tapped)
    }

    @Test
    fun theRevPickerOffersHeadAndRecentCommits() {
        val picked = mutableListOf<String>()
        show(entries = HostRead.Ok(emptyList()), onRev = { picked += it })

        composeTestRule.onNodeWithText("HEAD").performClick()
        composeTestRule.onNodeWithText("abc1234 Add parser").performClick()

        assertEquals(listOf("abc1234"), picked)
    }

    @Test
    fun anOpenFileShowsHighlightedLines() {
        val text = listOf("fun main() {", "    // hi", "}")
        show(
            file = "src/main.kt",
            content = HostRead.Ok(RepoFile(30, text.joinToString("\n"))),
            lines = FileLines(text, Highlighter.tokens(text, checkNotNull(Highlighter.syntaxFor("main.kt")))),
        )

        val lines = composeTestRule.onAllNodesWithTag(FILES_LINE_TAG)
        assertEquals(3, lines.fetchSemanticsNodes().size)
        lines[1].assertTextContains("    // hi")
        composeTestRule.onNodeWithText("Copy path").assertExists()
    }

    @Test
    fun aBinaryFileShowsItsSizeOnly() {
        show(file = "logo.png", content = HostRead.Ok(RepoFile(300, null)))

        composeTestRule.onNodeWithText("binary, 300 bytes").assertExists()
        composeTestRule.onNodeWithText("Copy text").assertIsNotEnabled()
    }
}
