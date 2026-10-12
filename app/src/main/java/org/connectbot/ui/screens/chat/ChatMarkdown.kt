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

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle

/** A block of agent text: the markdown agents write, kept to what reads well on a phone. */
sealed interface ChatBlock {
    /** A fenced code block, or a table (kept monospace so its columns line up). */
    data class Code(val text: String) : ChatBlock

    data class Heading(val text: String) : ChatBlock

    /** A list item: [marker] is "•" or "1." and so on. */
    data class Item(val marker: String, val text: String, val depth: Int) : ChatBlock

    data class Paragraph(val text: String) : ChatBlock
}

/** Basic markdown for agent replies: code fences, tables, headings, lists, paragraphs. */
object ChatMarkdown {
    private val FENCE = Regex("^\\s*(```|~~~)")
    private val HEADING = Regex("^#{1,6}\\s+(.*)$")
    private val BULLET = Regex("^(\\s*)[-*+]\\s+(.*)$")
    private val NUMBERED = Regex("^(\\s*)(\\d{1,3}[.)])\\s+(.*)$")
    private val INLINE = Regex("\\*\\*(.+?)\\*\\*|`([^`]+)`")

    fun blocks(text: String): List<ChatBlock> {
        val blocks = mutableListOf<ChatBlock>()
        val paragraph = mutableListOf<String>()
        fun flush() {
            if (paragraph.isNotEmpty()) blocks += ChatBlock.Paragraph(paragraph.joinToString("\n"))
            paragraph.clear()
        }
        val lines = text.lines()
        var i = 0
        while (i < lines.size) {
            val line = lines[i]
            val fence = FENCE.find(line)
            when {
                fence != null -> {
                    flush()
                    val close = fence.groupValues[1]
                    val code = mutableListOf<String>()
                    i++
                    while (i < lines.size && !lines[i].trimStart().startsWith(close)) code += lines[i++]
                    blocks += ChatBlock.Code(code.joinToString("\n"))
                }

                line.trimStart().startsWith("|") -> {
                    flush()
                    val table = mutableListOf<String>()
                    while (i < lines.size && lines[i].trimStart().startsWith("|")) table += lines[i++].trim()
                    blocks += ChatBlock.Code(table.joinToString("\n"))
                    continue
                }

                line.isBlank() -> flush()

                else -> {
                    val heading = HEADING.matchEntire(line.trim())
                    val bullet = BULLET.matchEntire(line)
                    val numbered = NUMBERED.matchEntire(line)
                    when {
                        heading != null -> {
                            flush()
                            blocks += ChatBlock.Heading(heading.groupValues[1])
                        }

                        bullet != null -> {
                            flush()
                            blocks += ChatBlock.Item("•", bullet.groupValues[2], bullet.groupValues[1].length / 2)
                        }

                        numbered != null -> {
                            flush()
                            blocks += ChatBlock.Item(numbered.groupValues[2], numbered.groupValues[3], numbered.groupValues[1].length / 2)
                        }

                        // An indented line right under a list item continues it.
                        paragraph.isEmpty() && line.startsWith("  ") && blocks.lastOrNull() is ChatBlock.Item -> {
                            val item = blocks.removeAt(blocks.lastIndex) as ChatBlock.Item
                            blocks += item.copy(text = item.text + "\n" + line.trim())
                        }

                        else -> paragraph += line
                    }
                }
            }
            i++
        }
        flush()
        return blocks
    }

    /** **bold** and `code` inside a line; everything else as written. */
    fun inline(text: String, code: SpanStyle = SpanStyle(fontFamily = FontFamily.Monospace)): AnnotatedString = buildAnnotatedString {
        var at = 0
        for (match in INLINE.findAll(text)) {
            append(text, at, match.range.first)
            val (bold, mono) = match.destructured
            if (bold.isNotEmpty()) {
                withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) { append(bold) }
            } else {
                withStyle(code) { append(mono) }
            }
            at = match.range.last + 1
        }
        append(text, at, text.length)
    }
}
