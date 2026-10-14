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

import android.content.Context
import android.content.SharedPreferences
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.assertj.core.api.Assertions.assertThat
import org.connectbot.data.entity.Host
import org.connectbot.di.CoroutineDispatchers
import org.connectbot.util.SecurePasswordStorage
import org.json.JSONException
import org.junit.After
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.mock

@RunWith(AndroidJUnit4::class)
class TailscaleRepositoryTest {

    private val testDispatcher = StandardTestDispatcher()
    private val dispatchers = CoroutineDispatchers(testDispatcher, testDispatcher, testDispatcher)
    private lateinit var database: ConnectBotDatabase
    private lateinit var hostRepository: HostRepository
    private lateinit var prefs: SharedPreferences
    private lateinit var repository: TailscaleRepository
    private val api = TailscaleApi(secret = { null }, hasSecret = { false }, saveSecret = {})

    private val sun by lazy { TailscaleStatus.parse(TailscaleStatusTest.FIXTURE).single { it.hostName == "Sun" } }

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, ConnectBotDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        hostRepository = HostRepository(
            context,
            database,
            database.hostDao(),
            database.portForwardDao(),
            database.knownHostDao(),
            mock<SecurePasswordStorage>(),
        )
        prefs = context.getSharedPreferences("tailscale-test", Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
        repository = TailscaleRepository(prefs, hostRepository, dispatchers, api)
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun findSavedHost_matchesExistingHostByTailscaleIp() = runTest(testDispatcher) {
        val saved = hostRepository.saveHost(Host(nickname = "home", protocol = "ssh", username = "alice", hostname = "100.100.1.1"))
        hostRepository.saveHost(Host(nickname = "moon", protocol = "ssh", username = "alice", hostname = "100.100.1.2"))

        assertThat(repository.findSavedHost(sun)?.id).isEqualTo(saved.id)
    }

    @Test
    fun findSavedHost_noMatch_returnsNull() = runTest(testDispatcher) {
        hostRepository.saveHost(Host(nickname = "moon", protocol = "ssh", username = "alice", hostname = "100.100.1.2"))

        assertThat(repository.findSavedHost(sun)).isNull()
    }

    @Test
    fun saveHostFor_createsSshHostOnTailscaleIp() = runTest(testDispatcher) {
        val created = repository.saveHostFor(sun, " alice ")!!

        val stored = hostRepository.findHostById(created.id)!!
        assertThat(stored.nickname).isEqualTo("Sun")
        assertThat(stored.hostname).isEqualTo("100.100.1.1")
        assertThat(stored.port).isEqualTo(22)
        assertThat(stored.username).isEqualTo("alice")
        assertThat(stored.protocol).isEqualTo("ssh")
        // The next tap on the same machine reuses it instead of saving again.
        assertThat(repository.findSavedHost(sun)?.id).isEqualTo(created.id)
    }

    @Test
    fun saveHostFor_blankUsername_savesNothing() = runTest(testDispatcher) {
        assertThat(repository.saveHostFor(sun, "  ")).isNull()
        assertThat(hostRepository.getHosts()).isEmpty()
    }

    @Test
    fun knownUsername_comesFromTheMachineUsernamesSetting() {
        // Empty by default: a first tap asks for a username.
        assertThat(repository.knownUsername(sun)).isNull()

        repository.machineUsernames = " sun=alice \n"

        assertThat(repository.machineUsernames).isEqualTo("sun=alice")
        assertThat(repository.knownUsername(sun)).isEqualTo("alice")
    }

    @Test
    fun applyStatus_isCachedForTheNextLaunch() = runTest(testDispatcher) {
        repository.applyStatus(TailscaleStatusTest.FIXTURE, sourceUsername = "alice", now = 1234L)

        val relaunched = TailscaleRepository(prefs, hostRepository, dispatchers, api)
        relaunched.loadCache()

        val cached = relaunched.machines.value
        assertThat(cached.devices.map { it.shortName }).containsExactly("server", "sun", "nas", "pi")
        assertThat(cached.updatedAt).isEqualTo(1234L)
        assertThat(cached.sourceUsername).isEqualTo("alice")
    }

    @Test
    fun applyStatus_badAnswer_keepsPreviousList() = runTest(testDispatcher) {
        repository.applyStatus(TailscaleStatusTest.FIXTURE, sourceUsername = "alice", now = 1234L)

        assertThrows(JSONException::class.java) {
            repository.applyStatus("bash: tailscale: command not found", sourceUsername = "alice", now = 5678L)
        }

        assertThat(repository.machines.value.updatedAt).isEqualTo(1234L)
        val relaunched = TailscaleRepository(prefs, hostRepository, dispatchers, api)
        relaunched.loadCache()
        assertThat(relaunched.machines.value.devices).hasSize(4)
    }

    @Test
    fun refresh_noSshSession_withSavedSecret_loadsFromTheApi() = runTest(testDispatcher) {
        val server = MockWebServer()
        server.start()
        try {
            server.enqueue(MockResponse().setBody("""{"access_token":"t","expires_in":3600}"""))
            server.enqueue(MockResponse().setBody(TailscaleApiTest.FIXTURE))
            val withSecret = TailscaleApi(
                secret = { TailscaleApiTest.SECRET },
                hasSecret = { true },
                saveSecret = {},
                baseUrl = server.url("/").toString().removeSuffix("/"),
            )
            val repository = TailscaleRepository(prefs, hostRepository, dispatchers, withSecret)

            assertThat(repository.canRefresh(emptyList(), null)).isTrue()
            assertThat(repository.refresh(emptyList(), null)).isTrue()

            assertThat(repository.machines.value.devices.map { it.hostName }).containsExactly("foo", "foo")
            assertThat(repository.lastError).isNull()
            assertThat(server.requestCount).isEqualTo(2)
            // The cached API answer is read back with the API parser on the next launch.
            val relaunched = TailscaleRepository(prefs, hostRepository, dispatchers, api)
            relaunched.loadCache()
            assertThat(relaunched.machines.value.devices.map { it.hostName }).containsExactly("foo", "foo")
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun refresh_noSshSession_noSecret_reportsNoSession() = runTest(testDispatcher) {
        assertThat(repository.canRefresh(emptyList(), null)).isFalse()
        assertThat(repository.refresh(emptyList(), null)).isFalse()
        assertThat(repository.lastError?.message).isEqualTo(TailscaleRepository.NO_SESSION)
    }
}
