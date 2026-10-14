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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.connectbot.service.TerminalBridge
import org.connectbot.terminal.TerminalDimensions
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentMatchers.anyBoolean
import org.mockito.ArgumentMatchers.anyInt
import org.mockito.ArgumentMatchers.eq
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.times
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import solutions.ormus.logos.herd.HerdrSwipe

/**
 * A slow one-finger drag on a Herdr terminal sent as mouse wheel notches, through the swipe
 * layer over a stand-in for termlib's terminal (as in HerdrSwipeInputTest) and a fake
 * bridge. Robolectric, xhdpi: 1dp = 2px. The terminal is 400dp = 800px square, 40 columns
 * by 20 rows of 20 x 40 px cells.
 */
@RunWith(AndroidJUnit4::class)
class HerdrDragScrollTest {
    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private val bridge = mock(TerminalBridge::class.java)
    private val fired = mutableListOf<HerdrSwipe>()
    private var drags = 0

    /** What the stand-in terminal saw: the first finger's unconsumed travel. */
    private var termTravel = Offset.Zero

    private fun setContent(mouseReporting: Boolean) {
        `when`(bridge.mouseReporting).thenReturn(mouseReporting)
        `when`(bridge.mouseWheel(anyInt(), anyInt(), anyBoolean())).thenReturn(mouseReporting)
        val wheel = TerminalWheel(
            bridge = { bridge },
            cell = { Size(20f, 40f) },
            size = { TerminalDimensions(rows = 20, columns = 40) },
            onDrag = { drags++ },
        )
        rule.setContent {
            Box(
                Modifier
                    .size(400.dp)
                    .herdrSwipes(enabled = { true }, wheel = wheel) { swipe, _ -> fired += swipe }
                    .testTag("terminal"),
            ) {
                Box(
                    Modifier.fillMaxSize().pointerInput(Unit) {
                        awaitEachGesture {
                            val down = awaitFirstDown(requireUnconsumed = false)
                            while (true) {
                                val event = awaitPointerEvent(PointerEventPass.Main)
                                event.changes.firstOrNull { it.id == down.id }?.let { termTravel += it.positionChange() }
                                if (event.changes.none { it.pressed }) break
                            }
                        }
                    },
                )
            }
        }
    }

    /** One finger from [from], moved by [by] (px) in [steps] frames of 16ms. */
    private fun drag(from: Offset, by: Offset, steps: Int) {
        rule.onNodeWithTag("terminal").performTouchInput {
            down(from)
            for (step in 1..steps) {
                moveTo(from + by * (step / steps.toFloat()))
            }
            up()
        }
        rule.waitForIdle()
    }

    @Test
    fun aSlowDrag_sendsAWheelNotchPerRowCrossed_atTheTouchCell_andAFlickStillSwitchesWorkspace() {
        setContent(mouseReporting = true)

        // 200px down over 320ms (312dp/s, under FLICK_DP_PER_S): five rows, five notches up, at column 16, row 6.
        drag(Offset(300f, 200f), Offset(0f, 200f), steps = 20)
        verify(bridge, times(5)).mouseWheel(16, 6, true)
        verify(bridge, never()).mouseWheel(anyInt(), anyInt(), eq(false))
        assertEquals(1, drags)
        assertTrue("the terminal stops scrolling once the drag is taken: saw ${termTravel.y}px", termTravel.y < 100f)

        // 160px up: four rows, four notches down, at column 21, row 16.
        drag(Offset(400f, 600f), Offset(0f, -160f), steps = 20)
        verify(bridge, times(4)).mouseWheel(21, 16, false)
        assertEquals(2, drags)

        // A flick up at or above FLICK_DP_PER_S: no notch, and the workspace still switches.
        rule.onNodeWithTag("terminal").performTouchInput { swipeUp(startY = 700f, endY = 200f, durationMillis = 80) }
        rule.waitForIdle()
        assertEquals(listOf(HerdrSwipe.NEXT_WORKSPACE), fired)
        verify(bridge, times(9)).mouseWheel(anyInt(), anyInt(), anyBoolean())
        assertEquals(2, drags)
    }

    @Test
    fun withoutMouseReporting_aSlowDragStaysTheTerminalsScroll() {
        setContent(mouseReporting = false)
        drag(Offset(300f, 200f), Offset(0f, 200f), steps = 20)
        verify(bridge, never()).mouseWheel(anyInt(), anyInt(), anyBoolean())
        assertEquals(0, drags)
        assertEquals(emptyList<HerdrSwipe>(), fired)
        assertEquals("the terminal saw the whole drag", 200f, termTravel.y, 0.5f)
    }

    @Test
    fun aDragAcross_isStillATab_neverTheWheel() {
        setContent(mouseReporting = true)
        drag(Offset(600f, 400f), Offset(-300f, 0f), steps = 10)
        assertEquals(listOf(HerdrSwipe.NEXT_TAB), fired)
        verify(bridge, never()).mouseWheel(anyInt(), anyInt(), anyBoolean())
    }
}
