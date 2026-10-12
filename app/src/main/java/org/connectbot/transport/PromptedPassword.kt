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

import org.connectbot.service.PasswordAnswer

/**
 * When a password typed at a login prompt is stored for the next connect. Only
 * after the server accepted it, only with "Remember password" ticked, and only
 * for a host saved in the database (quick-connect hosts carry a negative id).
 */
internal object PromptedPassword {

    /** A host with a stable database id; temporary hosts have id <= 0. */
    fun canRemember(hostId: Long): Boolean = hostId > 0L

    /**
     * The password to store after a login attempt, or null to store nothing.
     * [answers] are the remember-able prompts answered in that attempt; more than
     * one is ambiguous, so nothing is stored.
     */
    fun toSave(hostId: Long, answers: List<PasswordAnswer>, accepted: Boolean): String? {
        if (!accepted || !canRemember(hostId)) return null
        val answer = answers.singleOrNull() ?: return null
        return answer.password.takeIf { answer.remember && it.isNotEmpty() }
    }

    /**
     * True when a keyboard-interactive prompt asks for the account password
     * ("Password:", "alice@host's password:", "(alice@host) Password:"). Anything
     * else, such as a verification code or OTP, is never stored.
     */
    fun isAccountPasswordPrompt(prompt: String, echo: Boolean): Boolean {
        if (echo) return false
        val text = prompt.trim().lowercase()
            .replace(SOURCE_PREFIX, "")
            .trimEnd(':', ' ')
        return ACCOUNT_PASSWORD.matches(text)
    }

    private val SOURCE_PREFIX = Regex("^\\([^)]*\\)\\s*")
    private val ACCOUNT_PASSWORD = Regex("(?:\\S+'s )?password(?: for \\S+)?")
}
