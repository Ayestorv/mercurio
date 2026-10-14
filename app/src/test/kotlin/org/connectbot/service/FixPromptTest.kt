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

package org.connectbot.service

import org.connectbot.data.TailscaleRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import solutions.ormus.logos.herd.HerdrFailure

/** Fix prompts: framed for an agent, carrying the evidence, never a secret. */
class FixPromptTest {

    private val privateKey = "-----BEGIN OPENSSH PRIVATE KEY-----\nb3BlbnNzaC1rZXktdjEAAAAA\n-----END OPENSSH PRIVATE KEY-----"

    @Test
    fun connection_framesTheReportForAnAgent() {
        val prompt = FixPrompt.connection("### Mercurio connection report\n- Host: lab")

        assertTrue(prompt.startsWith("Mercurio can't connect, and I need you to fix it."))
        assertTrue("says what Mercurio is", prompt.contains("Mercurio is my Android SSH client"))
        assertTrue("asks for the root cause and a fix", prompt.contains("Find the root cause"))
        assertTrue("never asks for secrets", prompt.contains("Do not ask me for passwords or private keys"))
        assertTrue(prompt.endsWith("### Mercurio connection report\n- Host: lab\n"))
    }

    @Test
    fun herdr_carriesTheCommandExitAndOutput_scrubbed() {
        val prompt = FixPrompt.herdr(
            host = "lab (bob@100.100.1.5:22)",
            appVersion = "v1.22.0",
            command = "bash -lc 'herdr api snapshot'",
            exitCode = 127,
            stderr = "bash: line 1: herdr: command not found\n$privateKey",
            stdout = "",
            reason = "`herdr` was not found on the host (exit 127).",
        )

        assertTrue(prompt.startsWith("Mercurio connects to lab (bob@100.100.1.5:22), but its Herdr side fails"))
        assertTrue(prompt.contains("- Command: `bash -lc 'herdr api snapshot'`"))
        assertTrue(prompt.contains("- Exit code: 127"))
        assertTrue(prompt.contains("herdr: command not found"))
        assertTrue(prompt.contains("- stdout: (empty)"))
        assertTrue(prompt.contains("Mercurio v1.22.0"))
        assertFalse("no key material", prompt.contains("b3BlbnNzaC1rZXktdjEAAAAA"))
    }

    @Test
    fun herdr_keepsTheEndOfLongOutput() {
        val prompt = FixPrompt.herdr("h", "v", "c", 1, "x".repeat(10_000) + "THE ERROR", null, "r")

        assertTrue(prompt.contains("THE ERROR"))
        assertTrue(prompt.length < 8_000)
    }

    @Test
    fun herdrReason_namesWhatWentWrong() {
        assertEquals("Herdr has not answered yet.", FixPrompt.herdrReason(null))
        assertTrue(FixPrompt.herdrReason(HerdrFailure("c", null, "", "", sessionUp = false)).startsWith("No SSH session is up"))
        assertEquals("`herdr` was not found on the host (exit 127).", FixPrompt.herdrReason(HerdrFailure("c", 127, "", "", true)))
        assertEquals(
            "A Herdr command exited 2: socket not found",
            FixPrompt.herdrReason(HerdrFailure("c", 2, "\nsocket not found\nmore", "", true)),
        )
    }

    @Test
    fun machines_namesTheHostAndTheError() {
        val prompt = FixPrompt.machines("Sun", "v1.22.0", "java.io.IOException: channel closed")

        assertTrue(prompt.startsWith("Mercurio can't load my Tailscale machine list"))
        assertTrue(prompt.contains("`tailscale status --json` over SSH on Sun"))
        assertTrue(prompt.contains("java.io.IOException: channel closed"))
    }

    @Test
    fun machines_fromTheApi_saysItWasTheApiNotSsh() {
        val prompt = FixPrompt.machines(TailscaleRepository.API_SOURCE, "v1.22.0", "java.io.IOException: Tailscale API device list answered HTTP 403")

        assertTrue(prompt.contains("from Tailscale's API (api.tailscale.com)"))
        assertFalse(prompt.contains("`tailscale status --json`"))
        assertTrue(prompt.contains("HTTP 403"))
    }
}
