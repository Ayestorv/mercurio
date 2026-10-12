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

package solutions.ormus.logos.herd

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * The Herdr sessions on a host. Each session is its own server with its own
 * workspaces, and its pane ids repeat the other sessions' ("w1:p1" in each), so
 * a pane is only known by its session and its id together.
 *
 * Any Herdr command reaches a session other than the default with `--session <name>`
 * ahead of the subcommand (measured on Sun, herdr 0.9.0: `herdr --session <name> api
 * snapshot` returns that session's snapshot, and the flag wins over $HERDR_SOCKET_PATH).
 */
object HerdrSessions {
    /** Every session Herdr knows on the host, running or not, as JSON. */
    val listCommand: String = HerdrApi.loginShell("herdr session list --json")

    // Herdr session names; anything else is refused before it reaches a shell.
    private val NAME = Regex("[A-Za-z0-9_.:-]+")

    private val json = Json { ignoreUnknownKeys = true }

    @Serializable
    private data class Entry(val name: String = "", val running: Boolean = false)

    @Serializable
    private data class Listing(val sessions: List<Entry> = emptyList())

    fun isDefault(session: String): Boolean = session == HerdrCommands.DEFAULT_SESSION

    /**
     * The running sessions in `herdr session list --json` output, the default first and the
     * rest by name; null when it does not parse (an old Herdr, no Herdr).
     */
    fun parse(raw: String): List<String>? {
        val listing = runCatching { json.decodeFromString<Listing>(raw) }.getOrNull() ?: return null
        val running = listing.sessions.filter { it.running && NAME.matches(it.name) }.map { it.name }
        return running.sortedWith(compareBy<String> { !isDefault(it) }.thenBy { it })
    }

    /** The snapshot command for [session]: the default's is [Herd.snapshotCommand]. */
    fun snapshotCommand(session: String): String = if (isDefault(session)) {
        Herd.snapshotCommand
    } else {
        HerdrApi.loginShell("herdr --session ${quoted(session)} ${HerdrApi.SNAPSHOT_CMD.removePrefix("herdr ")}")
    }

    /** What a shell types to attach [session]: `herdr` for the default, `herdr session attach <name>` otherwise. */
    fun attachCommand(session: String): String = if (isDefault(session)) "herdr" else "herdr session attach ${quoted(session)}"

    /** The commands that act on [session]: the default's are the plain `herdr ...` ones. */
    fun commands(session: String): HerdrCommands = if (isDefault(session)) HerdrCommands() else HerdrCommands(session)

    /** [session] as one single-quoted shell word, after checking it is a Herdr name. */
    fun quoted(session: String): String {
        require(NAME.matches(session)) { "Unexpected Herdr session name: $session" }
        return HerdrApi.shQuote(session)
    }
}
