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

package solutions.ormus.logos.terminal

/**
 * Mouse reports the program on the other end of the terminal asked for, read from its
 * output, so a tap can reach a full-screen program (Herdr's sidebar, for one) as a click.
 * termlib parses these modes in libvterm but never shows them to the app or sends a mouse
 * report, so the output is watched here too.
 *
 * Tracked the way libvterm tracks them: DECSET ?9, ?1000, ?1002 or ?1003 turns reporting
 * on (any of their resets turns it off); ?1006 picks the SGR encoding and ?1015 the urxvt
 * one (either reset goes back to the legacy one). A full reset (RIS, `ESC c`) and leaving
 * the alternate screen turn everything off, for a program that quits without doing it.
 *
 * The alternate screen (?47, ?1047, ?1049) is tracked from the same output: a full-screen
 * program such as the Herdr client draws there, a shell prompt does not.
 *
 * Application cursor keys (DECCKM, ?1) are tracked too, for the arrows a drag sends
 * ([arrow]). Leaving the alternate screen keeps them: a shell such as zsh turns them on at
 * its prompt, and the program that leaves turns them off itself.
 */
class TerminalMouse {
    private enum class Encoding { LEGACY, SGR, URXVT }

    // The DECSET mode that turned reporting on, 0 while off.
    private var tracking = 0
    private var encoding = Encoding.LEGACY

    private var alternate = false
    private var cursorKeys = false
    private var entered = 0

    // An unfinished sequence at the end of the last output, finished by the next.
    private var carry = ""

    /** Whether the program asked for mouse reports. */
    val active: Boolean
        @Synchronized get() = tracking != 0

    /** Whether the terminal shows the alternate screen. */
    val alternateScreen: Boolean
        @Synchronized get() = alternate

    /** How many times the alternate screen went up: a change means a full-screen program started since. */
    val screensEntered: Int
        @Synchronized get() = entered

    /** Watch decoded output for the program turning mouse reports or the alternate screen on or off. */
    @Synchronized
    fun scan(output: CharSequence) {
        val window = carry + output
        for (match in SEQUENCE.findAll(window)) {
            if (match.value == RIS) {
                reset()
                continue
            }
            val on = match.groupValues[2] == "h"
            match.groupValues[1].split(';').forEach { set(it.toIntOrNull() ?: return@forEach, on) }
        }
        val tail = window.substring(window.lastIndexOf(ESC).coerceAtLeast(0))
        carry = if (PARTIAL.matches(tail)) tail else ""
    }

    /** Forget every mode: a new connection starts with none, on the normal screen. */
    @Synchronized
    fun reset() {
        cursorKeys = false
        resetScreen()
    }

    private fun resetScreen() {
        tracking = 0
        encoding = Encoding.LEGACY
        alternate = false
    }

    /**
     * The bytes for a left click at the 1-based [col] and [row]: a press, then a release
     * (X10 mode ?9 reports presses only). Null when the program did not ask for mouse
     * reports, or the cell is past the legacy encoding's reach (column or row 223).
     */
    @Synchronized
    fun click(col: Int, row: Int): ByteArray? {
        if (tracking == 0) return null
        val reports = if (tracking == X10) listOf(PRESS) else listOf(PRESS, RELEASE)
        return when (encoding) {
            Encoding.SGR -> reports.joinToString("") { "$ESC[<0;$col;$row${if (it == PRESS) 'M' else 'm'}" }
                .toByteArray(Charsets.US_ASCII)

            Encoding.URXVT -> reports.joinToString("") { "$ESC[${32 + it};$col;${row}M" }.toByteArray(Charsets.US_ASCII)

            Encoding.LEGACY -> {
                if (col > LEGACY_MAX || row > LEGACY_MAX) return null
                reports.flatMap { listOf(0x1b, '['.code, 'M'.code, 32 + it, 32 + col, 32 + row) }
                    .map { it.toByte() }.toByteArray()
            }
        }
    }

    /**
     * The bytes for one wheel notch at the 1-based [col] and [row]: button 64 for [up], 65 for
     * down, a press with no release, as xterm sends them. Null when the program did not ask
     * for mouse reports, or the cell is past the legacy encoding's reach.
     */
    @Synchronized
    fun wheel(col: Int, row: Int, up: Boolean): ByteArray? {
        if (tracking == 0) return null
        val button = if (up) WHEEL_UP else WHEEL_DOWN
        return when (encoding) {
            Encoding.SGR -> "$ESC[<$button;$col;${row}M".toByteArray(Charsets.US_ASCII)

            Encoding.URXVT -> "$ESC[${32 + button};$col;${row}M".toByteArray(Charsets.US_ASCII)

            Encoding.LEGACY -> {
                if (col > LEGACY_MAX || row > LEGACY_MAX) return null
                listOf(0x1b, '['.code, 'M'.code, 32 + button, 32 + col, 32 + row).map { it.toByte() }.toByteArray()
            }
        }
    }

    /** The bytes for [key]: ESC [ B for down, or ESC O B while the program turned on application cursor keys. */
    @Synchronized
    fun arrow(key: TerminalArrow): ByteArray = "$ESC${if (cursorKeys) 'O' else '['}${key.final}".toByteArray(Charsets.US_ASCII)

    private fun set(mode: Int, on: Boolean) {
        when (mode) {
            DECCKM -> cursorKeys = on

            X10, 1000, 1002, 1003 -> tracking = if (on) mode else 0

            1006 -> encoding = if (on) Encoding.SGR else Encoding.LEGACY

            1015 -> encoding = if (on) Encoding.URXVT else Encoding.LEGACY

            47, 1047, 1049 -> {
                if (on && !alternate) entered++
                alternate = on
                if (!on) resetScreen()
            }
        }
    }

    companion object {
        private const val ESC = '\u001b'
        private const val RIS = "\u001bc"
        private const val DECCKM = 1
        private const val X10 = 9
        private const val PRESS = 0
        private const val RELEASE = 3
        private const val WHEEL_UP = 64
        private const val WHEEL_DOWN = 65

        // The legacy encoding sends 32 + the 1-based column or row in one byte.
        private const val LEGACY_MAX = 223

        // RIS, or a DECSET/DECRST with its parameters.
        private val SEQUENCE = Regex("\u001b(?:c|\\[\\?([0-9;]+)([hl]))")
        private val PARTIAL = Regex("\u001b(?:\\[(?:\\?[0-9;]{0,32})?)?")

        /**
         * The 1-based cell under ([x], [y]) on a terminal of [cols] by [rows] cells of
         * [cellWidth] by [cellHeight] (px, from its top left corner), the way termlib maps
         * its own taps: a tap past the last row or column lands on it.
         */
        fun cell(x: Float, y: Float, cellWidth: Float, cellHeight: Float, cols: Int, rows: Int): Pair<Int, Int> = ((x / cellWidth).toInt().coerceIn(0, cols - 1) + 1) to ((y / cellHeight).toInt().coerceIn(0, rows - 1) + 1)
    }
}

/** An arrow key, by the final byte of its sequence. */
enum class TerminalArrow(val final: Char) { UP('A'), DOWN('B'), RIGHT('C'), LEFT('D') }
