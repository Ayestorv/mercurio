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

import org.junit.Assert.assertEquals
import org.junit.Test
import solutions.ormus.logos.herd.HerdrKeys
import solutions.ormus.logos.herd.HerdrStroke
import solutions.ormus.logos.herd.HerdrTermKey

/** The controller's keystrokes: herdr-pad's map, through the host's Herdr prefix and bindings. */
class PadActionTest {
    private val ctrlB = 2.toChar().toString()
    private val ctrlA = 1.toChar().toString()
    private val esc = 27.toChar().toString()

    private fun bytes(action: PadAction, keys: HerdrKeys = HerdrKeys.DEFAULT) = action.strokes(keys).map { (it as HerdrStroke.Bytes).bytes }.single()

    @Test
    fun herdrActionsGoThroughThePrefix() {
        assertEquals(ctrlB + "h", bytes(PadAction.FOCUS_LEFT))
        assertEquals(ctrlB + "l", bytes(PadAction.FOCUS_RIGHT))
        assertEquals(ctrlB + "p", bytes(PadAction.PREVIOUS_TAB))
        assertEquals(ctrlB + "n", bytes(PadAction.NEXT_TAB))
        assertEquals(ctrlB + esc + "p", bytes(PadAction.PREVIOUS_WORKSPACE))
        assertEquals(ctrlB + esc + "m", bytes(PadAction.NEXT_WORKSPACE))
        assertEquals(ctrlB + "w", bytes(PadAction.PICKER))
        assertEquals(ctrlB + "o", bytes(PadAction.ATTENTION))
        assertEquals(ctrlB + "x", bytes(PadAction.CLOSE_PANE))
        assertEquals(ctrlB + esc + "a", bytes(PadAction.NEXT_AGENT))
        assertEquals(ctrlB + "-", bytes(PadAction.SPLIT_DOWN))
    }

    @Test
    fun theHostsPrefixAndBindingsWin() {
        val keys = HerdrKeys.fromConfig("[keys]\nprefix = \"ctrl+a\"\nnext_tab = \"prefix+t\"\n")
        assertEquals(ctrlA + "t", bytes(PadAction.NEXT_TAB, keys))
        assertEquals(ctrlA + "z", bytes(PadAction.ZOOM, keys))
    }

    @Test
    fun terminalKeysSkipThePrefix() {
        assertEquals(listOf(HerdrStroke.Key(HerdrTermKey.ENTER)), PadAction.ENTER.strokes(HerdrKeys.DEFAULT))
        assertEquals(listOf(HerdrStroke.Key(HerdrTermKey.UP)), PadAction.UP.strokes(HerdrKeys.DEFAULT))
        assertEquals(esc, bytes(PadAction.ESC))
        assertEquals(esc + "[Z", bytes(PadAction.SHIFT_TAB))
        assertEquals(" ", bytes(PadAction.SPACE))
    }
}
