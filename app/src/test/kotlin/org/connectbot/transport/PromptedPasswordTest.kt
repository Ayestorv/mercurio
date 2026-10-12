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

import org.assertj.core.api.Assertions.assertThat
import org.connectbot.service.PasswordAnswer
import org.junit.Test

class PromptedPasswordTest {
    private val ticked = PasswordAnswer("s3cret", remember = true)

    @Test
    fun accepted_ticked_savedHost_isSaved() {
        assertThat(PromptedPassword.toSave(42L, listOf(ticked), accepted = true)).isEqualTo("s3cret")
    }

    @Test
    fun rejected_isNotSaved() {
        assertThat(PromptedPassword.toSave(42L, listOf(ticked), accepted = false)).isNull()
    }

    @Test
    fun unticked_isNotSaved() {
        val unticked = PasswordAnswer("s3cret", remember = false)
        assertThat(PromptedPassword.toSave(42L, listOf(unticked), accepted = true)).isNull()
    }

    @Test
    fun temporaryHost_isNotSaved() {
        assertThat(PromptedPassword.toSave(-1L, listOf(ticked), accepted = true)).isNull()
        assertThat(PromptedPassword.toSave(0L, listOf(ticked), accepted = true)).isNull()
        assertThat(PromptedPassword.canRemember(-3L)).isFalse()
    }

    @Test
    fun noAnswer_twoAnswers_orEmpty_isNotSaved() {
        assertThat(PromptedPassword.toSave(42L, emptyList(), accepted = true)).isNull()
        assertThat(PromptedPassword.toSave(42L, listOf(ticked, ticked), accepted = true)).isNull()
        assertThat(PromptedPassword.toSave(42L, listOf(PasswordAnswer("", true)), accepted = true)).isNull()
    }

    @Test
    fun accountPasswordPrompts_areSavable() {
        listOf(
            "Password:",
            "Password: ",
            "password",
            "alice@example.com's password: ",
            "(alice@example.com) Password: ",
            "Password for alice@example.com: ",
        ).forEach { prompt ->
            assertThat(PromptedPassword.isAccountPasswordPrompt(prompt, echo = false)).describedAs(prompt).isTrue()
        }
    }

    @Test
    fun codeAndOtpPrompts_areNeverSavable() {
        listOf(
            "Verification code: ",
            "OTP: ",
            "One-time password: ",
            "Passcode or option (1-3): ",
            "Enter PIN for 'token': ",
            "New password: ",
            "Current password: ",
            "Username: ",
        ).forEach { prompt ->
            assertThat(PromptedPassword.isAccountPasswordPrompt(prompt, echo = false)).describedAs(prompt).isFalse()
        }
    }

    @Test
    fun echoedPrompt_isNeverSavable() {
        assertThat(PromptedPassword.isAccountPasswordPrompt("Password: ", echo = true)).isFalse()
    }

    @Test
    fun answerToString_hidesThePassword() {
        assertThat(ticked.toString()).doesNotContain("s3cret")
    }
}
