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

package org.connectbot.transport

import com.trilead.ssh2.Connection
import com.trilead.ssh2.ExtendedServerHostKeyVerifier
import org.connectbot.data.entity.Host
import solutions.ormus.logos.herd.HostShell
import java.io.IOException
import java.security.KeyPair

/**
 * A short SSH login with no terminal, for work that cannot wait for the user (an
 * answer from a notification): no PTY, no shell, nothing typed anywhere; commands run
 * over exec channels. Nothing is asked either: the host key must pass [verifier]
 * without a prompt (a new or changed key is refused), and only the given keys are tried.
 */
class HeadlessSsh(private val host: Host, private val verifier: ExtendedServerHostKeyVerifier) : AutoCloseable {
    /** How the login went. */
    sealed interface Login {
        data object Ok : Login

        /** The host key is new or changed: only the user can accept it. */
        data object HostKeyRefused : Login

        /** The server took none of the keys. */
        data object KeysRefused : Login

        /** No connection, or it broke; [message] is the first line of why. */
        data class Failed(val message: String) : Login
    }

    private val connection = Connection(host.hostname, host.port)

    /** Connect and log in with the first of [keys] the server takes. Blocking. */
    fun login(keys: List<KeyPair>): Login {
        var hostKeyRefused = false
        val checked = object : ExtendedServerHostKeyVerifier() {
            override fun verifyServerHostKey(hostname: String, port: Int, algorithm: String, key: ByteArray): Boolean = verifier.verifyServerHostKey(hostname, port, algorithm, key).also { hostKeyRefused = !it }

            override fun getKnownKeyAlgorithmsForHost(hostname: String, port: Int): List<String>? = verifier.getKnownKeyAlgorithmsForHost(hostname, port)

            override fun removeServerHostKey(hostname: String, port: Int, algorithm: String, key: ByteArray?) = verifier.removeServerHostKey(hostname, port, algorithm, key)

            override fun addServerHostKey(hostname: String, port: Int, algorithm: String, key: ByteArray) = verifier.addServerHostKey(hostname, port, algorithm, key)
        }
        return try {
            connection.connect(checked, CONNECT_TIMEOUT_MS, KEX_TIMEOUT_MS, SSH.parseIpVersion(host.ipVersion, host.hostname))
            val user = host.username.trim()
            if (keys.any { connection.authenticateWithPublicKey(user, it) }) Login.Ok else Login.KeysRefused
        } catch (e: IOException) {
            if (hostKeyRefused) Login.HostKeyRefused else Login.Failed(firstLine(e))
        }
    }

    // Mercurio: a Windows host runs exec commands through cmd.exe; HostShell rewrites them once it says so.
    private val hostShell = HostShell()

    /** Run [command] over its own exec channel; see [SSH.execDetailed]. Blocking. */
    fun exec(command: String): CommandOutput = hostShell.run(command) { SSH.execDetailed(connection, it) }

    override fun close() {
        connection.close()
    }

    private fun firstLine(e: Throwable): String = generateSequence(e) { it.cause }
        .mapNotNull { it.message?.lineSequence()?.firstOrNull { line -> line.isNotBlank() }?.trim() }
        .lastOrNull() ?: e.javaClass.simpleName

    companion object {
        private const val CONNECT_TIMEOUT_MS = 10_000
        private const val KEX_TIMEOUT_MS = 15_000
    }
}
