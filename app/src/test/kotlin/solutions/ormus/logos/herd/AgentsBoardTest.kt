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

package solutions.ormus.logos.herd

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The Agents board's cards over several Herdr sessions, its sections, and its small readers. */
class AgentsBoardTest {
    // The default session: two workspaces, five agents (one unknown), and a shell pane.
    private val main = boardSnapshot(
        workspaces = listOf("w1" to "alpha", "w2" to "beta"),
        tabs = listOf("w1:t1" to "build", "w1:t2" to "docs", "w2:t1" to "tests", "w2:t2" to "shell"),
        agents = listOf(
            boardAgent("w2:p1", "w2:t1", "codex", HerdrAgentStatus.BLOCKED),
            boardAgent("w1:p2", "w1:t2", "claude", HerdrAgentStatus.WORKING),
            boardAgent("w1:p1", "w1:t1", "claude", HerdrAgentStatus.BLOCKED),
            boardAgent("w1:p3", "w1:t2", "grok", HerdrAgentStatus.DONE),
            boardAgent("w2:p2", "w2:t2", "pi", HerdrAgentStatus.UNKNOWN),
        ),
    )

    // A second session whose pane ids repeat the default's.
    private val work = boardSnapshot(
        workspaces = listOf("w1" to "gamma"),
        tabs = listOf("w1:t1" to "review"),
        agents = listOf(
            boardAgent("w1:p1", "w1:t1", "grok", HerdrAgentStatus.BLOCKED),
            boardAgent("w1:p2", "w1:t1", "claude", HerdrAgentStatus.IDLE),
        ),
    )

    private val cards = AgentsBoard.cards(mapOf("work" to work, "default" to main))

    @Test
    fun cards_everyAgentOfEverySession_defaultFirstThenByPlace() {
        assertEquals(
            listOf("default/w1:p1", "default/w1:p2", "default/w1:p3", "default/w2:p1", "default/w2:p2", "work/w1:p1", "work/w1:p2"),
            cards.map { "${it.session}/${it.paneId}" },
        )
        val card = cards.first { it.session == "default" && it.paneId == "w2:p1" }
        assertEquals("codex", card.agent)
        assertEquals("tests", card.tabLabel)
        assertEquals(1, card.tabNumber)
        assertEquals("beta", card.workspaceLabel)
        assertEquals(2, card.workspaceNumber)
        assertEquals("task w2:p1", card.title)
    }

    @Test
    fun sections_needsYouWorkingDoneIdle_eachInPlaceOrder_unknownWithIdle() {
        val sections = AgentsBoard.sections(cards)
        assertEquals(BoardColumn.entries.toList(), sections.keys.toList())
        assertEquals(listOf("default/w1:p1", "default/w2:p1", "work/w1:p1"), sections.getValue(BoardColumn.NEEDS_YOU).map { "${it.session}/${it.paneId}" })
        assertEquals(listOf("w1:p2"), sections.getValue(BoardColumn.WORKING).map { it.paneId })
        assertEquals(listOf("w1:p3"), sections.getValue(BoardColumn.DONE).map { it.paneId })
        assertEquals(listOf("default/w2:p2", "work/w1:p2"), sections.getValue(BoardColumn.IDLE).map { "${it.session}/${it.paneId}" })
    }

    @Test
    fun sections_withNoCards_stillListEverySection() {
        val sections = AgentsBoard.sections(emptyList())
        assertEquals(4, sections.size)
        assertTrue(sections.values.all { it.isEmpty() })
    }

    @Test
    fun needsYou_countsBlockedAgentsInEverySession() {
        assertEquals(3, AgentsBoard.needsYou(mapOf("default" to main, "work" to work)))
        assertEquals(1, AgentsBoard.needsYou(mapOf("work" to work)))
        assertEquals(0, AgentsBoard.needsYou(emptyMap()))
    }

    // [snapshot] with Herdr's focus on [pane] (its tab and workspace follow from the id).
    private fun focused(snapshot: HerdrSessionSnapshot, pane: String, tab: String) = snapshot.copy(focusedWorkspaceId = tab.substringBefore(':'), focusedTabId = tab, focusedPaneId = pane)

    private fun BoardCard?.where() = this?.let { "${it.session}/${it.paneId}" }

    @Test
    fun nextNeedsYou_isTheFirstWaitingAgentAfterTheFocus() {
        // Focus on the working claude in alpha's second tab: the next waiting one is beta's codex.
        val snapshots = mapOf("default" to focused(main, "w1:p2", "w1:t2"), "work" to work)
        assertEquals("default/w2:p1", AgentsBoard.nextNeedsYou(snapshots, "default").where())
        // A focus past every waiting agent of its session moves on to the next session.
        val onShell = mapOf("default" to focused(main, "w2:p2", "w2:t2"), "work" to work)
        assertEquals("work/w1:p1", AgentsBoard.nextNeedsYou(onShell, "default").where())
    }

    @Test
    fun nextNeedsYou_tapsCycleThroughEveryWaitingAgent_andWrap() {
        var snapshots = mapOf("default" to focused(main, "w1:p1", "w1:t1"), "work" to work)
        var attached = "default"
        val visited = mutableListOf<String>()
        repeat(4) {
            val next = checkNotNull(AgentsBoard.nextNeedsYou(snapshots, attached))
            visited += next.where()!!
            // The jump focuses that pane in its session, and the terminal attaches that session.
            snapshots = snapshots + (next.session to focused(snapshots.getValue(next.session), next.paneId, next.tabId))
            attached = next.session
        }
        // Starting on a waiting agent passes it over first, and comes back round to it.
        assertEquals(listOf("default/w2:p1", "work/w1:p1", "default/w1:p1", "default/w2:p1"), visited)
    }

    @Test
    fun nextNeedsYou_noneWaiting_isNull() {
        val calm = main.copy(agents = main.agents.map { it.copy(agentStatus = HerdrAgentStatus.WORKING) })
        assertNull(AgentsBoard.nextNeedsYou(mapOf("default" to calm), "default"))
        assertNull(AgentsBoard.nextNeedsYou(emptyMap(), "default"))
    }

    @Test
    fun nextNeedsYou_aSingleWaitingAgent_isItEvenWhenFocused() {
        val one = mapOf("work" to focused(work, "w1:p1", "w1:t1"))
        assertEquals("work/w1:p1", AgentsBoard.nextNeedsYou(one, "work").where())
    }

    @Test
    fun nextNeedsYou_withoutASnapshotOfTheAttachedSession_startsAtTheFirst() {
        val snapshots = mapOf("default" to focused(main, "w2:p1", "w2:t1"), "work" to work)
        assertEquals("default/w1:p1", AgentsBoard.nextNeedsYou(snapshots, "other").where())
    }

    @Test
    fun answerable_onlyBlockedClaudeAndCodex() {
        val answerable = cards.filter { it.answerable }.map { "${it.session}/${it.paneId}" }
        // The blocked grok in work gets no Approve or Deny: Enter and Esc are not proven there.
        assertEquals(listOf("default/w1:p1", "default/w2:p1"), answerable)
    }

    @Test
    fun cards_carryHerdrsStateChangeSeq() {
        val seen = boardAgent("w1:p1", "w1:t1", "claude", HerdrAgentStatus.BLOCKED).copy(stateChangeSeq = 42)
        val card = AgentsBoard.cards(mapOf("default" to boardSnapshot(listOf("w1" to "alpha"), listOf("w1:t1" to "build"), listOf(seen)))).single()
        assertEquals(42L, card.seq)
    }

    @Test
    fun jumpTarget_carriesTheSessionTabAndPlace() {
        val target = cards.first { it.session == "work" && it.paneId == "w1:p1" }.jumpTarget()
        assertEquals("work", target.session)
        assertEquals("w1:t1", target.tabId)
        assertEquals("review", target.tabLabel)
        assertEquals("gamma", target.workspaceLabel)
        assertEquals(HerdrAgentStatus.BLOCKED, target.state)
    }

    @Test
    fun cards_takeTheirSinceFromTheGivenClock() {
        val timed = AgentsBoard.cards(mapOf("work" to work)) { session, pane -> if (session == "work" && pane == "w1:p1") 42L else null }
        assertEquals(listOf(42L, null), timed.map { it.since })
    }

    @Test
    fun readCommand_quotesTheSessionAndPane() {
        assertEquals(Herd.readCommand("w1:p1", HerdApproval.READ_LINES), AgentsBoard.readCommand("default", "w1:p1"))
        val other = AgentsBoard.readCommand("work", "w1:p1", 12)
        assertTrue(other, other.contains("herdr --session"))
        assertTrue(other, other.contains("pane read"))
        assertTrue(other, other.contains("--lines 12"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun readCommand_refusesASessionNameAShellWouldRead() {
        AgentsBoard.readCommand("work; rm", "w1:p1")
    }

    @Test
    fun contextUse_readsTheStatusLine_lastOneWins() {
        val screen = "earlier ctx 12% (24k/200k)\n> working…\nOpus  ~/src  main  ctx 68% (137k/200k)\n"
        assertEquals(ContextUse(68, "137k/200k"), AgentsBoard.contextUse(screen))
        assertEquals(ContextUse(9, null), AgentsBoard.contextUse("model  ctx 9%"))
        assertEquals(ContextUse(41, "412k/1M"), AgentsBoard.contextUse("ctx 41% (412k/1M)"))
    }

    @Test
    fun contextUse_nothingWithoutAStatusLine() {
        assertNull(AgentsBoard.contextUse("no status line here\n100% done"))
        assertNull(AgentsBoard.contextUse("contexts 50%"))
        assertNull(AgentsBoard.contextUse("ctx 250%"))
    }

    @Test
    fun age_readsInTheLargestWholeUnit() {
        assertEquals("<1m", AgentsBoard.age(59_000))
        assertEquals("<1m", AgentsBoard.age(-5))
        assertEquals("12m", AgentsBoard.age(12 * 60_000L + 30_000))
        assertEquals("3h", AgentsBoard.age(3 * 3_600_000L + 59 * 60_000L))
        assertEquals("47h", AgentsBoard.age(47 * 3_600_000L))
        assertEquals("2d", AgentsBoard.age(50 * 3_600_000L))
    }

    @Test
    fun since_noTimeAtTheFirstLook_thenTheTimeOfTheChange() {
        var now = 1_000L
        val since = AgentSince({ now })
        since.observe("default", main)
        assertNull("a state already on at the first look has no start", since.of("default", "w1:p2"))

        now += HerdSession.POLL_INTERVAL_MS
        since.observe("default", main.withState("w1:p2", HerdrAgentStatus.DONE))
        assertEquals(now, since.of("default", "w1:p2"))
        val changed = now

        now += HerdSession.POLL_INTERVAL_MS
        since.observe("default", main.withState("w1:p2", HerdrAgentStatus.DONE))
        assertEquals("the same state keeps its start", changed, since.of("default", "w1:p2"))
        assertNull("an unchanged agent still has none", since.of("default", "w1:p1"))
    }

    @Test
    fun since_aChangeAfterALongGapHasNoTime() {
        var now = 0L
        val since = AgentSince({ now })
        since.observe("work", work)
        now += AgentSince.MAX_GAP_MS + 1
        since.observe("work", work.withState("w1:p1", HerdrAgentStatus.WORKING))
        assertNull(since.of("work", "w1:p1"))
    }

    @Test
    fun since_keepsSessionsApart_andForgetsClosedPanes() {
        var now = 0L
        val since = AgentSince({ now })
        since.observe("default", main)
        since.observe("work", work)
        now += 1_000
        since.observe("work", work.withState("w1:p1", HerdrAgentStatus.WORKING))
        assertEquals(1_000L, since.of("work", "w1:p1"))
        assertNull("the same pane id in another session", since.of("default", "w1:p1"))

        now += 1_000
        since.observe("work", work.copy(agents = work.agents.filter { it.paneId != "w1:p1" }))
        now += 1_000
        since.observe("work", work.withState("w1:p1", HerdrAgentStatus.BLOCKED))
        assertNull("a pane that came back is a first look again", since.of("work", "w1:p1"))
        assertNull(since.of("work", "w1:p2"))
    }

    private fun HerdrSessionSnapshot.withState(pane: String, state: HerdrAgentStatus) = copy(agents = agents.map { if (it.paneId == pane) it.copy(agentStatus = state) else it })
}

/** A synthetic snapshot: workspaces and tabs (numbered in order within their workspace) and agents. */
internal fun boardSnapshot(workspaces: List<Pair<String, String>>, tabs: List<Pair<String, String>>, agents: List<HerdrAgentInfo>): HerdrSessionSnapshot = HerdrSessionSnapshot(
    workspaces = workspaces.mapIndexed { i, (id, label) -> HerdrWorkspaceInfo(workspaceId = id, label = label, number = i + 1) },
    tabs = tabs.map { (id, label) ->
        val workspace = id.substringBefore(':')
        HerdrTabInfo(tabId = id, workspaceId = workspace, label = label, number = tabs.filter { it.first.startsWith("$workspace:") }.indexOfFirst { it.first == id } + 1)
    },
    panes = agents.map { HerdrPaneInfo(paneId = it.paneId, workspaceId = it.workspaceId, tabId = it.tabId, agent = it.agent, agentStatus = it.agentStatus) },
    agents = agents,
)

/** A synthetic agent in [tab], titled after its pane. */
internal fun boardAgent(pane: String, tab: String, agent: String, state: HerdrAgentStatus): HerdrAgentInfo = HerdrAgentInfo(
    paneId = pane,
    workspaceId = tab.substringBefore(':'),
    tabId = tab,
    agent = agent,
    agentStatus = state,
    terminalTitleStripped = "task $pane",
)
