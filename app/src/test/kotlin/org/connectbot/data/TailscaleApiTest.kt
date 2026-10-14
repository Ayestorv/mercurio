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

import androidx.test.ext.junit.runners.AndroidJUnit4
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.assertj.core.api.Assertions.assertThat
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import timber.log.Timber
import java.io.IOException

/**
 * The OAuth token and device list calls against a local MockWebServer, on the
 * device list copied from tailscale-client-go-v2's testdata/devices.json (MIT,
 * see NOTICE). Robolectric runner so the platform org.json classes are real.
 */
@RunWith(AndroidJUnit4::class)
class TailscaleApiTest {

    private lateinit var server: MockWebServer
    private lateinit var api: TailscaleApi
    private val logs = CapturingTree()
    private var now = 1_000_000L

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        api = apiFor(server)
        Timber.plant(logs)
    }

    @After
    fun tearDown() {
        Timber.uproot(logs)
        server.shutdown()
    }

    private fun apiFor(server: MockWebServer) = TailscaleApi(
        secret = { SECRET },
        hasSecret = { true },
        saveSecret = {},
        baseUrl = server.url("/").toString().removeSuffix("/"),
        clock = { now },
    )

    @Test
    fun oneTokenServesTwoDeviceFetchesInsideItsLifetime() {
        server.enqueue(token("first", expiresIn = 3600))
        server.enqueue(devices())
        server.enqueue(devices())

        api.fetchDevices()
        now += 30 * 60_000L
        api.fetchDevices()

        assertThat(server.requestCount).isEqualTo(3)
        val tokenRequest = server.takeRequest()
        assertThat(tokenRequest.path).isEqualTo("/api/v2/oauth/token")
        assertThat(tokenRequest.method).isEqualTo("POST")
        val form = tokenRequest.body.readUtf8()
        assertThat(form).contains("grant_type=client_credentials").contains("client_id=TEST")
        repeat(2) {
            val fetch = server.takeRequest()
            assertThat(fetch.path).isEqualTo("/api/v2/tailnet/-/devices")
            assertThat(fetch.getHeader("Authorization")).isEqualTo("Bearer first")
        }
    }

    @Test
    fun anExpiredTokenIsReplacedBeforeTheNextFetch() {
        server.enqueue(token("first", expiresIn = 3600))
        server.enqueue(devices())
        server.enqueue(token("second", expiresIn = 3600))
        server.enqueue(devices())

        api.fetchDevices()
        now += 3600 * 1000L
        api.fetchDevices()

        assertThat(server.requestCount).isEqualTo(4)
        server.takeRequest()
        server.takeRequest()
        assertThat(server.takeRequest().path).isEqualTo("/api/v2/oauth/token")
        assertThat(server.takeRequest().getHeader("Authorization")).isEqualTo("Bearer second")
    }

    @Test
    fun a401RefreshesTheTokenOnceAndRetries() {
        server.enqueue(token("first", expiresIn = 3600))
        server.enqueue(MockResponse().setResponseCode(401))
        server.enqueue(token("second", expiresIn = 3600))
        server.enqueue(devices())

        val body = api.fetchDevices()

        assertThat(TailscaleStatus.parseApiDevices(body)).isNotEmpty()
        assertThat(server.requestCount).isEqualTo(4)
        val paths = (1..4).map { server.takeRequest() }
        assertThat(paths.map { it.path }).containsExactly(
            "/api/v2/oauth/token",
            "/api/v2/tailnet/-/devices",
            "/api/v2/oauth/token",
            "/api/v2/tailnet/-/devices",
        )
        assertThat(paths[3].getHeader("Authorization")).isEqualTo("Bearer second")
    }

    @Test
    fun a401AfterTheRetryFailsWithoutAnotherRefresh() {
        server.enqueue(token("first", expiresIn = 3600))
        server.enqueue(MockResponse().setResponseCode(401))
        server.enqueue(token("second", expiresIn = 3600))
        server.enqueue(MockResponse().setResponseCode(401))

        val error = assertThrows(IOException::class.java) { api.fetchDevices() }

        assertThat(error.message).contains("401")
        assertThat(server.requestCount).isEqualTo(4)
    }

    @Test
    fun parseApiDevices_keepsOwnedAndTagged_dropsOtherOwners() {
        val list = JSONObject(FIXTURE)
        // The upstream list has no tagged device, so add one synthetic server.
        list.getJSONArray("devices").put(
            JSONObject()
                .put("hostname", "build")
                .put("name", "build.example.com")
                .put("addresses", org.json.JSONArray().put("100.64.0.9"))
                .put("tags", org.json.JSONArray().put("tag:server"))
                .put("connectedToControl", true)
                .put("os", "linux"),
        )

        val devices = TailscaleStatus.parseApiDevices(list.toString())

        // "hello" belongs to services@example.com and is shared in (isExternal): dropped.
        assertThat(devices.map { it.hostName }).containsExactly("build", "foo", "foo")
        val online = devices.single { it.hostName == "foo" && it.online }
        assertThat(online.ipv4).isEqualTo("100.69.123.1")
        assertThat(online.dnsName).isEqualTo("foo.example.com")
        assertThat(online.osLabel).isEqualTo("Linux")
        assertThat(devices.single { it.hostName == "foo" && !it.online }.lastSeen).isEqualTo("2022-04-15T13:25:21Z")
    }

    @Test
    fun theSecretNeverReachesALogLineOrAnError() {
        // A token refusal that echoes the secret back must not carry it any further.
        server.enqueue(MockResponse().setResponseCode(400).setBody("""{"message":"bad secret $SECRET"}"""))
        val refused = assertThrows(IOException::class.java) { api.fetchDevices() }
        Timber.w(refused, "Tailscale API device list failed")

        server.enqueue(token("first", expiresIn = 3600))
        server.enqueue(MockResponse().setResponseCode(401))
        server.enqueue(token("second", expiresIn = 3600))
        server.enqueue(devices())
        api.fetchDevices()

        assertThat(logs.lines).isNotEmpty()
        assertThat(logs.lines).noneMatch { it.contains(SECRET) || it.contains("notarealsecret") }
        assertThat(refused.stackTraceToString()).doesNotContain(SECRET)
        // It was sent, in the token request body only.
        assertThat(server.takeRequest().body.readUtf8()).contains("client_secret=$SECRET")
    }

    @Test
    fun clientId_isTheThirdPartOfAClientSecret() {
        assertThat(TailscaleApi.clientId(SECRET)).isEqualTo("TEST")
        assertThat(TailscaleApi.clientId("not-a-secret")).isNull()
    }

    private fun token(value: String, expiresIn: Int) = MockResponse()
        .setHeader("Content-Type", "application/json")
        .setBody("""{"access_token":"$value","token_type":"Bearer","expires_in":$expiresIn,"scope":"devices:core:read"}""")

    private fun devices() = MockResponse().setHeader("Content-Type", "application/json").setBody(FIXTURE)

    private class CapturingTree : Timber.Tree() {
        val lines = mutableListOf<String>()

        override fun log(priority: Int, tag: String?, message: String, t: Throwable?) {
            lines += message
            t?.let { lines += it.stackTraceToString() }
        }
    }

    companion object {
        /** Synthetic: the shape of a Tailscale OAuth client secret, not a real one. */
        const val SECRET = "tskey-client-TEST-notarealsecret"

        val FIXTURE: String by lazy {
            checkNotNull(TailscaleApiTest::class.java.getResourceAsStream("/tailscale/devices.json")).readBytes().decodeToString()
        }
    }
}
