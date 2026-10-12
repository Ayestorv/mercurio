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

/** The Agents board's sections, in the order they show. */
enum class BoardColumn { NEEDS_YOU, WORKING, DONE, IDLE }

/** One agent on the Agents board: what it is, what it is doing, and where it lives. */
data class BoardCard(
    val session: String,
    val paneId: String,
    val tabId: String,
    /** The agent's kind (claude, codex ...); null when Herdr names none. */
    val agent: String?,
    val state: HerdrAgentStatus,
    /** The pane's terminal title, which agents set to their task. */
    val title: String?,
    val tabLabel: String,
    val tabNumber: Int,
    val workspaceLabel: String,
    val workspaceNumber: Int,
    /** When Mercurio saw the agent enter [state] (see [AgentSince]); null when it cannot tell. */
    val since: Long? = null,
) {
    val column: BoardColumn get() = AgentsBoard.column(state)

    /** Approve and Deny reach this agent: blocked, of a kind whose prompt Enter and Esc answer. */
    val answerable: Boolean get() = state == HerdrAgentStatus.BLOCKED && agent in HerdApproval.PROVEN_KINDS

    /** The card as a Jump to target, so a long press takes the same path as the Jump to list. */
    fun jumpTarget(): JumpTarget = JumpTarget(
        session = session,
        tabId = tabId,
        tabLabel = tabLabel,
        tabNumber = tabNumber,
        workspaceLabel = workspaceLabel,
        workspaceNumber = workspaceNumber,
        agent = agent,
        state = state,
        focused = false,
    )
}

/** A Claude Code status line's context use: [percent] of the window, and "137k/200k" when shown. */
data class ContextUse(val percent: Int, val detail: String?)

/**
 * The Agents board (Moshi's "Agents & Usages"): every agent of every running Herdr session
 * on the host, in four sections by what it needs from the user. Pure: the board screen feeds
 * it the snapshots the Herdr side already holds.
 */
object AgentsBoard {
    /** Herdr's state as a board section; an unknown state sits with the idle ones. */
    fun column(state: HerdrAgentStatus): BoardColumn = when (state) {
        HerdrAgentStatus.BLOCKED -> BoardColumn.NEEDS_YOU
        HerdrAgentStatus.WORKING -> BoardColumn.WORKING
        HerdrAgentStatus.DONE -> BoardColumn.DONE
        HerdrAgentStatus.IDLE, HerdrAgentStatus.UNKNOWN -> BoardColumn.IDLE
    }

    /**
     * Every agent in [snapshots] (by session name), the default session first, then by
     * session name, workspace number, tab number and pane, so a card keeps its place while
     * its state holds. [since] says when an agent entered its state.
     */
    fun cards(snapshots: Map<String, HerdrSessionSnapshot>, since: (session: String, paneId: String) -> Long? = { _, _ -> null }): List<BoardCard> = snapshots.flatMap { (session, snapshot) ->
        snapshot.agents.map { agent ->
            val tab = snapshot.tabs.firstOrNull { it.tabId == agent.tabId }
            val workspace = snapshot.workspaces.firstOrNull { it.workspaceId == agent.workspaceId }
            BoardCard(
                session = session,
                paneId = agent.paneId,
                tabId = agent.tabId,
                agent = agent.agent?.ifBlank { null },
                state = agent.agentStatus,
                title = agent.title(),
                tabLabel = tab?.label.orEmpty(),
                tabNumber = tab?.number ?: 0,
                workspaceLabel = workspace?.label.orEmpty(),
                workspaceNumber = workspace?.number ?: 0,
                since = since(session, agent.paneId),
            )
        }
    }.sortedWith(ORDER)

    /**
     * The agent the compose bar's Needs you key jumps to: the first one waiting for the user
     * after where [session] has its focus (its focused workspace, tab and pane, in the board's
     * order), wrapping round; the first waiting one when [session] has no snapshot. Each tap
     * moves the focus on, so taps cycle through every waiting agent. Null when none waits.
     */
    fun nextNeedsYou(snapshots: Map<String, HerdrSessionSnapshot>, session: String): BoardCard? {
        val waiting = cards(snapshots).filter { it.column == BoardColumn.NEEDS_YOU }
        if (waiting.isEmpty()) return null
        val snapshot = snapshots[session] ?: return waiting.first()
        val here = BoardCard(
            session = session,
            paneId = snapshot.focusedPaneId.orEmpty(),
            tabId = snapshot.focusedTabId.orEmpty(),
            agent = null,
            state = HerdrAgentStatus.UNKNOWN,
            title = null,
            tabLabel = "",
            tabNumber = snapshot.tabs.firstOrNull { it.tabId == snapshot.focusedTabId }?.number ?: 0,
            workspaceLabel = "",
            workspaceNumber = snapshot.workspaces.firstOrNull { it.workspaceId == snapshot.focusedWorkspaceId }?.number ?: 0,
        )
        return waiting.firstOrNull { ORDER.compare(it, here) > 0 } ?: waiting.first()
    }

    /** [cards] in every section, in [BoardColumn] order; a section with no cards is still there. */
    fun sections(cards: List<BoardCard>): Map<BoardColumn, List<BoardCard>> = BoardColumn.entries.associateWith { column -> cards.filter { it.column == column } }

    /** How many agents in [snapshots] wait for the user. */
    fun needsYou(snapshots: Map<String, HerdrSessionSnapshot>): Int = snapshots.values.sumOf { snapshot -> snapshot.agents.count { it.agentStatus == HerdrAgentStatus.BLOCKED } }

    /**
     * The pane read that shows an expanded card: its screen's last lines, in [session].
     * One read when the user opens a card, never on a timer.
     */
    fun readCommand(session: String, paneId: String, lines: Int = HerdApproval.READ_LINES): String = if (HerdrSessions.isDefault(session)) {
        Herd.readCommand(paneId, lines)
    } else {
        HerdrApi.loginShell("herdr --session ${HerdrSessions.quoted(session)} ${HerdrApi.paneReadCmd(paneId, lines).removePrefix("herdr ")}")
    }

    /**
     * The context use a Claude Code status line prints at the bottom of [screen]
     * ("ctx 68% (137k/200k)"), the last one when there are more; null when there is none.
     */
    fun contextUse(screen: String): ContextUse? {
        val match = CONTEXT.findAll(screen).lastOrNull() ?: return null
        val percent = match.groupValues[1].toIntOrNull()?.takeIf { it in 0..100 } ?: return null
        return ContextUse(percent, match.groupValues[2].ifEmpty { null })
    }

    /** How long [ms] reads on a card: "<1m", "12m", "3h", "2d". */
    fun age(ms: Long): String {
        val minutes = ms.coerceAtLeast(0) / 60_000
        return when {
            minutes < 1 -> "<1m"
            minutes < 60 -> "${minutes}m"
            minutes < 48 * 60 -> "${minutes / 60}h"
            else -> "${minutes / (24 * 60)}d"
        }
    }

    // The default session first, then by session name, workspace number, tab number and pane.
    private val ORDER = compareBy<BoardCard> { !HerdrSessions.isDefault(it.session) }
        .thenBy { it.session }
        .thenBy { it.workspaceNumber }
        .thenBy { it.tabNumber }
        .thenBy { it.paneId }

    private val CONTEXT = Regex("""\bctx\s+(\d{1,3})%(?:\s*\(([0-9.]+[kKmM]?/[0-9.]+[kKmM]?)\))?""")
}

/**
 * When each agent entered its state, as far as Mercurio saw it. Herdr's snapshot carries
 * no times, so this keeps the time of the snapshot that first showed a new state, and only
 * when the one before it in that session is recent ([maxGapMs]): a state already on at the
 * first look, or one that changed during a long gap, has no time rather than a wrong one.
 * The default session is polled every few seconds, so its times are close.
 */
class AgentSince(private val clock: () -> Long, private val maxGapMs: Long = MAX_GAP_MS) {
    private data class Seen(val state: HerdrAgentStatus, val at: Long?)

    // session -> pane -> its state and since when; session -> when it was last seen.
    private val seen = HashMap<String, HashMap<String, Seen>>()
    private val lastLook = HashMap<String, Long>()

    /** [session] showed [snapshot] now. */
    @Synchronized
    fun observe(session: String, snapshot: HerdrSessionSnapshot) {
        val now = clock()
        val recent = lastLook[session]?.let { now - it <= maxGapMs } == true
        lastLook[session] = now
        val before = seen[session].orEmpty()
        seen[session] = snapshot.agents.associateTo(HashMap()) { agent ->
            val old = before[agent.paneId]
            agent.paneId to when {
                old != null && old.state == agent.agentStatus -> old
                old != null && recent -> Seen(agent.agentStatus, now)
                else -> Seen(agent.agentStatus, null)
            }
        }
    }

    /** When [paneId] in [session] entered its current state; null when unknown. */
    @Synchronized
    fun of(session: String, paneId: String): Long? = seen[session]?.get(paneId)?.at

    companion object {
        /** Five polls of the default session: a longer gap and a change has no time. */
        const val MAX_GAP_MS = 5 * HerdSession.POLL_INTERVAL_MS
    }
}
