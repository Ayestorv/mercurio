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

/**
 * One row of the conversation: the items from [first], [count] of them. A run of tool calls
 * folds into one row; a message is a row of its own. [turn] marks the first row of an agent
 * turn, which the chat draws under a hairline with the agent's label.
 */
data class ChatRow(val first: Int, val count: Int, val turn: Boolean) {
    val range: IntRange get() = first until first + count

    companion object {
        /** [items] as rows: each run of tool calls in one row. */
        fun of(items: List<ChatItem>): List<ChatRow> {
            val rows = mutableListOf<ChatRow>()
            var i = 0
            while (i < items.size) {
                var end = i + 1
                if (items[i].isTool) while (end < items.size && items[end].isTool) end++
                val turn = !items[i].isUser && (i == 0 || items[i - 1].isUser)
                rows += ChatRow(i, end - i, turn)
                i = end
            }
            return rows
        }

        /** The row in [rows] that holds item [item]. */
        fun indexOf(rows: List<ChatRow>, item: Int): Int = rows.indexOfFirst { item in it.range }.coerceAtLeast(0)
    }
}
