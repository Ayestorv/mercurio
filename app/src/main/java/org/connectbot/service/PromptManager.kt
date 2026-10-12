/*
 * ConnectBot: simple, powerful, open-source SSH client for Android
 * Copyright 2025-2026 Kenny Root
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

package org.connectbot.service

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import solutions.ormus.logos.herd.WorkspaceChoice
import solutions.ormus.logos.herd.WorkspacePick

/**
 * Modern prompt manager using Kotlin coroutines instead of semaphores and blocking.
 * Manages prompts for password, host verification, etc.
 */
class PromptManager {
    private val _promptState = MutableStateFlow<PromptRequest?>(null)
    val promptState: StateFlow<PromptRequest?> = _promptState.asStateFlow()

    private var currentDeferred: CompletableDeferred<PromptResponse>? = null

    /**
     * Request a boolean prompt (yes/no dialog)
     */
    suspend fun requestBooleanPrompt(
        instructions: String?,
        message: String,
    ): Boolean {
        val deferred = CompletableDeferred<PromptResponse>()
        currentDeferred = deferred

        _promptState.update {
            PromptRequest.BooleanPrompt(
                instructions = instructions,
                message = message,
            )
        }

        val response = deferred.await()
        _promptState.update { null }

        return (response as? PromptResponse.BooleanResponse)?.value ?: false
    }

    /**
     * Request a string prompt (text input dialog)
     */
    suspend fun requestStringPrompt(
        instructions: String?,
        hint: String?,
        isPassword: Boolean = false,
    ): String? = askString(PromptRequest.StringPrompt(instructions, hint, isPassword))?.value

    /**
     * Ask for a host's login password. With [offerRemember] the prompt carries a
     * "Remember password" box, ticked to start. Null when the prompt was cancelled.
     */
    suspend fun requestPasswordPrompt(
        instructions: String?,
        hint: String?,
        offerRemember: Boolean,
    ): PasswordAnswer? {
        val response = askString(PromptRequest.StringPrompt(instructions, hint, isPassword = true, offerRemember))
        val password = response?.value ?: return null
        return PasswordAnswer(password, remember = offerRemember && response.remember)
    }

    private suspend fun askString(request: PromptRequest.StringPrompt): PromptResponse.StringResponse? {
        val deferred = CompletableDeferred<PromptResponse>()
        currentDeferred = deferred

        _promptState.update { request }

        val response = deferred.await()
        _promptState.update { null }

        return response as? PromptResponse.StringResponse
    }

    /**
     * Request biometric authentication for a key stored in Android Keystore
     */
    suspend fun requestBiometricAuth(
        keyNickname: String,
        keystoreAlias: String,
    ): Boolean {
        val deferred = CompletableDeferred<PromptResponse>()
        currentDeferred = deferred

        _promptState.update {
            PromptRequest.BiometricPrompt(
                keyNickname = keyNickname,
                keystoreAlias = keystoreAlias,
            )
        }

        val response = deferred.await()
        _promptState.update { null }

        return (response as? PromptResponse.BiometricResponse)?.success ?: false
    }

    /**
     * Request host key fingerprint verification prompt
     */
    suspend fun requestHostKeyFingerprintPrompt(
        hostname: String,
        keyType: String,
        keySize: Int,
        serverHostKey: ByteArray,
        randomArt: String,
        bubblebabble: String,
        sha256: String,
        md5: String,
    ): Boolean {
        val deferred = CompletableDeferred<PromptResponse>()
        currentDeferred = deferred

        _promptState.update {
            PromptRequest.HostKeyFingerprintPrompt(
                hostname = hostname,
                keyType = keyType,
                keySize = keySize,
                serverHostKey = serverHostKey,
                randomArt = randomArt,
                bubblebabble = bubblebabble,
                sha256 = sha256,
                md5 = md5,
            )
        }

        val response = deferred.await()
        _promptState.update { null }

        return (response as? PromptResponse.BooleanResponse)?.value ?: false
    }

    /**
     * Offer a Herdr host's workspaces before its post-login attaches. A cancelled
     * prompt (the session closed under it) reads as [WorkspacePick.Dismiss].
     */
    suspend fun requestHerdrWorkspace(choices: List<WorkspaceChoice>): WorkspacePick {
        val deferred = CompletableDeferred<PromptResponse>()
        currentDeferred = deferred

        _promptState.update { PromptRequest.HerdrWorkspacePrompt(choices) }

        val response = try {
            deferred.await()
        } catch (e: CancellationException) {
            currentCoroutineContext().ensureActive()
            null
        }
        _promptState.update { null }

        return (response as? PromptResponse.HerdrWorkspaceResponse)?.pick ?: WorkspacePick.Dismiss
    }

    /**
     * Respond to the current prompt
     */
    fun respond(response: PromptResponse) {
        currentDeferred?.complete(response)
        currentDeferred = null
    }

    /**
     * Cancel the current prompt
     */
    fun cancelPrompt() {
        currentDeferred?.cancel()
        currentDeferred = null
        _promptState.update { null }
    }
}

/**
 * Represents a prompt request
 */
sealed class PromptRequest {
    data class BooleanPrompt(
        val instructions: String?,
        val message: String,
    ) : PromptRequest()

    data class StringPrompt(
        val instructions: String?,
        val hint: String?,
        val isPassword: Boolean,
        /** Show the "Remember password" box (a saved host's login password only). */
        val offerRemember: Boolean = false,
    ) : PromptRequest()

    /** The connect-time Herdr workspace picker. */
    data class HerdrWorkspacePrompt(
        val choices: List<WorkspaceChoice>,
    ) : PromptRequest()

    data class BiometricPrompt(
        val keyNickname: String,
        val keystoreAlias: String,
    ) : PromptRequest()

    data class HostKeyFingerprintPrompt(
        val hostname: String,
        val keyType: String,
        val keySize: Int,
        val serverHostKey: ByteArray,
        val randomArt: String,
        val bubblebabble: String,
        val sha256: String,
        val md5: String,
    ) : PromptRequest() {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is HostKeyFingerprintPrompt) return false
            return hostname == other.hostname &&
                keyType == other.keyType &&
                keySize == other.keySize &&
                serverHostKey.contentEquals(other.serverHostKey) &&
                randomArt == other.randomArt &&
                bubblebabble == other.bubblebabble &&
                sha256 == other.sha256 &&
                md5 == other.md5
        }

        override fun hashCode(): Int {
            var result = hostname.hashCode()
            result = 31 * result + keyType.hashCode()
            result = 31 * result + keySize
            result = 31 * result + serverHostKey.contentHashCode()
            result = 31 * result + randomArt.hashCode()
            result = 31 * result + bubblebabble.hashCode()
            result = 31 * result + sha256.hashCode()
            result = 31 * result + md5.hashCode()
            return result
        }
    }
}

/**
 * Represents a prompt response
 */
sealed class PromptResponse {
    data class BooleanResponse(val value: Boolean) : PromptResponse()
    data class StringResponse(val value: String?, val remember: Boolean = false) : PromptResponse()
    data class BiometricResponse(val success: Boolean) : PromptResponse()
    data class HerdrWorkspaceResponse(val pick: WorkspacePick) : PromptResponse()
}

/** A password typed at a login prompt, and whether "Remember password" was ticked. */
class PasswordAnswer(val password: String, val remember: Boolean) {
    override fun toString(): String = "PasswordAnswer(remember=$remember)"
}
