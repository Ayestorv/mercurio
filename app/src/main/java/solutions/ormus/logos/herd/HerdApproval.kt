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

    /** The agent is still blocked, but Herdr's state changed since the prompt was shown, so nothing was sent. */
    data object PromptChanged : HerdAnswerOutcome

    /** Nothing or something unknown reached the pane; [reason] is one line for the notification. */
    data class Failed(val reason: String) : HerdAnswerOutcome
}

/**
 * A permission prompt Approve and Deny answer: Enter takes [approve], the highlighted
 * first option, and Esc takes [deny]. Both are the options as numbered on screen.
 */
data class ApprovalPrompt(val approve: String, val deny: String)

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

    /**
     * Agent kinds where a numbered option's own number picks it. Measured in the lab (issue
     * #145): at a claude prompt, `herdr pane send-keys <pane> N` selects option N and submits
     * it, no Enter. codex is not measured, so its options stay text.
     */
    val OPTION_KINDS = setOf("claude")

    /** Lines of recent output to read; the prompt is near the bottom. */
    const val READ_LINES = 40

    /** Lines of the prompt the notification shows. */
    const val TAIL_LINES = 12

    // Herdr pane ids; anything else is refused before it reaches a shell.
    private val PANE_ID = Regex("[A-Za-z0-9_.:-]+")

    // Herdr key names and option numbers; the same.
    private val KEYS = Regex("[a-z0-9]+")

    /** Whether [agent] is blocked and of a kind whose prompt Enter and Esc answer. */
    fun offersAnswers(agent: HerdrAgentInfo): Boolean = agent.agentStatus == HerdrAgentStatus.BLOCKED && agent.agent in PROVEN_KINDS

    /** Whether [paneId] is still an answerable blocked agent in [snapshot]. */
    fun stillBlocked(snapshot: HerdrSessionSnapshot, paneId: String): Boolean = snapshot.agents.any { it.paneId == paneId && offersAnswers(it) }

    /**
     * Whether an answer to [paneId] may go out, judged on a fresh [snapshot]: null when it
     * may, else why not. [seq] is the `state_change_seq` the user saw the prompt at; a
     * different one means the agent moved on and blocked again, on a prompt not yet shown.
     */
    fun guard(snapshot: HerdrSessionSnapshot, paneId: String, seq: Long?): HerdAnswerOutcome? {
        val agent = snapshot.agents.firstOrNull { it.paneId == paneId }
        return when {
            agent == null || !offersAnswers(agent) -> HerdAnswerOutcome.NotBlocked
            seq != null && agent.stateChangeSeq != seq -> HerdAnswerOutcome.PromptChanged
            else -> null
        }
    }

    /**
     * The yes-or-no permission prompt at the bottom of a pane's [screen], or null when the
     * screen ends on anything else (a question with picks, a plan, a trust check, no prompt).
     * It is one only when its numbered options start at a highlighted "Yes" (what Enter
     * takes), end at a plain "No" (what Esc takes), and nothing but a footer sits below.
     */
    fun classify(screen: String): ApprovalPrompt? {
        val lines = screen.lineSequence()
            .map { line -> line.trim().trim { it in BOX_DRAWING || it.isWhitespace() } }
            .filter { it.isNotEmpty() }
            .toList()
        val end = lines.indexOfLast { OPTION.matches(it) }
        if (end < 0 || lines.size - 1 - end > FOOTER_LINES) return null
        // Up from the last option to the one numbered 1; wrapped option text may sit between.
        val options = ArrayList<MatchResult>()
        var gap = 0
        for (i in end downTo 0) {
            val match = OPTION.matchEntire(lines[i])
            if (match == null) {
                if (++gap > FOOTER_LINES) return null
                continue
            }
            val number = match.groupValues[2].toInt()
            if (options.isNotEmpty() && number != options.last().groupValues[2].toInt() - 1) return null
            options += match
            gap = 0
            if (match.groupValues[2] == "1") break
        }
        val first = options.last()
        val last = options.first()
        val ok = first.groupValues[2] == "1" &&
            first.groupValues[1].isNotEmpty() &&
            APPROVE.matches(first.groupValues[3]) &&
            DENY.matches(last.groupValues[3]) &&
            options.count { NO.containsMatchIn(it.groupValues[3]) } == 1
        return if (ok) ApprovalPrompt("1. ${first.groupValues[3]}", "${last.groupValues[2]}. ${last.groupValues[3]}") else null
    }

    /**
     * The keys that pick each of [options] ("1. Yes", "2. No") on [agent]'s prompt, in order:
     * the option's number, one digit. Null when [agent] is not of a kind where that was
     * measured, or an option is not numbered 1 to 9 in a row from 1.
     */
    fun optionKeys(agent: String?, options: List<String>): List<String>? {
        if (agent !in OPTION_KINDS || options.isEmpty()) return null
        val keys = options.map { it.substringBefore('.') }
        return keys.takeIf { it == (1..keys.size).map(Int::toString) && keys.size <= 9 }
    }

    /** `herdr pane send-keys <pane> <key>` through a login shell, in [session]. */
    fun sendKeysCommand(paneId: String, answer: HerdAnswer, session: String = HerdrCommands.DEFAULT_SESSION): String = sendKeysCommand(paneId, answer.key, session)

    /** `herdr pane send-keys <pane> <keys>` through a login shell, in [session]; [keys] a key name or an option number. */
    fun sendKeysCommand(paneId: String, keys: String, session: String = HerdrCommands.DEFAULT_SESSION): String {
        require(PANE_ID.matches(paneId)) { "Unexpected Herdr pane id: $paneId" }
        require(KEYS.matches(keys)) { "Unexpected Herdr keys: $keys" }
        val herdr = if (HerdrSessions.isDefault(session)) "herdr" else "herdr --session ${HerdrSessions.quoted(session)}"
        return HerdrApi.loginShell("$herdr pane send-keys ${HerdrApi.shQuote(paneId)} $keys")
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

    // Lines a prompt's footer (and wrapped option text) may take below or between its options.
    private const val FOOTER_LINES = 3

    // "❯ 1. Yes", "› 1. Yes, proceed (y)", "  3. No": a numbered option and its highlight.
    private val OPTION = Regex("""^([❯›>]\s*)?(\d{1,2})[.)]\s+(.+)$""")

    // The first option Enter takes: a plain yes, never "Yes, and ..." with more attached.
    private val APPROVE = Regex("""Yes(?:, proceed)?(?: \(y\))?""")

    // The last option Esc takes: a plain no, or no and tell the agent what to do instead.
    private val DENY = Regex("""No(?:, and tell \S+ what to do differently)?(?: \(esc\))?""")
    private val NO = Regex("""^No\b""")
}
