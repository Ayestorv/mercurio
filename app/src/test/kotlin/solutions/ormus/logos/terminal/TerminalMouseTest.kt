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

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Mouse modes read from a program's output, and the click a tap sends for them. */
class TerminalMouseTest {
    private val esc = "\u001b"

    private fun ByteArray?.text() = this?.toString(Charsets.ISO_8859_1)

    private fun mouse(vararg output: String) = TerminalMouse().apply { output.forEach { scan(it) } }

    @Test
    fun aTapWhileTheProgramAskedForSgrMouse_sendsALeftPressAndRelease() {
        val mouse = TerminalMouse()
        mouse.scan("$esc[?1000h$esc[?1006h")
        assertEquals("$esc[<0;5;3M$esc[<0;5;3m", mouse.click(5, 3).text())
    }

    @Test
    fun withoutAMouseMode_aTapSendsNothing() {
        assertFalse(mouse().active)
        assertNull(mouse().click(1, 1))
        // The encoding alone is not a request for reports.
        assertNull(mouse("$esc[?1006h").click(1, 1))
    }

    @Test
    fun theAlternateScreen_isTrackedByEachOfItsModes_andRisOrANewConnectionLeavesIt() {
        assertFalse(mouse().alternateScreen)
        for (mode in listOf(47, 1047, 1049)) {
            assertTrue("?$mode", mouse("$esc[?${mode}h").alternateScreen)
            assertFalse("?$mode", mouse("$esc[?${mode}h", "$esc[?${mode}l").alternateScreen)
        }
        assertFalse(mouse("$esc[?1049h", "${esc}c").alternateScreen)
        assertFalse(mouse("$esc[?1049h").apply { reset() }.alternateScreen)
        // Counted once per time it goes up, split reads included.
        assertEquals(2, mouse("$esc[?1049h", "$esc[?1049h", "$esc[?1049l", "$esc[?10", "49h").screensEntered)
    }

    @Test
    fun eachTrackingMode_turnsReportsOn_andAnyResetTurnsThemOff() {
        for (mode in listOf(9, 1000, 1002, 1003)) {
            assertTrue("?$mode", mouse("$esc[?${mode}h").active)
            assertFalse("?$mode", mouse("$esc[?${mode}h", "$esc[?${mode}l").active)
        }
        // libvterm: a reset of any of them turns reporting off.
        assertFalse(mouse("$esc[?1003h", "$esc[?1000l").active)
    }

    @Test
    fun bracketedPaste_isOnWhileTheProgramAskedForIt_andRisOrANewConnectionTurnsItOff() {
        assertFalse(mouse().bracketedPaste)
        assertTrue(mouse("$esc[?2004h").bracketedPaste)
        assertFalse(mouse("$esc[?2004h", "$esc[?2004l").bracketedPaste)
        assertFalse(mouse("$esc[?2004h", "${esc}c").bracketedPaste)
        assertFalse(mouse("$esc[?2004h").apply { reset() }.bracketedPaste)
        // Leaving the alternate screen keeps it, like the cursor keys: the program turns it off itself.
        assertTrue(mouse("$esc[?1049h$esc[?2004h", "$esc[?1049l").bracketedPaste)
        // Split across reads, and among other modes.
        assertTrue(mouse("$esc[?20", "04h").bracketedPaste)
        assertTrue(mouse("$esc[?1049;2004;1006h").bracketedPaste)
    }

    @Test
    fun severalModesInOneSequence_andModesAmongOtherOutput() {
        val mouse = mouse("hello$esc[2J$esc[?1049;1000;1006h$esc[1;1Hsidebar")
        assertEquals("$esc[<0;2;1M$esc[<0;2;1m", mouse.click(2, 1).text())
    }

    @Test
    fun aSequenceSplitAcrossReads_isStillSeen() {
        val whole = "$esc[?1000h$esc[?1006h"
        for (cut in 1 until whole.length) {
            val mouse = mouse(whole.substring(0, cut), whole.substring(cut))
            assertEquals("cut at $cut", "$esc[<0;1;1M$esc[<0;1;1m", mouse.click(1, 1).text())
        }
        assertFalse(mouse("$esc[?10", "00h", "$esc", "[?1000l").active)
    }

    @Test
    fun aFullReset_orLeavingTheAlternateScreen_turnsReportsOff() {
        assertFalse(mouse("$esc[?1000h$esc[?1006h", "${esc}c").active)
        assertFalse(mouse("$esc[?1000h", "$esc", "c").active)
        for (screen in listOf(47, 1047, 1049)) {
            assertFalse("?$screen", mouse("$esc[?${screen}h$esc[?1000h", "$esc[?${screen}l").active)
        }
        // After a reset the encoding is legacy again.
        val mouse = mouse("$esc[?1000h$esc[?1006h${esc}c$esc[?1000h")
        assertEquals("$esc[M !!$esc[M#!!", mouse.click(1, 1).text())
    }

    @Test
    fun resetForgetsEveryMode() {
        val mouse = mouse("$esc[?1002h")
        mouse.reset()
        assertFalse(mouse.active)
    }

    @Test
    fun legacyEncoding_isOneByteEach_upToColumn223() {
        val mouse = mouse("$esc[?1000h")
        // Button 0 press (32), button 3 release (35), column and row as 32 + n.
        assertArrayEquals(
            byteArrayOf(27, '['.code.toByte(), 'M'.code.toByte(), 32, 37, 35, 27, '['.code.toByte(), 'M'.code.toByte(), 35, 37, 35),
            mouse.click(5, 3),
        )
        assertEquals(255.toByte(), mouse.click(223, 1)!![4])
        assertNull(mouse.click(224, 1))
        assertNull(mouse.click(1, 224))
        // SGR has no such limit.
        assertEquals("$esc[<0;300;2M$esc[<0;300;2m", mouse("$esc[?1000;1006h").click(300, 2).text())
    }

    @Test
    fun urxvtEncoding_isDecimalWith32Added() {
        assertEquals("$esc[32;5;3M$esc[35;5;3M", mouse("$esc[?1000h$esc[?1015h").click(5, 3).text())
        // Its reset goes back to legacy.
        assertEquals("$esc[M %#$esc[M#%#", mouse("$esc[?1000;1015h", "$esc[?1015l").click(5, 3).text())
    }

    @Test
    fun x10Mode_reportsThePressOnly() {
        assertEquals("$esc[M %#", mouse("$esc[?9h").click(5, 3).text())
        assertEquals("$esc[<0;5;3M", mouse("$esc[?9;1006h").click(5, 3).text())
    }

    @Test
    fun otherPrivateModes_changeNothing() {
        assertFalse(mouse("$esc[?25h$esc[?2004h$esc[?1004h$esc[?1005h").active)
        assertFalse(mouse("$esc[1000h").active) // not a private mode
    }

    @Test
    fun wheel_isButton64Up_65Down_aPressOnly_inTheAskedEncoding() {
        assertEquals("$esc[<64;5;3M", mouse("$esc[?1000;1006h").wheel(5, 3, up = true).text())
        assertEquals("$esc[<65;5;3M", mouse("$esc[?1002h$esc[?1006h").wheel(5, 3, up = false).text())
        assertEquals("$esc[96;5;3M", mouse("$esc[?1000;1015h").wheel(5, 3, up = true).text())
        assertEquals("$esc[Ma%#", mouse("$esc[?1000h").wheel(5, 3, up = false).text())
        assertNull(mouse().wheel(5, 3, up = true))
        assertNull(mouse("$esc[?1000h").wheel(224, 3, up = true))
    }

    @Test
    fun arrows_followApplicationCursorKeys_throughTheAlternateScreen_untilAReset() {
        assertEquals("$esc[B", mouse().arrow(TerminalArrow.DOWN).text())
        assertEquals("$esc[A$esc[B$esc[C$esc[D", TerminalArrow.entries.joinToString("") { mouse().arrow(it).text()!! })
        val keys = mouse("$esc[?1h")
        assertEquals("${esc}OB", keys.arrow(TerminalArrow.DOWN).text())
        assertFalse("not a mouse mode", keys.active)
        keys.scan("$esc[?1049h$esc[?1049l")
        assertEquals("${esc}OC", keys.arrow(TerminalArrow.RIGHT).text())
        keys.scan("$esc[?1l")
        assertEquals("$esc[C", keys.arrow(TerminalArrow.RIGHT).text())
        assertEquals("$esc[D", mouse("$esc[?1h", "${esc}c").arrow(TerminalArrow.LEFT).text())
    }

    @Test
    fun cell_mapsPixelsToOneBasedCells_likeTermlib() {
        // 10 x 20 px cells on an 80 x 24 terminal.
        assertEquals(1 to 1, TerminalMouse.cell(0f, 0f, 10f, 20f, 80, 24))
        assertEquals(1 to 1, TerminalMouse.cell(9.9f, 19.9f, 10f, 20f, 80, 24))
        assertEquals(2 to 2, TerminalMouse.cell(10f, 20f, 10f, 20f, 80, 24))
        assertEquals(13 to 6, TerminalMouse.cell(125f, 110f, 10f, 20f, 80, 24))
        // The strip under the last row and past the last column lands on them.
        assertEquals(80 to 24, TerminalMouse.cell(805f, 495f, 10f, 20f, 80, 24))
        assertEquals(1 to 1, TerminalMouse.cell(-3f, -3f, 10f, 20f, 80, 24))
    }
}
