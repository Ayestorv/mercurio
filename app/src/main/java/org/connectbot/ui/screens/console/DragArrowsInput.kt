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

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import org.connectbot.service.TerminalBridge
import solutions.ormus.logos.terminal.TerminalArrow
import kotlin.math.abs

/**
 * Arrow keys by a drag on [bridge]'s terminal, one per cell the finger crosses, after a long
 * press held still. [cell] is the cell in px, empty while it is unknown (a forced size).
 * [enabled] is read as each touch starts: false while a selection is already up, so its
 * handles stay termlib's. [onStart] runs once as each drag starts sending; termlib started
 * its selection when the press was held, and [onStart] clears it.
 */
internal class DragArrows(
    private val bridge: () -> TerminalBridge,
    val cell: () -> Size,
    val enabled: () -> Boolean = { true },
    val onStart: () -> Unit = {},
) {
    fun send(key: TerminalArrow) = bridge().arrowKey(key)
}

/**
 * Long-press and drag sends arrow keys ([DragArrows]). The touch is watched on the Initial
 * pass. Only a finger held within touch slop past the long-press timeout is followed; any
 * other touch (a tap, a swipe, a scroll, a pinch) is let go at once and reaches the Herdr
 * swipes and termlib untouched. A held finger that never crosses a cell stays termlib's
 * long press, its selection. Once it crosses one, the drag locks to the axis it went
 * along, consumes its events and sends one arrow per cell crossed: back over a cell sends
 * the opposite arrow.
 */
internal fun Modifier.dragArrows(arrows: DragArrows): Modifier = pointerInput(arrows) {
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
        if (!arrows.enabled()) return@awaitEachGesture
        // Null when the hold lasted: no lift, no second finger and no move past slop before the timeout.
        val letGo = withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) {
            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                val finger = event.changes.firstOrNull { it.id == down.id }
                if (finger == null || !finger.pressed || event.changes.any { it.id != down.id && it.pressed }) break
                if ((finger.position - down.position).getDistance() > viewConfiguration.touchSlop) break
            }
            true
        }
        if (letGo != null) return@awaitEachGesture
        val cell = arrows.cell().takeIf { it.width > 0f && it.height > 0f } ?: return@awaitEachGesture
        var across: Boolean? = null
        var sent = 0
        while (true) {
            val event = awaitPointerEvent(PointerEventPass.Initial)
            val finger = event.changes.firstOrNull { it.id == down.id }
            if (finger == null || !finger.pressed) break
            val cols = (finger.position.x - down.position.x) / cell.width
            val rows = (finger.position.y - down.position.y) / cell.height
            if (across == null) {
                if (cols.toInt() == 0 && rows.toInt() == 0) continue // still the long press
                across = abs(cols) > abs(rows)
                arrows.onStart()
            }
            val axis: Boolean = across
            event.changes.forEach { it.consume() }
            val to = (if (axis) cols else rows).toInt()
            while (sent != to) {
                val forward = to > sent
                arrows.send(
                    when {
                        axis && forward -> TerminalArrow.RIGHT
                        axis -> TerminalArrow.LEFT
                        forward -> TerminalArrow.DOWN
                        else -> TerminalArrow.UP
                    },
                )
                sent += if (forward) 1 else -1
            }
        }
    }
}
