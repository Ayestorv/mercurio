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
import org.junit.Assert.assertNull
import org.junit.Test
import solutions.ormus.logos.herd.HerdrAgentStatus

/** The ask the chat pins at its top, from synthetic transcripts and pane screens. */
class ChatAskTest {

    private val user = ChatItem(ChatItem.USER, text = "Add a dark mode toggle.")
    private val pending = ChatItem(ChatItem.TOOL, line = "Bash: ./gradlew test", detail = "./gradlew test", state = "run")

    // A permission prompt as Claude Code draws it, framed, with its footer and status line under it.
    private val permissionScreen = """
        │ Bash command                                          │
        │   ./gradlew test                                      │
        │ Do you want to proceed?                               │
        │ ❯ 1. Yes                                              │
        │   2. Yes, and don't ask again for ./gradlew commands  │
        │   3. No, and tell Claude what to do differently (esc) │
        ╰───────────────────────────────────────────────────────╯
          Esc to cancel
    """.trimIndent()

    @Test
    fun blocked_withNumberedOptions_onScreen_whileTheCallWaits() {
        val items = listOf(user, ChatItem(ChatItem.AGENT, text = "Running the tests."), pending)

        val ask = ChatAsk.of(HerdrAgentStatus.BLOCKED, items, permissionScreen)

        assertEquals(
            ChatAsk.Blocked(
                question = "Do you want to proceed?",
                options = listOf("1. Yes", "2. Yes, and don't ask again for ./gradlew commands", "3. No, and tell Claude what to do differently (esc)"),
                item = 2,
            ),
            ask,
        )
    }

    @Test
    fun blocked_withNumberedOptions_inTheLastReply_whenTheScreenHasNone() {
        val reply = ChatItem(ChatItem.AGENT, text = "Two ways to do it:\n\n1. Follow the system setting\n2. Add a switch in Settings\n\nWhich one do you want?")
        val items = listOf(user, reply)

        val ask = ChatAsk.of(HerdrAgentStatus.BLOCKED, items, screen = "> \n  ? for shortcuts")

        assertEquals(ChatAsk.Blocked("Which one do you want?", listOf("1. Follow the system setting", "2. Add a switch in Settings"), 1), ask)
    }

    @Test
    fun blocked_withoutOptions_takesTheQuestion() {
        val reply = ChatItem(ChatItem.AGENT, text = "The theme lives in two places.\nShould the toggle also change the terminal colors?\nI can do either.")
        val items = listOf(user, reply)

        assertEquals(ChatAsk.Blocked("Should the toggle also change the terminal colors?", emptyList(), 1), ChatAsk.of(HerdrAgentStatus.BLOCKED, items))
    }

    @Test
    fun blocked_withNothingToRead_namesTheWaitingCall() {
        val items = listOf(user, pending)

        assertEquals(ChatAsk.Blocked("Bash: ./gradlew test", emptyList(), 1), ChatAsk.of(HerdrAgentStatus.BLOCKED, items, screen = null))
    }

    @Test
    fun working_namesTheRunningCall_elseTheLastLine() {
        assertEquals(ChatAsk.Working("Bash: ./gradlew test", 2), ChatAsk.of(HerdrAgentStatus.WORKING, listOf(user, ChatItem(ChatItem.AGENT, text = "On it."), pending)))

        val wrote = listOf(user, pending.copy(state = "ok"), ChatItem(ChatItem.AGENT, text = "Tests pass.\nNow the docs."))
        assertEquals(ChatAsk.Working("Now the docs.", 2), ChatAsk.of(HerdrAgentStatus.WORKING, wrote))
    }

    @Test
    fun done_showsTheFirstLinesOfTheLastReply() {
        val reply = ChatItem(ChatItem.AGENT, text = "Done.\n\nThe toggle is in Settings.\nIt follows the system.\nTests pass.")
        val items = listOf(user, ChatItem(ChatItem.AGENT, text = "Earlier."), pending.copy(state = "ok"), reply)

        assertEquals(ChatAsk.Quiet("Done.\nThe toggle is in Settings.\nIt follows the system.", 3), ChatAsk.of(HerdrAgentStatus.DONE, items))
        assertEquals(ChatAsk.Quiet("Done.\nThe toggle is in Settings.\nIt follows the system.", 3), ChatAsk.of(HerdrAgentStatus.IDLE, items))
    }

    @Test
    fun noAgent_noAsk() {
        assertNull(ChatAsk.of(null, listOf(user)))
        assertNull(ChatAsk.of(HerdrAgentStatus.IDLE, listOf(user)))
    }
}
