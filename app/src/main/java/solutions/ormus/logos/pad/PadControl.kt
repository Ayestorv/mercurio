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

import android.content.Context
import android.hardware.input.InputManager
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.InputDevice
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.connectbot.service.TerminalBridge
import org.connectbot.service.vtermKey
import org.connectbot.usage.UsageActions
import org.connectbot.usage.UsageLog
import solutions.ormus.logos.herd.HerdrAction
import solutions.ormus.logos.herd.HerdrKeys
import solutions.ormus.logos.herd.HerdrStroke
import timber.log.Timber
import java.util.WeakHashMap

/**
 * The game controller for the whole app: which console session it drives ([target], set by
 * the console while it shows), whether a controller is plugged in ([connected]) and whether
 * controller control is on ([enabled]). While both hold, the console drops its touch key bar.
 * Main thread only.
 */
object PadControl {
    private val handler = Handler(Looper.getMainLooper())

    private val _target = MutableStateFlow<TerminalBridge?>(null)

    /** The console session the pad drives; null while no console shows. */
    val target: StateFlow<TerminalBridge?> = _target.asStateFlow()

    private val _enabled = MutableStateFlow(true)

    /** Whether controller control is on: Guide (or View+Menu on the Thor) flips it. */
    val enabled: StateFlow<Boolean> = _enabled.asStateFlow()

    private val _connected = MutableStateFlow(false)

    /** Whether any game controller is attached (the Thor's built-in pad always is). */
    val connected: StateFlow<Boolean> = _connected.asStateFlow()

    private val _last = MutableStateFlow<PadAction?>(null)

    /** The last action the pad sent, for the second screen's echo. */
    val last: StateFlow<PadAction?> = _last.asStateFlow()

    /** Where pad actions are counted (ids only); set by the activity. */
    var usage: UsageLog = UsageLog { }

    private val tones: ToneGenerator? by lazy {
        runCatching { ToneGenerator(AudioManager.STREAM_NOTIFICATION, TONE_VOLUME) }.getOrNull()
    }

    val gamepad = TerminalGamepad(
        now = SystemClock::uptimeMillis,
        schedule = { delayMs, block ->
            val r = Runnable(block)
            handler.postDelayed(r, delayMs)
            ({ handler.removeCallbacks(r) })
        },
        send = ::deliver,
        onToggle = { on ->
            _enabled.value = on
            usage.log(UsageActions.PAD_TOGGLE)
            tones?.startTone(if (on) ToneGenerator.TONE_PROP_ACK else ToneGenerator.TONE_PROP_NACK, TONE_MS)
        },
        onTalk = { down -> mic?.talk(_target.value, down) },
    )

    // The phone's mic for Claude Code voice on the host (Y held); set by [init].
    private var mic: PadMic? = null

    /** Give the pad the app context it needs for the phone's mic. */
    fun init(context: Context) {
        if (mic == null) mic = PadMic(context.applicationContext)
    }

    /** Whether the phone's mic may be used (false until the user grants it). */
    fun micAllowed(): Boolean = mic?.allowed() ?: false

    /** The console shows [bridge] (null: it went away). */
    fun setTarget(bridge: TerminalBridge?) {
        if (_target.value === bridge) return
        _target.value = bridge
        gamepad.reset()
        mic?.stopNow()
    }

    /** Drop [bridge] as the target, if it still is one (the console left it). */
    fun clearTarget(bridge: TerminalBridge) {
        if (_target.value === bridge) setTarget(null)
    }

    private fun deliver(action: PadAction?): Boolean {
        val bridge = _target.value ?: return false
        if (action == null) return true
        val herd = bridge.herd
        // Herdr's keys mean nothing to a shell: hold them back until Herdr's client shows.
        if (action.herdr && !bridge.herdrOnScreen) return true
        val keys = herd?.keys?.value ?: HerdrKeys.DEFAULT
        val strokes = action.strokes(keys)
        if (strokes.isEmpty()) {
            Timber.d("Pad action %s has no keys on this host", action)
            return true
        }
        strokes.forEach { stroke ->
            when (stroke) {
                is HerdrStroke.Bytes -> bridge.injectString(herd?.keystroke(stroke.bytes) ?: stroke.bytes)
                is HerdrStroke.Key -> bridge.terminalEmulator.dispatchKey(0, stroke.key.vtermKey)
            }
        }
        _last.value = action
        usage.log(UsageActions.PAD_ACTION)
        return true
    }

    // Bridges whose Herdr sidebar this app collapsed since its client last came on screen.
    private val collapsed = WeakHashMap<TerminalBridge, Boolean>()

    /**
     * With Herdr's sidebar on a second screen, collapse the one in the terminal: once each time
     * [bridge]'s Herdr client comes on screen (a new client starts with it open). Called while
     * the second screen shows; a sidebar the user opened again stays open.
     */
    fun collapseHerdrSidebar(bridge: TerminalBridge) {
        if (!bridge.herdrOnScreen) {
            collapsed.remove(bridge)
            return
        }
        if (collapsed[bridge] == true) return
        val herd = bridge.herd ?: return
        collapsed[bridge] = true
        herd.perform(HerdrAction.TOGGLE_SIDEBAR)
    }

    /** Keep [connected] current as controllers come and go. Returns the stop. */
    fun watchDevices(context: Context): () -> Unit {
        val input = context.getSystemService(InputManager::class.java) ?: return {}
        fun scan() {
            _connected.value = InputDevice.getDeviceIds().any { TerminalGamepad.isPadDevice(InputDevice.getDevice(it)) }
        }
        val listener = object : InputManager.InputDeviceListener {
            override fun onInputDeviceAdded(deviceId: Int) = scan()
            override fun onInputDeviceRemoved(deviceId: Int) = scan()
            override fun onInputDeviceChanged(deviceId: Int) = scan()
        }
        input.registerInputDeviceListener(listener, handler)
        scan()
        return { input.unregisterInputDeviceListener(listener) }
    }

    private const val TONE_VOLUME = 60
    private const val TONE_MS = 120
}
