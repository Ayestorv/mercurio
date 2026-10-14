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

package org.connectbot.ui.screens.console

import android.graphics.Typeface
import android.text.TextPaint
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.sp
import org.connectbot.service.TerminalBridge
import org.connectbot.terminal.TerminalDimensions
import solutions.ormus.logos.terminal.TerminalMouse
import kotlin.math.ceil

/**
 * Where the last touch on the terminal landed, when it stayed a tap: one finger that never
 * moved past touch slop. termlib decides what a touch was and calls its onTerminalTap
 * without a position; this supplies the position. It also keeps out a quick flick, which
 * termlib can still report as a tap (it ignores slop for its first moments, waiting for a
 * second finger).
 */
internal class TerminalTaps {
    var last: Offset? = null

    /** The last tap's position, once. */
    fun take(): Offset? = last.also { last = null }
}

/**
 * Record taps on the terminal in [taps]. Watched on the Initial pass and never consumed, so
 * termlib, the Herdr swipes and every other gesture see the touch exactly as before. The
 * position is in the terminal's own space, the one termlib maps its taps from.
 */
internal fun Modifier.terminalTaps(taps: TerminalTaps): Modifier = pointerInput(taps) {
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
        taps.last = null
        var tap = true
        while (true) {
            val event = awaitPointerEvent(PointerEventPass.Initial)
            if (event.changes.any { it.id != down.id && it.pressed }) tap = false
            event.changes.firstOrNull { it.id == down.id }?.let {
                if ((it.position - down.position).getDistance() > viewConfiguration.touchSlop) tap = false
            }
            if (event.changes.none { it.pressed }) break
        }
        if (tap) taps.last = down.position
    }
}

/**
 * The cell termlib draws at [fontSizeSp] in [typeface], in px: the width of "M" and the
 * font's line height rounded up, as termlib measures them.
 */
@Composable
internal fun rememberCellSize(typeface: Typeface, fontSizeSp: Float): Size {
    val density = LocalDensity.current
    return remember(typeface, fontSizeSp, density) {
        val paint = TextPaint().apply {
            this.typeface = typeface
            textSize = with(density) { fontSizeSp.sp.toPx() }
        }
        Size(paint.measureText("M"), ceil(paint.fontMetrics.let { it.descent - it.ascent }))
    }
}

/**
 * A tap at [at] on [bridge]'s terminal, sent as a left click on the cell under it when the
 * program there asked for mouse reports. [size] is the terminal in cells. Returns whether a
 * click was sent.
 */
internal fun clickTap(
    bridge: TerminalBridge,
    at: Offset,
    cell: Size,
    size: TerminalDimensions = bridge.terminalEmulator.dimensions,
): Boolean {
    if (cell.width <= 0f || cell.height <= 0f) return false
    val (col, row) = TerminalMouse.cell(at.x, at.y, cell.width, cell.height, size.columns, size.rows)
    return bridge.mouseClick(col, row)
}

/**
 * A slow one-finger drag on [bridge]'s terminal, sent as mouse wheel notches to a program
 * that asked for mouse reports (Herdr scrolls the pane under them), one per row the finger
 * crosses. [cell] is the cell in px, empty while it is unknown (a forced size); [size] the
 * terminal in cells. [onDrag] runs once as each drag starts sending.
 */
internal class TerminalWheel(
    private val bridge: () -> TerminalBridge,
    private val cell: () -> Size,
    private val size: () -> TerminalDimensions = { bridge().terminalEmulator.dimensions },
    val onDrag: () -> Unit = {},
) {
    /** The row height in px a drag scrolls by; null when the drag stays termlib's scroll. */
    fun rowHeight(): Float? {
        val cell = cell()
        if (cell.width <= 0f || cell.height <= 0f || !bridge().mouseReporting) return null
        return cell.height
    }

    /** One wheel notch, [up] or down, at the cell under [at]. */
    fun notch(at: Offset, up: Boolean) {
        val cell = cell()
        val size = size()
        val (col, row) = TerminalMouse.cell(at.x, at.y, cell.width, cell.height, size.columns, size.rows)
        bridge().mouseWheel(col, row, up)
    }
}
