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

import solutions.ormus.logos.terminal.TerminalMouse

/**
 * Whether the Herdr client is what a session's terminal shows, from its output: the
 * alternate screen is up ([screen] tracks it) and the client has not quit since it went up
 * ([HerdrDrop.exited]). A shell prompt is on the normal screen, so keys meant for Herdr's
 * client are held back there instead of being typed into the shell.
 */
class HerdrOnScreen(private val screen: TerminalMouse) {
    // The tail of the previous output, so an exit split across reads is still seen.
    private var carry = ""

    @Volatile
    private var exited = false

    /** Whether the Herdr client is on screen now. */
    val active: Boolean
        get() = screen.alternateScreen && !exited

    /** Watch decoded output; it scans [screen] too. */
    @Synchronized
    fun scan(output: CharSequence) {
        val window = carry + output
        carry = window.takeLast(HerdrDrop.CARRY_CHARS)
        if (HerdrDrop.exited(window)) {
            exited = true
            // Seen once: the carry must not report it again after Herdr comes back.
            carry = ""
        }
        val screens = screen.screensEntered
        screen.scan(output)
        // A full-screen program went up after the exit: Herdr again, or another one.
        if (screen.screensEntered != screens) exited = false
    }
}
