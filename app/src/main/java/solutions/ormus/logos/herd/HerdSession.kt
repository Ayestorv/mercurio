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

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.connectbot.service.FixPrompt
import org.connectbot.transport.CommandOutput
import timber.log.Timber

/** How the last Herdr action went, for the one result line in the command sheet. */
sealed interface HerdResult {
    val action: HerdrAction

    data class Running(override val action: HerdrAction) : HerdResult

    /** It worked; [output] is what it printed when the sheet should show it (explain). */
    data class Succeeded(override val action: HerdrAction, val output: String? = null) : HerdResult

    /** Nothing to act on yet (no focused pane, no blocked agent, a blank name). */
    data class Unavailable(override val action: HerdrAction) : HerdResult

    /** It failed; [message] is Herdr's error, one short line. */
    data class Failed(override val action: HerdrAction, val message: String) : HerdResult
}

/**
 * A Herdr command that failed: what ran, its exit code (null when it threw or never ran),
 * and its output. [sessionUp] is false when no SSH session was there to run it.
 */
data class HerdrFailure(
    val command: String,
    val exitCode: Int?,
    val stderr: String,
    val stdout: String,
    val sessionUp: Boolean,
)

// Esc leaves Herdr's prefix mode and is swallowed there.
private val ESC: String = 27.toChar().toString()

/**
 * The live Herdr side of one SSH session whose host runs Herdr: polls the
 * snapshot with [HerdrMonitor] while the session is up, and runs the command
 * sheet's actions against the focused ids in the latest snapshot.
 *
 * The host may run more Herdr sessions than the default one ([sessions]). The
 * monitor, the attention rules and [snapshot] stay on the default session; the
 * others are fetched when asked ([refresh] with a session name, kept in [others]),
 * and [perform] and [answer] take the session to act on. When the terminal attached
 * another session ([attached]), each poll fetches that one too.
 *
 * [runner] runs one command over an exec channel beside the PTY and blocks
 * (SSH.execDetailed); null means it never ran. [sendKeys] writes client
 * keystrokes to the PTY; [sendKey] presses a key there through the terminal, which
 * encodes it in the host's modes (DECCKM for arrows) and writes it after what was sent
 * before. Blocking work runs on [io].
 */
class HerdSession(
    private val hostName: String,
    private val runner: (String) -> CommandOutput?,
    private val sendKeys: (String) -> Unit,
    private val scope: CoroutineScope,
    private val io: CoroutineDispatcher,
    private val commands: HerdrCommands = HerdrCommands(),
    pollIntervalMs: Long = POLL_INTERVAL_MS,
    /** The time for the attention rules (a fake clock in tests). */
    clock: () -> Long = System::currentTimeMillis,
    /** Whether this session's console is on screen now (the user is looking at its focused pane). */
    private val isOnScreen: () -> Boolean = { false },
    /** Notifications the attention rules want posted. */
    private val onNotices: (List<HerdAttention.Notice>) -> Unit = {},
    private val sendKey: (HerdrTermKey) -> Unit = {},
) {
    private val attention = HerdAttention(clock)

    /** When each agent entered its state, from every snapshot this side reads (the Agents board). */
    val since = AgentSince(clock)

    private val _snapshot = MutableStateFlow<HerdrSessionSnapshot?>(null)

    /** The default session's latest snapshot, null until the first poll lands. */
    val snapshot: StateFlow<HerdrSessionSnapshot?> = _snapshot.asStateFlow()

    private val _sessions = MutableStateFlow(listOf(HerdrCommands.DEFAULT_SESSION))

    /** The running Herdr sessions on the host, the default first; just the default until listed. */
    val sessions: StateFlow<List<String>> = _sessions.asStateFlow()

    private val _others = MutableStateFlow<Map<String, HerdrSessionSnapshot>>(emptyMap())

    /** The latest snapshot of each other running session that was fetched, by name. */
    val others: StateFlow<Map<String, HerdrSessionSnapshot>> = _others.asStateFlow()

    private val _attached = MutableStateFlow(HerdrCommands.DEFAULT_SESSION)

    /** The Herdr session the terminal attached to: the default unless the connect picker attached another. */
    val attached: StateFlow<String> = _attached.asStateFlow()

    private val _chosen = MutableStateFlow(HerdrCommands.DEFAULT_SESSION)

    /** The session the Herdr panel acts on: the attached one until the user picks another. */
    val chosen: StateFlow<String> = _chosen.asStateFlow()

    private val _failure = MutableStateFlow<HerdrFailure?>(null)

    /** The last Herdr command that failed on this host, null once one succeeds. */
    val failure: StateFlow<HerdrFailure?> = _failure.asStateFlow()

    private val _result = MutableStateFlow<HerdResult?>(null)

    /** The last action's outcome, null before any. */
    val result: StateFlow<HerdResult?> = _result.asStateFlow()

    private val _keys = MutableStateFlow(HerdrKeys.DEFAULT)

    /** Herdr's prefix and client keys on this host, read from its config when the session starts. */
    val keys: StateFlow<HerdrKeys> = _keys.asStateFlow()

    private val _crossesMachines = MutableStateFlow(false)

    /**
     * Whether the host's Herdr client has a saved machine enabled ([HerdrMachines]): workspace
     * swipes then go through Herdr's switcher, the one way to reach another machine.
     */
    val crossesMachines: StateFlow<Boolean> = _crossesMachines.asStateFlow()

    private val monitor = HerdrMonitor(
        hostName = hostName,
        runCommand = { command -> stdoutOrNull(command) },
        scope = CoroutineScope(scope.coroutineContext + io),
        pollIntervalMs = pollIntervalMs,
        onSnapshot = { snapshot ->
            since.observe(HerdrCommands.DEFAULT_SESSION, snapshot)
            _snapshot.value = snapshot
            val notices = attention.onSnapshot(snapshot, isOnScreen())
            if (notices.isNotEmpty()) onNotices(notices.map(::withPaneTail))
            // Swipes act on the attached session: keep its focused ids as fresh as the default's.
            val other = _attached.value
            if (!HerdrSessions.isDefault(other)) {
                stdoutOrNull(HerdrSessions.snapshotCommand(other))?.let(::parseHerdrSnapshot)?.let { keep(other, it) }
            }
        },
    )

    // A "Needs you" carries the end of its pane, so the notification shows what Approve
    // answers. Runs on the poller's io thread; the text is never logged.
    private fun withPaneTail(notice: HerdAttention.Notice): HerdAttention.Notice {
        if (notice !is HerdAttention.Notice.NeedsYou) return notice
        val read = stdoutOrNull(Herd.readCommand(notice.agent.paneId, HerdApproval.READ_LINES))
        return notice.copy(paneTail = read?.let { HerdApproval.tail(it) })
    }

    /** Start polling (again); a restart forgets the old state so it is not read as a transition. */
    fun start() {
        attention.reset()
        monitor.stop()
        monitor.start()
        scope.launch {
            readKeys()
            refreshSessions()
            readMachines()
        }
    }

    fun stop() {
        monitor.stop()
    }

    /** Fetch [session]'s snapshot now, outside the poll rhythm (after an action, or on opening a screen). */
    suspend fun refresh(session: String = HerdrCommands.DEFAULT_SESSION) {
        val raw = withContext(io) { stdoutOrNull(HerdrSessions.snapshotCommand(session)) } ?: return
        parseHerdrSnapshot(raw)?.let { keep(session, it) }
    }

    /** List the running sessions again and fetch every one's snapshot (the Jump to list). */
    suspend fun refreshAll() {
        refreshSessions()
        readMachines()
        _sessions.value.forEach { refresh(it) }
    }

    /** The last [lines] lines of [paneId] in [session], as plain text; null when the read failed. */
    suspend fun readPane(paneId: String, session: String, lines: Int = HerdApproval.READ_LINES): String? = withContext(io) { stdoutOrNull(AgentsBoard.readCommand(session, paneId, lines)) }

    /** [session]'s latest snapshot, null until one landed. */
    fun snapshotOf(session: String): HerdrSessionSnapshot? = if (HerdrSessions.isDefault(session)) _snapshot.value else _others.value[session]

    /**
     * List the host's running Herdr sessions again. Off the failure line, like [readKeys]: a
     * Herdr without `session list --json` simply keeps the default session alone.
     */
    suspend fun refreshSessions() {
        val out = withContext(io) { runCatching { runner(HerdrSessions.listCommand) }.getOrNull() }
        val running = out?.takeIf { it.exitCode == null || it.exitCode == 0 }?.stdout?.let(HerdrSessions::parse) ?: return
        val names = (listOf(HerdrCommands.DEFAULT_SESSION) + running).distinct()
        _sessions.value = names
        _others.update { snapshots -> snapshots.filterKeys { it in names } }
        if (_chosen.value !in names) _chosen.value = HerdrCommands.DEFAULT_SESSION
    }

    /**
     * Read whether the host's Herdr client has saved machines. Off the failure line, like
     * [refreshSessions]: a Herdr without `machine list` simply has none; a read that never
     * ran keeps what was known.
     */
    suspend fun readMachines() {
        val out = withContext(io) { runCatching { runner(HerdrMachines.listCommand) }.getOrNull() } ?: return
        _crossesMachines.value = out.takeIf { it.exitCode == null || it.exitCode == 0 }?.stdout?.let(HerdrMachines::anyEnabled) ?: false
    }

    /** Act on [session] from now on in the Herdr panel; its snapshot follows. */
    fun choose(session: String) {
        _chosen.value = session
    }

    /** The terminal attached [session]: swipes and the panel act on it from now on. */
    fun attach(session: String) {
        _attached.value = session
        _chosen.value = session
    }

    private fun keep(session: String, snapshot: HerdrSessionSnapshot) {
        since.observe(session, snapshot)
        if (HerdrSessions.isDefault(session)) _snapshot.value = snapshot else _others.update { it + (session to snapshot) }
    }

    /**
     * Read Herdr's keys from the host's config. Off the failure line on purpose: a
     * config that cannot be read is the prefix's [HerdrPrefix.problem], not a Herdr failure.
     */
    suspend fun readKeys() {
        val out = withContext(io) {
            try {
                runner(HerdrPrefix.readCommand)
            } catch (e: Exception) {
                Timber.w(e, "Reading the Herdr keys failed on %s", hostName)
                null
            }
        }
        _keys.value = HerdrKeys.fromRead(out)
    }

    // Whether Herdr's prefix ([keys]) went ahead of the next keystroke: the Herdr key
    // sends it as the panel opens. Herdr stays in prefix mode until a key arrives
    // (measured on Sun, herdr 0.9.0), so it is either used by the next Herdr keystroke
    // or cancelled with Esc, which Herdr swallows.
    private var prefixArmed = false

    /** Send Herdr's prefix now, so the next Herdr keystroke needs only its key. */
    fun armPrefix() {
        if (prefixArmed) return
        prefixArmed = true
        sendKeys(_keys.value.prefix.bytes)
    }

    /** Leave prefix mode without acting: Esc, which Herdr swallows. Nothing when not armed. */
    fun cancelPrefix() {
        if (!prefixArmed) return
        prefixArmed = false
        sendKeys(ESC)
    }

    /**
     * What to write for [sequence] given the armed prefix: a Herdr keystroke (prefix, then
     * key) sends just its key; anything else cancels prefix mode first.
     */
    fun keystroke(sequence: String): String {
        if (!prefixArmed) return sequence
        val prefix = _keys.value.prefix.bytes
        if (sequence.startsWith(prefix)) {
            prefixArmed = false
            return sequence.removePrefix(prefix)
        }
        cancelPrefix()
        return sequence
    }

    /**
     * Move the attached client one workspace [down] (the next in the sidebar) or up through
     * Herdr's own switcher, which walks every connected machine's workspaces where `workspace
     * focus` reaches only this host's. The keys go out together: herdr 0.9.3 took them in one
     * write every time (measured). Returns why nothing was sent when the host binds a switcher
     * key to nothing Mercurio can send.
     */
    fun switchWorkspace(down: Boolean): String? {
        val keys = _keys.value
        val strokes = keys.switchWorkspace(down) ?: return keys.switchProblem(down)
        strokes.forEach { stroke ->
            when (stroke) {
                is HerdrStroke.Bytes -> sendKeys(keystroke(stroke.bytes))
                is HerdrStroke.Key -> sendKey(stroke.key)
            }
        }
        scope.launch { refresh(_attached.value) }
        return null
    }

    /** Clear the result line (the sheet closed). */
    fun clearResult() {
        _result.value = null
    }

    /**
     * Run [action] (with [text] for a rename) in [session]. Keystrokes go to the PTY at
     * once, whatever the session; commands run off the main thread, and success refreshes
     * that session's snapshot.
     */
    fun perform(action: HerdrAction, text: String? = null, session: String = HerdrCommands.DEFAULT_SESSION) {
        val builder = if (HerdrSessions.isDefault(session)) commands else HerdrSessions.commands(session)
        when (val invocation = builder.build(action, snapshotOf(session), text, _keys.value)) {
            null -> _result.value = HerdResult.Unavailable(action)

            is HerdrInvocation.Keys -> {
                sendKeys(keystroke(invocation.sequence))
                _result.value = HerdResult.Succeeded(action)
            }

            is HerdrInvocation.Cli -> {
                // A command runs beside the PTY; a waiting prefix would eat the next keystroke.
                cancelPrefix()
                _result.value = HerdResult.Running(action)
                scope.launch {
                    val out = withContext(io) { run(invocation.command) }
                    val error = HerdrApi.parseCommandError(out)
                    _result.value = if (error != null) {
                        HerdResult.Failed(action, oneLine(error.message.ifBlank { error.code }))
                    } else {
                        HerdResult.Succeeded(action, out?.stdout?.trim()?.takeIf { action == HerdrAction.ExplainDetection })
                    }
                    if (error == null) refresh(session)
                }
            }
        }
    }

    /**
     * Answer the blocked agent in [session]'s [paneId] from its notification: read a fresh
     * snapshot of that session and send [answer]'s key only if that pane is still blocked
     * on an answerable agent.
     */
    suspend fun answer(paneId: String, answer: HerdAnswer, session: String = HerdrCommands.DEFAULT_SESSION): HerdAnswerOutcome = withContext(io) {
        val snapshot = stdoutOrNull(HerdrSessions.snapshotCommand(session))?.let { parseHerdrSnapshot(it) }
            ?: return@withContext HerdAnswerOutcome.Failed(FixPrompt.herdrReason(_failure.value))
        keep(session, snapshot)
        if (!HerdApproval.stillBlocked(snapshot, paneId)) return@withContext HerdAnswerOutcome.NotBlocked
        val error = HerdrApi.parseCommandError(run(HerdApproval.sendKeysCommand(paneId, answer, session)))
        if (error != null) return@withContext HerdAnswerOutcome.Failed(oneLine(error.message.ifBlank { error.code }))
        // The attention rules watch the default session only.
        if (HerdrSessions.isDefault(session)) attention.answered(paneId)
        HerdAnswerOutcome.Sent
    }

    /**
     * Reply to the agent waiting in [session]'s [paneId] from its board card: read a fresh
     * snapshot of that session and run [command], the reply typed into that pane, only if
     * the pane still holds a blocked agent.
     */
    suspend fun reply(paneId: String, session: String, command: String): HerdAnswerOutcome = withContext(io) {
        val snapshot = stdoutOrNull(HerdrSessions.snapshotCommand(session))?.let { parseHerdrSnapshot(it) }
            ?: return@withContext HerdAnswerOutcome.Failed(FixPrompt.herdrReason(_failure.value))
        keep(session, snapshot)
        if (snapshot.agents.none { it.paneId == paneId && it.agentStatus == HerdrAgentStatus.BLOCKED }) return@withContext HerdAnswerOutcome.NotBlocked
        val error = HerdrApi.parseCommandError(run(command))
        if (error != null) return@withContext HerdAnswerOutcome.Failed(oneLine(error.message.ifBlank { error.code }))
        HerdAnswerOutcome.Sent
    }

    private fun stdoutOrNull(command: String): String? = run(command)?.takeIf { it.exitCode == null || it.exitCode == 0 }?.stdout

    private fun run(command: String): CommandOutput? {
        val out = try {
            runner(command)
        } catch (e: Exception) {
            // Timeouts, a dropped connection: report it as "never ran", not a crash.
            Timber.w(e, "Herdr command failed on %s", hostName)
            _failure.value = HerdrFailure(command, exitCode = null, stderr = e.toString(), stdout = "", sessionUp = true)
            return null
        }
        _failure.value = when {
            out == null -> HerdrFailure(command, exitCode = null, stderr = "", stdout = "", sessionUp = false)
            out.exitCode != null && out.exitCode != 0 -> HerdrFailure(command, out.exitCode, out.stderr, out.stdout, sessionUp = true)
            else -> null
        }
        return out
    }

    private fun oneLine(message: String): String = message.lineSequence().firstOrNull { it.isNotBlank() }?.trim()?.take(MAX_ERROR_CHARS) ?: message

    companion object {
        const val POLL_INTERVAL_MS = 4_000L
        private const val MAX_ERROR_CHARS = 160
    }
}
