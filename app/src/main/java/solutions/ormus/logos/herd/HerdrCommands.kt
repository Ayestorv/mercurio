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

/** The groups the Herdr command sheet shows, in order. */
enum class HerdrGroup { AGENTS, PANES, TABS, WORKSPACES, SESSION }

/** Which side a pane focus or swap moves toward; [cli] is Herdr's spelling. */
enum class HerdrDirection(val cli: String) { LEFT("left"), RIGHT("right"), UP("up"), DOWN("down") }

/**
 * Every Herdr action the phone offers. Most run a Herdr CLI command against the
 * focused ids in the latest snapshot, so they work whatever the keybindings are;
 * the [HerdrGroup.SESSION] ones are client keystrokes (prefix + key) sent to the PTY.
 *
 * [needsText]: asks for one line (a name) before it runs. [confirm]: closes or
 * stops something, so it asks once before it runs.
 */
sealed class HerdrAction(
    val group: HerdrGroup,
    val needsText: Boolean = false,
    val confirm: Boolean = false,
) {
    data object FocusNextBlocked : HerdrAction(HerdrGroup.AGENTS)

    /** Start [kind] (a Herdr agent kind such as claude) in the focused pane. */
    data class StartAgent(val kind: String) : HerdrAction(HerdrGroup.AGENTS)

    data object ExplainDetection : HerdrAction(HerdrGroup.AGENTS)

    /** Focus one agent's pane (a Herd card). */
    data class FocusAgent(val paneId: String) : HerdrAction(HerdrGroup.AGENTS)

    data object SplitRight : HerdrAction(HerdrGroup.PANES)

    data object SplitDown : HerdrAction(HerdrGroup.PANES)

    data object ZoomPane : HerdrAction(HerdrGroup.PANES)

    data class FocusPane(val direction: HerdrDirection) : HerdrAction(HerdrGroup.PANES)

    data class SwapPane(val direction: HerdrDirection) : HerdrAction(HerdrGroup.PANES)

    data object RenamePane : HerdrAction(HerdrGroup.PANES, needsText = true)

    data object ClosePane : HerdrAction(HerdrGroup.PANES, confirm = true)

    data object NewTab : HerdrAction(HerdrGroup.TABS)

    data object RenameTab : HerdrAction(HerdrGroup.TABS, needsText = true)

    data object CloseTab : HerdrAction(HerdrGroup.TABS, confirm = true)

    data class FocusTab(val tabId: String) : HerdrAction(HerdrGroup.TABS)

    data object NewWorkspace : HerdrAction(HerdrGroup.WORKSPACES)

    data object RenameWorkspace : HerdrAction(HerdrGroup.WORKSPACES, needsText = true)

    data object CloseWorkspace : HerdrAction(HerdrGroup.WORKSPACES, confirm = true)

    data class FocusWorkspace(val workspaceId: String) : HerdrAction(HerdrGroup.WORKSPACES)

    /** A client keystroke of the user's: the Herdr prefix, then [key] (bytes). */
    data class ClientKey(val key: String) : HerdrAction(HerdrGroup.SESSION)

    /** A Herdr client action: [key] as the host's Herdr binds it (usually the prefix, then a key). */
    data class BoundKey(val key: HerdrKey) : HerdrAction(HerdrGroup.SESSION)

    data object StopSession : HerdrAction(HerdrGroup.SESSION, confirm = true)

    companion object {
        /** The agents the sheet can start in the focused pane. */
        val START_KINDS = listOf("claude", "codex", "grok")

        // Client keystrokes: each one's bytes come from the host's Herdr config (HerdrKeys).
        val COPY_MODE = BoundKey(HerdrKey.COPY_MODE)
        val GOTO = BoundKey(HerdrKey.GOTO)
        val OPEN_NOTIFICATION = BoundKey(HerdrKey.OPEN_NOTIFICATION)
        val DETACH = BoundKey(HerdrKey.DETACH)
        val RELOAD_CONFIG = BoundKey(HerdrKey.RELOAD_CONFIG)
        val HELP = BoundKey(HerdrKey.HELP)
        val TOGGLE_SIDEBAR = BoundKey(HerdrKey.TOGGLE_SIDEBAR)

        // Herdr's workspace picker: focus moves into the side panel's workspace list.
        val NAVIGATE_WORKSPACES = BoundKey(HerdrKey.WORKSPACE_PICKER)
    }
}

/** What running an action means: a command over an exec channel, or bytes for the PTY. */
sealed interface HerdrInvocation {
    /** A shell command line, already wrapped in a login shell. */
    data class Cli(val command: String) : HerdrInvocation

    /** Raw bytes to write to the interactive session. */
    data class Keys(val sequence: String) : HerdrInvocation
}

/**
 * Builds the command for each [HerdrAction] from the latest snapshot. [session]
 * names a Herdr session other than the default one (the attached session is the
 * default; tests use a throwaway one).
 */
class HerdrCommands(private val session: String? = null) {
    init {
        require(session == null || ID.matches(session)) { "Unexpected Herdr session name: $session" }
    }

    /**
     * The invocation for [action] against [snapshot], or null when it has
     * nothing to act on (no focused pane, no blocked agent, a blank name, a client key
     * the host binds to nothing Mercurio can send). [text] is the name a rename action
     * asked for; [keys] are the host's Herdr keys, which client keystrokes send.
     */
    fun build(
        action: HerdrAction,
        snapshot: HerdrSessionSnapshot?,
        text: String? = null,
        keys: HerdrKeys = HerdrKeys.DEFAULT,
    ): HerdrInvocation? {
        val pane = snapshot?.focusedPaneId?.takeIf { ID.matches(it) }
        val tab = snapshot?.focusedTabId?.takeIf { ID.matches(it) }
        val workspace = snapshot?.focusedWorkspaceId?.takeIf { ID.matches(it) }
        val label = text?.trim()?.ifEmpty { null }
        return when (action) {
            HerdrAction.FocusNextBlocked -> nextBlocked(snapshot)?.let { focusAgent(it, snapshot) }

            is HerdrAction.StartAgent -> pane?.let {
                // Herdr agent names are unique per session, so name it after its pane;
                // answer inside the exec channel's timeout even if startup is slow.
                cli(
                    "agent", "start", q("${action.kind}-${it.replace(':', '-')}"),
                    "--kind", q(action.kind), "--pane", q(it), "--timeout", START_TIMEOUT_MS.toString(),
                )
            }

            HerdrAction.ExplainDetection -> pane?.let { cli("agent", "explain", q(it)) }

            is HerdrAction.FocusAgent -> focusAgent(action.paneId, snapshot)

            HerdrAction.SplitRight -> pane?.let { cli("pane", "split", q(it), "--direction", "right", "--focus") }

            HerdrAction.SplitDown -> pane?.let { cli("pane", "split", q(it), "--direction", "down", "--focus") }

            HerdrAction.ZoomPane -> pane?.let { cli("pane", "zoom", q(it), "--toggle") }

            is HerdrAction.FocusPane -> pane?.let { cli("pane", "focus", "--pane", q(it), "--direction", action.direction.cli) }

            is HerdrAction.SwapPane -> pane?.let { cli("pane", "swap", "--pane", q(it), "--direction", action.direction.cli) }

            HerdrAction.RenamePane -> if (pane != null && label != null) cli("pane", "rename", q(pane), q(label)) else null

            HerdrAction.ClosePane -> pane?.let { cli("pane", "close", q(it)) }

            HerdrAction.NewTab -> workspace?.let { cli("tab", "create", "--workspace", q(it), "--focus") }

            HerdrAction.RenameTab -> if (tab != null && label != null) cli("tab", "rename", q(tab), q(label)) else null

            HerdrAction.CloseTab -> tab?.let { cli("tab", "close", q(it)) }

            is HerdrAction.FocusTab -> cli("tab", "focus", q(action.tabId))

            HerdrAction.NewWorkspace -> cli("workspace", "create", "--focus")

            HerdrAction.RenameWorkspace ->
                if (workspace != null && label != null) cli("workspace", "rename", q(workspace), q(label)) else null

            HerdrAction.CloseWorkspace -> workspace?.let { cli("workspace", "close", q(it)) }

            is HerdrAction.FocusWorkspace -> cli("workspace", "focus", q(action.workspaceId))

            is HerdrAction.ClientKey -> HerdrInvocation.Keys(keys.prefix.bytes + action.key)

            is HerdrAction.BoundKey -> keys.bytes(action.key)?.let { HerdrInvocation.Keys(it) }

            HerdrAction.StopSession -> cli("session", "stop", q(session ?: DEFAULT_SESSION))
        }
    }

    /**
     * The blocked agent to jump to: the newest blocked agent that is not already
     * focused, so repeated taps walk through them; the focused one when it is the
     * only one.
     */
    private fun nextBlocked(snapshot: HerdrSessionSnapshot?): String? {
        val blocked = Herd.order(snapshot?.agents.orEmpty()).filter { it.agentStatus == HerdrAgentStatus.BLOCKED }
        return (blocked.firstOrNull { it.paneId != snapshot?.focusedPaneId } ?: blocked.firstOrNull())?.paneId
    }

    /**
     * Focus the agent in [paneId]. Herdr 0.9.0's `agent focus` moves Herdr's focus to a pane
     * in another tab but leaves an attached client showing the tab it was on; `tab focus`
     * moves the client (measured). So the agent's tab, when [snapshot] knows it, is focused
     * first, in the same login shell, and the pane after it.
     */
    private fun focusAgent(paneId: String, snapshot: HerdrSessionSnapshot?): HerdrInvocation.Cli {
        val tab = (snapshot?.agents?.firstOrNull { it.paneId == paneId }?.tabId ?: snapshot?.panes?.firstOrNull { it.paneId == paneId }?.tabId)
            ?.takeIf { ID.matches(it) }
            ?: return cli("agent", "focus", q(paneId))
        return HerdrInvocation.Cli(HerdrApi.loginShell(herdr("tab", "focus", q(tab)) + " && " + herdr("agent", "focus", q(paneId))))
    }

    /** `herdr [--session S] <words>` run through a login shell; values arrive already quoted by [q]. */
    private fun cli(vararg words: String): HerdrInvocation.Cli = HerdrInvocation.Cli(HerdrApi.loginShell(herdr(*words)))

    private fun herdr(vararg words: String): String {
        val session = session?.let { "--session ${q(it)} " }.orEmpty()
        return "herdr $session${words.joinToString(" ")}"
    }

    /** A value (id, name, kind) as one single-quoted shell word. */
    private fun q(value: String): String = HerdrApi.shQuote(value)

    companion object {
        /** Herdr's default session, the one `herdr` attaches to. */
        const val DEFAULT_SESSION = "default"

        /** How long `agent start` may wait for the agent, under SSH.exec's 15 s channel timeout. */
        const val START_TIMEOUT_MS = 12_000

        // Herdr ids and session names; anything else is refused before it reaches a shell.
        private val ID = Regex("[A-Za-z0-9_.:-]+")
    }
}
