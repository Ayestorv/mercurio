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

package org.connectbot.ui.machines

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.launch
import org.connectbot.BuildConfig
import org.connectbot.data.TailnetMachines
import org.connectbot.data.TailscaleDevice
import org.connectbot.data.TailscaleRepository
import org.connectbot.data.entity.Host
import org.connectbot.service.FixPrompt
import org.connectbot.service.TerminalBridge
import org.connectbot.service.TerminalManager
import javax.inject.Inject

/**
 * Backs the Machines list (session drawer and host list): the cached tailnet
 * machines, on-demand refresh over a live SSH session or Tailscale's API, and resolving a tapped
 * machine to a saved host that the existing console route can open.
 */
@HiltViewModel
class MachinesViewModel @Inject constructor(
    private val repository: TailscaleRepository,
) : ViewModel() {
    private var terminalManager: TerminalManager? = null

    val machines: StateFlow<TailnetMachines> = repository.machines

    private val _refreshing = MutableStateFlow(false)
    val refreshing: StateFlow<Boolean> = _refreshing.asStateFlow()

    private val _refreshFailed = MutableStateFlow(false)
    val refreshFailed: StateFlow<Boolean> = _refreshFailed.asStateFlow()

    /** A fix prompt for the last failed refresh, to paste into an agent; null when it worked. */
    fun fixPrompt(): String? = repository.lastError?.let {
        FixPrompt.machines(it.source, BuildConfig.VERSION_NAME, it.message)
    }

    init {
        viewModelScope.launch { repository.loadCache() }
    }

    fun setTerminalManager(manager: TerminalManager) {
        if (terminalManager == manager) return
        terminalManager = manager
        // Until a first list exists, fetch it now over the API when a secret is
        // saved, or as soon as any SSH session is up, so the list fills on its own.
        viewModelScope.launch {
            repository.loadCache()
            merge(flowOf(Unit), manager.hostStatusChangedFlow).collect {
                if (machines.value.updatedAt == 0L && !_refreshing.value) {
                    runRefresh(preferred = null, reportFailure = false)
                }
            }
        }
    }

    /** Re-read the tailnet, asking [preferred] (the session on screen) first. */
    fun refresh(preferred: TerminalBridge? = null) {
        if (_refreshing.value) return
        viewModelScope.launch { runRefresh(preferred, reportFailure = true) }
    }

    private suspend fun runRefresh(preferred: TerminalBridge?, reportFailure: Boolean) {
        val bridges = terminalManager?.bridgesFlow?.value.orEmpty()
        if (!repository.canRefresh(bridges, preferred)) {
            // Let the repository record why, so the fix prompt can say so.
            repository.refresh(bridges, preferred)
            if (reportFailure) _refreshFailed.value = true
            return
        }
        _refreshing.value = true
        try {
            val ok = repository.refresh(bridges, preferred)
            _refreshFailed.value = reportFailure && !ok
        } finally {
            _refreshing.value = false
        }
    }

    private val _apiSecretSaved = MutableStateFlow(false)

    /** True when a Tailscale OAuth client secret is saved. */
    val apiSecretSaved: StateFlow<Boolean> = _apiSecretSaved.asStateFlow()

    init {
        viewModelScope.launch { _apiSecretSaved.value = repository.hasApiSecret() }
    }

    /** Save the Tailscale OAuth client secret (blank clears it) and read the list with it. */
    fun saveApiSecret(secret: String) {
        viewModelScope.launch {
            repository.saveApiSecret(secret)
            _apiSecretSaved.value = repository.hasApiSecret()
            if (_apiSecretSaved.value && !_refreshing.value) runRefresh(preferred = null, reportFailure = true)
        }
    }

    /** The saved host already pointing at [device], or null if none matches. */
    suspend fun findSavedHost(device: TailscaleDevice): Host? = repository.findSavedHost(device)

    /** The SSH user set for [device] in "Machine usernames", or null. */
    fun knownUsername(device: TailscaleDevice): String? = repository.knownUsername(device)

    var machineUsernames: String
        get() = repository.machineUsernames
        set(value) {
            repository.machineUsernames = value
        }

    /** Save a new host for [device] logging in as [username]; null if it can't. */
    suspend fun saveHostFor(device: TailscaleDevice, username: String): Host? = repository.saveHostFor(device, username)
}
