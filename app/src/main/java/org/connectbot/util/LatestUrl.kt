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

package org.connectbot.util

/**
 * The last web address in a stretch of terminal output, as the tray's URL key copies it.
 * A URL the program broke across rows at the screen edge (a sign-in link, say) is read back
 * whole: a row as wide as the output's rows run that ends inside a URL continues on the next.
 */
object LatestUrl {
    // Stops at whitespace, quotes, backticks and brackets, so a markdown link
    // `[text](https://x.y)` or a quoted URL gives just the address.
    private val URL = Regex("""https?://[^\s<>"'`()\[\]{}]+""")

    // A character a URL can go on with at the start of the next row.
    private val CONTINUES = Regex("""[^\s<>"'`()\[\]{}]""")

    // Rows shorter than this are never taken for the screen width: no phone is that narrow.
    private const val MIN_WIDTH = 30

    /** The last http(s) URL in [text], without trailing sentence punctuation; null if none. */
    fun find(text: String): String? = findAll(text).lastOrNull()

    /** Every http(s) URL in [text], oldest first, each without trailing sentence punctuation. */
    fun findAll(text: String): List<String> = URL.findAll(joinWrapped(text)).map { it.value.trimEnd('.', ',', ';', ':', '!', '?') }.toList()

    /**
     * [text] with each URL that runs off the end of a full row joined to its next row. The
     * width is the commonest length of the long rows (a program draws its rules and wraps its
     * text at it), the longest on a tie; a row one short of it counts, for a program that
     * keeps the last column clear.
     */
    internal fun joinWrapped(text: String): String {
        val rows = text.split('\n')
        val width = rows.map { it.length }.filter { it >= MIN_WIDTH }.groupingBy { it }.eachCount()
            .maxWithOrNull(compareBy({ it.value }, { it.key }))?.key ?: return text
        val out = StringBuilder(text.length)
        val line = StringBuilder()
        rows.forEachIndexed { i, row ->
            line.append(row)
            val next = rows.getOrNull(i + 1)
            if (next != null && wraps(row, line, next, width)) return@forEachIndexed
            out.append(line)
            if (next != null) out.append('\n')
            line.setLength(0)
        }
        return out.toString()
    }

    private fun wraps(row: String, line: CharSequence, next: String, width: Int): Boolean {
        if (row.length !in width - 1..width) return false
        val url = URL.findAll(line).lastOrNull() ?: return false
        return url.range.last == line.length - 1 &&
            next.isNotEmpty() &&
            CONTINUES.matchesAt(next, 0) &&
            // A row that starts a URL of its own is a second address, not the rest of this one.
            !next.startsWith("http://", ignoreCase = true) &&
            !next.startsWith("https://", ignoreCase = true)
    }
}
