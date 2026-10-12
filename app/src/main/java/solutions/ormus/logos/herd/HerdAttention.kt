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

import org.connectbot.data.entity.Host

/**
 * Decides which Herdr state changes deserve a phone notification, so a Herdr
 * session notifies from what Herdr knows instead of the bell and idle guesses:
 *
 * - nothing on the first snapshot after a connect (that is the state you left,
 *   not news);
 * - an agent that turns blocked and stays blocked for [holdMs] gets one
 *   [Notice.NeedsYou] (a momentary blocked, an agent asking itself, does not);
 * - the same pane blocking again within [repeatWindowMs] of that notice gets
 *   nothing more;
 * - an agent that goes from blocked or working to done gets one [Notice.Finished];
 * - nothing for the agent you are already looking at (its session on screen and
 *   its pane focused in Herdr).
 *
 * Pure: feed it each poll with the time from [clock]; it keeps per-pane memory.
 */
class HerdAttention(
    private val clock: () -> Long,
    private val holdMs: Long = HOLD_MS,
    private val repeatWindowMs: Long = REPEAT_WINDOW_MS,
) {
    /** One notification to post, about [agent] in Herdr [session], with where it lives. */
    sealed interface Notice {
        val agent: HerdrAgentInfo
        val workspaceLabel: String?
        val tabLabel: String?
        val session: String

        /** [paneTail]: the last lines of the blocked pane, what Approve would answer. */
        data class NeedsYou(
            override val agent: HerdrAgentInfo,
            override val workspaceLabel: String?,
            override val tabLabel: String?,
            val paneTail: String? = null,
            override val session: String = HerdrCommands.DEFAULT_SESSION,
        ) : Notice

        data class Finished(
            override val agent: HerdrAgentInfo,
            override val workspaceLabel: String?,
            override val tabLabel: String?,
            override val session: String = HerdrCommands.DEFAULT_SESSION,
        ) : Notice
    }

    private var primed = false
    private val last = HashMap<String, HerdrAgentStatus>()

    // pane -> when its current blocked spell started, until it is notified or ends.
    private val blockedSince = HashMap<String, Long>()

    // pane -> when its last "Needs you" was due (posted, or seen on screen).
    private val lastNeedsYou = HashMap<String, Long>()

    /** Forget everything: the next snapshot is the first after a connect. */
    @Synchronized
    fun reset() {
        primed = false
        last.clear()
        blockedSince.clear()
        lastNeedsYou.clear()
    }

    /**
     * The user answered [paneId] from its notification: its next block is news, even
     * inside the repeat window.
     */
    @Synchronized
    fun answered(paneId: String) {
        lastNeedsYou.remove(paneId)
    }

    /**
     * The notices for [snapshot]. [sessionOnScreen]: this session's console is
     * what the user sees right now, so its focused pane is being looked at.
     */
    @Synchronized
    fun onSnapshot(snapshot: HerdrSessionSnapshot, sessionOnScreen: Boolean): List<Notice> {
        val now = clock()
        val current = snapshot.agents.associate { it.paneId to it.agentStatus }
        if (!primed) {
            primed = true
            last.putAll(current)
            return emptyList()
        }
        val notices = ArrayList<Notice>()
        for (agent in snapshot.agents) {
            val pane = agent.paneId
            val before = last[pane]
            val watching = sessionOnScreen && snapshot.focusedPaneId == pane
            when (agent.agentStatus) {
                HerdrAgentStatus.BLOCKED -> {
                    if (before != HerdrAgentStatus.BLOCKED) blockedSince[pane] = now
                    val since = blockedSince[pane]
                    if (since != null && now - since >= holdMs) {
                        blockedSince.remove(pane)
                        val previous = lastNeedsYou[pane]
                        if (previous == null || now - previous >= repeatWindowMs) {
                            lastNeedsYou[pane] = now
                            if (!watching) notices += Notice.NeedsYou(agent, snapshot.workspaceLabel(agent.workspaceId), snapshot.tabLabel(agent.tabId))
                        }
                    }
                }

                HerdrAgentStatus.DONE -> {
                    blockedSince.remove(pane)
                    val finished = before == HerdrAgentStatus.BLOCKED || before == HerdrAgentStatus.WORKING
                    if (finished && !watching) {
                        notices += Notice.Finished(agent, snapshot.workspaceLabel(agent.workspaceId), snapshot.tabLabel(agent.tabId))
                    }
                }

                else -> blockedSince.remove(pane)
            }
        }
        // Panes that closed take their memory with them.
        last.keys.retainAll(current.keys)
        blockedSince.keys.retainAll(current.keys)
        last.putAll(current)
        return notices
    }

    companion object {
        /**
         * Whether the bell and idle guesses may notify for [host] when the user is
         * [away] from it: never for a Herdr session, which notifies from Herdr's
         * own status instead; a host without Herdr keeps today's behaviour.
         */
        fun bellMayNotify(host: Host, away: Boolean): Boolean = away && !Herd.runsHerdr(host)

        /** How long an agent must stay blocked before it is worth a notification. */
        const val HOLD_MS = 5_000L

        /** A pane that blocks again this soon after its last "Needs you" stays quiet. */
        const val REPEAT_WINDOW_MS = 60_000L
    }
}
