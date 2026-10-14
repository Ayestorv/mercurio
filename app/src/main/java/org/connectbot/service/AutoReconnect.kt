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

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.connectbot.di.CoroutineDispatchers
import org.connectbot.transport.AbsTransport
import solutions.ormus.logos.herd.HerdrWorkspacePicker

/**
 * One reconnect with no tap when an open session's link drops ([DisconnectReason.IO_ERROR])
 * while the network is up and its console is on screen. A second drop within [WINDOW_MS] of
 * that reconnect gets the reconnect overlay as before, so a host that keeps failing never loops.
 */
internal class AutoReconnect(
    private val scope: CoroutineScope,
    private val dispatchers: CoroutineDispatchers,
    private val clock: () -> Long,
    private val reconnect: () -> Unit,
) {
    private var startedAt: Long? = null
    private var pending: Job? = null

    /** Whether the connection being opened was started here, not by a tap. */
    @Volatile
    var reconnecting = false
        private set

    /**
     * A session ended for [reason] on [transport]. Returns true when a reconnect starts after
     * [DELAY_MS]; false leaves the drop to the overlay.
     */
    @Synchronized
    fun onDrop(
        reason: DisconnectReason,
        transport: AbsTransport?,
        networkUp: Boolean,
        onScreen: Boolean,
        inGracePeriod: Boolean,
    ): Boolean {
        reconnecting = false
        if (reason != DisconnectReason.IO_ERROR || inGracePeriod || !networkUp || !onScreen) return false
        // A connect that never opened (refused, auth) is not a dropped link.
        if (transport?.isSessionOpen() != true || !transport.usesNetwork()) return false
        val now = clock()
        startedAt?.let { if (now - it < WINDOW_MS) return false }
        pending?.cancel()
        pending = scope.launch(dispatchers.default) {
            delay(DELAY_MS)
            synchronized(this@AutoReconnect) {
                startedAt = clock()
                reconnecting = true
            }
            reconnect()
        }
        return true
    }

    /** The user closed the session: a reconnect not yet started never starts. */
    @Synchronized
    fun cancel() {
        pending?.cancel()
        pending = null
    }

    companion object {
        const val DELAY_MS = 2_000L
        const val WINDOW_MS = 60_000L

        /**
         * The post-login an automatic reconnect sends: a Herdr launch attaches [attached],
         * the session the terminal had, as the connect picker's pick would, with no picker
         * to tap. Any other post-login goes as it is.
         */
        fun postLogin(postLogin: String?, attached: String?): String? {
            if (postLogin == null || attached == null || !HerdrWorkspacePicker.offers(postLogin)) return postLogin
            return HerdrWorkspacePicker.attachPostLogin(postLogin, attached)
        }
    }
}
