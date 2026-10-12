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

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performScrollToNode
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.connectbot.ui.theme.ConnectBotTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import solutions.ormus.logos.herd.HerdrAgentStatus

/**
 * Renders the agent chat on a device from a synthetic reader payload (chat/payload-demo.json)
 * and saves a screenshot to /data/local/tmp/chat-*.png (pull with adb) as evidence of how it looks.
 */
@RunWith(AndroidJUnit4::class)
class ChatScreenshotTest {

    @get:Rule
    val composeTestRule = createAndroidComposeRule<ComponentActivity>()

    private val payload = checkNotNull(
        AgentChat.parse(checkNotNull(javaClass.getResourceAsStream("/chat/payload-demo.json")).readBytes().decodeToString()),
    )

    private fun screencap(name: String) {
        composeTestRule.waitForIdle()
        // Let the first frame reach the display before capturing it.
        Thread.sleep(SCREENCAP_SETTLE_MS)
        InstrumentationRegistry.getInstrumentation().uiAutomation
            .executeShellCommand("screencap -p /data/local/tmp/$name.png")
            .close()
        // screencap runs asynchronously in the shell; give it a moment to write.
        Thread.sleep(SCREENCAP_SETTLE_MS)
    }

    private fun show(status: HerdrAgentStatus) {
        val state = ChatUiState(
            target = ChatTarget(paneId = "w1:p1", agent = "claude", sessionId = "demo", status = status, title = "Dark mode toggle", place = "Demo / app"),
            items = payload.items,
            truncated = payload.truncated,
        )
        composeTestRule.setContent {
            ConnectBotTheme {
                Box(Modifier.safeDrawingPadding()) {
                    ChatContent(
                        state = state,
                        fallbackTitle = "Demo",
                        onClose = {},
                        onRefresh = {},
                        onSend = {},
                        onAnswer = {},
                        onOpenReader = {},
                    )
                }
            }
        }
    }

    @Test
    fun working() {
        show(HerdrAgentStatus.WORKING)
        screencap("chat-working")
    }

    @Test
    fun waitingForYou() {
        show(HerdrAgentStatus.BLOCKED)
        composeTestRule.onNodeWithTag(CHAT_LIST_TAG).performScrollToNode(hasTestTag(CHAT_WAITING_TAG))
        screencap("chat-waiting")
    }

    private companion object {
        const val SCREENCAP_SETTLE_MS = 1500L
    }
}
