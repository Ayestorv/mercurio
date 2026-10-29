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

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.connectbot.data.entity.Host
import org.connectbot.transport.HeadlessSsh
import solutions.ormus.logos.herd.Herd
import solutions.ormus.logos.herd.HerdSession
import solutions.ormus.logos.herd.HerdrAction
import solutions.ormus.logos.herd.HerdrCommands
import solutions.ormus.logos.herd.HerdrSessionSnapshot
import solutions.ormus.logos.herd.HerdrSessions
import solutions.ormus.logos.herd.parseHerdrSnapshot
import timber.log.Timber
import java.security.KeyPair

/**
 * Every Herdr host's workspaces and agents at once, for the second screen's sidebar.
 * A host with a live session is read through it; any other is read over a background
 * login with its saved key ([HeadlessSsh], no terminal, nothing typed anywhere) that
 * stays open while someone [watch]es. A host that cannot be reached is tried again
 * after [RETRY_MS], keeping its last state.
 */
class HerdFleet(
    private val scope: CoroutineScope,
    private val io: CoroutineDispatcher,
    private val hosts: suspend () -> List<Host>,
    private val live: (hostId: Long) -> TerminalBridge?,
    private val connect: (Host) -> HeadlessSsh,
    private val keys: suspend (Host) -> List<KeyPair>,
) {
    /** One host's state: its default session's [snapshot], or why there is none ([problem]). */
    data class Member(val host: Host, val snapshot: HerdrSessionSnapshot?, val problem: String?, val live: Boolean)

    private val _members = MutableStateFlow<List<Member>>(emptyList())

    /** Every Herdr host, in the host list's order. */
    val members: StateFlow<List<Member>> = _members.asStateFlow()

    private val logins = mutableMapOf<Long, HeadlessSsh>()
    private val failedAt = mutableMapOf<Long, Long>()
    private var job: Job? = null
    private var watchers = 0

    /** Poll while the returned stop has not run (main thread). */
    fun watch(): () -> Unit {
        watchers++
        if (job == null) job = scope.launch(io) { run() }
        var stopped = false
        return {
            if (!stopped) {
                stopped = true
                watchers--
                if (watchers <= 0) {
                    watchers = 0
                    job?.cancel()
                    job = null
                }
            }
        }
    }

    /**
     * Run [action] (focus a workspace or an agent's pane) on [host]'s Herdr: through its live
     * session when there is one, else over the background login. Returns why it failed, or null.
     */
    suspend fun perform(host: Host, action: HerdrAction): String? = withContext(io) {
        live(host.id)?.herd?.let {
            it.perform(action)
            return@withContext null
        }
        val ssh = login(host) ?: return@withContext "Cannot log in to ${host.nickname}"
        HerdSession(host.nickname, { runCatching { ssh.exec(it) }.getOrNull() }, sendKeys = {}, scope, io).perform(action)
        null
    }

    private suspend fun run() {
        try {
            while (scope.isActive) {
                val list = hosts().filter { Herd.runsHerdr(it) }
                val previous = _members.value.associateBy { it.host.id }
                _members.value = coroutineScope {
                    list.map { host -> async { read(host, previous[host.id]) } }.awaitAll()
                }
                delay(POLL_MS)
            }
        } finally {
            synchronized(logins) {
                logins.values.forEach { runCatching { it.close() } }
                logins.clear()
            }
        }
    }

    private suspend fun read(host: Host, last: Member?): Member {
        live(host.id)?.herd?.let { herd ->
            val session = herd.attached.value
            herd.refresh(session)
            return Member(host, herd.snapshotOf(session) ?: last?.snapshot, null, live = true)
        }
        val ssh = login(host) ?: return Member(host, last?.snapshot, last?.problem ?: "Not reachable", live = false)
        return try {
            val out = ssh.exec(HerdrSessions.snapshotCommand(HerdrCommands.DEFAULT_SESSION))
            val snapshot = if (out.exitCode == null || out.exitCode == 0) parseHerdrSnapshot(out.stdout) else null
            Member(host, snapshot, if (snapshot == null) "Herdr is not running" else null, live = false)
        } catch (e: Exception) {
            Timber.w(e, "Fleet read failed on %s", host.nickname)
            synchronized(logins) { logins.remove(host.id) }?.let { runCatching { it.close() } }
            Member(host, last?.snapshot, e.message ?: "Read failed", live = false)
        }
    }

    // The open background login for [host], logging in when there is none (not more often than RETRY_MS).
    private suspend fun login(host: Host): HeadlessSsh? {
        synchronized(logins) { logins[host.id] }?.let { return it }
        val now = System.currentTimeMillis()
        if (now - (failedAt[host.id] ?: 0L) < RETRY_MS) return null
        val ssh = connect(host)
        return when (val result = runCatching { ssh.login(keys(host)) }.getOrElse { HeadlessSsh.Login.Failed(it.message ?: "") }) {
            HeadlessSsh.Login.Ok -> {
                failedAt.remove(host.id)
                synchronized(logins) { logins[host.id] = ssh }
                ssh
            }

            else -> {
                Timber.w("Fleet login to %s failed: %s", host.nickname, result)
                failedAt[host.id] = now
                runCatching { ssh.close() }
                null
            }
        }
    }

    companion object {
        const val POLL_MS = 3_000L
        const val RETRY_MS = 30_000L
    }
}
