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

/** The last web address in a stretch of terminal output, as the tray's URL key copies it. */
object LatestUrl {
    // Stops at whitespace, quotes, backticks and brackets, so a markdown link
    // `[text](https://x.y)` or a quoted URL gives just the address.
    private val URL = Regex("""https?://[^\s<>"'`()\[\]{}]+""")

    /** The last http(s) URL in [text], without trailing sentence punctuation; null if none. */
    fun find(text: String): String? = findAll(text).lastOrNull()

    /** Every http(s) URL in [text], oldest first, each without trailing sentence punctuation. */
    fun findAll(text: String): List<String> = URL.findAll(text).map { it.value.trimEnd('.', ',', ';', ':', '!', '?') }.toList()
}
