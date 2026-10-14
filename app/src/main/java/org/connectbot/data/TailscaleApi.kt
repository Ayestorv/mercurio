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

import org.connectbot.util.SecurePasswordStorage
import org.json.JSONException
import org.json.JSONObject
import timber.log.Timber
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Reads the tailnet's device list from Tailscale's own API with an OAuth
 * client secret the operator saved (Android Keystore), so the Machines list
 * loads with no SSH session open. One access token serves every call until it
 * nears expiry; a 401 drops it and asks for a new one, once.
 *
 * Blocking: call it off the main thread. The secret and the token never reach
 * a log line or an exception message.
 */
@Singleton
class TailscaleApi(
    private val secret: () -> String?,
    private val hasSecret: () -> Boolean,
    private val saveSecret: (String?) -> Unit,
    private val baseUrl: String = BASE_URL,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    @Inject constructor(storage: SecurePasswordStorage) : this(
        secret = { storage.getSecret(SECRET_NAME) },
        hasSecret = { storage.hasSecret(SECRET_NAME) },
        saveSecret = { storage.saveSecret(SECRET_NAME, it) },
    )

    private var token: String? = null
    private var tokenExpiresAt = 0L

    /** True when an OAuth client secret is saved. */
    fun isConfigured(): Boolean = hasSecret()

    /** Save the OAuth client secret; blank clears it. Any cached token is dropped. */
    @Synchronized
    fun setSecret(value: String?) {
        saveSecret(value?.trim()?.takeIf { it.isNotEmpty() })
        token = null
    }

    /**
     * The `GET /api/v2/tailnet/-/devices` answer.
     *
     * @throws IOException when no secret is saved or Tailscale refuses or cannot be reached.
     */
    @Synchronized
    fun fetchDevices(): String {
        val clientSecret = secret() ?: throw IOException("No Tailscale OAuth client secret is saved")
        val first = getDevices(accessToken(clientSecret))
        if (first.code != HttpURLConnection.HTTP_UNAUTHORIZED) return first.okBody("device list")
        Timber.i("Tailscale API token was refused; asking for a new one")
        token = null
        return getDevices(accessToken(clientSecret)).okBody("device list")
    }

    private fun accessToken(clientSecret: String): String {
        token?.takeIf { clock() < tokenExpiresAt }?.let { return it }
        val form = buildString {
            append("grant_type=client_credentials")
            clientId(clientSecret)?.let { append("&client_id=").append(URLEncoder.encode(it, "UTF-8")) }
            append("&client_secret=").append(URLEncoder.encode(clientSecret, "UTF-8"))
        }
        val body = request("$baseUrl/api/v2/oauth/token") {
            requestMethod = "POST"
            doOutput = true
            setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
            outputStream.use { it.write(form.toByteArray(Charsets.UTF_8)) }
        }.okBody("token request")
        val answer = try {
            JSONObject(body)
        } catch (e: JSONException) {
            throw IOException("Tailscale token answer was not JSON")
        }
        val fresh = answer.optString("access_token").ifEmpty { throw IOException("Tailscale token answer had no token") }
        val lifetimeMs = answer.optLong("expires_in", DEFAULT_LIFETIME_S) * 1000
        token = fresh
        tokenExpiresAt = clock() + (lifetimeMs - EXPIRY_MARGIN_MS).coerceAtLeast(0L)
        return fresh
    }

    private fun getDevices(accessToken: String): Response = request("$baseUrl/api/v2/tailnet/-/devices") {
        setRequestProperty("Authorization", "Bearer $accessToken")
    }

    private fun request(url: String, setUp: HttpURLConnection.() -> Unit): Response {
        val connection = URL(url).openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = TIMEOUT_MS
            connection.readTimeout = TIMEOUT_MS
            connection.setRequestProperty("Accept", "application/json")
            connection.setUp()
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            return Response(code, stream?.use { it.readBytes().decodeToString() }.orEmpty())
        } finally {
            connection.disconnect()
        }
    }

    private class Response(val code: Int, val body: String) {
        /** The body of a 2xx answer; the message names only the call and the status. */
        fun okBody(call: String): String {
            if (code !in 200..299) throw IOException("Tailscale API $call answered HTTP $code")
            return body
        }
    }

    companion object {
        const val BASE_URL = "https://api.tailscale.com"
        private const val SECRET_NAME = "tailscale_oauth_client"
        private const val TIMEOUT_MS = 10_000
        private const val DEFAULT_LIFETIME_S = 3600L

        /** Ask for a new token this long before the old one expires. */
        private const val EXPIRY_MARGIN_MS = 60_000L

        /** A client secret reads `tskey-client-<id>-<secret>`; the id is its third part. */
        fun clientId(secret: String): String? = secret.split('-').takeIf { it.size >= 4 && it[0] == "tskey" && it[1] == "client" }?.get(2)
    }
}
