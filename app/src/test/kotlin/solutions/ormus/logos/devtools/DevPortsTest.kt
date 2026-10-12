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
import org.connectbot.util.LatestUrl
import org.junit.Assert.assertEquals
import org.junit.Test

/** The dev preview's parsers on synthetic ss, lsof and terminal output. */
class DevPortsTest {
    private fun ports(text: String) = (DevPorts.readListening(CommandOutput(text, "", 0)) as DevPorts.Listening.Ports).targets

    @Test
    fun ssListsServersAboveTheSystemPorts_loopbackFirst() {
        val text = """
            #ss
            LISTEN 0 128 0.0.0.0:22 0.0.0.0:* users:(("sshd",pid=10,fd=3))
            LISTEN 0 5 127.0.0.1:8000 0.0.0.0:* users:(("python3",pid=11,fd=3))
            LISTEN 0 511 *:3000 *:* users:(("node",pid=12,fd=20))
            LISTEN 0 511 [::1]:5173 [::]:* users:(("node",pid=13,fd=21))
            LISTEN 0 511 [::]:8000 [::]:*
            LISTEN 0 50 [::ffff:127.0.0.1]:9000 *:* users:(("java",pid=14,fd=5))
            LISTEN 0 4096 127.0.0.53%lo:53 0.0.0.0:*
        """.trimIndent()

        assertEquals(
            listOf(
                PreviewTarget("127.0.0.1", 3000, process = "node"),
                PreviewTarget("::1", 5173, process = "node"),
                PreviewTarget("127.0.0.1", 8000, process = "python3"),
                PreviewTarget("127.0.0.1", 9000, process = "java"),
            ),
            ports(text),
        )
    }

    @Test
    fun lsofWhereThereIsNoSs() {
        val text = """
            #lsof
            COMMAND   PID USER   FD   TYPE DEVICE SIZE/OFF NODE NAME
            node      101 dev   22u  IPv6 0x1      0t0  TCP *:3000 (LISTEN)
            Python    102 dev    4u  IPv4 0x2      0t0  TCP 127.0.0.1:8000 (LISTEN)
        """.trimIndent()

        assertEquals(listOf(PreviewTarget("127.0.0.1", 3000, process = "node"), PreviewTarget("127.0.0.1", 8000, process = "Python")), ports(text))
    }

    @Test
    fun exitCodesNameTheProblem() {
        assertEquals(DevPorts.Listening.NotConnected, DevPorts.readListening(null))
        assertEquals(DevPorts.Listening.NoTool, DevPorts.readListening(CommandOutput("", "", 5)))
        assertEquals(DevPorts.Listening.Failed("nope"), DevPorts.readListening(CommandOutput("", "nope\n", 1)))
    }

    @Test
    fun printedLocalUrlsNewestFirstWithTheirPath() {
        val text = "Local: http://localhost:5173/app/ ready\nsee https://example.com/docs\nServing at http://127.0.0.1:8000.\nagain http://localhost:5173/"

        assertEquals(
            listOf(PreviewTarget("127.0.0.1", 5173, "/"), PreviewTarget("127.0.0.1", 8000, "/")),
            DevPorts.printed(text),
        )
    }

    @Test
    fun printedUrlsComeFirstAndBorrowTheProcessName() {
        val printed = listOf(PreviewTarget("127.0.0.1", 8000, "/docs"))
        val listening = listOf(PreviewTarget("127.0.0.1", 3000, process = "node"), PreviewTarget("127.0.0.1", 8000, process = "python3"))

        assertEquals(
            listOf(PreviewTarget("127.0.0.1", 8000, "/docs", "python3"), PreviewTarget("127.0.0.1", 3000, process = "node")),
            DevPorts.targets(printed, listening),
        )
    }

    @Test
    fun latestUrlStillFindsTheLastOne() {
        assertEquals(listOf("http://a.example/1", "http://b.example/2"), LatestUrl.findAll("x http://a.example/1, then http://b.example/2."))
        assertEquals("http://b.example/2", LatestUrl.find("x http://a.example/1, then http://b.example/2."))
    }
}
