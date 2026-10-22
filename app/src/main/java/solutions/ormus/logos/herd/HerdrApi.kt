/*
 * Copied from Termish, https://github.com/farlume/termish (commit ee9e460),
 * composeApp/src/commonMain/kotlin/dev/termish/herdr/HerdrApi.kt.
 * Mercurio changes: login-shell snapshot candidate, checked pane read for the
 * reader, parseCommandError reads herdr 0.9.0 id-first errors and keeps the
 * stderr line; comments translated.
 *
 * MIT License
 *
 * Copyright (c) 2026 Termish Project Authors
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */

package solutions.ormus.logos.herd

import kotlinx.serialization.json.Json
import org.connectbot.transport.CommandOutput

/**
 * Herdr CLI command building and output parsing (control plane).
 *
 * Everything runs through SSH.execDetailed on the authenticated connection
 * (reuses the connection, never interrupts the shell). Behaviour measured on
 * herdr 0.8.0 (Termish) and 0.9.0 (Sun):
 * - `herdr api snapshot`: JSON with result.snapshot (see HerdrModels)
 * - `herdr pane read`: the terminal content as plain text, no JSON wrapper
 * - `herdr agent prompt`: success prints the CLI wrapper JSON; failure **exits
 *   non-zero (exit 1) with the error JSON on stderr** (such as
 *   `{"error":{"code":"agent_not_found",...},"id":"cli:agent:prompt"}`), so
 *   [parseCommandError] must look at the whole [CommandOutput] (stderr + exit
 *   code); reading only stdout would silently treat a failure as success.
 */
object HerdrApi {
    const val SNAPSHOT_CMD = "herdr api snapshot"
    private val json = Json { ignoreUnknownKeys = true }

    /** Mercurio: lines of recent pane output the reader asks for. */
    const val READ_LINES = 1000

    // Mercurio: pane ids look like "w1:p1" or "w1:pB"; anything else is refused
    // before it reaches a shell.
    private val PANE_ID = Regex("[A-Za-z0-9_.:-]+")

    /**
     * Herdr binary path candidates (sshd's non-interactive exec PATH often lacks
     * ~/.local/bin, /usr/local/bin, /opt/homebrew and other common install
     * locations, so on failure each is tried in turn). $HOME is expanded by the
     * remote shell (the standard way an ssh exec channel runs a command).
     *
     * Mercurio: the first candidate runs through a login shell, which is how Sun
     * finds herdr (installed in ~/.local/bin, added to PATH by the profile).
     */
    val BIN_CANDIDATES =
        listOf(
            "herdr",
            "\$HOME/.local/bin/herdr",
            "/usr/local/bin/herdr",
            "/opt/homebrew/bin/herdr",
        )

    /** Snapshot command candidates (Mercurio: login shell first, then [BIN_CANDIDATES]). */
    val SNAPSHOT_CMD_CANDIDATES = listOf(loginShell(SNAPSHOT_CMD)) + BIN_CANDIDATES.map { "$it api snapshot" }

    /**
     * Read a pane's recent output (the reader). The output is plain text.
     * Mercurio: soft wraps joined and up to [READ_LINES] lines; `pane read` works
     * on agent and plain shell panes alike; the pane id is checked and quoted.
     */
    fun paneReadCmd(
        paneId: String,
        lines: Int = READ_LINES,
    ): String {
        require(PANE_ID.matches(paneId)) { "Unexpected Herdr pane id: $paneId" }
        return "herdr pane read ${shQuote(paneId)} --source recent-unwrapped --lines $lines"
    }

    /** Submit a reply to an agent (approve from the phone). The text is shell single-quoted (exec runs through /bin/sh). */
    fun agentPromptCmd(
        paneId: String,
        text: String,
    ): String = "herdr agent prompt $paneId ${shQuote(text)}"

    /** Shell single-quote escaping: `'` -> `'\''` (the POSIX trick, prevents text injection). */
    fun shQuote(s: String): String = "'" + s.replace("'", "'\\''") + "'"

    /**
     * Mercurio: an exec channel gets a bare environment, so run through a login shell to find herdr.
     * On a Windows host [HostShell] takes the wrapper off again and requotes the words for cmd.exe.
     */
    fun loginShell(command: String): String = "bash -lc ${shQuote(command)}"

    /**
     * Parse a Herdr CLI error (`{"error":{...}}`) from a command result.
     *
     * Order of checks:
     * 1. stderr/stdout holds error JSON (Herdr writes the error JSON to stderr on failure, e.g. agent_not_found)
     * 2. a non-zero exit with no JSON -> a synthetic error (code = "exit_<n>", keeps the exit code)
     * 3. a connection-level failure (null output) -> a synthetic error (code = "command_failed"),
     *    so callers never mistake "the command never ran" for "submitted"
     * Success (exit 0 and no error JSON) returns null.
     */
    fun parseCommandError(out: CommandOutput?): HerdrApiError? {
        if (out == null) return HerdrApiError("command_failed", "ssh command failed or timed out")
        val raw =
            listOf(out.stderr, out.stdout)
                .firstOrNull { it.contains("\"error\"") }
                ?: ""
        if (raw.isNotEmpty()) {
            // Take only the error object (the CLI wrapper has a trailing id field; parse leniently).
            // Mercurio: herdr 0.9.0 also prints `{"id":...,"error":{...}}` (id first), so
            // fall back to the first brace, and ignore text that only mentions "error".
            val start = raw.indexOf("{\"error\"").takeIf { it >= 0 } ?: raw.indexOf('{')
            val parsed =
                if (start < 0) {
                    null
                } else {
                    runCatching {
                        json
                            .decodeFromString<HerdrCliResponse>(raw.substring(start).trim())
                            .error
                    }.getOrNull()
                }
            if (parsed != null) return parsed
        }
        val exit = out.exitCode
        if (exit != null && exit != 0) {
            // Mercurio: keep the first stderr line so the phone can say what went wrong.
            val detail = out.stderr.lineSequence().map { it.trim() }.firstOrNull { it.isNotEmpty() }
            return HerdrApiError("exit_$exit", detail ?: "command exited with code $exit")
        }
        return null
    }
}
