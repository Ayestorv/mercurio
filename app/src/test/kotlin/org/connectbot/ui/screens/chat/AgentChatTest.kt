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

package org.connectbot.ui.screens.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import solutions.ormus.logos.herd.HerdrAgentInfo
import solutions.ormus.logos.herd.HerdrAgentSession
import solutions.ormus.logos.herd.HerdrAgentStatus
import solutions.ormus.logos.herd.HerdrPaneInfo
import solutions.ormus.logos.herd.HerdrSessionSnapshot
import solutions.ormus.logos.herd.HerdrTabInfo
import solutions.ormus.logos.herd.HerdrWorkspaceInfo
import java.io.File

/**
 * The host-side chat reader, run with the build machine's python3 over synthetic
 * transcripts (test resources `chat/`), and which pane it reads.
 */
class AgentChatTest {

    @get:Rule
    val temp = TemporaryFolder()

    private val claudeId = "11111111-2222-3333-4444-555555555555"
    private val codexId = "019f0000-1111-7222-8333-444455556666"

    private fun fixture(name: String): String = checkNotNull(javaClass.getResourceAsStream("/chat/$name")).readBytes().decodeToString()

    private fun claudeTranscript(text: String): File {
        val project = File(temp.root, ".claude/projects/-home-demo-app").apply { mkdirs() }
        return File(project, "$claudeId.jsonl").apply { writeText(text.replace("@HOME@", temp.root.path)) }
    }

    private fun run(agent: String, id: String, knownSize: Long = -1): Pair<Int, String> {
        assumeTrue("python3 on the build machine", File("/usr/bin/python3").exists())
        val command = checkNotNull(AgentChat.command(agent, id, knownSize))
        val process = ProcessBuilder("sh", "-c", command).apply { environment()["HOME"] = temp.root.path }.start()
        val out = process.inputStream.bufferedReader().readText()
        return process.waitFor() to out
    }

    private fun read(agent: String = "claude", id: String = claudeId): ChatPayload {
        val (exit, out) = run(agent, id)
        assertEquals(0, exit)
        return checkNotNull(AgentChat.parse(out)) { out }
    }

    @Test
    fun claude_userTextAndToolCallsInOrder_withoutMetaThinkingSidechainOrToolOutput() {
        claudeTranscript(fixture("claude-session.jsonl"))

        val payload = read()

        val shown = payload.items.map { it.k to (it.line.ifEmpty { it.text }) }
        assertEquals(
            listOf(
                "user" to "/model opus",
                "user" to "Add a dark mode toggle to the demo app settings.",
                "agent" to "I will look at the settings screen first.",
                "tool" to "Read src/Settings.kt",
                "tool" to "Edit src/Settings.kt",
                "tool" to "Bash: ./gradlew test",
                // Two text blocks in a row read as one reply.
                "agent" to "One test failed. Fixing it:\n\n- the toggle default\n- the `night` resource",
                "tool" to "send_note: status",
                "user" to "Also check the tablet layout.\n[image]",
                "tool" to "Bash: rm -rf build",
            ),
            shown,
        )
        assertFalse(payload.truncated)
        assertFalse(payload.items.any { "do-not-ship" in it.text + it.detail })
    }

    @Test
    fun claude_toolResultsMarkTheirCalls_andAPendingCallStaysRunning() {
        claudeTranscript(fixture("claude-session.jsonl"))

        val tools = read().items.filter { it.isTool }

        assertEquals(listOf("ok", "ok", "error", "run", "run"), tools.map { it.state })
        // The full target opens on a tap: the path under home with ~, the whole command.
        assertEquals("~/app/src/Settings.kt", tools[0].detail)
        assertEquals("./gradlew test\necho second line", tools[2].detail)
    }

    @Test
    fun claude_unchangedSize_sendsOnlySame() {
        val file = claudeTranscript(fixture("claude-session.jsonl"))

        val (exit, out) = run("claude", claudeId, knownSize = file.length())

        assertEquals(0, exit)
        val payload = checkNotNull(AgentChat.parse(out))
        assertTrue(payload.same)
        assertTrue(payload.items.isEmpty())
        assertTrue(out.length < 64)
    }

    @Test
    fun claude_longTranscript_readsOnlyItsTail_andSaysSo() {
        val filler = """{"type":"assistant","message":{"content":[{"type":"text","text":"${"x".repeat(1000)}"}]}}"""
        val old = """{"type":"user","message":{"content":"the very first question"}}"""
        val lines = listOf(old) + List(AgentChat.TAIL_BYTES / filler.length + 10) { filler } +
            """{"type":"user","message":{"content":"the latest question"}}"""
        claudeTranscript(lines.joinToString("\n"))

        val payload = read()

        assertTrue(payload.truncated)
        assertTrue(payload.items.size <= AgentChat.MAX_ITEMS)
        assertFalse(payload.items.any { it.text == "the very first question" })
        assertEquals("the latest question", payload.items.last().text)
        assertTrue(payload.items.all { it.text.length <= AgentChat.MAX_TEXT_CHARS })
    }

    @Test
    fun claude_manyTurns_keepsTheLastOnes() {
        val turns = (1..AgentChat.MAX_ITEMS + 20).map { """{"type":"user","message":{"content":"question $it"}}""" }
        claudeTranscript(turns.joinToString("\n"))

        val payload = read()

        assertTrue(payload.truncated)
        assertEquals(AgentChat.MAX_ITEMS, payload.items.size)
        assertEquals("question ${AgentChat.MAX_ITEMS + 20}", payload.items.last().text)
    }

    @Test
    fun claude_longReply_isClipped() {
        claudeTranscript("""{"type":"assistant","message":{"content":[{"type":"text","text":"${"y".repeat(AgentChat.MAX_TEXT_CHARS * 2)}"}]}}""")

        val text = read().items.single().text

        assertEquals(AgentChat.MAX_TEXT_CHARS, text.length)
        assertTrue(text.endsWith("…"))
    }

    @Test
    fun noTranscript_exitsThree() {
        assertEquals(AgentChat.EXIT_NO_TRANSCRIPT, run("claude", claudeId).first)
    }

    @Test
    fun codex_userMessagesRepliesAndTools() {
        val day = File(temp.root, ".codex/sessions/2026/10/08").apply { mkdirs() }
        File(day, "rollout-2026-10-08T09-00-00-$codexId.jsonl").writeText(fixture("codex-session.jsonl"))

        val items = read("codex", codexId).items

        assertEquals(
            listOf(
                "user" to "Rename the export button.",
                "agent" to "Looking for the button.",
                "tool" to "Shell: rg -n Export src",
                "tool" to "apply_patch src/Export.kt",
                "tool" to "Search: compose button text",
                "agent" to "Renamed it to **Download**.",
            ),
            items.map { it.k to (it.line.ifEmpty { it.text }) },
        )
        assertEquals(listOf("ok", "error", "ok"), items.filter { it.isTool }.map { it.state })
    }

    @Test
    fun command_refusesUnknownAgentsAndMalformedIds() {
        assertNull(AgentChat.command("grok", claudeId))
        assertNull(AgentChat.command("claude", "x'; rm -rf ~; '"))
        assertNull(AgentChat.command("claude", "short"))
        assertNotNull(AgentChat.command("codex", codexId))
    }

    @Test
    fun sendCommand_typesTheTextIntoThatPane_thenEnter_withTheTextQuoted() {
        assumeTrue("bash on the build machine", File("/bin/bash").exists())
        // A stand-in herdr that prints each argument it got in brackets.
        val bin = File(temp.root, "bin").apply { mkdirs() }
        File(bin, "herdr").apply {
            writeText("#!/bin/sh\nfor a in \"$@\"; do printf '[%s]' \"${'$'}a\"; done; echo\n")
            setExecutable(true)
        }
        val text = "it's \"quoted\" ${'$'}HOME `id`; rm -rf ~"
        val command = AgentChat.sendCommand("w2:p7", text)
        val process = ProcessBuilder("sh", "-c", command).apply {
            environment()["HOME"] = temp.root.path
            environment()["PATH"] = bin.path + ":" + System.getenv("PATH")
        }.start()
        val out = process.inputStream.bufferedReader().readText()

        assertEquals(0, process.waitFor())
        assertEquals("[pane][send-text][w2:p7][$text]\n[pane][send-keys][w2:p7][enter]\n", out)
    }

    @Test
    fun sendCommand_inAnotherHerdrSession_namesItForBothKeys() {
        assumeTrue("bash on the build machine", File("/bin/bash").exists())
        val bin = File(temp.root, "bin").apply { mkdirs() }
        File(bin, "herdr").apply {
            writeText("#!/bin/sh\nfor a in \"$@\"; do printf '[%s]' \"${'$'}a\"; done; echo\n")
            setExecutable(true)
        }
        val process = ProcessBuilder("sh", "-c", AgentChat.sendCommand("w2:p7", "hi", "work")).apply {
            environment()["HOME"] = temp.root.path
            environment()["PATH"] = bin.path + ":" + System.getenv("PATH")
        }.start()
        val out = process.inputStream.bufferedReader().readText()

        assertEquals(0, process.waitFor())
        assertEquals("[--session][work][pane][send-text][w2:p7][hi]\n[--session][work][pane][send-keys][w2:p7][enter]\n", out)
    }

    @Test(expected = IllegalArgumentException::class)
    fun sendCommand_refusesAMalformedPaneId() {
        AgentChat.sendCommand("w1:p1; rm -rf ~", "hi")
    }

    @Test
    fun stillThere_onlyWhileThePaneRunsTheSameSession() {
        val target = checkNotNull(AgentChat.target(snapshot(claudePane)))

        assertTrue(AgentChat.stillThere(snapshot(claudePane), target))
        assertFalse(AgentChat.stillThere(snapshot(claudePane.copy(agentSession = HerdrAgentSession("claude", "id", "99999999-2222-3333-4444-555555555555"))), target))
        assertFalse(AgentChat.stillThere(snapshot(claudePane.copy(agent = null)), target))
        assertFalse(AgentChat.stillThere(snapshot(claudePane.copy(paneId = "w1:p2")), target))
        assertFalse(AgentChat.stillThere(null, target))
    }

    @Test
    fun parse_refusesAnythingButTheReadersJson() {
        assertNull(AgentChat.parse("Traceback (most recent call last):"))
        assertNull(AgentChat.parse("""{"v":2,"items":[]}"""))
        assertEquals(42L, AgentChat.parse("""{"v":1,"size":42,"same":true}""")?.size)
    }

    private fun snapshot(pane: HerdrPaneInfo, status: HerdrAgentStatus = HerdrAgentStatus.WORKING) = HerdrSessionSnapshot(
        workspaces = listOf(HerdrWorkspaceInfo(workspaceId = "w1", label = "Demo")),
        tabs = listOf(HerdrTabInfo(tabId = "t1", workspaceId = "w1", label = "app")),
        panes = listOf(pane),
        agents = listOf(
            HerdrAgentInfo(paneId = pane.paneId, workspaceId = "w1", tabId = "t1", agent = pane.agent, agentStatus = status, terminalTitle = "Dark mode toggle"),
        ),
        focusedPaneId = pane.paneId,
    )

    private val claudePane = HerdrPaneInfo(
        paneId = "w1:p1",
        workspaceId = "w1",
        tabId = "t1",
        agent = "claude",
        agentSession = HerdrAgentSession(agent = "claude", kind = "id", value = claudeId),
    )

    @Test
    fun target_isTheFocusedAgentPane_withItsSessionAndState() {
        val target = checkNotNull(AgentChat.target(snapshot(claudePane, HerdrAgentStatus.BLOCKED)))

        assertEquals("w1:p1", target.paneId)
        assertEquals("claude", target.agent)
        assertEquals(claudeId, target.sessionId)
        assertEquals(HerdrAgentStatus.BLOCKED, target.status)
        assertEquals("Dark mode toggle", target.title)
        assertEquals("Demo / app", target.place)
    }

    @Test
    fun target_none_forAShellAnExitedAgentOrAnUnknownAgent() {
        assertNull(AgentChat.target(null))
        assertNull(AgentChat.target(snapshot(claudePane.copy(agent = null, agentSession = null))))
        // The agent exited: Herdr still names its session, but the pane is a shell now.
        assertNull(AgentChat.target(snapshot(claudePane.copy(agent = null))))
        assertNull(AgentChat.target(snapshot(claudePane.copy(agent = "grok", agentSession = HerdrAgentSession("grok", "id", claudeId)))))
        assertNull(AgentChat.target(snapshot(claudePane.copy(agentSession = HerdrAgentSession("claude", "id", "bad id; rm")))))
        assertNull(AgentChat.target(snapshot(claudePane).copy(focusedPaneId = null)))
    }
}
