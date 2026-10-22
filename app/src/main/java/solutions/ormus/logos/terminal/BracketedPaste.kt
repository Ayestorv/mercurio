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

package solutions.ormus.logos.terminal

/**
 * Text with line breaks, sent as one paste. A program that turned on bracketed paste
 * (DECSET ?2004: Claude Code, Herdr, bash and zsh at their prompts) reads what comes between
 * ESC [200~ and ESC [201~ as pasted, so each line break stays a line break instead of
 * submitting the line before it. Text on one line, or for a program that did not ask,
 * goes as it is.
 */
object BracketedPaste {
    const val START = "\u001b[200~"
    const val END = "\u001b[201~"

    fun wrap(text: String, bracketed: Boolean): String {
        if (!bracketed || text.none { it == '\n' || it == '\r' }) return text
        // An end marker inside the text would end the paste early and run the rest as typed.
        return START + text.replace(END, "") + END
    }
}
