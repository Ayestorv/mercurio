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

import android.net.Uri
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import org.assertj.core.api.Assertions.assertThat
import org.connectbot.data.entity.Host
import org.connectbot.di.CoroutineDispatchers
import org.connectbot.transport.AbsTransport
import org.connectbot.usage.UsageActions
import org.junit.Test

class AutoReconnectTest {
    private val dispatcher = StandardTestDispatcher()
    private val scope = TestScope(dispatcher)
    private val dispatchers = CoroutineDispatchers(dispatcher, dispatcher, dispatcher)
    private var reconnects = 0
    private val auto = AutoReconnect(scope, dispatchers, { scope.testScheduler.currentTime }) { reconnects++ }

    // A session that opened over the network, as SSH reports one after its shell came up.
    private class FakeTransport(private val open: Boolean = true) : AbsTransport() {
        override fun connect() = Unit
        override fun read(buffer: ByteArray, offset: Int, length: Int) = 0
        override fun write(buffer: ByteArray) = Unit
        override fun write(c: Int) = Unit
        override fun flush() = Unit
        override fun close() = Unit
        override fun setDimensions(columns: Int, rows: Int, width: Int, height: Int) = Unit
        override fun isConnected() = false
        override fun isSessionOpen() = open
        override fun getDefaultPort() = 22
        override fun getDefaultNickname(username: String?, hostname: String?, port: Int) = "test"
        override fun getSelectionArgs(uri: Uri, selection: MutableMap<String, String>) = Unit
        override fun createHost(uri: Uri) = Host()
        override fun usesNetwork() = true
        override fun getLocalIpAddress(): String? = null
    }

    private fun drop(
        reason: DisconnectReason = DisconnectReason.IO_ERROR,
        transport: AbsTransport = FakeTransport(),
        networkUp: Boolean = true,
        onScreen: Boolean = true,
        inGracePeriod: Boolean = false,
    ) = auto.onDrop(reason, transport, networkUp, onScreen, inGracePeriod)

    @Test
    fun ioErrorOnAnOpenSession_reconnectsOnceAfterTwoSeconds() {
        assertThat(drop()).isTrue()

        scope.advanceTimeBy(AutoReconnect.DELAY_MS - 1)
        scope.runCurrent()
        assertThat(reconnects).isEqualTo(0)

        scope.advanceTimeBy(1)
        scope.runCurrent()
        assertThat(reconnects).isEqualTo(1)
        assertThat(auto.reconnecting).isTrue()

        scope.advanceTimeBy(10 * AutoReconnect.WINDOW_MS)
        scope.runCurrent()
        assertThat(reconnects).isEqualTo(1)
    }

    @Test
    fun secondIoErrorWithinAMinuteOfTheReconnect_leavesItToTheOverlay() {
        drop()
        scope.advanceTimeBy(AutoReconnect.DELAY_MS)
        scope.runCurrent()

        scope.advanceTimeBy(AutoReconnect.WINDOW_MS - 1)
        assertThat(drop()).isFalse()
        assertThat(auto.reconnecting).isFalse()
        scope.advanceTimeBy(10 * AutoReconnect.WINDOW_MS)
        scope.runCurrent()
        assertThat(reconnects).isEqualTo(1)
    }

    @Test
    fun ioErrorAMinuteAfterTheReconnect_reconnectsAgain() {
        drop()
        scope.advanceTimeBy(AutoReconnect.DELAY_MS)
        scope.runCurrent()

        scope.advanceTimeBy(AutoReconnect.WINDOW_MS)
        assertThat(drop()).isTrue()
        scope.advanceTimeBy(AutoReconnect.DELAY_MS)
        scope.runCurrent()
        assertThat(reconnects).isEqualTo(2)
    }

    @Test
    fun userRequestedAndNetworkLostInGrace_neverReconnect() {
        assertThat(drop(reason = DisconnectReason.USER_REQUESTED)).isFalse()
        assertThat(drop(reason = DisconnectReason.NETWORK_LOST, inGracePeriod = true)).isFalse()
        assertThat(drop(reason = DisconnectReason.NETWORK_LOST)).isFalse()
        assertThat(drop(inGracePeriod = true)).isFalse()
        scope.advanceTimeBy(10 * AutoReconnect.WINDOW_MS)
        scope.runCurrent()
        assertThat(reconnects).isEqualTo(0)
    }

    @Test
    fun networkDownOffScreenOrNeverOpened_neverReconnect() {
        assertThat(drop(networkUp = false)).isFalse()
        assertThat(drop(onScreen = false)).isFalse()
        assertThat(drop(transport = FakeTransport(open = false))).isFalse()
        scope.advanceTimeBy(10 * AutoReconnect.WINDOW_MS)
        scope.runCurrent()
        assertThat(reconnects).isEqualTo(0)
    }

    @Test
    fun closedBeforeTheDelay_neverReconnects() {
        drop()
        auto.cancel()
        scope.advanceTimeBy(10 * AutoReconnect.WINDOW_MS)
        scope.runCurrent()
        assertThat(reconnects).isEqualTo(0)
    }

    @Test
    fun herdrHost_reattachesTheSessionTheTerminalHad() {
        assertThat(AutoReconnect.postLogin("herdr", "default")).isEqualTo("herdr")
        assertThat(AutoReconnect.postLogin("herdr", "work")).isEqualTo("herdr session attach 'work'")
        assertThat(AutoReconnect.postLogin("tmux attach", "work")).isEqualTo("tmux attach")
        assertThat(AutoReconnect.postLogin(null, "work")).isNull()
    }

    @Test
    fun autoReconnect_hasItsUsageId() {
        assertThat(UsageActions.SESSION_AUTO_RECONNECT).isEqualTo("session:auto-reconnect")
        assertThat(UsageActions.isKnown(UsageActions.SESSION_AUTO_RECONNECT)).isTrue()
    }
}
