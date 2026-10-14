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

import android.content.SharedPreferences
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import org.connectbot.data.entity.Host
import org.connectbot.di.CoroutineDispatchers
import org.connectbot.service.DiagnosticsReport
import org.connectbot.service.TerminalBridge
import org.connectbot.transport.SSH
import org.json.JSONException
import timber.log.Timber
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton

/** The last known list of tailnet machines and where it came from. */
data class TailnetMachines(
    val devices: List<TailscaleDevice> = emptyList(),
    /** Epoch millis of the last successful refresh; 0 when never fetched. */
    val updatedAt: Long = 0L,
    /** Username of the host the list was read from, used as the default login. */
    val sourceUsername: String = "",
)

/**
 * Lists the machines on the user's Tailscale network without any Tailscale
 * credentials: it runs `tailscale status --json` over an SSH session Mercurio
 * already has open to a tailnet member. With no session open and a Tailscale
 * OAuth client secret saved, it reads Tailscale's API instead ([TailscaleApi]).
 * The last good answer is cached in SharedPreferences so the list is there
 * before any connection exists.
 */
@Singleton
class TailscaleRepository @Inject constructor(
    private val prefs: SharedPreferences,
    private val hostRepository: HostRepository,
    private val dispatchers: CoroutineDispatchers,
    private val api: TailscaleApi,
) {
    private val _machines = MutableStateFlow(TailnetMachines())
    val machines: StateFlow<TailnetMachines> = _machines.asStateFlow()
    private val cacheLoaded = AtomicBoolean(false)

    /** Publish the cached list (read and parsed off the main thread) once. */
    suspend fun loadCache() {
        if (!cacheLoaded.compareAndSet(false, true)) return
        val cached = withContext(dispatchers.io) { loadCached() }
        // A refresh that finished first is newer than the cache.
        _machines.compareAndSet(TailnetMachines(), cached)
    }

    /**
     * Re-read the tailnet over a connected SSH session, preferring [preferred]
     * (the session on screen), else over Tailscale's API when a secret is
     * saved. Returns false when neither is there or the answer failed; the
     * cached list is kept then.
     */
    suspend fun refresh(bridges: List<TerminalBridge>, preferred: TerminalBridge?): Boolean {
        val source = pickSource(bridges, preferred)
        val ssh = source?.transport as? SSH
        if (source == null || ssh == null) {
            if (hasApiSecret()) return refreshFromApi()
            lastError = RefreshError(source?.host?.nickname, NO_SESSION)
            return false
        }
        return withContext(dispatchers.io) {
            try {
                applyStatus(ssh.exec(TailscaleStatus.COMMAND, EXEC_TIMEOUT_MS), source.host.username)
                lastError = null
                true
            } catch (e: IOException) {
                Timber.w(e, "tailscale status failed on ${source.host.nickname}")
                lastError = RefreshError(source.host.nickname, DiagnosticsReport.describeFailure(e))
                false
            } catch (e: IllegalStateException) {
                Timber.w(e, "tailscale status failed on ${source.host.nickname}")
                lastError = RefreshError(source.host.nickname, DiagnosticsReport.describeFailure(e))
                false
            } catch (e: JSONException) {
                Timber.w(e, "tailscale status returned unreadable JSON")
                lastError = RefreshError(source.host.nickname, "tailscale status returned unreadable JSON: " + DiagnosticsReport.describeFailure(e))
                false
            }
        }
    }

    /** Re-read the tailnet over Tailscale's API. */
    suspend fun refreshFromApi(): Boolean = withContext(dispatchers.io) {
        try {
            applyApiDevices(api.fetchDevices())
            lastError = null
            true
        } catch (e: IOException) {
            Timber.w(e, "Tailscale API device list failed")
            lastError = RefreshError(API_SOURCE, DiagnosticsReport.describeFailure(e))
            false
        } catch (e: JSONException) {
            Timber.w(e, "Tailscale API returned an unreadable device list")
            lastError = RefreshError(API_SOURCE, "the device list was unreadable JSON: " + DiagnosticsReport.describeFailure(e))
            false
        }
    }

    /** True when a refresh has somewhere to read from: a connected SSH session or a saved API secret. */
    suspend fun canRefresh(bridges: List<TerminalBridge>, preferred: TerminalBridge?): Boolean = pickSource(bridges, preferred) != null || hasApiSecret()

    /** True when a Tailscale OAuth client secret is saved. */
    suspend fun hasApiSecret(): Boolean = withContext(dispatchers.io) { api.isConfigured() }

    /** Save the Tailscale OAuth client secret in the Keystore; blank clears it. */
    suspend fun saveApiSecret(secret: String) = withContext(dispatchers.io) { api.setSecret(secret) }

    /** Why the last refresh failed, for a fix prompt: the host it ran on and the error. */
    data class RefreshError(val source: String?, val message: String)

    /** The last refresh's failure, null after a refresh that worked. */
    @Volatile
    var lastError: RefreshError? = null
        private set

    /**
     * Parse a status document, publish it and cache it. Parsing happens before
     * anything is written, so a bad answer never replaces a good cache.
     *
     * @throws JSONException when [json] is not a status document.
     */
    fun applyStatus(json: String, sourceUsername: String, now: Long = System.currentTimeMillis()) {
        publish(TailscaleStatus.parse(json), json, FORMAT_STATUS, sourceUsername, now)
    }

    /**
     * Parse an API device list, publish it and cache it. A bad answer never
     * replaces a good cache.
     *
     * @throws JSONException when [json] is not a device list.
     */
    fun applyApiDevices(json: String, now: Long = System.currentTimeMillis()) {
        publish(TailscaleStatus.parseApiDevices(json), json, FORMAT_API, "", now)
    }

    private fun publish(devices: List<TailscaleDevice>, json: String, format: String, sourceUsername: String, now: Long) {
        prefs.edit {
            putString(PREF_JSON, json)
            putString(PREF_FORMAT, format)
            putLong(PREF_UPDATED, now)
            putString(PREF_USERNAME, sourceUsername)
        }
        _machines.value = TailnetMachines(devices, now, sourceUsername)
    }

    /** The "Machine usernames" setting, one `name=user` line per machine. */
    var machineUsernames: String
        get() = prefs.getString(MachineUsers.PREF_KEY, null).orEmpty()
        set(value) = prefs.edit { putString(MachineUsers.PREF_KEY, value.trim()) }

    /** The SSH user "Machine usernames" sets for [device], so a first tap needs no prompt. */
    fun knownUsername(device: TailscaleDevice): String? = MachineUsers.forDevice(machineUsernames, device)

    /** A saved SSH host that already points at [device], if any. */
    suspend fun findSavedHost(device: TailscaleDevice): Host? = hostRepository.getHosts().firstOrNull { device.matches(it) }

    /**
     * Save a new SSH host for [device] (nickname = its hostname, hostname = its
     * Tailscale IPv4, port 22) and return it with its new id. Returns null
     * when the device has no address to connect to or [username] is blank.
     */
    suspend fun saveHostFor(device: TailscaleDevice, username: String): Host? {
        val address = device.ipv4 ?: device.dnsName.takeIf { it.isNotBlank() } ?: return null
        if (username.isBlank()) return null
        val host = Host(
            nickname = device.hostName.ifBlank { device.shortName },
            protocol = "ssh",
            username = username.trim(),
            hostname = address,
            port = 22,
            lastConnect = System.currentTimeMillis(),
        )
        return hostRepository.saveHost(host)
    }

    private fun loadCached(): TailnetMachines {
        val json = prefs.getString(PREF_JSON, null) ?: return TailnetMachines()
        return try {
            TailnetMachines(
                devices = if (prefs.getString(PREF_FORMAT, null) == FORMAT_API) {
                    TailscaleStatus.parseApiDevices(json)
                } else {
                    TailscaleStatus.parse(json)
                },
                updatedAt = prefs.getLong(PREF_UPDATED, 0L),
                sourceUsername = prefs.getString(PREF_USERNAME, null).orEmpty(),
            )
        } catch (e: JSONException) {
            Timber.w(e, "Dropping unreadable cached tailscale status")
            TailnetMachines()
        }
    }

    companion object {
        /** The refresh had no connected SSH session to run `tailscale status` on. */
        const val NO_SESSION = "No SSH session is connected, so there was no host to run `tailscale status` on."

        /** The refresh source a fix prompt names when the API was asked. */
        const val API_SOURCE = "api.tailscale.com"

        private const val PREF_JSON = "tailscaleStatusJson"
        private const val PREF_FORMAT = "tailscaleStatusFormat"
        private const val FORMAT_STATUS = "status"
        private const val FORMAT_API = "api"
        private const val PREF_UPDATED = "tailscaleStatusUpdated"
        private const val PREF_USERNAME = "tailscaleStatusUsername"
        private const val EXEC_TIMEOUT_MS = 10_000L

        /**
         * The SSH session to ask: [preferred] when it is a connected SSH
         * session, else any connected SSH session.
         */
        fun pickSource(bridges: List<TerminalBridge>, preferred: TerminalBridge?): TerminalBridge? {
            fun usable(bridge: TerminalBridge) = bridge.transport is SSH && bridge.isSessionOpen && !bridge.isDisconnected
            return preferred?.takeIf { usable(it) } ?: bridges.firstOrNull { usable(it) }
        }
    }
}
