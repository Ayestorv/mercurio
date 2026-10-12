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

package org.connectbot.transport

import com.trilead.ssh2.Connection
import com.trilead.ssh2.InteractiveCallback
import org.assertj.core.api.Assertions.assertThat
import org.connectbot.data.entity.Host
import org.connectbot.service.PromptManager
import org.connectbot.service.PromptRequest
import org.connectbot.service.PromptResponse
import org.connectbot.service.TerminalBridge
import org.connectbot.service.TerminalManager
import org.connectbot.usage.UsageActions
import org.connectbot.util.HostConstants
import org.connectbot.util.SecurePasswordStorage
import org.junit.Test
import org.mockito.ArgumentMatchers.any
import org.mockito.ArgumentMatchers.anyLong
import org.mockito.ArgumentMatchers.anyString
import org.mockito.ArgumentMatchers.eq
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.concurrent.thread

/**
 * A password typed at the login prompt is stored for the host only after the
 * server accepted it, and only with "Remember password" left ticked.
 */
class SSHRememberPasswordTest {
    private val promptManager = PromptManager()
    private val terminalBridge = mock(TerminalBridge::class.java).also {
        `when`(it.promptManager).thenReturn(promptManager)
    }
    private val storage = mock(SecurePasswordStorage::class.java)
    private val terminalManager = mock(TerminalManager::class.java).also {
        `when`(it.securePasswordStorage).thenReturn(storage)
    }
    private val connection = mock(Connection::class.java)

    /** Every prompt shown, in order. */
    private val shown = CopyOnWriteArrayList<PromptRequest.StringPrompt>()

    // Named apart from SSH's own bridge and manager, which apply {} would resolve first.
    private fun ssh(hostId: Long) = SSH().apply {
        setHost(
            Host(
                id = hostId,
                nickname = "target",
                username = "alice",
                hostname = "example.com",
                pubkeyId = HostConstants.PUBKEYID_NEVER,
                wantSession = false,
            ),
        )
        setBridge(terminalBridge)
        setManager(terminalManager)
        setConnectionForTesting(connection)
    }

    /** Answer the next [count] string prompts with [answer] and the box state [remember]. */
    private fun answerPrompts(count: Int, answer: String, remember: Boolean): Thread = thread(isDaemon = true) {
        repeat(count) {
            var request: PromptRequest? = null
            while (request == null) {
                request = promptManager.promptState.value
                if (request == null) Thread.sleep(5)
            }
            shown += request as PromptRequest.StringPrompt
            promptManager.respond(PromptResponse.StringResponse(answer, remember))
            while (promptManager.promptState.value === request) Thread.sleep(5)
        }
    }

    private fun passwordOnly(accepts: Boolean) {
        `when`(connection.isAuthMethodAvailable("alice", "password")).thenReturn(true)
        `when`(connection.authenticateWithPassword("alice", "s3cret")).thenReturn(accepts)
    }

    private fun runAuth(ssh: SSH, answerer: Thread) {
        ssh.authenticate()
        answerer.join(5_000)
    }

    @Test
    fun passwordAccepted_ticked_isSaved() {
        passwordOnly(accepts = true)
        runAuth(ssh(42L), answerPrompts(1, "s3cret", remember = true))

        assertThat(shown.single().offerRemember).isTrue()
        verify(storage).savePassword(42L, "s3cret")
        verify(terminalManager).logUsage(UsageActions.AUTH_PASSWORD_SAVED)
    }

    @Test
    fun passwordRejected_isNotSaved() {
        passwordOnly(accepts = false)
        runAuth(ssh(42L), answerPrompts(1, "s3cret", remember = true))

        verify(storage, never()).savePassword(anyLong(), anyString())
    }

    @Test
    fun passwordAccepted_unticked_isNotSaved() {
        passwordOnly(accepts = true)
        runAuth(ssh(42L), answerPrompts(1, "s3cret", remember = false))

        verify(storage, never()).savePassword(anyLong(), anyString())
    }

    @Test
    fun temporaryHost_noBox_nothingSaved() {
        passwordOnly(accepts = true)
        runAuth(ssh(-1L), answerPrompts(1, "s3cret", remember = true))

        assertThat(shown.single().offerRemember).isFalse()
        verify(storage, never()).savePassword(anyLong(), anyString())
    }

    @Test
    fun savedPasswordRejected_typedOneAccepted_replacesIt() {
        passwordOnly(accepts = true)
        `when`(storage.getPassword(42L)).thenReturn("old")
        `when`(connection.authenticateWithPassword("alice", "old")).thenReturn(false)
        runAuth(ssh(42L), answerPrompts(1, "s3cret", remember = true))

        verify(storage).savePassword(42L, "s3cret")
    }

    private fun keyboardInteractive(prompt: String, accepts: Boolean) {
        `when`(connection.isAuthMethodAvailable("alice", "keyboard-interactive")).thenReturn(true)
        `when`(connection.authenticateWithKeyboardInteractive(eq("alice"), any(InteractiveCallback::class.java)))
            .thenAnswer { call ->
                val callback = call.getArgument<InteractiveCallback>(1)
                callback.replyToChallenge("", "", 1, arrayOf(prompt), booleanArrayOf(false))
                accepts
            }
    }

    @Test
    fun keyboardInteractivePassword_accepted_isSaved() {
        keyboardInteractive("Password: ", accepts = true)
        runAuth(ssh(42L), answerPrompts(1, "s3cret", remember = true))

        assertThat(shown.single().offerRemember).isTrue()
        verify(storage).savePassword(42L, "s3cret")
    }

    @Test
    fun keyboardInteractivePassword_rejected_isNotSaved() {
        keyboardInteractive("Password: ", accepts = false)
        runAuth(ssh(42L), answerPrompts(1, "s3cret", remember = true))

        verify(storage, never()).savePassword(anyLong(), anyString())
    }

    @Test
    fun keyboardInteractiveOtp_noBox_nothingSaved() {
        keyboardInteractive("Verification code: ", accepts = true)
        runAuth(ssh(42L), answerPrompts(1, "123456", remember = true))

        assertThat(shown.single().offerRemember).isFalse()
        verify(storage, never()).savePassword(anyLong(), anyString())
    }
}
