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

/**
 * One tab on the Jump to list: where it lives ([session], workspace) and the agent in it
 * that most needs the user ([agent] kind and [state]; null kind for a tab without one).
 */
data class JumpTarget(
    val session: String,
    val tabId: String,
    val tabLabel: String,
    val tabNumber: Int,
    val workspaceLabel: String,
    val workspaceNumber: Int,
    val agent: String?,
    val state: HerdrAgentStatus,
    /** Herdr's focused tab in its session. */
    val focused: Boolean,
)

/**
 * The Jump to list: every tab of every running Herdr session, the ones that need the user
 * first, so a tab is one tap away without cycling through the others (Moshi's "Jump to").
 */
object HerdrJump {
    /**
     * Every tab in [snapshots] (by session name): blocked first, then working, then done,
     * then the rest; inside each, the default session first, then by session name,
     * workspace number and tab number.
     */
    fun targets(snapshots: Map<String, HerdrSessionSnapshot>): List<JumpTarget> = snapshots.flatMap { (session, snapshot) ->
        snapshot.tabs.map { tab ->
            val workspace = snapshot.workspaces.firstOrNull { it.workspaceId == tab.workspaceId }
            val agents = snapshot.agents.filter { it.tabId == tab.tabId }
            val state = HerdrWorkspacePicker.mostUrgent(agents.map { it.agentStatus } + tab.agentStatus)
            JumpTarget(
                session = session,
                tabId = tab.tabId,
                tabLabel = tab.label,
                tabNumber = tab.number,
                workspaceLabel = workspace?.label.orEmpty(),
                workspaceNumber = workspace?.number ?: 0,
                agent = (agents.firstOrNull { it.agentStatus == state } ?: agents.firstOrNull())?.agent?.ifBlank { null },
                state = state,
                focused = tab.tabId == snapshot.focusedTabId,
            )
        }
    }.sortedWith(
        compareBy<JumpTarget> { urgency(it.state) }
            .thenBy { !HerdrSessions.isDefault(it.session) }
            .thenBy { it.session }
            .thenBy { it.workspaceNumber }
            .thenBy { it.tabNumber },
    )

    /** The [targets] matching every word of [query] in their tab, workspace, session or agent, ignoring case. */
    fun filter(targets: List<JumpTarget>, query: String): List<JumpTarget> {
        val words = query.trim().lowercase().split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (words.isEmpty()) return targets
        return targets.filter { target ->
            val text = listOfNotNull(target.tabLabel, target.workspaceLabel, target.session, target.agent).joinToString(" ").lowercase()
            words.all { it in text }
        }
    }

    /**
     * Whether the terminal can leave its Herdr session for another: Herdr's detach must drop
     * back to a shell, which an `exec herdr` post-login does not have (the session would end).
     */
    fun canReattach(postLogin: String?): Boolean = postLogin == null || !EXEC_HERDR.containsMatchIn(postLogin)

    /**
     * Whether [output] shows the Herdr client leaving the screen. Measured on Sun (herdr
     * 0.9.0, a client in a pty): on detach it resets its modes and leaves the alternate
     * screen (`ESC[?1049l`), then the shell prompt returns. Herdr draws its panes itself, so
     * a program inside a pane never puts that sequence on the client's own output.
     */
    fun clientLeft(output: CharSequence): Boolean = output.contains(LEAVE_ALT_SCREEN)

    // How much of the previous output to keep, so the sequence split across reads is still seen.
    internal const val CARRY_CHARS = 16

    private const val LEAVE_ALT_SCREEN = "\u001b[?1049l"

    // Blocked, working, done, then idle and unknown together.
    private fun urgency(state: HerdrAgentStatus): Int = when (state) {
        HerdrAgentStatus.BLOCKED -> 0
        HerdrAgentStatus.WORKING -> 1
        HerdrAgentStatus.DONE -> 2
        HerdrAgentStatus.IDLE, HerdrAgentStatus.UNKNOWN -> 3
    }

    private val EXEC_HERDR = Regex("""\bexec\s+(\S*/)?herdr\b""")
}

/**
 * A jump to another Herdr session, between the detach key and the attach: [expect] it, feed
 * the terminal's output to [onOutput], which names the session to attach once the client
 * has left ([HerdrJump.clientLeft]) and never before, so nothing is typed into a pane.
 */
class HerdrReattach {
    @Volatile
    private var next: String? = null
    private var carry = ""

    /** The detach key went out for a jump to [session]. */
    fun expect(session: String) {
        carry = ""
        next = session
    }

    /** The client did not leave in time: attach nothing later. */
    fun giveUp(session: String) {
        if (next == session) next = null
    }

    /** The session to attach now that [text] showed the client leave; null otherwise. */
    fun onOutput(text: CharSequence): String? {
        val session = next ?: return null
        val window = carry + text
        carry = window.takeLast(HerdrJump.CARRY_CHARS)
        if (!HerdrJump.clientLeft(window)) return null
        next = null
        return session
    }
}
