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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import solutions.ormus.logos.herd.HerdrSwipes.Claim

/**
 * Which touches become Herdr swipes (dp, dp per second; y grows downward), and what each
 * swipe runs against the snapshot recorded on Sun (workspaces w1..w5 numbered 1-4, w1 focused).
 */
class HerdrSwipesTest {
    private val snapshot = checkNotNull(parseHerdrSnapshot(herdrFixture("snapshot-sun-mixed.json")))

    @Test
    fun claim_waitsUntilTheTouchTravels() {
        assertEquals(Claim.WAIT, HerdrSwipes.claim(1, 0f, 0f))
        assertEquals(Claim.WAIT, HerdrSwipes.claim(1, -12f, 3f))
        assertEquals(Claim.WAIT, HerdrSwipes.claim(2, 0f, -15f))
    }

    @Test
    fun claim_takesOneFingerAcrossAndTwoFingersEitherWay() {
        assertEquals(Claim.CLAIM, HerdrSwipes.claim(1, -24f, 4f))
        assertEquals(Claim.CLAIM, HerdrSwipes.claim(1, 30f, -10f))
        assertEquals(Claim.CLAIM, HerdrSwipes.claim(2, 25f, 0f))
        assertEquals(Claim.CLAIM, HerdrSwipes.claim(2, 3f, -25f))
    }

    @Test
    fun claim_takesAFastOneFingerFlickUpOrDown() {
        assertEquals(Claim.CLAIM, HerdrSwipes.claim(1, 2f, -25f, vy = -1500f))
        assertEquals(Claim.CLAIM, HerdrSwipes.claim(1, -3f, 30f, vx = -100f, vy = 1000f))
    }

    @Test
    fun claim_leavesScrollPinchDiagonalsAndThreeFingersToTheTerminal() {
        assertEquals("one finger up or down is scroll", Claim.RELEASE, HerdrSwipes.claim(1, 4f, -30f))
        assertEquals("a drag up that is not fast is scroll", Claim.RELEASE, HerdrSwipes.claim(1, 0f, -30f, vy = -900f))
        assertEquals("fast, but the other way it travelled", Claim.RELEASE, HerdrSwipes.claim(1, 0f, -30f, vy = 1500f))
        assertEquals("a fast diagonal", Claim.RELEASE, HerdrSwipes.claim(1, 20f, -25f, vx = 1500f, vy = -1500f))
        assertEquals("a pinch changes the gap between fingers", Claim.RELEASE, HerdrSwipes.claim(2, 2f, 1f, spanChange = 40f))
        assertEquals("a pinch in", Claim.RELEASE, HerdrSwipes.claim(2, 0f, 0f, spanChange = -25f))
        assertEquals("diagonal", Claim.RELEASE, HerdrSwipes.claim(1, 25f, 20f))
        assertEquals("diagonal", Claim.RELEASE, HerdrSwipes.claim(2, -20f, 20f))
        assertEquals(Claim.RELEASE, HerdrSwipes.claim(3, -40f, 0f))
    }

    @Test
    fun classify_oneFingerAcrossMovesBetweenTabs() {
        assertEquals(HerdrSwipe.NEXT_TAB, HerdrSwipes.classify(1, -80f, 5f, 0f, 0f))
        assertEquals(HerdrSwipe.PREV_TAB, HerdrSwipes.classify(1, 80f, -5f, 0f, 0f))
    }

    @Test
    fun classify_aFastOneFingerFlickMovesBetweenWorkspaces() {
        assertEquals(HerdrSwipe.NEXT_WORKSPACE, HerdrSwipes.classify(1, 0f, -200f, 0f, -3000f))
        assertEquals(HerdrSwipe.PREV_WORKSPACE, HerdrSwipes.classify(1, 5f, 40f, 0f, 1200f))
        assertNull("long, but it left slowly", HerdrSwipes.classify(1, 0f, -200f, 0f, -600f))
        assertNull("fast, but flung back the other way", HerdrSwipes.classify(1, 0f, -80f, 0f, 2000f))
        assertNull("under the judging distance", HerdrSwipes.classify(1, 0f, -15f, 0f, -3000f))
    }

    @Test
    fun classify_keepsTheAxisTheTouchWasClaimedOn() {
        assertEquals(HerdrSwipe.NEXT_TAB, HerdrSwipes.classify(1, -80f, 5f, -900f, 0f, across = true))
        assertNull("claimed across, ended as a flick up", HerdrSwipes.classify(1, -10f, -90f, 0f, -3000f, across = true))
        assertNull("claimed up, ended across", HerdrSwipes.classify(2, -90f, -10f, 0f, 0f, across = false))
        assertEquals(HerdrSwipe.NEXT_WORKSPACE, HerdrSwipes.classify(2, -8f, -70f, 0f, 0f, across = false))
    }

    @Test
    fun classify_twoFingersMovePaneFocusAcrossAndWorkspacesUpOrDown() {
        assertEquals(HerdrSwipe.PANE_RIGHT, HerdrSwipes.classify(2, -70f, 0f, 0f, 0f))
        assertEquals(HerdrSwipe.PANE_LEFT, HerdrSwipes.classify(2, 70f, 10f, 0f, 0f))
        assertEquals(HerdrSwipe.NEXT_WORKSPACE, HerdrSwipes.classify(2, 0f, -70f, 0f, 0f))
        assertEquals(HerdrSwipe.PREV_WORKSPACE, HerdrSwipes.classify(2, -8f, 70f, 0f, 0f))
    }

    @Test
    fun classify_shortSwipesFireOnlyWhenFastAndForward() {
        assertNull("short and slow", HerdrSwipes.classify(1, -40f, 0f, -200f, 0f))
        assertEquals("short and fast", HerdrSwipe.NEXT_TAB, HerdrSwipes.classify(1, -40f, 0f, -900f, 0f))
        assertNull("fast, but flung back the other way", HerdrSwipes.classify(1, -40f, 0f, 900f, 0f))
        assertNull("under the judging distance", HerdrSwipes.classify(1, -15f, 0f, -2000f, 0f))
        assertEquals(HerdrSwipe.NEXT_WORKSPACE, HerdrSwipes.classify(2, 0f, -30f, 0f, -800f))
    }

    @Test
    fun classify_ignoresDiagonalsAndThreeFingers() {
        assertNull(HerdrSwipes.classify(1, -80f, -60f, 0f, 0f))
        assertNull(HerdrSwipes.classify(3, -80f, 0f, 0f, 0f))
        assertNull(HerdrSwipes.classify(0, -80f, 0f, 0f, 0f))
    }

    @Test
    fun action_tabsArePrefixKeysAndPanesFocusByDirection() {
        assertEquals(HerdrAction.BoundKey(HerdrKey.PREVIOUS_TAB), HerdrSwipes.action(HerdrSwipe.PREV_TAB, null))
        assertEquals(HerdrAction.BoundKey(HerdrKey.NEXT_TAB), HerdrSwipes.action(HerdrSwipe.NEXT_TAB, null))
        assertEquals(HerdrAction.FocusPane(HerdrDirection.LEFT), HerdrSwipes.action(HerdrSwipe.PANE_LEFT, snapshot))
        assertEquals(HerdrAction.FocusPane(HerdrDirection.RIGHT), HerdrSwipes.action(HerdrSwipe.PANE_RIGHT, snapshot))
        // Same bytes as the compose bar's ← and → keys.
        val commands = HerdrCommands()
        assertEquals(HerdrInvocation.Keys(HerdrPrefix.DEFAULT.bytes + "p"), commands.build(HerdrSwipes.action(HerdrSwipe.PREV_TAB, null)!!, null))
        assertEquals(HerdrInvocation.Keys(HerdrPrefix.DEFAULT.bytes + "n"), commands.build(HerdrSwipes.action(HerdrSwipe.NEXT_TAB, null)!!, null))
    }

    @Test
    fun action_workspacesStepInNumberOrderAndWrap() {
        assertEquals(HerdrAction.FocusWorkspace("w3"), HerdrSwipes.action(HerdrSwipe.NEXT_WORKSPACE, snapshot))
        assertEquals(HerdrAction.FocusWorkspace("w5"), HerdrSwipes.action(HerdrSwipe.PREV_WORKSPACE, snapshot))
        val onLast = snapshot.copy(focusedWorkspaceId = "w5")
        assertEquals(HerdrAction.FocusWorkspace("w1"), HerdrSwipes.action(HerdrSwipe.NEXT_WORKSPACE, onLast))
        assertEquals(HerdrAction.FocusWorkspace("w4"), HerdrSwipes.action(HerdrSwipe.PREV_WORKSPACE, onLast))
    }

    @Test
    fun action_workspacesNeedAnotherWorkspaceAndAFocus() {
        assertNull(HerdrSwipes.action(HerdrSwipe.NEXT_WORKSPACE, null))
        assertNull(HerdrSwipes.action(HerdrSwipe.NEXT_WORKSPACE, snapshot.copy(focusedWorkspaceId = null)))
        val alone = snapshot.copy(workspaces = snapshot.workspaces.filter { it.workspaceId == "w1" })
        assertNull(HerdrSwipes.action(HerdrSwipe.PREV_WORKSPACE, alone))
    }

    @Test
    fun dragScrolls_isOneFingerUpOrDown() {
        assertTrue(HerdrSwipes.dragScrolls(1, 4f, -30f))
        assertTrue(HerdrSwipes.dragScrolls(1, 0f, 40f))
        assertFalse("across", HerdrSwipes.dragScrolls(1, -30f, 4f))
        assertFalse("diagonal", HerdrSwipes.dragScrolls(1, 25f, 20f))
        assertFalse("two fingers", HerdrSwipes.dragScrolls(2, 0f, -30f))
    }
}
