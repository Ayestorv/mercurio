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
 * The SSH machines saved in the Herdr client on a host (`herdr machine add`). They live on
 * the client side (its state dir, client/endpoints.json), and choosing one in the client
 * does not retarget the CLI: every `herdr` command still reaches only the host's own
 * server and its workspaces. Herdr's workspace switcher (workspace_picker, then the
 * navigate keys, then Enter) walks the workspaces of every connected machine in sidebar
 * order (measured with herdr 0.9.3).
 */
object HerdrMachines {
    /** The saved machines, as JSON: `[{"label":..,"enabled":true,..}]`. */
    val listCommand: String = HerdrApi.loginShell("herdr machine list --json")

    private val json = Json { ignoreUnknownKeys = true }

    @Serializable
    private data class Entry(val enabled: Boolean = false)

    /**
     * Whether `herdr machine list --json` output lists an enabled machine (Herdr's switcher
     * skips the others). False when it does not parse: a Herdr without saved machines.
     */
    fun anyEnabled(raw: String): Boolean = runCatching { json.decodeFromString<List<Entry>>(raw) }.getOrNull().orEmpty().any { it.enabled }
}
