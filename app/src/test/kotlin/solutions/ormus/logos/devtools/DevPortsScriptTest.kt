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

package solutions.ormus.logos.devtools

import org.connectbot.transport.CommandOutput
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.net.InetAddress
import java.net.ServerSocket

/** The listening-port script runs for real on the test machine. Skipped without ss and lsof. */
class DevPortsScriptTest {
    private fun sh(command: String): CommandOutput {
        val process = ProcessBuilder("sh", "-c", command).start()
        val stdout = process.inputStream.readBytes().toString(Charsets.UTF_8)
        val stderr = process.errorStream.readBytes().toString(Charsets.UTF_8)
        return CommandOutput(stdout, stderr, process.waitFor())
    }

    @Test
    fun findsAServerListeningOnLoopback() {
        assumeTrue(sh("command -v ss || command -v lsof").exitCode == 0)
        ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { server ->
            val read = DevPorts.readListening(sh(DevPorts.listenCommand))

            val targets = (read as DevPorts.Listening.Ports).targets
            assertTrue(targets.toString(), targets.any { it.port == server.localPort && it.host == "127.0.0.1" })
        }
    }
}
