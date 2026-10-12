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
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.TouchInjectionScope
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.connectbot.service.TerminalBridge
import org.connectbot.service.vtermKey
import org.connectbot.terminal.VTermKey
import org.connectbot.transport.CommandOutput
import org.connectbot.usage.UsageActions
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import solutions.ormus.logos.herd.HerdSession
import solutions.ormus.logos.herd.HerdrAction
import solutions.ormus.logos.herd.HerdrCommands
import solutions.ormus.logos.herd.HerdrInvocation
import solutions.ormus.logos.herd.HerdrMachines
import solutions.ormus.logos.herd.HerdrPrefix
import solutions.ormus.logos.herd.HerdrSessions
import solutions.ormus.logos.herd.HerdrSwipe
import solutions.ormus.logos.herd.HerdrTermKey

/**
 * The swipe layer over a stand-in for termlib's terminal: a child that, like termlib, reads
 * the first finger's movement on the Main pass (consumed movement reads as none) and counts
 * pinches. Real multi-pointer touches go through Compose's input pipeline (Robolectric,
 * xhdpi: 1dp = 2px).
 */
@RunWith(AndroidJUnit4::class)
class HerdrSwipeInputTest {
    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private val fired = mutableListOf<HerdrSwipe>()
    private val fingers = mutableListOf<Int>()
    private var enabled = true

    /** What the stand-in terminal saw: the first finger's unconsumed travel, and touches it ended. */
    private var termTravel = Offset.Zero
    private var termTouches = 0
    private var termMaxPointers = 0

    private fun setContent() {
        rule.setContent {
            Box(
                Modifier
                    .size(400.dp)
                    .herdrSwipes(enabled = { enabled }) { swipe, count ->
                        fired += swipe
                        fingers += count
                    }
                    .testTag("terminal"),
            ) {
                Box(
                    Modifier.fillMaxSize().pointerInput(Unit) {
                        awaitEachGesture {
                            val down = awaitFirstDown(requireUnconsumed = false)
                            while (true) {
                                val event = awaitPointerEvent(PointerEventPass.Main)
                                termMaxPointers = maxOf(termMaxPointers, event.changes.count { it.pressed })
                                event.changes.firstOrNull { it.id == down.id }?.let { termTravel += it.positionChange() }
                                if (event.changes.none { it.pressed }) break
                            }
                            termTouches++
                        }
                    },
                )
            }
        }
    }

    /** Move every listed finger by [by] (px) in [steps] frames of 16ms. */
    private fun TouchInjectionScope.drag(fingers: List<Int>, by: Offset, steps: Int = 10) {
        val start = fingers.associateWith { currentPosition(it)!! }
        for (step in 1..steps) {
            fingers.forEach { updatePointerTo(it, start.getValue(it) + by * (step / steps.toFloat())) }
            move()
        }
    }

    private fun swipe(fingers: Int, by: Offset, steps: Int = 10) {
        rule.onNodeWithTag("terminal").performTouchInput {
            down(0, Offset(300f, 400f))
            if (fingers == 2) down(1, Offset(500f, 400f))
            drag((0 until fingers).toList(), by, steps)
            (0 until fingers).forEach { up(it) }
        }
        rule.waitForIdle()
    }

    @Test
    fun oneFingerAcross_switchesTab_andTheTerminalStopsMovingOnceClaimed() {
        setContent()
        swipe(1, Offset(-300f, 0f))
        assertEquals(listOf(HerdrSwipe.NEXT_TAB), fired)
        // 20dp = 40px claims it; after that the terminal sees no movement.
        assertTrue("terminal saw ${termTravel.x}px", termTravel.x > -100f && termTravel.x <= -40f)

        swipe(1, Offset(300f, 0f))
        assertEquals(listOf(HerdrSwipe.NEXT_TAB, HerdrSwipe.PREV_TAB), fired)
    }

    @Test
    fun oneFingerUpOrDown_isTheTerminalsScroll() {
        setContent()
        swipe(1, Offset(0f, -300f), steps = 30) // 150dp over 480ms: a drag, not a flick
        swipe(1, Offset(0f, 300f), steps = 30)
        assertEquals(emptyList<HerdrSwipe>(), fired)
        assertEquals("the terminal saw both drags whole", 0f, termTravel.y, 0.5f)
        assertEquals(2, termTouches)
    }

    @Test
    fun oneFingerFlickUpOrDown_switchesWorkspace_andTheTerminalStopsMovingOnceClaimed() {
        setContent()
        swipe(1, Offset(0f, -300f), steps = 5) // 150dp over 80ms
        assertEquals(listOf(HerdrSwipe.NEXT_WORKSPACE), fired)
        // Claimed on its first 60px move; after that the terminal sees no movement.
        assertTrue("terminal saw ${termTravel.y}px", termTravel.y > -100f && termTravel.y <= -40f)

        swipe(1, Offset(0f, 300f), steps = 5)
        assertEquals(listOf(HerdrSwipe.NEXT_WORKSPACE, HerdrSwipe.PREV_WORKSPACE), fired)
        assertEquals(listOf(1, 1), fingers)
        assertEquals(UsageActions.SWIPE_FLICK_NEXT_WORKSPACE, usageIdFor(HerdrSwipe.NEXT_WORKSPACE, 1))
        assertEquals(UsageActions.SWIPE_NEXT_WORKSPACE, usageIdFor(HerdrSwipe.NEXT_WORKSPACE, 2))
    }

    @Test
    fun aDragThatStartsSlow_staysTheTerminalsScroll_evenWhenItEndsInAFlick() {
        setContent()
        rule.onNodeWithTag("terminal").performTouchInput {
            down(0, Offset(300f, 600f))
            drag(listOf(0), Offset(0f, -60f), steps = 10) // 30dp over 160ms: judged slow, let go
            drag(listOf(0), Offset(0f, -300f), steps = 3) // then fast
            up(0)
        }
        rule.waitForIdle()
        assertEquals(emptyList<HerdrSwipe>(), fired)
        assertEquals(-360f, termTravel.y, 0.5f)
    }

    @Test
    fun claimVelocity_fallsBackToTheAverageWhenTheTrackerHasNothingOrReadsTheWrongWay() {
        // Measured on the emulator: injected flicks reach the judging distance with a tracker reading 0 or the other way.
        assertEquals(Velocity(0f, 20_000f), claimVelocity(Velocity(0f, -30_000f), Offset(0f, 60f), 3))
        assertEquals(Velocity(0f, -6_000f), claimVelocity(Velocity.Zero, Offset(0f, -6f), 0))
        assertEquals("a tracker that reads faster the right way wins", Velocity(0f, -9_000f), claimVelocity(Velocity(0f, -9_000f), Offset(0f, -40f), 10))
        assertEquals("a slow drag stays slow", Velocity(0f, 300f), claimVelocity(Velocity(0f, 300f), Offset(0f, 40f), 160))
    }

    @Test
    fun aFastDiagonal_neverFires() {
        setContent()
        swipe(1, Offset(-200f, -240f), steps = 4)
        assertEquals(emptyList<HerdrSwipe>(), fired)
        assertEquals(-240f, termTravel.y, 0.5f)
    }

    @Test
    fun twoFingers_movePaneFocusAcrossAndWorkspacesUpOrDown() {
        setContent()
        swipe(2, Offset(-300f, 0f))
        swipe(2, Offset(300f, 0f))
        swipe(2, Offset(0f, -300f))
        swipe(2, Offset(0f, 300f))
        assertEquals(
            listOf(HerdrSwipe.PANE_RIGHT, HerdrSwipe.PANE_LEFT, HerdrSwipe.NEXT_WORKSPACE, HerdrSwipe.PREV_WORKSPACE),
            fired,
        )
        assertEquals(listOf(2, 2, 2, 2), fingers)
    }

    @Test
    fun pinch_staysTheTerminalsZoom() {
        setContent()
        rule.onNodeWithTag("terminal").performTouchInput {
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
        rule.waitForIdle()
        assertEquals(emptyList<HerdrSwipe>(), fired)
        assertEquals(2, termMaxPointers)
        assertEquals("the terminal saw the whole pinch", -150f, termTravel.x, 0.5f)
    }

    @Test
    fun tapsAndSlowShortMoves_neverFire() {
        setContent()
        rule.onNodeWithTag("terminal").performTouchInput { click(Offset(300f, 400f)) }
        swipe(1, Offset(-60f, 0f), steps = 30) // 30dp over 480ms: short and slow
        assertEquals(emptyList<HerdrSwipe>(), fired)
        assertEquals(2, termTouches)
    }

    @Test
    fun disabled_offHerdrOrWithASelectionUp_leavesEveryTouchToTheTerminal() {
        enabled = false
        setContent()
        swipe(1, Offset(-300f, 0f))
        swipe(2, Offset(0f, -300f))
        swipe(1, Offset(0f, -300f), steps = 5)
        assertEquals(emptyList<HerdrSwipe>(), fired)
        assertEquals(-300f, termTravel.x, 0.5f)
        assertEquals("the flick was all the terminal's", -600f, termTravel.y, 0.5f)
    }

    @Test
    fun tabSwipes_sendTheHostsBindings_orSayWhyNot() = runBlocking {
        val sent = mutableListOf<String>()
        val config = "[keys]\nprevious_tab = \"prefix+shift+p\"\nnext_tab = \"alt+right\"\n"
        val herd = HerdSession(
            hostName = "stock",
            runner = { command -> if (command == HerdrPrefix.readCommand) CommandOutput(config, "", 0) else null },
            sendKeys = { sent += it },
            scope = CoroutineScope(Dispatchers.Unconfined),
            io = Dispatchers.Unconfined,
        )
        herd.readKeys()
        val bridge = mock(TerminalBridge::class.java)
        `when`(bridge.herd).thenReturn(herd)
        `when`(bridge.herdrOnScreen).thenReturn(true)

        assertNull(runHerdrSwipe(bridge, HerdrSwipe.PREV_TAB))
        // alt+right is an arrow chord Mercurio cannot send: nothing goes out, and the hint says why.
        val why = runHerdrSwipe(bridge, HerdrSwipe.NEXT_TAB)
        assertTrue(why.toString(), why!!.contains("next_tab to \"alt+right\""))
        assertEquals(listOf(CTRL_B + "P"), sent)
    }

    @Test
    fun workspaceSwipe_actsInTheAttachedSession() = runBlocking {
        val ran = mutableListOf<String>()
        val work = checkNotNull(javaClass.getResourceAsStream("/herdr/snapshot-picker-test.json")).readBytes().decodeToString()
        val herd = HerdSession(
            hostName = "Sun",
            runner = { command ->
                ran += command
                CommandOutput(if (command == HerdrSessions.snapshotCommand("work")) work else """{"id":"cli","result":{}}""", "", 0)
            },
            sendKeys = {},
            scope = CoroutineScope(Dispatchers.Unconfined),
            io = Dispatchers.Unconfined,
        )
        herd.attach("work")
        herd.refresh("work")
        val bridge = mock(TerminalBridge::class.java)
        `when`(bridge.herd).thenReturn(herd)

        // Work's focus is on w1 of three: the next workspace is w2, focused in work.
        assertNull(runHerdrSwipe(bridge, HerdrSwipe.NEXT_WORKSPACE))
        assertEquals(HerdrSessions.commands("work").build(HerdrAction.FocusWorkspace("w2"), null), HerdrInvocation.Cli(ran[1]))
    }

    @Test
    fun workspaceSwipe_withSavedMachines_drivesHerdrsSwitcher_notTheCli() = runBlocking {
        // The attached client has a saved machine: `workspace focus` only reaches this
        // host's own workspaces, so the swipe must go through Herdr's switcher keys.
        val ran = mutableListOf<String>()
        val sent = mutableListOf<String>()
        var machines = checkNotNull(javaClass.getResourceAsStream("/herdr/machine-list.json")).readBytes().decodeToString()
        val sun = checkNotNull(javaClass.getResourceAsStream("/herdr/snapshot-sun-mixed.json")).readBytes().decodeToString()
        val herd = HerdSession(
            hostName = "host",
            runner = { command ->
                ran += command
                when (command) {
                    HerdrMachines.listCommand -> CommandOutput(machines, "", 0)
                    HerdrSessions.snapshotCommand(HerdrCommands.DEFAULT_SESSION) -> CommandOutput(sun, "", 0)
                    else -> CommandOutput("""{"id":"cli","result":{}}""", "", 0)
                }
            },
            sendKeys = { sent += it },
            scope = CoroutineScope(Dispatchers.Unconfined),
            io = Dispatchers.Unconfined,
            sendKey = { sent += "<$it>" },
        )
        herd.refreshAll()
        val bridge = mock(TerminalBridge::class.java)
        `when`(bridge.herd).thenReturn(herd)
        `when`(bridge.herdrOnScreen).thenReturn(true)

        val snapshots = { ran.count { it == HerdrSessions.snapshotCommand(HerdrCommands.DEFAULT_SESSION) } }
        val before = snapshots()

        // Next is the workspace below in Herdr's sidebar, on whichever machine; previous the one above.
        assertTrue(usesSwitcher(bridge, HerdrSwipe.NEXT_WORKSPACE))
        assertNull(runHerdrSwipe(bridge, HerdrSwipe.NEXT_WORKSPACE))
        assertNull(runHerdrSwipe(bridge, HerdrSwipe.PREV_WORKSPACE))
        assertEquals(emptyList<String>(), ran.filter { "workspace focus" in it })
        assertEquals(listOf(CTRL_B + "w", "<DOWN>", "<ENTER>", CTRL_B + "w", "<UP>", "<ENTER>"), sent)
        // Each switch refreshes the attached session's snapshot.
        assertEquals(before + 2, snapshots())
        // Tabs and panes are this host's either way.
        assertTrue(!usesSwitcher(bridge, HerdrSwipe.NEXT_TAB) && !usesSwitcher(bridge, HerdrSwipe.PANE_LEFT))

        // The machine is gone after a refresh: the CLI again, nothing typed.
        machines = "[]"
        herd.refreshAll()
        sent.clear()
        assertNull(runHerdrSwipe(bridge, HerdrSwipe.NEXT_WORKSPACE))
        assertEquals(listOf(HerdrCommands().build(HerdrAction.FocusWorkspace("w3"), null)), ran.filter { "workspace focus" in it }.map { HerdrInvocation.Cli(it) })
        assertEquals(emptyList<String>(), sent)
    }

    @Test
    fun keySwipes_sendNothing_whileHerdrIsNotOnScreen() = runBlocking {
        // A shell prompt (normal screen), or the Herdr client quit: the keys would be typed
        // into the shell. Nothing goes out, and the hint says Herdr is not on screen.
        val ran = mutableListOf<String>()
        val sent = mutableListOf<String>()
        val machines = checkNotNull(javaClass.getResourceAsStream("/herdr/machine-list.json")).readBytes().decodeToString()
        val herd = HerdSession(
            hostName = "host",
            runner = { command ->
                ran += command
                if (command == HerdrMachines.listCommand) CommandOutput(machines, "", 0) else null
            },
            sendKeys = { sent += it },
            scope = CoroutineScope(Dispatchers.Unconfined),
            io = Dispatchers.Unconfined,
            sendKey = { sent += "<$it>" },
        )
        herd.readMachines()
        val bridge = mock(TerminalBridge::class.java)
        `when`(bridge.herd).thenReturn(herd)
        `when`(bridge.herdrOnScreen).thenReturn(false)

        assertEquals(HERDR_NOT_ON_SCREEN, runHerdrSwipe(bridge, HerdrSwipe.NEXT_WORKSPACE))
        assertEquals(HERDR_NOT_ON_SCREEN, runHerdrSwipe(bridge, HerdrSwipe.PREV_WORKSPACE))
        assertEquals(HERDR_NOT_ON_SCREEN, runHerdrSwipe(bridge, HerdrSwipe.NEXT_TAB))
        assertEquals(emptyList<String>(), sent)
        // No CLI fallback: it could not reach the other machines.
        assertEquals(emptyList<String>(), ran.filter { "focus" in it })

        // Herdr back on screen: the same swipe sends the switcher keys.
        `when`(bridge.herdrOnScreen).thenReturn(true)
        assertNull(runHerdrSwipe(bridge, HerdrSwipe.NEXT_WORKSPACE))
        assertEquals(listOf(CTRL_B + "w", "<DOWN>", "<ENTER>"), sent)

        // Without a Herdr side (telnet), the tab keys are held back too.
        val plain = mock(TerminalBridge::class.java)
        assertEquals(HERDR_NOT_ON_SCREEN, runHerdrSwipe(plain, HerdrSwipe.PREV_TAB))
        verify(plain, never()).injectString(org.mockito.ArgumentMatchers.anyString())
    }

    @Test
    fun theNotOnScreenHint_isAMonoLabel() {
        rule.setContent { HerdrSwipeHint(HerdrSwipe.NEXT_WORKSPACE, fired = 1, problem = HERDR_NOT_ON_SCREEN) }
        rule.onNodeWithText("Herdr not on screen").assertIsDisplayed()
    }

    @Test
    fun switcherSwipes_logTheirOwnIds_andArrowsAreTheEmulatorsKeys() {
        assertEquals(UsageActions.SWIPE_FLICK_NEXT_WORKSPACE_SWITCHER, usageIdFor(HerdrSwipe.NEXT_WORKSPACE, 1, switcher = true))
        assertEquals(UsageActions.SWIPE_FLICK_PREV_WORKSPACE_SWITCHER, usageIdFor(HerdrSwipe.PREV_WORKSPACE, 1, switcher = true))
        assertEquals(UsageActions.SWIPE_NEXT_WORKSPACE_SWITCHER, usageIdFor(HerdrSwipe.NEXT_WORKSPACE, 2, switcher = true))
        assertEquals(UsageActions.SWIPE_PREV_WORKSPACE_SWITCHER, usageIdFor(HerdrSwipe.PREV_WORKSPACE, 2, switcher = true))
        assertEquals(UsageActions.SWIPE_FLICK_NEXT_WORKSPACE, usageIdFor(HerdrSwipe.NEXT_WORKSPACE, 1))
        // libvterm encodes these by the host's DECCKM: ESC [ B normally, ESC O B in application mode.
        assertEquals(VTermKey.UP, HerdrTermKey.UP.vtermKey)
        assertEquals(VTermKey.DOWN, HerdrTermKey.DOWN.vtermKey)
        assertEquals(VTermKey.ENTER, HerdrTermKey.ENTER.vtermKey)
    }

    @Test
    fun swipeIntro_saysWhatASwipeDoes_andItsKeyPutsItAway() {
        var dismissed = 0
        rule.setContent { HerdrSwipeIntro(onDismiss = { dismissed++ }) }

        rule.onNodeWithText("Swipe ←→ tabs, flick ↑↓ workspaces").assertIsDisplayed()
        rule.onNodeWithText("Got it").performClick()
        assertEquals(1, dismissed)
    }

    @Test
    fun tabSwipes_withoutAHerdrSide_sendHerdrsDefaults() {
        val bridge = mock(TerminalBridge::class.java)
        `when`(bridge.herdrOnScreen).thenReturn(true)

        assertNull(runHerdrSwipe(bridge, HerdrSwipe.NEXT_TAB))
        assertNull(runHerdrSwipe(bridge, HerdrSwipe.PREV_TAB))

        verify(bridge).injectString(CTRL_B + "n")
        verify(bridge).injectString(CTRL_B + "p")
    }

    private companion object {
        val CTRL_B = 2.toChar().toString()
    }
}
