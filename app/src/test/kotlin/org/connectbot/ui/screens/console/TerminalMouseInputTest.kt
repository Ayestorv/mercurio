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
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.test.swipeUp
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
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import solutions.ormus.logos.herd.HerdrSwipe

/**
 * Taps sent on as clicks, over a stand-in for termlib's terminal that decides taps the way
 * termlib does (Terminal.kt, 0.1.0): a touch is a tap unless a long press, a move past slop
 * after its first 40ms, or a second finger in those 40ms made it something else. The Herdr
 * swipes and the drawer's edge strip sit where ConsoleScreen puts them. Robolectric, xhdpi:
 * 1dp = 2px.
 */
@RunWith(AndroidJUnit4::class)
class TerminalMouseInputTest {
    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private val taps = TerminalTaps()

    /** Where the clicks went: the tap positions termlib's onTerminalTap found in [taps]. */
    private val clicks = mutableListOf<Offset>()
    private var termTaps = 0
    private val swipes = mutableListOf<HerdrSwipe>()

    private fun setContent() {
        rule.setContent {
            Box(Modifier.size(400.dp).testTag("screen")) {
                Box(
                    Modifier
                        .fillMaxSize()
                        .herdrSwipes(enabled = { true }) { swipe, _ -> swipes += swipe }
                        .terminalTaps(taps)
                        .termlibTaps {
                            termTaps++
                            taps.take()?.let { clicks += it }
                        },
                )
                // The drawer's edge strip, as ConsoleScreen draws it over the terminal.
                Box(
                    Modifier
                        .align(Alignment.CenterStart)
                        .fillMaxHeight()
                        .width(24.dp)
                        .pointerInput(Unit) { detectHorizontalDragGestures { change, dx -> if (dx > 0) change.consume() } },
                )
            }
        }
    }

    // termlib's tap decision, trimmed to what makes a touch a tap or not.
    private fun Modifier.termlibTaps(onTap: () -> Unit) = pointerInput(Unit) {
        coroutineScope {
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false)
                var tap = true
                val longPress = launch {
                    delay(viewConfiguration.longPressTimeoutMillis)
                    tap = false
                }
                var pan = Offset.Zero
                val grace = down.uptimeMillis + 40
                while (true) {
                    val event = awaitPointerEvent(PointerEventPass.Main)
                    val change = event.changes.firstOrNull { it.id == down.id } ?: event.changes.first()
                    pan += change.positionChange()
                    val inGrace = change.uptimeMillis <= grace
                    if (inGrace && event.changes.any { it.id != down.id && it.pressed }) tap = false // its zoom
                    if (!inGrace && pan.getDistance() > viewConfiguration.touchSlop) tap = false // its scroll
                    if (event.changes.none { it.pressed }) break
                }
                longPress.cancel()
                if (tap) onTap()
            }
        }
    }

    private fun touch(block: androidx.compose.ui.test.TouchInjectionScope.() -> Unit) {
        rule.onNodeWithTag("screen").performTouchInput(block)
        rule.waitForIdle()
    }

    @Test
    fun aTap_isAClickWhereTheFingerLanded() {
        setContent()
        touch { click(Offset(300f, 400f)) }
        touch { click(Offset(120f, 50f)) }
        assertEquals(listOf(Offset(300f, 400f), Offset(120f, 50f)), clicks)
    }

    @Test
    fun aLongPress_isTheSelection_notAClick() {
        setContent()
        touch { longClick(Offset(300f, 400f)) }
        assertEquals(0, termTaps)
        assertEquals(emptyList<Offset>(), clicks)
    }

    @Test
    fun aQuickFlick_termlibStillCallsATap_isNotAClick() {
        setContent()
        touch {
            down(Offset(300f, 400f))
            moveBy(Offset(-100f, 0f))
            up()
        }
        assertEquals(1, termTaps)
        assertEquals(emptyList<Offset>(), clicks)
    }

    @Test
    fun swipesAndScrolls_areNotClicks() {
        setContent()
        touch { swipeLeft(startX = 600f, endX = 100f) } // one finger across: a tab
        touch { swipeUp(startY = 700f, endY = 200f, durationMillis = 1_000) } // one finger up, slow: the terminal's scroll
        assertEquals(listOf(HerdrSwipe.NEXT_TAB), swipes)
        assertEquals(emptyList<Offset>(), clicks)
    }

    @Test
    fun aOneFingerFlickUpOrDown_isAWorkspaceSwipe_neverAClick() {
        setContent()
        touch { swipeUp(startY = 700f, endY = 200f, durationMillis = 80) }
        touch {
            down(Offset(300f, 200f))
            moveBy(Offset(0f, 150f))
            up()
        }
        assertEquals(listOf(HerdrSwipe.NEXT_WORKSPACE, HerdrSwipe.PREV_WORKSPACE), swipes)
        assertEquals(emptyList<Offset>(), clicks)
    }

    @Test
    fun twoFingers_tappedOrPinched_areNotClicks() {
        setContent()
        // A second finger after termlib's first 40ms: termlib calls it a tap.
        touch {
            down(0, Offset(300f, 400f))
            advanceEventTime(100)
            down(1, Offset(500f, 400f))
            up(1)
            up(0)
        }
        // A pinch.
        touch {
            down(0, Offset(300f, 400f))
            down(1, Offset(500f, 400f))
            for (step in 1..10) {
                updatePointerTo(0, Offset(300f - 15f * step, 400f))
                updatePointerTo(1, Offset(500f + 15f * step, 400f))
                move()
            }
            up(0)
            up(1)
        }
        assertEquals(1, termTaps)
        assertEquals(emptyList<Offset>(), clicks)
    }

    @Test
    fun aTapOnTheDrawerEdgeStrip_neverReachesTheTerminal() {
        setContent()
        touch { click(Offset(20f, 400f)) } // 10dp in, inside the 24dp strip
        assertEquals(0, termTaps)
        assertEquals(emptyList<Offset>(), clicks)
        touch { click(Offset(60f, 400f)) } // 30dp in, past it
        assertEquals(listOf(Offset(60f, 400f)), clicks)
    }

    @Test
    fun aTapIsTakenOnce_andAnotherGestureClearsIt() {
        setContent()
        touch { click(Offset(300f, 400f)) }
        assertEquals(null, taps.take())
        touch { swipeUp(startY = 700f, endY = 200f) }
        assertEquals(null, taps.last)
    }

    @Test
    fun clickTap_sendsTheCellUnderTheTap_toTheBridge() {
        val bridge = mock(TerminalBridge::class.java)
        `when`(bridge.mouseClick(13, 6)).thenReturn(true)
        val size = TerminalDimensions(rows = 24, columns = 80)

        // 10 x 20 px cells: (125, 110) is column 13, row 6.
        assertTrue(clickTap(bridge, Offset(125f, 110f), Size(10f, 20f), size))
        verify(bridge).mouseClick(13, 6)
        // Before the font is measured, nothing is sent.
        assertFalse(clickTap(bridge, Offset(125f, 110f), Size(0f, 20f), size))
        verify(bridge, never()).mouseClick(1, 1)
    }
}
