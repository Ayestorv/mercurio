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

/**
 * Spots the Herdr client dropping its server connection in a session's output, so the
 * session can attach again. Herdr's server keeps every pane and agent, so a new `herdr`
 * comes back to the same place.
 *
 * Measured on Sun (herdr 0.9.0, a client in a pty): on exit the client leaves the
 * alternate screen (`ESC[?1049l`), resets a few modes, then prints `herdr: <reason>`.
 * Requiring that exit sequence right before the words means an agent printing the same
 * words inside a pane (which Herdr draws itself, never with that sequence) does not
 * count. Only a lost connection counts: "server shut down" is a stop on purpose.
 */
object HerdrDrop {
    private val DROP = Regex("\u001b\\[\\?1049l(?:\u001b\\[[0-9;?<> ]*[A-Za-z])*herdr: lost connection to server")

    // Any client exit: the same screen exit, then "herdr: <reason>".
    private val EXIT = Regex("\u001b\\[\\?1049l(?:\u001b\\[[0-9;?<> ]*[A-Za-z])*herdr: ")

    /** How much of the previous output to keep, so a drop split across reads is still seen. */
    const val CARRY_CHARS = 256

    fun dropped(output: CharSequence): Boolean = DROP.containsMatchIn(output)

    /** Whether [output] holds the Herdr client quitting, for any reason (a drop, a stop). */
    fun exited(output: CharSequence): Boolean = EXIT.containsMatchIn(output)
}
