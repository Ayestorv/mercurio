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

import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import kotlin.math.abs

/**
 * Turns game controller input into [PadAction]s, with herdr-pad's timing: taps, holds,
 * repeats and stick flicks. It knows nothing about terminals: [send] delivers each action
 * and says whether a session took it (false while no console shows, so the pad then
 * drives the rest of the app as Android's focus navigation does).
 *
 * Built for the Ayn Thor's "Odin Controller" (D-pad as keys and hat, triggers as both
 * L2/R2 keys and GAS/BRAKE axes, right stick on Z/RZ) and for paired Xbox pads (right
 * stick on Z/RZ or RX/RY, triggers on LTRIGGER/RTRIGGER, Share as MEDIA_RECORD).
 * The Thor has no Share or Xbox button: holding View opens a tab, and View+Menu
 * together toggles controller control, as Guide does.
 *
 * Main thread only. [schedule] runs a block after a delay and returns a cancel.
 */
class TerminalGamepad(
    private val now: () -> Long,
    private val schedule: (delayMs: Long, block: () -> Unit) -> () -> Unit,
    private val send: (PadAction?) -> Boolean,
    private val onToggle: (enabled: Boolean) -> Unit,
    private val onTalk: (down: Boolean) -> Unit = {},
) {
    /** Whether controller control is on (Guide, or View+Menu, flips it). */
    var enabled: Boolean = true
        private set

    // Herdr's workspace picker: open after the right stick's first flick, until A, B or another action.
    private var pickerOpen = false

    private var bHold: (() -> Unit)? = null
    private var bRepeating = false
    private var xHold: (() -> Unit)? = null
    private var xFired = false
    private var yHold: (() -> Unit)? = null
    private var yRepeating = false
    private var viewHold: (() -> Unit)? = null
    private var viewFired = false
    private var viewDown = false
    private var menuDown = false
    private var chordUsed = false

    private var leftStick: PadAction? = null
    private var leftRepeat: (() -> Unit)? = null
    private var rightArmed = true
    private val hat = mutableMapOf<PadAction, Long>()
    private var hatX = 0
    private var hatY = 0
    private val triggerAt = LongArray(2)
    private val triggerDown = BooleanArray(2)

    /** Whether [event] comes from a game controller (not a keyboard's arrows or a phone's keys). */
    fun isPad(event: KeyEvent): Boolean = isPadDevice(event.device) && event.keyCode in PAD_KEYS

    /** Handle a key from a controller; true when it was used (do not let Android see it). */
    fun onKey(event: KeyEvent): Boolean {
        if (!isPad(event)) return false
        val down = event.action == KeyEvent.ACTION_DOWN
        if (down && event.repeatCount > 0) return enabled
        val code = event.keyCode

        // The toggle works in both states.
        when (code) {
            KeyEvent.KEYCODE_BUTTON_MODE -> {
                if (down) toggle()
                return true
            }

            KeyEvent.KEYCODE_BUTTON_SELECT, KeyEvent.KEYCODE_BUTTON_START -> {
                if (code == KeyEvent.KEYCODE_BUTTON_SELECT) viewDown = down else menuDown = down
                if (down && viewDown && menuDown) {
                    chordUsed = true
                    cancelView()
                    toggle()
                    return true
                }
                if (!enabled) {
                    if (!down && !viewDown && !menuDown && chordUsed) {
                        chordUsed = false
                        return true
                    }
                    return chordUsed
                }
            }
        }
        if (!enabled) return false
        if (!target()) {
            reset()
            return false
        }

        return if (down) keyDown(code) else keyUp(code)
    }

    private fun keyDown(code: Int): Boolean = when (code) {
        KeyEvent.KEYCODE_BUTTON_A -> {
            pickerOpen = false
            act(PadAction.ENTER)
        }

        KeyEvent.KEYCODE_BUTTON_B -> {
            bRepeating = false
            bHold?.invoke()
            bHold = schedule(B_HOLD_MS) {
                bRepeating = true
                repeatAction(PadAction.BACKSPACE, KEY_REPEAT_MS) { bHold = it }
            }
            target()
        }

        KeyEvent.KEYCODE_BUTTON_X -> {
            xFired = false
            xHold?.invoke()
            xHold = schedule(X_HOLD_MS) {
                xFired = true
                act(PadAction.CLOSE_PANE)
            }
            target()
        }

        KeyEvent.KEYCODE_BUTTON_Y -> {
            // The phone's mic starts now, ahead of hold-to-talk: the host must switch sources first.
            onTalk(true)
            yRepeating = false
            yHold?.invoke()
            yHold = schedule(Y_HOLD_MS) {
                yRepeating = true
                repeatAction(PadAction.SPACE, SPACE_REPEAT_MS) { yHold = it }
            }
            target()
        }

        KeyEvent.KEYCODE_BUTTON_L1 -> act(PadAction.PREVIOUS_TAB)

        KeyEvent.KEYCODE_BUTTON_R1 -> act(PadAction.NEXT_TAB)

        KeyEvent.KEYCODE_BUTTON_L2 -> trigger(0, true)

        KeyEvent.KEYCODE_BUTTON_R2 -> trigger(1, true)

        KeyEvent.KEYCODE_BUTTON_THUMBL -> act(PadAction.ZOOM)

        KeyEvent.KEYCODE_BUTTON_THUMBR -> act(PadAction.NEXT_AGENT)

        KeyEvent.KEYCODE_MEDIA_RECORD, KeyEvent.KEYCODE_BUTTON_C, KeyEvent.KEYCODE_BUTTON_Z -> act(PadAction.NEW_TAB)

        KeyEvent.KEYCODE_BUTTON_SELECT -> {
            viewFired = false
            viewHold?.invoke()
            viewHold = schedule(VIEW_HOLD_MS) {
                viewFired = true
                act(PadAction.NEW_TAB)
            }
            target()
        }

        KeyEvent.KEYCODE_BUTTON_START -> target()

        KeyEvent.KEYCODE_DPAD_LEFT -> hatAct(PadAction.FOCUS_LEFT)

        KeyEvent.KEYCODE_DPAD_RIGHT -> hatAct(PadAction.FOCUS_RIGHT)

        KeyEvent.KEYCODE_DPAD_UP -> hatAct(PadAction.FOCUS_UP)

        KeyEvent.KEYCODE_DPAD_DOWN -> hatAct(PadAction.FOCUS_DOWN)

        else -> false
    }

    private fun keyUp(code: Int): Boolean = when (code) {
        KeyEvent.KEYCODE_BUTTON_B -> {
            bHold?.invoke()
            bHold = null
            if (!bRepeating) {
                pickerOpen = false
                act(PadAction.ESC)
            }
            bRepeating = false
            target()
        }

        KeyEvent.KEYCODE_BUTTON_X -> {
            xHold?.invoke()
            xHold = null
            if (!xFired) act(PadAction.ATTENTION)
            target()
        }

        KeyEvent.KEYCODE_BUTTON_Y -> {
            onTalk(false)
            yHold?.invoke()
            yHold = null
            if (!yRepeating) act(PadAction.SHIFT_TAB)
            yRepeating = false
            target()
        }

        KeyEvent.KEYCODE_BUTTON_SELECT -> {
            val chord = chordUsed
            cancelView()
            if (!chord && !viewFired) act(PadAction.SPLIT_RIGHT)
            if (!menuDown) chordUsed = false
            target()
        }

        KeyEvent.KEYCODE_BUTTON_START -> {
            if (!chordUsed) act(PadAction.SPLIT_DOWN)
            if (!viewDown) chordUsed = false
            target()
        }

        KeyEvent.KEYCODE_BUTTON_L2 -> trigger(0, false)

        KeyEvent.KEYCODE_BUTTON_R2 -> trigger(1, false)

        else -> code in PAD_KEYS && target()
    }

    /** Handle stick, hat and trigger motion from a controller; true when it was used. */
    fun onMotion(event: MotionEvent): Boolean {
        if (!enabled || event.action != MotionEvent.ACTION_MOVE) return false
        if (event.source and InputDevice.SOURCE_JOYSTICK != InputDevice.SOURCE_JOYSTICK) return false
        if (!target()) return false

        // D-pad as a hat.
        val hx = event.getAxisValue(MotionEvent.AXIS_HAT_X).let {
            if (it > 0.5f) {
                1
            } else if (it < -0.5f) {
                -1
            } else {
                0
            }
        }
        val hy = event.getAxisValue(MotionEvent.AXIS_HAT_Y).let {
            if (it > 0.5f) {
                1
            } else if (it < -0.5f) {
                -1
            } else {
                0
            }
        }
        if (hx != hatX) {
            hatX = hx
            if (hx < 0) {
                hatAct(PadAction.FOCUS_LEFT)
            } else if (hx > 0) {
                hatAct(PadAction.FOCUS_RIGHT)
            }
        }
        if (hy != hatY) {
            hatY = hy
            if (hy < 0) {
                hatAct(PadAction.FOCUS_UP)
            } else if (hy > 0) {
                hatAct(PadAction.FOCUS_DOWN)
            }
        }

        // Triggers: the Thor reports BRAKE/GAS, Xbox pads LTRIGGER/RTRIGGER.
        val lt = maxOf(event.getAxisValue(MotionEvent.AXIS_BRAKE), event.getAxisValue(MotionEvent.AXIS_LTRIGGER))
        val rt = maxOf(event.getAxisValue(MotionEvent.AXIS_GAS), event.getAxisValue(MotionEvent.AXIS_RTRIGGER))
        axisTrigger(0, lt)
        axisTrigger(1, rt)

        // Left stick: arrows, repeating while held.
        val lx = event.getAxisValue(MotionEvent.AXIS_X)
        val ly = event.getAxisValue(MotionEvent.AXIS_Y)
        val dir = when {
            maxOf(abs(lx), abs(ly)) < STICK_ON -> null
            abs(lx) > abs(ly) -> if (lx < 0) PadAction.LEFT else PadAction.RIGHT
            else -> if (ly < 0) PadAction.UP else PadAction.DOWN
        }
        if (dir != leftStick && (dir != null || maxOf(abs(lx), abs(ly)) < STICK_OFF)) {
            leftStick = dir
            leftRepeat?.invoke()
            leftRepeat = null
            if (dir != null) {
                act(dir)
                leftRepeat = schedule(STICK_REPEAT_DELAY_MS) { repeatAction(dir, STICK_REPEAT_MS) { leftRepeat = it } }
            }
        }

        // Right stick up/down: Herdr's workspace picker. Z/RZ on the Thor and most Android pads, RX/RY on some.
        val rz = event.getAxisValue(MotionEvent.AXIS_RZ)
        val ry = event.getAxisValue(MotionEvent.AXIS_RY)
        val rv = if (abs(rz) >= abs(ry)) rz else ry
        if (rightArmed && abs(rv) > FLICK_ON) {
            rightArmed = false
            if (!pickerOpen) {
                act(PadAction.PICKER)
                pickerOpen = true
            } else {
                act(if (rv < 0) PadAction.UP else PadAction.DOWN, keepPicker = true)
            }
        } else if (abs(rv) < FLICK_OFF) {
            rightArmed = true
        }
        return true
    }

    /** Release everything held (the console went away, or control was toggled off). */
    fun reset() {
        if (yHold != null || yRepeating) onTalk(false)
        listOf(bHold, xHold, yHold, viewHold, leftRepeat).forEach { it?.invoke() }
        bHold = null
        xHold = null
        yHold = null
        viewHold = null
        leftRepeat = null
        bRepeating = false
        yRepeating = false
        leftStick = null
        pickerOpen = false
        rightArmed = true
    }

    private fun toggle() {
        enabled = !enabled
        reset()
        onToggle(enabled)
    }

    private fun cancelView() {
        viewHold?.invoke()
        viewHold = null
    }

    // Whether a session would take actions now: probe with no action.
    private fun target(): Boolean = send(PROBE)

    private fun act(action: PadAction, keepPicker: Boolean = false): Boolean {
        if (!keepPicker && action != PadAction.PICKER) pickerOpen = false
        return send(action)
    }

    private fun repeatAction(action: PadAction, everyMs: Long, keep: (cancel: () -> Unit) -> Unit) {
        act(action, keepPicker = true)
        keep(schedule(everyMs) { repeatAction(action, everyMs, keep) })
    }

    // The Thor's D-pad comes as both keys and a hat: one press, one action.
    private fun hatAct(action: PadAction): Boolean {
        val t = now()
        val last = hat[action]
        hat[action] = t
        if (last != null && t - last < DEDUPE_MS) return true
        return act(action)
    }

    // The Thor's triggers come as both keys (L2/R2) and axes: fire once per pull.
    private fun trigger(side: Int, down: Boolean): Boolean {
        if (down == triggerDown[side]) return target()
        triggerDown[side] = down
        if (!down) return target()
        val t = now()
        val recent = t - triggerAt[side] < DEDUPE_MS
        triggerAt[side] = t
        if (recent) return target()
        return act(if (side == 0) PadAction.PREVIOUS_WORKSPACE else PadAction.NEXT_WORKSPACE)
    }

    private fun axisTrigger(side: Int, value: Float) {
        if (!triggerDown[side] && value > TRIGGER_ON) {
            trigger(side, true)
        } else if (triggerDown[side] && value < TRIGGER_OFF) {
            trigger(side, false)
        }
    }

    companion object {
        /** Passed to `send` only to ask whether a session is there; never sent. */
        val PROBE: PadAction? = null

        const val B_HOLD_MS = 350L
        const val X_HOLD_MS = 600L
        const val Y_HOLD_MS = 300L
        const val VIEW_HOLD_MS = 600L
        const val KEY_REPEAT_MS = 50L
        const val SPACE_REPEAT_MS = 33L
        const val STICK_REPEAT_DELAY_MS = 350L
        const val STICK_REPEAT_MS = 90L
        const val DEDUPE_MS = 150L
        private const val STICK_ON = 0.5f
        private const val STICK_OFF = 0.35f
        private const val FLICK_ON = 0.6f
        private const val FLICK_OFF = 0.3f
        private const val TRIGGER_ON = 0.5f
        private const val TRIGGER_OFF = 0.2f

        private val PAD_KEYS = setOf(
            KeyEvent.KEYCODE_BUTTON_A, KeyEvent.KEYCODE_BUTTON_B, KeyEvent.KEYCODE_BUTTON_X, KeyEvent.KEYCODE_BUTTON_Y,
            KeyEvent.KEYCODE_BUTTON_C, KeyEvent.KEYCODE_BUTTON_Z,
            KeyEvent.KEYCODE_BUTTON_L1, KeyEvent.KEYCODE_BUTTON_R1, KeyEvent.KEYCODE_BUTTON_L2, KeyEvent.KEYCODE_BUTTON_R2,
            KeyEvent.KEYCODE_BUTTON_THUMBL, KeyEvent.KEYCODE_BUTTON_THUMBR,
            KeyEvent.KEYCODE_BUTTON_SELECT, KeyEvent.KEYCODE_BUTTON_START, KeyEvent.KEYCODE_BUTTON_MODE,
            KeyEvent.KEYCODE_MEDIA_RECORD,
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN,
        )

        /** Whether [device] is a game controller (its D-pad keys count as pad keys, a keyboard's do not). */
        fun isPadDevice(device: InputDevice?): Boolean {
            val sources = device?.sources ?: return false
            return sources and InputDevice.SOURCE_GAMEPAD == InputDevice.SOURCE_GAMEPAD ||
                sources and InputDevice.SOURCE_JOYSTICK == InputDevice.SOURCE_JOYSTICK
        }
    }
}
