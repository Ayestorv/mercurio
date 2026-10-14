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

package org.connectbot.data

import org.connectbot.data.entity.Host
import org.json.JSONObject

/**
 * One machine on the user's tailnet, as reported by `tailscale status --json`
 * on a host Mercurio is already connected to.
 */
data class TailscaleDevice(
    /** The OS hostname the device reports (e.g. "Sun"). */
    val hostName: String,
    /** MagicDNS name without the trailing dot (e.g. "sun.tail1234.ts.net"). */
    val dnsName: String,
    /** First Tailscale IPv4 address, or null when the device has none. */
    val ipv4: String?,
    val online: Boolean,
    val os: String,
    /** Tailscale SSH is on: the device publishes SSH host keys. */
    val tailscaleSsh: Boolean = false,
    /** When Tailscale last saw it, ISO-8601, or null when it never recorded one. */
    val lastSeen: String? = null,
) {
    /** Phones are on the tailnet but run no SSH server worth listing first. */
    val isPhone: Boolean
        get() = os.lowercase() in PHONE_OS

    /** Display words for [os] ("linux" -> "Linux"), or the raw value. */
    val osLabel: String
        get() = OS_WORDS[os.lowercase()] ?: os

    /** MagicDNS short name ("sun"), falling back to the OS hostname. */
    val shortName: String
        get() = dnsName.substringBefore('.').ifBlank { hostName }

    /**
     * True when a saved SSH host already points at this device: its hostname is
     * the Tailscale IPv4, the MagicDNS short name or FQDN, or its nickname is
     * the device's hostname.
     */
    fun matches(host: Host): Boolean {
        if (host.protocol != "ssh") return false
        val target = host.hostname.trim().removeSuffix(".")
        val hostnameMatches = listOfNotNull(ipv4, shortName, dnsName)
            .any { it.isNotBlank() && it.equals(target, ignoreCase = true) }
        return hostnameMatches || host.nickname.equals(hostName, ignoreCase = true)
    }
}

private val PHONE_OS = setOf("ios", "android")
private val OS_WORDS = mapOf(
    "linux" to "Linux",
    "windows" to "Windows",
    "macos" to "macOS",
    "ios" to "iOS",
    "android" to "Android",
    "freebsd" to "FreeBSD",
)

/**
 * The "Machine usernames" setting: one `name=user` line per machine, so a first
 * tap on a listed machine needs no username prompt (the phone cannot read
 * ~/.ssh/config). A name is the MagicDNS short name or the OS hostname, any case.
 * Empty by default.
 */
object MachineUsers {
    const val PREF_KEY = "machineUsernames"

    fun parse(text: String): Map<String, String> = text.lineSequence()
        .mapNotNull { line -> line.split('=', limit = 2).takeIf { it.size == 2 } }
        .map { (name, user) -> name.trim().lowercase() to user.trim() }
        .filter { (name, user) -> name.isNotEmpty() && user.isNotEmpty() }
        .toMap()

    /** The user set for [device] in [text], or null. */
    fun forDevice(text: String, device: TailscaleDevice): String? {
        val users = parse(text)
        return users[device.shortName.lowercase()] ?: users[device.hostName.lowercase()]
    }
}

/**
 * Parser for `tailscale status --json`. Pure (no I/O) so the filtering rules
 * can be unit tested against a fixture.
 *
 * The backend check, the Mullvad exit-node filter, the Tailscale SSH flag and the
 * ordering (online by name, then offline by last seen, then phones) are ported
 * from Sotto's src/main/hosts/tailscale.ts (MIT, github.com/millZach/Sotto).
 */
object TailscaleStatus {
    /** Command run over SSH; a login shell so `tailscale` is on PATH. */
    const val COMMAND = "bash -lc 'tailscale status --json'"

    /** An exit node offered through Mullvad is on the tailnet but is nobody's machine. */
    private const val MULLVAD_SUFFIX = ".mullvad.ts.net"

    /** Tailscale writes the zero time for a device it has never seen go offline. */
    private const val ZERO_TIME_PREFIX = "0001-"

    /** LoginName Tailscale assigns to the owner of tagged (server) devices. */
    private const val TAGGED_DEVICES_LOGIN = "tagged-devices"

    /**
     * Returns the peers worth showing: tagged devices plus devices owned by the
     * tailnet's own login, hiding devices other people share into the tailnet.
     * Self is excluded. Sorted online first, then by name.
     *
     * @throws org.json.JSONException when [json] is not a status document.
     * @throws IllegalStateException when Tailscale on that host is stopped or signed out.
     */
    fun parse(json: String): List<TailscaleDevice> {
        val root = JSONObject(json)
        // A stopped or signed-out node still prints a status, but its peer list is stale.
        val backend = root.optString("BackendState")
        check(backend.isEmpty() || backend == "Running") { "Tailscale is $backend on that host" }
        val tailnetName = root.optJSONObject("CurrentTailnet")?.optString("Name").orEmpty()
        val users = root.optJSONObject("User")
        fun loginOf(userId: Long): String = users?.optJSONObject(userId.toString())?.optString("LoginName").orEmpty()

        // On an organization tailnet CurrentTailnet.Name is the domain, not a
        // login, so also treat the owner of this (Self) device as "mine".
        val selfUserId = root.optJSONObject("Self")?.optLong("UserID", -1L) ?: -1L

        val peers = root.optJSONObject("Peer") ?: return emptyList()
        val devices = mutableListOf<TailscaleDevice>()
        for (key in peers.keys()) {
            val peer = peers.optJSONObject(key) ?: continue
            val tags = peer.optJSONArray("Tags")
            val userId = peer.optLong("UserID", -1L)
            val owner = loginOf(userId)
            val keep = (tags != null && tags.length() > 0) ||
                owner == TAGGED_DEVICES_LOGIN ||
                (tailnetName.isNotEmpty() && owner == tailnetName) ||
                (selfUserId >= 0 && userId == selfUserId)
            if (!keep) continue
            val dnsName = peer.optString("DNSName").removeSuffix(".")
            if (dnsName.endsWith(MULLVAD_SUFFIX, ignoreCase = true)) continue

            val ips = peer.optJSONArray("TailscaleIPs")
            val ipv4 = ips?.let { arr -> (0 until arr.length()).map { arr.optString(it) } }
                .orEmpty()
                .firstOrNull { it.isNotBlank() && !it.contains(':') }
            devices += TailscaleDevice(
                hostName = peer.optString("HostName"),
                dnsName = dnsName,
                ipv4 = ipv4,
                online = peer.optBoolean("Online", false),
                os = peer.optString("OS"),
                tailscaleSsh = (peer.optJSONArray("sshHostKeys")?.length() ?: 0) > 0,
                lastSeen = peer.optString("LastSeen").takeIf { it.isNotBlank() && !it.startsWith(ZERO_TIME_PREFIX) },
            )
        }
        return ordered(devices)
    }

    /**
     * Returns the devices worth showing from Tailscale's API device list
     * (`GET /api/v2/tailnet/-/devices`): tagged devices plus the tailnet's own
     * devices, hiding devices other owners share in from their tailnets
     * (`isExternal`). Same order as [parse].
     *
     * @throws org.json.JSONException when [json] is not a device list.
     */
    fun parseApiDevices(json: String): List<TailscaleDevice> {
        val list = JSONObject(json).getJSONArray("devices")
        val devices = mutableListOf<TailscaleDevice>()
        for (i in 0 until list.length()) {
            val device = list.optJSONObject(i) ?: continue
            val tagged = (device.optJSONArray("tags")?.length() ?: 0) > 0
            if (!tagged && device.optBoolean("isExternal", false)) continue
            val dnsName = device.optString("name").removeSuffix(".")
            if (dnsName.endsWith(MULLVAD_SUFFIX, ignoreCase = true)) continue
            val addresses = device.optJSONArray("addresses")
            devices += TailscaleDevice(
                hostName = device.optString("hostname"),
                dnsName = dnsName,
                ipv4 = addresses?.let { arr -> (0 until arr.length()).map { arr.optString(it) } }
                    .orEmpty()
                    .firstOrNull { it.isNotBlank() && !it.contains(':') },
                online = device.optBoolean("connectedToControl", false),
                os = device.optString("os"),
                lastSeen = device.optString("lastSeen").takeIf { it.isNotBlank() && !it.startsWith(ZERO_TIME_PREFIX) },
            )
        }
        return ordered(devices)
    }

    private fun ordered(devices: List<TailscaleDevice>): List<TailscaleDevice> {
        val byName = compareBy(String.CASE_INSENSITIVE_ORDER) { d: TailscaleDevice -> d.shortName }
        val (phones, computers) = devices.partition { it.isPhone }
        val (online, offline) = computers.partition { it.online }
        return online.sortedWith(byName) +
            offline.sortedWith(compareByDescending<TailscaleDevice> { it.lastSeen.orEmpty() }.then(byName)) +
            phones.sortedWith(byName)
    }
}
