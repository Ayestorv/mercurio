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

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.connectbot.transport.CommandOutput
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Whether a host's Herdr client has saved machines. machine-list.json has the shape of
 * `herdr machine list --json` (herdr 0.9.3, the same in 0.9.0), with generic names.
 */
class HerdrMachinesTest {
    @Test
    fun anyEnabled_onlyCountsEnabledMachines() {
        assertTrue(HerdrMachines.anyEnabled(herdrFixture("machine-list.json")))
        assertFalse("no machines saved", HerdrMachines.anyEnabled("[]"))
        assertFalse("only a disabled one", HerdrMachines.anyEnabled("""[{"id":"a","label":"Old","target":"old","session":null,"enabled":false,"selected":false}]"""))
        assertFalse("a Herdr without machines", HerdrMachines.anyEnabled("error: unrecognized subcommand 'machine'"))
        assertEquals("bash -lc 'herdr machine list --json'", HerdrMachines.listCommand)
    }

    // Bytes as they are, keys as "<KEY>", in the order they went out.
    private fun session(answer: () -> CommandOutput?, sent: MutableList<String>) = HerdSession(
        hostName = "host",
        runner = { command -> if (command == HerdrMachines.listCommand) answer() else null },
        sendKeys = { sent += it },
        scope = CoroutineScope(Dispatchers.Unconfined),
        io = Dispatchers.Unconfined,
        sendKey = { sent += "<$it>" },
    )

    @Test
    fun readMachines_keepsWhatWasKnownWhenTheReadNeverRan() = runBlocking {
        var answer: CommandOutput? = CommandOutput(herdrFixture("machine-list.json"), "", 0)
        val herd = session({ answer }, mutableListOf())
        assertFalse(herd.crossesMachines.value)
        herd.readMachines()
        assertTrue(herd.crossesMachines.value)
        answer = null // the SSH session dropped
        herd.readMachines()
        assertTrue(herd.crossesMachines.value)
        answer = CommandOutput("", "error: unrecognized subcommand 'machine'", 2)
        herd.readMachines()
        assertFalse(herd.crossesMachines.value)
    }

    @Test
    fun switchWorkspace_sendsThePickerKey_thenPressesTheArrowAndEnter() {
        val sent = mutableListOf<String>()
        val herd = session({ null }, sent)
        val ctrlB = 2.toChar().toString()

        assertEquals(null, herd.switchWorkspace(down = true))
        // The arrow and Enter go through the terminal, which encodes the arrow in the host's
        // cursor-key mode and writes both after the picker key.
        assertEquals(listOf(ctrlB + "w", "<DOWN>", "<ENTER>"), sent)

        // With the prefix already armed (the Herdr panel opened), only its key follows.
        sent.clear()
        herd.armPrefix()
        herd.switchWorkspace(down = false)
        assertEquals(listOf(ctrlB, "w", "<UP>", "<ENTER>"), sent)
    }
}
