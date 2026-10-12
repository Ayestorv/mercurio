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

import solutions.ormus.logos.herd.HerdrAgentStatus

/** What the chat pins at its top: what the agent needs from you, or what it is doing. [item] is the turn it came from. */
sealed interface ChatAsk {
    val item: Int?

    /** The agent waits on [question], with its numbered [options] when the prompt has them. */
    data class Blocked(val question: String, val options: List<String>, override val item: Int?) : ChatAsk

    /** The agent works: [line] is the tool call running now, or the last line it wrote. */
    data class Working(val line: String, override val item: Int?) : ChatAsk

    /** The agent finished or is idle: [text] is the start of its last reply. */
    data class Quiet(val text: String, override val item: Int?) : ChatAsk

    companion object {
        /** Lines of the last reply the quiet card shows. */
        const val QUIET_LINES = 3

        // "❯ 1. Yes", "  2) No", "› 3. Yes, and don't ask again": a prompt's numbered option.
        private val OPTION = Regex("""^(?:[❯›>]\s*)?(\d{1,2})[.)]\s+(.+)$""")

        private val BOX = '─'..'╿'

        /**
         * The ask in [items] for an agent in [status]; [screen] is the pane's last lines, read
         * while it is blocked. A blocked agent's prompt comes from the fresher source: the pane's screen when the transcript ends on a tool call still waiting (a permission
         * prompt is only drawn on screen) or when the screen shows numbered options; otherwise the
         * transcript's last agent turn, which holds a question the agent wrote as its reply.
         */
        fun of(status: HerdrAgentStatus?, items: List<ChatItem>, screen: String? = null): ChatAsk? {
            val last = items.indices.lastOrNull()
            val reply = items.indices.lastOrNull { items[it].k == ChatItem.AGENT }
            return when (status) {
                HerdrAgentStatus.BLOCKED -> blocked(items, screen)

                HerdrAgentStatus.WORKING -> {
                    val running = items.indices.lastOrNull { items[it].isTool && items[it].state == "run" }?.takeIf { it == last }
                    when {
                        running != null -> Working(items[running].line, running)
                        reply != null -> Working(lines(items[reply].text).lastOrNull().orEmpty(), reply)
                        else -> Working("", null)
                    }
                }

                null -> null

                else -> reply?.let { Quiet(lines(items[it].text).take(QUIET_LINES).joinToString("\n"), it) }
            }
        }

        private fun blocked(items: List<ChatItem>, screen: String?): Blocked {
            val last = items.indices.lastOrNull()
            val pending = last?.takeIf { items[it].isTool && items[it].state == "run" }
            val fromScreen = screen?.let { prompt(lines(it)) }
            val agentLast = last?.takeIf { items[it].k == ChatItem.AGENT }
            val fromTranscript = agentLast?.let { prompt(lines(items[it].text)) }
            return when {
                fromScreen != null && (pending != null || fromScreen.options.isNotEmpty() || fromTranscript == null) -> fromScreen.copy(item = last)
                fromTranscript != null -> fromTranscript.copy(item = agentLast)
                else -> Blocked(pending?.let { items[it].line }.orEmpty(), emptyList(), last)
            }
        }

        /**
         * The prompt at the end of [lines]: the last run of numbered options and the line that
         * asks them (a question after the options, else the line above them); without options,
         * the last question, else the last two lines.
         */
        internal fun prompt(lines: List<String>): Blocked? {
            if (lines.isEmpty()) return null
            val end = lines.indexOfLast { OPTION.matches(it) }
            if (end < 0) {
                val question = lines.lastOrNull { it.endsWith("?") }
                return Blocked(question ?: lines.takeLast(2).joinToString("\n"), emptyList(), null)
            }
            // The options run up from the last one, down to the one numbered 1.
            var start = end
            while (start > 0 && OPTION.matchEntire(lines[start])?.groupValues?.get(1) != "1" && OPTION.matches(lines[start - 1])) start--
            val options = lines.subList(start, end + 1).map { OPTION.matchEntire(it)!!.destructured.let { (n, text) -> "$n. $text" } }
            val after = lines.subList(end + 1, lines.size).lastOrNull { it.endsWith("?") }
            return Blocked(after ?: lines.getOrNull(start - 1).orEmpty(), options, null)
        }

        /** [text]'s lines worth reading: trimmed, no blank lines, no box-drawing frames around them. */
        private fun lines(text: String): List<String> = text.lineSequence()
            .map { line -> line.trim().trim { it in BOX || it.isWhitespace() } }
            .filter { it.isNotEmpty() }
            .toList()
    }
}
