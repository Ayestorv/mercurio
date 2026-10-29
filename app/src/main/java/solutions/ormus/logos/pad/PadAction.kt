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

package solutions.ormus.logos.pad

import solutions.ormus.logos.herd.HerdrKey
import solutions.ormus.logos.herd.HerdrKeys
import solutions.ormus.logos.herd.HerdrPrefix
import solutions.ormus.logos.herd.HerdrStroke
import solutions.ormus.logos.herd.HerdrTermKey

/**
 * One thing a game controller does in a Herdr session, and the keystrokes it sends.
 * The map follows herdr-pad, the desktop Xbox daemon: the same buttons do the same
 * things on the Ayn Thor's built-in pad and on a paired Xbox pad.
 *
 * [bound] actions use the host's own Herdr binding when its config sets one; [key]
 * is the key sent after Herdr's prefix otherwise (or always, when [bound] is null).
 * [term] actions are terminal keys, [raw] plain bytes; neither touches the prefix.
 */
enum class PadAction(
    val label: String,
    private val key: String? = null,
    private val bound: HerdrKey? = null,
    private val term: HerdrTermKey? = null,
    private val raw: String? = null,
) {
    FOCUS_LEFT("Focus pane left", key = "h"),
    FOCUS_DOWN("Focus pane down", key = "j"),
    FOCUS_UP("Focus pane up", key = "k"),
    FOCUS_RIGHT("Focus pane right", key = "l"),
    PREVIOUS_TAB("Previous tab", key = "p", bound = HerdrKey.PREVIOUS_TAB),
    NEXT_TAB("Next tab", key = "n", bound = HerdrKey.NEXT_TAB),
    PREVIOUS_WORKSPACE("Previous workspace", key = "alt+p"),
    NEXT_WORKSPACE("Next workspace", key = "alt+m"),
    NEW_TAB("New tab", key = "c"),
    PICKER("Workspace picker", key = "w", bound = HerdrKey.WORKSPACE_PICKER),
    ATTENTION("Agent that needs you", key = "o", bound = HerdrKey.OPEN_NOTIFICATION),
    CLOSE_PANE("Close pane", key = "x"),
    ZOOM("Zoom pane", key = "z"),
    NEXT_AGENT("Next agent", key = "alt+a"),
    SPLIT_RIGHT("Split right", key = "v"),
    SPLIT_DOWN("Split down", key = "minus"),
    UP("↑", term = HerdrTermKey.UP),
    DOWN("↓", term = HerdrTermKey.DOWN),
    LEFT("←", term = HerdrTermKey.LEFT),
    RIGHT("→", term = HerdrTermKey.RIGHT),
    ENTER("Enter", term = HerdrTermKey.ENTER),
    ESC("Esc", raw = "\u001b"),
    BACKSPACE("Backspace", raw = "\u007f"),
    SHIFT_TAB("Shift+Tab", raw = "\u001b[Z"),
    SPACE("Space", raw = " "),
    ;

    /** Whether this goes through Herdr's prefix (and so needs a Herdr host to mean anything). */
    val herdr: Boolean get() = key != null

    /** The keystrokes for this action on a host with [keys]; empty when it cannot be sent there. */
    fun strokes(keys: HerdrKeys): List<HerdrStroke> {
        term?.let { return listOf(HerdrStroke.Key(it)) }
        raw?.let { return listOf(HerdrStroke.Bytes(it)) }
        bound?.let { keys.bytes(it) }?.let { return listOf(HerdrStroke.Bytes(it)) }
        val bytes = key?.let { HerdrPrefix.keyBytes(it) } ?: return emptyList()
        return listOf(HerdrStroke.Bytes(keys.prefix.bytes + bytes))
    }
}
