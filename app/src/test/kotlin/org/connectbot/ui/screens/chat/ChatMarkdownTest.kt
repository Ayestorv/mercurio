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

import org.junit.Assert.assertEquals
import org.junit.Test

class ChatMarkdownTest {

    @Test
    fun blocks_codeFencesTablesHeadingsListsAndParagraphs() {
        val text = """
            |## Result
            |The build is green.
            |Two files changed.
            |
            |- first point
            |  wrapped onto a second line
            |  - nested point
            |1. step one
            |
            |```kotlin
            |val x = 1
            |
            |val y = 2
            |```
            || a | b |
            ||---|---|
            || 1 | 2 |
            |Done.
        """.trimMargin()

        assertEquals(
            listOf(
                ChatBlock.Heading("Result"),
                ChatBlock.Paragraph("The build is green.\nTwo files changed."),
                ChatBlock.Item("•", "first point\nwrapped onto a second line", 0),
                ChatBlock.Item("•", "nested point", 1),
                ChatBlock.Item("1.", "step one", 0),
                ChatBlock.Code("val x = 1\n\nval y = 2"),
                ChatBlock.Code("| a | b |\n|---|---|\n| 1 | 2 |"),
                ChatBlock.Paragraph("Done."),
            ),
            ChatMarkdown.blocks(text),
        )
    }

    @Test
    fun blocks_anUnclosedFence_runsToTheEnd() {
        assertEquals(listOf(ChatBlock.Paragraph("Here:"), ChatBlock.Code("partial\ncode")), ChatMarkdown.blocks("Here:\n```\npartial\ncode"))
    }

    @Test
    fun inline_dropsTheMarkers_andStylesBoldAndCode() {
        val styled = ChatMarkdown.inline("Run **all** the `tests` now")

        assertEquals("Run all the tests now", styled.text)
        assertEquals(2, styled.spanStyles.size)
        assertEquals("all", styled.text.substring(styled.spanStyles[0].start, styled.spanStyles[0].end))
        assertEquals("tests", styled.text.substring(styled.spanStyles[1].start, styled.spanStyles[1].end))
    }
}
