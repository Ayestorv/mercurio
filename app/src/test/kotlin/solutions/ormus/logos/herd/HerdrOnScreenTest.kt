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

package solutions.ormus.logos.herd

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import solutions.ormus.logos.terminal.TerminalMouse

/** Whether the Herdr client is on the terminal, from output shaped like herdr's (captured on Sun, herdr 0.9.0). */
class HerdrOnScreenTest {
    private val esc = 27.toChar().toString()

    // What a herdr client writes as it starts drawing, and as it quits.
    private val enter = "$esc[?1049h$esc[?1000h$esc[?1006h$esc[2J"
    private val exitModes = "$esc[<1u$esc[?7h$esc[?1004l$esc[?2004l$esc[?1000l$esc[?1049l$esc[?2031l$esc[?25h$esc[0 q"

    private fun screen(vararg output: String) = HerdrOnScreen(TerminalMouse()).apply { output.forEach { scan(it) } }

    @Test
    fun aShellPrompt_isNotHerdr() {
        assertFalse(screen().active)
        assertFalse(screen("bash-5.3$ ").active)
    }

    @Test
    fun theAlternateScreen_isHerdrOnScreen() {
        assertTrue(screen(enter, "workspaces").active)
        for (mode in listOf(47, 1047, 1049)) assertTrue("?$mode", screen("$esc[?${mode}h").active)
    }

    @Test
    fun aClientExit_takesHerdrOffScreen_untilAFullScreenProgramComesBack() {
        val quit = exitModes + "herdr: server shut down: server is shutting down\r\n"
        assertFalse(screen(enter, quit).active)
        // Attached again: the same read may carry the exit and the new screen.
        assertTrue(screen(enter, quit + enter).active)
        assertTrue(screen(enter, quit, "bash-5.3$ herdr\r\n", enter).active)
        // The exit kept in the carry is not seen a second time once Herdr is back.
        val back = HerdrOnScreen(TerminalMouse()).apply {
            scan(enter)
            scan(quit)
            scan(enter)
            scan("x")
        }
        assertTrue(back.active)
    }

    @Test
    fun anExitSplitAcrossReads_isStillSeen() {
        val mouse = TerminalMouse()
        val herdr = HerdrOnScreen(mouse).apply { scan(enter) }
        // A program that leaves the screen but sends the screen exit in one read, the words in the next.
        herdr.scan(exitModes)
        herdr.scan("herdr: lost connection to server\r\n")
        assertFalse(herdr.active)
        assertEquals(1, mouse.screensEntered)
    }
}
