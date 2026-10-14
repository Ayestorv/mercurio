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

import androidx.activity.ComponentActivity
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.TouchInjectionScope
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.connectbot.service.TerminalBridge
import org.connectbot.terminal.TerminalDimensions
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.doAnswer
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import solutions.ormus.logos.herd.HerdrSwipe
import solutions.ormus.logos.terminal.TerminalArrow
import solutions.ormus.logos.terminal.TerminalMouse

/**
 * Long-press and drag sends arrow keys, through the gesture layers in ConsoleScreen's order
 * (the Herdr swipes with the wheel, the drag arrows, the taps) over a stand-in for termlib's
 * long press, which starts a selection at the long-press timeout whatever the layers above
 * do (Terminal.kt, 0.1.0). The bridge's arrows are encoded by a real [TerminalMouse].
 * Robolectric, xhdpi: 1dp = 2px. The terminal is 400dp = 800px square, 40 columns by 20
 * rows of 20 x 40 px cells. Mouse reporting is on, so a plain slow drag is the wheel's.
 */
@RunWith(AndroidJUnit4::class)
class DragArrowsTest {
    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private val esc = "\u001b"
    private val mouse = TerminalMouse()

    /** What reached the host: each arrow's bytes, and each wheel notch as "wheel". */
    private val sent = mutableListOf<String>()
    private val bridge = mock<TerminalBridge> {
        on { mouseReporting } doAnswer { true }
        on { mouseWheel(any(), any(), any()) } doAnswer {
            sent += "wheel"
            true
        }
        on { arrowKey(anyOrNull()) } doAnswer { sent += mouse.arrow(it.getArgument(0)).toString(Charsets.US_ASCII) }
    }
    private val swipes = mutableListOf<HerdrSwipe>()
    private var starts = 0

    // termlib's selection: up (mode set), and following the finger (isSelecting).
    private var selectionActive = false
    private var selecting = false

    /** The cells termlib's selection followed the finger to, as (column, row) from 0. */
    private val selectionCells = mutableSetOf<Pair<Int, Int>>()

    private fun setContent() {
        val cell = { Size(20f, 40f) }
        val wheel = TerminalWheel(bridge = { bridge }, cell = cell, size = { TerminalDimensions(rows = 20, columns = 40) })
        val arrows = DragArrows(
            bridge = { bridge },
            cell = cell,
            enabled = { !selectionActive },
            onStart = {
                // As ConsoleScreen's SelectionController.clearSelection().
                selectionActive = false
                selecting = false
                starts++
            },
        )
        rule.setContent {
            Box(
                Modifier
                    .size(400.dp)
                    .herdrSwipes(enabled = { !selectionActive }, wheel = wheel) { swipe, _ -> swipes += swipe }
                    .dragArrows(arrows)
                    .terminalTaps(TerminalTaps())
                    .termlibLongPress()
                    .testTag("terminal"),
            )
        }
    }

    // termlib's long press, trimmed: a timer from the down that starts a selection unless the
    // finger moved past slop first; a move while selecting extends the selection.
    private fun Modifier.termlibLongPress() = pointerInput(Unit) {
        coroutineScope {
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false)
                val longPress = launch {
                    delay(viewConfiguration.longPressTimeoutMillis)
                    if (!selectionActive) {
                        selectionActive = true
                        selecting = true
                    }
                }
                var pan = Offset.Zero
                while (true) {
                    val event = awaitPointerEvent(PointerEventPass.Main)
                    val change = event.changes.firstOrNull { it.id == down.id } ?: event.changes.first()
                    pan += change.positionChange()
                    if (selecting) selectionCells += (change.position.x / 20f).toInt() to (change.position.y / 40f).toInt()
                    if (!selecting && pan.getDistance() > viewConfiguration.touchSlop) longPress.cancel()
                    if (event.changes.none { it.pressed }) break
                }
                longPress.cancel()
                selecting = false
            }
        }
    }

    private fun touch(block: TouchInjectionScope.() -> Unit) {
        rule.onNodeWithTag("terminal").performTouchInput(block)
        rule.waitForIdle()
    }

    /** Hold at [from] past the long-press timeout, then drag by [by] (px) in [steps] frames of 16ms. */
    private fun holdAndDrag(from: Offset, by: Offset, steps: Int) = touch {
        down(from)
        advanceEventTime(viewConfiguration.longPressTimeoutMillis + 100)
        for (step in 1..steps) {
            moveTo(from + by * (step / steps.toFloat()))
        }
        up()
    }

    @Test
    fun aHeldPressDraggedDownThreeCells_sendsThreeDownArrows_andNoSelection() {
        setContent()
        holdAndDrag(Offset(300f, 200f), Offset(0f, 130f), steps = 13)
        assertEquals(List(3) { "$esc[B" }, sent)
        assertFalse("no selection is left up", selectionActive)
        assertEquals("the selection never left the pressed cell", setOf(15 to 5), selectionCells)
        assertEquals(1, starts)
        assertEquals(emptyList<HerdrSwipe>(), swipes)
    }

    @Test
    fun underApplicationCursorKeys_theDownArrowIsEscOB() {
        mouse.scan("$esc[?1h")
        setContent()
        holdAndDrag(Offset(300f, 200f), Offset(0f, 130f), steps = 13)
        assertEquals(List(3) { "${esc}OB" }, sent)
        assertFalse(selectionActive)
    }

    @Test
    fun aHeldPressDraggedRightTwoCells_sendsTwoRightArrows_andBackOneSendsALeft() {
        setContent()
        holdAndDrag(Offset(300f, 400f), Offset(50f, 6f), steps = 10)
        assertEquals(List(2) { "$esc[C" }, sent)
        assertFalse(selectionActive)
        assertEquals(emptyList<HerdrSwipe>(), swipes)

        sent.clear()
        touch {
            down(Offset(300f, 400f))
            advanceEventTime(viewConfiguration.longPressTimeoutMillis + 100)
            for (x in listOf(310f, 330f, 345f, 330f, 325f)) moveTo(Offset(x, 400f))
            up()
        }
        assertEquals(listOf("$esc[C", "$esc[C", "$esc[D"), sent)
    }

    @Test
    fun aHeldPressThatJumpsSevenCellsInOneMove_isArrows_neverATab() {
        setContent()
        holdAndDrag(Offset(300f, 400f), Offset(140f, 0f), steps = 1)
        assertEquals(List(7) { "$esc[C" }, sent)
        assertEquals(emptyList<HerdrSwipe>(), swipes)
    }

    @Test
    fun aLongPressThatDoesNotMove_stillStartsTheSelection_andSendsNothing() {
        setContent()
        touch { longClick(Offset(300f, 400f)) }
        assertTrue("termlib's selection is up", selectionActive)
        assertEquals(emptyList<String>(), sent)
        assertEquals(0, starts)
    }

    @Test
    fun aDragWithoutTheHold_staysTheWheelAndTheSwipes() {
        setContent()
        // A slow drag down, 200px over 320ms: five wheel notches (atom 1), no arrow.
        touch {
            down(Offset(300f, 200f))
            for (step in 1..20) moveTo(Offset(300f, 200f + 10f * step))
            up()
        }
        assertEquals(List(5) { "wheel" }, sent)
        // One finger across: still a tab.
        touch { swipeLeft(startX = 600f, endX = 100f) }
        assertEquals(listOf(HerdrSwipe.NEXT_TAB), swipes)
        assertEquals(List(5) { "wheel" }, sent)
        assertEquals(0, starts)
        assertFalse(selectionActive)
    }
}
