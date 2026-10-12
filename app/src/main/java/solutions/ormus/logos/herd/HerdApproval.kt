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

/** An answer to a blocked agent from its notification; [key] is the Herdr key name sent to the pane. */
enum class HerdAnswer(val key: String) {
    /** Enter: takes the highlighted option, which is "Yes" on a fresh approval prompt. */
    APPROVE("enter"),

    /** Esc: declines and interrupts the agent's turn. */
    DENY("esc"),
}

/** What came of an answer. */
sealed interface HerdAnswerOutcome {
    /** The key went to the pane. */
    data object Sent : HerdAnswerOutcome

    /** The pane was no longer blocked (or no longer an answerable agent), so nothing was sent. */
    data object NotBlocked : HerdAnswerOutcome

    /** Nothing or something unknown reached the pane; [reason] is one line for the notification. */
    data class Failed(val reason: String) : HerdAnswerOutcome
}

/**
 * Which "Needs you" notices get Approve and Deny, and the commands behind them.
 *
 * Measured on Sun (herdr 0.9.0, own throwaway session): at a blocked permission
 * prompt, `herdr pane send-keys <pane> enter` ran the command and `esc`
 * declined it, for claude 2.1.289 and codex 0.159.3. Other agent kinds are not
 * proven, so they get no actions.
 */
object HerdApproval {
    /** Agent kinds where Enter and Esc were proven to mean yes and no. */
    val PROVEN_KINDS = setOf("claude", "codex")

    /** Lines of recent output to read; the prompt is near the bottom. */
    const val READ_LINES = 40

    /** Lines of the prompt the notification shows. */
    const val TAIL_LINES = 12

    // Herdr pane ids; anything else is refused before it reaches a shell.
    private val PANE_ID = Regex("[A-Za-z0-9_.:-]+")

    /** Whether [agent] is blocked and of a kind whose prompt Enter and Esc answer. */
    fun offersAnswers(agent: HerdrAgentInfo): Boolean = agent.agentStatus == HerdrAgentStatus.BLOCKED && agent.agent in PROVEN_KINDS

    /** Whether [paneId] is still an answerable blocked agent in [snapshot]. */
    fun stillBlocked(snapshot: HerdrSessionSnapshot, paneId: String): Boolean = snapshot.agents.any { it.paneId == paneId && offersAnswers(it) }

    /** `herdr pane send-keys <pane> <key>` through a login shell, in [session]. */
    fun sendKeysCommand(paneId: String, answer: HerdAnswer, session: String = HerdrCommands.DEFAULT_SESSION): String {
        require(PANE_ID.matches(paneId)) { "Unexpected Herdr pane id: $paneId" }
        val herdr = if (HerdrSessions.isDefault(session)) "herdr" else "herdr --session ${HerdrSessions.quoted(session)}"
        return HerdrApi.loginShell("$herdr pane send-keys ${HerdrApi.shQuote(paneId)} ${answer.key}")
    }

    /**
     * The last [lines] lines of a pane read worth reading: blank lines and rules made
     * only of box-drawing characters are dropped. Null when nothing is left.
     */
    fun tail(text: String, lines: Int = TAIL_LINES): String? = text.lineSequence()
        .map { it.trimEnd() }
        .filter { line -> line.any { !it.isWhitespace() && it !in BOX_DRAWING } }
        .toList()
        .takeLast(lines)
        .joinToString("\n")
        .ifEmpty { null }

    private val BOX_DRAWING = '─'..'╿'
}
