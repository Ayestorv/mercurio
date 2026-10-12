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

class ChatRowsTest {

    private val user = ChatItem(ChatItem.USER, text = "Add a dark mode toggle.")
    private val agent = ChatItem(ChatItem.AGENT, text = "Looking at the settings first.")
    private val tool = ChatItem(ChatItem.TOOL, line = "Read src/Settings.kt")

    @Test
    fun aRunOfToolCalls_foldsIntoOneRow() {
        val items = listOf(user, agent, tool, tool, tool, agent, user, tool)

        assertEquals(
            listOf(
                ChatRow(0, 1, turn = false),
                ChatRow(1, 1, turn = true),
                ChatRow(2, 3, turn = false),
                ChatRow(5, 1, turn = false),
                ChatRow(6, 1, turn = false),
                ChatRow(7, 1, turn = true),
            ),
            ChatRow.of(items),
        )
    }

    @Test
    fun aConversationThatStartsMidTurn_opensWithATurn() {
        assertEquals(listOf(ChatRow(0, 2, turn = true), ChatRow(2, 1, turn = false)), ChatRow.of(listOf(tool, tool, agent)))
        assertEquals(emptyList<ChatRow>(), ChatRow.of(emptyList()))
    }

    @Test
    fun indexOf_findsTheRowThatHoldsAnItem() {
        val rows = ChatRow.of(listOf(user, agent, tool, tool, tool, agent))

        assertEquals(0, ChatRow.indexOf(rows, 0))
        assertEquals(2, ChatRow.indexOf(rows, 2))
        assertEquals(2, ChatRow.indexOf(rows, 4))
        assertEquals(3, ChatRow.indexOf(rows, 5))
    }
}
