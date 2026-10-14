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
import solutions.ormus.logos.herd.HerdrFailure

/**
 * Turns a Mercurio failure into a prompt Ormus can paste straight into a coding agent:
 * what Mercurio is, what failed, what to do and not to do, then the evidence. Every
 * prompt carries only text already scrubbed of secrets (see [DiagnosticsReport.redact]);
 * it never holds passwords, passphrases or key material.
 */
object FixPrompt {
    private const val OUTPUT_MAX_CHARS = 4 * 1024

    private val ABOUT = """
        Mercurio is my Android SSH client (a ConnectBot fork, repo Ormus-Solutions/mercurio).
        It connects from my phone over Tailscale to my machines. Hosts whose post-login
        command is `herdr`, or where Herdr answers, also get a Herdr side: Mercurio runs
        `herdr` CLI commands over SSH exec channels beside the terminal.
    """.trimIndent()

    private val ASK = """
        Please:
        1. Find the root cause from the evidence below. Say what you checked.
        2. If the fix is on the host or the network (sshd, authorized_keys, Tailscale,
           firewall, Herdr install or config), make it there, or give me the exact commands.
        3. If the fix is a Mercurio setting on the phone (host entry, username, port, key,
           post-login command), tell me which screen and field to change, and to what.
        4. If it is a bug in Mercurio itself, say so and point at the likely code.
        Do not ask me for passwords or private keys; none are included, by design.
    """.trimIndent()

    /** A failed or dropped SSH connection, around the connection [report] (DiagnosticsReport). */
    fun connection(report: String): String = prompt(
        title = "Mercurio can't connect, and I need you to fix it.",
        what = "The connection failed or dropped. The report below comes from the phone.",
        evidence = report,
    )

    /**
     * The Herdr side on [host] (`user@hostname:port`, nickname first) failing: [command] is
     * what Mercurio ran, with its [exitCode] (null when it never ran) and output.
     */
    fun herdr(
        host: String,
        appVersion: String,
        command: String?,
        exitCode: Int?,
        stderr: String?,
        stdout: String?,
        reason: String,
    ): String = prompt(
        title = "Mercurio connects to $host, but its Herdr side fails, and I need you to fix it.",
        what = buildString {
            append(reason).append('\n')
            append("Mercurio ").append(appVersion)
            append(". Over SSH it expects `herdr api snapshot` to print the live session as JSON.")
        },
        evidence = buildString {
            append("### What Mercurio ran\n")
            append("- Command: ").append(command?.let { "`$it`" } ?: "(nothing ran: no SSH session)").append('\n')
            append("- Exit code: ").append(exitCode?.toString() ?: "none").append('\n')
            appendOutput("stderr", stderr)
            appendOutput("stdout", stdout)
        },
    )

    /** One line on why the Herdr side is not answering, for the panel and the prompt. */
    fun herdrReason(failure: HerdrFailure?): String = when {
        failure == null -> "Herdr has not answered yet."

        !failure.sessionUp -> "No SSH session is up, so Mercurio could not run any Herdr command."

        failure.exitCode == 127 -> "`herdr` was not found on the host (exit 127)."

        failure.exitCode != null ->
            "A Herdr command exited ${failure.exitCode}: " +
                (failure.stderr.lineSequence().firstOrNull { it.isNotBlank() }?.trim()?.take(160) ?: "no error output")

        else -> "A Herdr command did not finish: " + failure.stderr.take(160)
    }

    /** The Tailscale machine list failing to load through [source] (a host nickname). */
    fun machines(source: String?, appVersion: String, error: String): String = prompt(
        title = "Mercurio can't load my Tailscale machine list, and I need you to fix it.",
        what = buildString {
            append("Mercurio ").append(appVersion)
            if (source == TailscaleRepository.API_SOURCE) {
                append(" reads the list from Tailscale's API (").append(source).append(") with a saved OAuth client secret")
            } else {
                append(" reads the list by running ")
                append("`tailscale status --json` over SSH on ").append(source ?: "a connected host")
            }
            append(". It failed.")
        },
        evidence = "### Error\n```\n" + DiagnosticsReport.redact(error).take(OUTPUT_MAX_CHARS) + "\n```\n",
    )

    private fun prompt(title: String, what: String, evidence: String): String = buildString {
        append(title).append("\n\n")
        append(ABOUT).append("\n\n")
        append("What happened: ").append(what.trim()).append("\n\n")
        append(ASK).append("\n\n")
        append("## Evidence (from the phone, secrets removed)\n\n")
        append(evidence.trim()).append('\n')
    }

    private fun StringBuilder.appendOutput(name: String, text: String?) {
        val clean = text?.let { DiagnosticsReport.redact(it) }?.trim().orEmpty()
        append("- ").append(name).append(": ")
        if (clean.isEmpty()) {
            append("(empty)\n")
            return
        }
        // Keep the end: errors print last.
        val kept = if (clean.length > OUTPUT_MAX_CHARS) "…" + clean.takeLast(OUTPUT_MAX_CHARS) else clean
        append("\n```\n").append(kept).append("\n```\n")
    }
}
