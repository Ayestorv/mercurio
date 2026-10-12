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

import org.connectbot.transport.CommandOutput

/**
 * A Herdr client action Mercurio sends as keystrokes: [config] is its name under
 * `[keys]` in Herdr's config, [default] Herdr's own binding when the config sets none.
 *
 * The defaults are Herdr 0.9.0's: `herdr --default-config` lists each one, except
 * copy_mode, which it leaves out; its "prefix+[" is from the defaults compiled into
 * the herdr 0.9.0 binary, in its key order between edit_scrollback and focus_pane_left.
 */
enum class HerdrKey(val config: String, val default: String) {
    TOGGLE_SIDEBAR("toggle_sidebar", "prefix+b"),
    COPY_MODE("copy_mode", "prefix+["),
    GOTO("goto", "prefix+g"),
    OPEN_NOTIFICATION("open_notification_target", "prefix+o"),
    DETACH("detach", "prefix+q"),
    RELOAD_CONFIG("reload_config", "prefix+shift+r"),
    HELP("help", "prefix+?"),
    WORKSPACE_PICKER("workspace_picker", "prefix+w"),
    PREVIOUS_TAB("previous_tab", "prefix+p"),
    NEXT_TAB("next_tab", "prefix+n"),
}

/**
 * One [HerdrKey] on a host: [spec], the binding Mercurio uses as Herdr spells it, and
 * [bytes], what it writes for it (the prefix included). Null [bytes] means the key is off,
 * and [problem] says why.
 */
data class HerdrBinding(val spec: String?, val bytes: String?, val problem: String? = null)

/** A key the terminal encodes in the host's modes: arrows by DECCKM (ESC [ B, or ESC O B), Enter by LNM. */
enum class HerdrTermKey { UP, DOWN, LEFT, RIGHT, ENTER }

/**
 * One keystroke: [Bytes] written as they are, or a [Key] the terminal encodes. Keys reach
 * the PTY after the bytes written before them, so raw bytes never follow a key.
 */
sealed interface HerdrStroke {
    data class Bytes(val bytes: String) : HerdrStroke

    data class Key(val key: HerdrTermKey) : HerdrStroke
}

/** A key of Herdr's navigate mode on one host: [stroke] for [spec], or null when [problem] says why not. */
data class HerdrNavigateKey(val spec: String?, val stroke: HerdrStroke?, val problem: String? = null)

/**
 * Herdr's keys on one host: its [prefix], the binding of every [HerdrKey], and the
 * navigate-mode keys that move up and down Herdr's workspace switcher.
 */
data class HerdrKeys(
    val prefix: HerdrPrefix,
    val bindings: Map<HerdrKey, HerdrBinding>,
    val workspaceUp: HerdrNavigateKey,
    val workspaceDown: HerdrNavigateKey,
) {
    /** What to write for [key], or null when the host binds it to nothing Mercurio can send. */
    fun bytes(key: HerdrKey): String? = bindings[key]?.bytes

    /** Why [key] is off on this host; null when it can be sent. */
    fun problem(key: HerdrKey): String? = bindings[key]?.problem

    /**
     * One step through Herdr's workspace switcher, [down] (the next workspace in sidebar
     * order, on any connected machine) or up: workspace_picker, the navigate key, Enter.
     * Null when the host binds either key to nothing Mercurio can send ([switchProblem]).
     */
    fun switchWorkspace(down: Boolean): List<HerdrStroke>? {
        val picker = bytes(HerdrKey.WORKSPACE_PICKER) ?: return null
        val step = (if (down) workspaceDown else workspaceUp).stroke ?: return null
        return listOf(HerdrStroke.Bytes(picker), step, HerdrStroke.Key(HerdrTermKey.ENTER))
    }

    /** Why [switchWorkspace] sends nothing on this host; null when it can be sent. */
    fun switchProblem(down: Boolean): String? = problem(HerdrKey.WORKSPACE_PICKER) ?: (if (down) workspaceDown else workspaceUp).problem

    companion object {
        // Arrow keys by Herdr's names: sent as arrows, so the terminal picks their encoding.
        // Ahead of DEFAULT, which reads it.
        private val ARROWS = mapOf("up" to HerdrTermKey.UP, "down" to HerdrTermKey.DOWN, "left" to HerdrTermKey.LEFT, "right" to HerdrTermKey.RIGHT)

        /** Herdr's built-in keys: no config, or none that could be read. */
        val DEFAULT = resolve(HerdrPrefix.DEFAULT, emptyMap())

        /** The keys from what [HerdrPrefix.readCommand] printed; null [out] means it never ran. */
        fun fromRead(out: CommandOutput?): HerdrKeys = if (out != null && (out.exitCode == null || out.exitCode == 0)) {
            fromConfig(out.stdout)
        } else {
            resolve(HerdrPrefix.fromRead(out), emptyMap())
        }

        /** The keys a Herdr config.toml sets; Herdr's defaults for the ones it leaves out. */
        fun fromConfig(toml: String): HerdrKeys = resolve(HerdrPrefix.fromConfig(toml), HerdrPrefix.keyBindings(toml))

        private const val PREFIX_PLUS = "prefix+"

        /**
         * Each key's binding: the first prefix binding Mercurio can send (it arms the prefix,
         * then sends the key), else the first direct chord it can send, else off.
         */
        private fun resolve(prefix: HerdrPrefix, config: Map<String, List<String>>): HerdrKeys = HerdrKeys(
            prefix,
            HerdrKey.entries.associateWith { key ->
                val specs = (config[key.config] ?: listOf(key.default)).map { it.trim() }.filter { it.isNotEmpty() }
                val (prefixed, direct) = specs.partition { it.startsWith(PREFIX_PLUS, ignoreCase = true) }
                prefixed.firstNotNullOfOrNull { spec ->
                    HerdrPrefix.keyBytes(spec.substring(PREFIX_PLUS.length))?.let { HerdrBinding(spec, prefix.bytes + it) }
                } ?: direct.firstNotNullOfOrNull { spec ->
                    HerdrPrefix.keyBytes(spec)?.let { HerdrBinding(spec, it) }
                } ?: HerdrBinding(
                    specs.firstOrNull(),
                    null,
                    if (specs.isEmpty()) {
                        "Herdr's config leaves ${key.config} unbound."
                    } else {
                        "Herdr's config binds ${key.config} to ${specs.joinToString { "\"$it\"" }}, " +
                            "which Mercurio cannot send from a terminal."
                    },
                )
            },
            navigate("navigate_workspace_up", "up", config),
            navigate("navigate_workspace_down", "down", config),
        )

        /**
         * A navigate-mode key: the first of its specs Mercurio can send, an arrow or a plain
         * key ("k", "ctrl+p"); Herdr's [default] when the config sets none.
         */
        private fun navigate(name: String, default: String, config: Map<String, List<String>>): HerdrNavigateKey {
            val specs = (config[name] ?: listOf(default)).map { it.trim() }.filter { it.isNotEmpty() }
            return specs.firstNotNullOfOrNull { spec ->
                val stroke = ARROWS[spec.lowercase()]?.let { HerdrStroke.Key(it) } ?: HerdrPrefix.keyBytes(spec)?.let { HerdrStroke.Bytes(it) }
                stroke?.let { HerdrNavigateKey(spec, it) }
            } ?: HerdrNavigateKey(
                specs.firstOrNull(),
                null,
                if (specs.isEmpty()) {
                    "Herdr's config leaves $name unbound."
                } else {
                    "Herdr's config binds $name to ${specs.joinToString { "\"$it\"" }}, which Mercurio cannot send from a terminal."
                },
            )
        }
    }
}
