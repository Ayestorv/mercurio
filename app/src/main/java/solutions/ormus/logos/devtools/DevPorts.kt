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
import java.io.Closeable

/**
 * A dev server to preview: [port] on the host, reached from the host at [host]; [path]
 * from a URL the agent printed ("/" otherwise); [process] when the host says which.
 */
data class PreviewTarget(val host: String, val port: Int, val path: String = "/", val process: String? = null)

/** A loopback port on the phone forwarded through SSH to a host port. Close it to stop. */
class LoopbackForward(val port: Int, private val stop: () -> Unit) : Closeable {
    override fun close() = stop()
}

/**
 * The dev preview's reads: TCP ports listening on the host (`ss -ltnp`, or `lsof` where
 * there is no ss, such as macOS) and the local URLs an agent printed. Ports below 1024
 * (sshd, DNS, printing) are left out: dev servers listen above.
 */
object DevPorts {
    const val CAP = 64 * 1024
    private const val LOWEST = 1024
    private const val EXIT_NO_TOOL = 5

    /** One exec: "#ss" or "#lsof", then that tool's listening sockets. */
    val listenCommand: String = "sh -c '" +
        "command -v ss >/dev/null 2>&1 || command -v lsof >/dev/null 2>&1 || exit $EXIT_NO_TOOL; " +
        "if command -v ss >/dev/null 2>&1; then echo \"#ss\"; ss -ltnpH 2>/dev/null; " +
        "else echo \"#lsof\"; lsof -nP -iTCP -sTCP:LISTEN 2>/dev/null; fi | head -c $CAP'"

    /** The listening servers, or why they could not be read. */
    sealed interface Listening {
        data class Ports(val targets: List<PreviewTarget>) : Listening

        data object NotConnected : Listening

        data object NoTool : Listening

        data class Failed(val message: String) : Listening
    }

    fun readListening(out: CommandOutput?): Listening = when {
        out == null -> Listening.NotConnected
        out.exitCode == EXIT_NO_TOOL -> Listening.NoTool
        out.exitCode != null && out.exitCode != 0 -> Listening.Failed(out.stderr.lineSequence().firstOrNull { it.isNotBlank() }?.trim() ?: "exit ${out.exitCode}")
        else -> Listening.Ports(parseListening(out.stdout))
    }

    internal fun parseListening(text: String): List<PreviewTarget> {
        val lines = text.lines()
        val parsed = when (lines.firstOrNull()?.trim()) {
            "#ss" -> lines.drop(1).mapNotNull(::parseSs)
            "#lsof" -> lines.drop(1).mapNotNull(::parseLsof)
            else -> emptyList()
        }
        // IPv4 and IPv6 sockets on one port are one server; the loopback one is the one to reach.
        return parsed.filter { it.port >= LOWEST }
            .groupBy { it.port }
            .map { (_, same) -> same.firstOrNull { it.host == LOOPBACK } ?: same.first() }
            .sortedBy { it.port }
    }

    // "LISTEN 0 5 127.0.0.1:8000 0.0.0.0:* users:(("python3",pid=1,fd=3))"
    private fun parseSs(line: String): PreviewTarget? {
        val columns = line.trim().split(Regex("\\s+"))
        if (columns.size < 4) return null
        val process = Regex("users:\\(\\(\"([^\"]+)\"").find(line)?.groupValues?.get(1)
        return target(columns[3], process)
    }

    // "python3 1 user 3u IPv4 0x1 0t0 TCP 127.0.0.1:8000 (LISTEN)"
    private fun parseLsof(line: String): PreviewTarget? {
        val columns = line.trim().split(Regex("\\s+"))
        if (columns.size < 10 || columns.last() != "(LISTEN)") return null
        return target(columns[columns.size - 2], columns[0])
    }

    /** A socket's local address ("127.0.0.1:8000", "*:3000", "[::1]:5173") as a target. */
    private fun target(address: String, process: String?): PreviewTarget? {
        val port = address.substringAfterLast(':').toIntOrNull() ?: return null
        // An IPv4 address on a dual-stack socket reads "[::ffff:127.0.0.1]".
        val bound = address.substringBeforeLast(':').removeSurrounding("[", "]").substringBefore('%').removePrefix("::ffff:")
        return PreviewTarget(reach(bound) ?: return null, port, process = process)
    }

    /** Where the host reaches a socket bound to [address]: loopback for the wildcards. */
    private fun reach(address: String): String? = when (address) {
        "*", "0.0.0.0", "::", "localhost" -> LOOPBACK
        "::1" -> "::1"
        else -> address.takeIf { it.isNotEmpty() }
    }

    private const val LOOPBACK = "127.0.0.1"

    // Printed IPv6 URLs ("http://[::1]:5173") are not picked up: LatestUrl stops at brackets.
    private val LOCAL_HOSTS = setOf("localhost", "127.0.0.1", "0.0.0.0")

    /** The local dev server URLs in [text] (what the agent printed), newest first, each port once. */
    fun printed(text: String): List<PreviewTarget> = LatestUrl.findAll(text).asReversed().mapNotNull { url ->
        val rest = url.substringAfter("://")
        val authority = rest.substringBefore('/')
        val path = "/" + rest.substringAfter('/', "")
        val port = authority.substringAfter(':', "").toIntOrNull() ?: return@mapNotNull null
        if (authority.substringBefore(':') !in LOCAL_HOSTS) return@mapNotNull null
        PreviewTarget(LOOPBACK, port, path)
    }.distinctBy { it.port }

    /** What to offer: the printed URLs first (they carry a path), then the other listening ports. */
    fun targets(printed: List<PreviewTarget>, listening: List<PreviewTarget>): List<PreviewTarget> {
        val ports = listening.associateBy { it.port }
        val withProcess = printed.map { it.copy(process = ports[it.port]?.process) }
        return withProcess + listening.filter { port -> printed.none { it.port == port.port } }
    }
}
