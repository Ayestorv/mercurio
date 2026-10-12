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

package org.connectbot.ui.components

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.connectbot.ui.theme.ConnectBotTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.GraphicsMode

/** The compose bar's Needs you key on its own: label, tone, and what a tap and a long press run. */
@RunWith(AndroidJUnit4::class)
class NeedsYouKeyTest {
    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private var taps = 0
    private var longPresses = 0
    private var waiting by mutableIntStateOf(0)
    private var enabled = true
    private var width = 120.dp
    private var gold = Color.Unspecified
    private var ghost = Color.Unspecified

    private fun setKey() {
        rule.setContent {
            ConnectBotTheme {
                gold = MaterialTheme.colorScheme.primaryContainer
                ghost = MaterialTheme.colorScheme.surfaceContainerHighest
                // Row 1's share of a 411dp phone: two of 6.4 weights.
                Box(Modifier.width(width)) {
                    NeedsYouKey(
                        waiting = waiting,
                        onClick = { taps++ },
                        onLongClick = { longPresses++ },
                        enabled = enabled,
                        modifier = Modifier.width(width).testTag(KEY),
                    )
                }
            }
        }
        rule.waitForIdle()
    }

    // The key's fill, just inside its hairline at the left middle (clear of the label), read
    // from a draw of the whole window (captureToImage does not finish under Robolectric).
    private fun fill(): Color {
        rule.waitForIdle()
        val bounds = rule.onNodeWithTag(KEY).fetchSemanticsNode().boundsInWindow
        val view = rule.activity.window.decorView
        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        view.draw(Canvas(bitmap))
        return Color(bitmap.getPixel(bounds.left.toInt() + 4, bounds.center.y.toInt()))
    }

    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun noneWaiting_plainLabel_ghostTone() {
        setKey()
        rule.onNodeWithText("Needs you").assertExists()
        rule.onNodeWithContentDescription("Needs you, 0 waiting").assertIsEnabled()
        assertEquals(ghost.toArgb(), fill().toArgb())
    }

    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun agentsWaiting_countInTheLabel_goldTone_andItFollowsTheCount() {
        waiting = 3
        setKey()
        rule.onNodeWithText("Needs you · 3").assertExists()
        rule.onNodeWithContentDescription("Needs you, 3 waiting").assertExists()
        assertEquals(gold.toArgb(), fill().toArgb())

        waiting = 0
        rule.waitForIdle()
        rule.onNodeWithText("Needs you").assertExists()
        assertEquals(ghost.toArgb(), fill().toArgb())
    }

    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE) // real text measurement
    fun aTwoDigitCount_fitsTheKeyOnA360dpPhone() {
        waiting = 12
        width = 103.dp // two of 6.4 weights of a 360dp phone's row 1
        setKey()
        val layouts = mutableListOf<TextLayoutResult>()
        rule.onNodeWithText("Needs you · 12").performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        val layout = layouts.single()
        // The whole label on one line inside the key's width.
        assertTrue("label ${layout.multiParagraph.intrinsics.maxIntrinsicWidth}px in ${layout.layoutInput.constraints.maxWidth}px", layout.multiParagraph.intrinsics.maxIntrinsicWidth <= layout.layoutInput.constraints.maxWidth)
        assertEquals(false, layout.multiParagraph.didExceedMaxLines)
    }

    @Test
    fun tap_andLongPress_runTheirOwnActions() {
        waiting = 2
        setKey()
        rule.onNodeWithTag(KEY).performClick()
        rule.onNodeWithTag(KEY).performTouchInput { longClick() }
        assertEquals(1, taps)
        assertEquals(1, longPresses)
    }

    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun disabled_isDimmed_neverGold_andTakesNoTap() {
        waiting = 2
        enabled = false
        setKey()
        rule.onNodeWithContentDescription("Needs you, 2 waiting").assertIsNotEnabled()
        rule.onNodeWithTag(KEY).performClick()
        assertEquals(0, taps)
        assertEquals(false, fill().toArgb() == gold.toArgb())
    }

    private companion object {
        const val KEY = "needsYouKey"
    }
}
