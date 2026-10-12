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
import kotlinx.coroutines.async
import kotlinx.coroutines.withTimeoutOrNull
import org.connectbot.transport.CommandOutput
import kotlin.time.TimeSource

/** One workspace on the connect picker, in Herdr [session]; [state] is its most urgent agent state. */
data class WorkspaceChoice(
    val workspaceId: String,
    val number: Int,
    val label: String,
    val focused: Boolean,
    val state: HerdrAgentStatus,
    val session: String = HerdrCommands.DEFAULT_SESSION,
)

/** What the connect picker answered. */
sealed interface WorkspacePick {
    /** The gold key: attach to the workspace that already has focus. */
    data object Attach : WorkspacePick

    /** Back: attach as before, without a choice. */
    data object Dismiss : WorkspacePick

    /** A row: focus [workspaceId] in [session], then attach to that session. */
    data class Focus(val workspaceId: String, val session: String = HerdrCommands.DEFAULT_SESSION) : WorkspacePick
}

/**
 * The workspace picker a Herdr host shows on connect, before its post-login
 * command attaches: one row per workspace of every running Herdr session, so a
 * tap focuses one first and the attach lands there. Herdr's focus is the
 * server's, so a new client opens on the focused workspace (measured on Sun,
 * herdr 0.9.0). A row in another session attaches to that session instead
 * ([postLogin]).
 *
 * The picker never holds a connection up: no snapshot inside
 * [SNAPSHOT_WAIT_MS], stopped servers, or one workspace in all means attach at once.
 */
object HerdrWorkspacePicker {
    /** How long a connect waits for the snapshot before it attaches as before. */
    const val SNAPSHOT_WAIT_MS = 1_500L

    /** How long a picked workspace's focus may take before the attach goes ahead anyway. */
    const val FOCUS_WAIT_MS = 3_000L

    /**
     * Whether [postLogin] attaches Herdr's default session, the one the snapshot
     * and the focus command talk to. A named session or a remote attach gets no picker.
     */
    fun offers(postLogin: String?): Boolean {
        val command = postLogin ?: return false
        return command.contains("herdr", ignoreCase = true) && OTHER_SESSION.none { command.contains(it) }
    }

    /**
     * The rows to offer from each session's snapshot: the default session first, the others
     * by name, each by workspace number. Null when the connect should attach at once. Only
     * the default session's focused workspace is marked: that is where the gold key attaches.
     */
    fun choices(snapshots: Map<String, HerdrSessionSnapshot>): List<WorkspaceChoice>? = snapshots.entries
        .sortedWith(compareBy<Map.Entry<String, HerdrSessionSnapshot>> { !HerdrSessions.isDefault(it.key) }.thenBy { it.key })
        .flatMap { (session, snapshot) -> rows(session, snapshot) }
        .takeIf { it.size >= 2 }

    private fun rows(session: String, snapshot: HerdrSessionSnapshot): List<WorkspaceChoice> = snapshot.workspaces.sortedBy { it.number }.map { workspace ->
        val id = workspace.workspaceId
        val states = snapshot.agents.filter { it.workspaceId == id }.map { it.agentStatus } +
            snapshot.panes.filter { it.workspaceId == id }.map { it.agentStatus } +
            workspace.agentStatus
        WorkspaceChoice(
            workspaceId = id,
            number = workspace.number,
            label = workspace.label,
            focused = HerdrSessions.isDefault(session) && id == snapshot.focusedWorkspaceId,
            state = mostUrgent(states),
            session = session,
        )
    }

    /** The state a workspace shows: blocked, then working, done, idle; unknown when none says. */
    fun mostUrgent(states: Iterable<HerdrAgentStatus>): HerdrAgentStatus = states.minByOrNull(::urgency) ?: HerdrAgentStatus.UNKNOWN

    private fun urgency(status: HerdrAgentStatus): Int = when (status) {
        HerdrAgentStatus.BLOCKED -> 0
        HerdrAgentStatus.WORKING -> 1
        HerdrAgentStatus.DONE -> 2
        HerdrAgentStatus.IDLE -> 3
        HerdrAgentStatus.UNKNOWN -> 4
    }

    /**
     * Fetch every running session's snapshot, [ask] when there are workspaces to choose
     * from, and focus the picked one. Returns the pick, or null when no picker was shown;
     * either way the caller sends [postLogin] next. [run] runs one command over an exec
     * channel with a timeout and blocks; it may throw. Blocking work runs in [scope] on
     * [io], so a slow host never holds this up past the waits: the default session's
     * snapshot and the session list share one wait, the other sessions' snapshots one more.
     */
    suspend fun offer(
        scope: CoroutineScope,
        io: CoroutineDispatcher,
        run: (command: String, timeoutMs: Long) -> CommandOutput?,
        ask: suspend (List<WorkspaceChoice>) -> WorkspacePick,
    ): WorkspacePick? {
        val started = TimeSource.Monotonic.markNow()
        val listing = scope.async(io) { runCatching { run(HerdrSessions.listCommand, SNAPSHOT_WAIT_MS) }.getOrNull() }
        val snapshots = LinkedHashMap<String, HerdrSessionSnapshot>()
        within(scope, io, SNAPSHOT_WAIT_MS) { run(Herd.snapshotCommand, SNAPSHOT_WAIT_MS) }.snapshot()
            ?.let { snapshots[HerdrCommands.DEFAULT_SESSION] = it }
        val left = (SNAPSHOT_WAIT_MS - started.elapsedNow().inWholeMilliseconds).coerceAtLeast(1)
        val others = withTimeoutOrNull(left) { listing.await() }.ok()?.let(HerdrSessions::parse).orEmpty()
            .filterNot(HerdrSessions::isDefault)
        val fetches = others.associateWith { session ->
            scope.async(io) { runCatching { run(HerdrSessions.snapshotCommand(session), SNAPSHOT_WAIT_MS) }.getOrNull() }
        }
        withTimeoutOrNull(SNAPSHOT_WAIT_MS) {
            fetches.forEach { (session, fetch) -> fetch.await().snapshot()?.let { snapshots[session] = it } }
        }
        val choices = choices(snapshots) ?: return null
        val pick = ask(choices)
        if (pick is WorkspacePick.Focus) {
            val focus = HerdrSessions.commands(pick.session)
                .build(HerdrAction.FocusWorkspace(pick.workspaceId), snapshots[pick.session]) as HerdrInvocation.Cli
            within(scope, io, FOCUS_WAIT_MS) { run(focus.command, FOCUS_WAIT_MS) }
        }
        return pick
    }

    /**
     * The post-login to send after [pick]: as it is, unless the pick is in another session,
     * where its `herdr` launch becomes `herdr session attach <session>` (herdr 0.9.0's way to
     * attach a named session that runs).
     */
    fun postLogin(postLogin: String, pick: WorkspacePick?): String {
        val session = (pick as? WorkspacePick.Focus)?.session ?: return postLogin
        if (HerdrSessions.isDefault(session)) return postLogin
        return HERDR_LAUNCH.replaceFirst(postLogin, "$0 session attach ${HerdrSessions.quoted(session)}")
    }

    private fun CommandOutput?.ok(): String? = this?.takeIf { it.exitCode == null || it.exitCode == 0 }?.stdout

    private fun CommandOutput?.snapshot(): HerdrSessionSnapshot? = ok()?.let(::parseHerdrSnapshot)

    // The command runs apart from the caller, so a host that never answers costs
    // the caller [waitMs] and no more; a failure reads as no answer.
    private suspend fun within(
        scope: CoroutineScope,
        io: CoroutineDispatcher,
        waitMs: Long,
        block: () -> CommandOutput?,
    ): CommandOutput? {
        val result = scope.async(io) { runCatching(block).getOrNull() }
        return withTimeoutOrNull(waitMs) { result.await() }
    }

    // Post-login words that attach something other than the default session.
    private val OTHER_SESSION = listOf("--session", "session attach", "--remote")

    // The `herdr` word that launches Herdr in a post-login (`herdr`, `exec herdr`, a path to it).
    private val HERDR_LAUNCH = Regex("""(?<![\w.-])herdr(?=\s|$)""")
}
