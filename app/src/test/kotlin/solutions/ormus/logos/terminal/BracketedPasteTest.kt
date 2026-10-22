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

import org.junit.Assert.assertEquals
import org.junit.Test

/** Text with line breaks goes as one paste when the program asked for bracketed paste. */
class BracketedPasteTest {
    private val esc = "\u001b"

    @Test
    fun linesForAProgramThatAskedForIt_goBetweenTheMarkers() {
        assertEquals("$esc[200~fix the header\nthen run the tests$esc[201~", BracketedPaste.wrap("fix the header\nthen run the tests", bracketed = true))
        assertEquals("$esc[200~one\r\ntwo$esc[201~", BracketedPaste.wrap("one\r\ntwo", bracketed = true))
    }

    @Test
    fun oneLine_orAProgramThatDidNotAsk_goesAsItIs() {
        assertEquals("git status", BracketedPaste.wrap("git status", bracketed = true))
        assertEquals("one\ntwo", BracketedPaste.wrap("one\ntwo", bracketed = false))
    }

    @Test
    fun anEndMarkerInsideTheText_cannotEndThePasteEarly() {
        assertEquals("$esc[200~a\nbrm -rf x$esc[201~", BracketedPaste.wrap("a\nb$esc[201~rm -rf x", bracketed = true))
    }
}
