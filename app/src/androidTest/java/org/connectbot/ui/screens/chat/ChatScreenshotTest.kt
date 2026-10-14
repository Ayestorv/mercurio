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

import android.view.View
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.connectbot.ui.theme.ConnectBotTheme
import org.junit.Assert.assertTrue
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

    // The view the chat is drawn in: the dialog's window when it is in one.
    private var chatView: View? = null

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

    // The test activity draws edge to edge, as the chat dialog does.
    private fun show(status: HerdrAgentStatus, inDialog: Boolean = false, screen: String? = null) {
        val state = ChatUiState(
            target = ChatTarget(paneId = "w1:p1", agent = "claude", sessionId = "demo", status = status, title = "Dark mode toggle", place = "Demo / app"),
            items = payload.items,
            truncated = payload.truncated,
            screen = screen,
            since = System.currentTimeMillis() - 3 * 60_000,
        )
        composeTestRule.setContent {
            ConnectBotTheme {
                val chat = @Composable {
                    chatView = LocalView.current
                    ChatContent(
                        state = state,
                        fallbackTitle = "Demo",
                        onClose = {},
                        onRefresh = {},
                        onSend = {},
                        onAnswer = {},
                        onPick = {},
                        onOpenReader = {},
                    )
                }
                if (inDialog) Dialog(onDismissRequest = {}, properties = CHAT_DIALOG_PROPERTIES) { chat() } else chat()
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
        show(HerdrAgentStatus.BLOCKED, inDialog = true, screen = PERMISSION_SCREEN)
        composeTestRule.onNodeWithText("Do you want to proceed?").assertExists()
        screencap("chat-waiting")
    }

    /** The reply box with the keyboard up: it sits above the keyboard, not under it. */
    @Test
    fun replyAboveTheKeyboard() {
        show(HerdrAgentStatus.IDLE, inDialog = true)
        composeTestRule.onNodeWithTag(CHAT_REPLY_TAG).performClick()
        composeTestRule.waitUntil(KEYBOARD_TIMEOUT_MS) { imeTop() != null }
        composeTestRule.waitForIdle()
        Thread.sleep(SCREENCAP_SETTLE_MS)
        val reply = composeTestRule.onNodeWithTag(CHAT_REPLY_TAG).fetchSemanticsNode().boundsInWindow
        val ime = checkNotNull(imeTop())
        assertTrue("reply bottom ${reply.bottom} px is over the keyboard top $ime px", reply.bottom <= ime)
        screencap("chat-keyboard")
    }

    /** The keyboard's top edge in the chat dialog's window, in pixels; null while it is hidden. */
    private fun imeTop(): Int? {
        val view = chatView?.rootView ?: return null
        val insets = ViewCompat.getRootWindowInsets(view) ?: return null
        val bottom = insets.getInsets(WindowInsetsCompat.Type.ime()).bottom
        return if (bottom > 0) view.height - bottom else null
    }

    /** The status bar's height on this device, in pixels. */
    private fun statusBarTop(): Int {
        val insets = checkNotNull(ViewCompat.getRootWindowInsets(composeTestRule.activity.window.decorView))
        return insets.getInsets(WindowInsetsCompat.Type.statusBars()).top
    }

    private fun assertBelowStatusBar(text: String) {
        composeTestRule.waitForIdle()
        val top = composeTestRule.onNodeWithText(text).fetchSemanticsNode().positionOnScreen.y
        val bar = statusBarTop()
        assertTrue("status bar $bar px", bar > 0)
        assertTrue("$text top $top px is under the status bar ($bar px)", top >= bar)
    }

    @Test
    fun inTheDialog_titleBelowTheStatusBar() {
        show(HerdrAgentStatus.WORKING, inDialog = true)
        assertBelowStatusBar("claude · Demo / app")
        screencap("chat-dialog")
    }

    /** Diff, Files, Preview, Herd and the reader open this way: the dialog window fits the bars itself. */
    @Test
    fun fullScreenDialog_fitsTheBars() {
        composeTestRule.setContent {
            ConnectBotTheme {
                Dialog(onDismissRequest = {}, properties = DialogProperties(usePlatformDefaultWidth = false)) {
                    Surface(Modifier.fillMaxSize()) { Text("Top of the dialog") }
                }
            }
        }
        assertBelowStatusBar("Top of the dialog")
        screencap("chat-full-screen-dialog")
    }

    private companion object {
        const val SCREENCAP_SETTLE_MS = 1500L
        const val KEYBOARD_TIMEOUT_MS = 10_000L

        // A synthetic Claude Code permission prompt, as the pane's last lines show it.
        val PERMISSION_SCREEN = """
            Bash command
              ./gradlew testDebugUnitTest
              Run the unit tests
            Do you want to proceed?
            ❯ 1. Yes
              2. Yes, and don't ask again for ./gradlew commands
              3. No, and tell Claude what to do differently (esc)
        """.trimIndent()
    }
}
