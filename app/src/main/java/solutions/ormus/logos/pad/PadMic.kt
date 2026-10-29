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

import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import androidx.core.content.ContextCompat
import org.connectbot.service.TerminalBridge
import org.connectbot.transport.SSH
import timber.log.Timber

/**
 * The phone's own microphone as the host's mic, for Claude Code voice (hold Y to talk).
 * Claude Code records the default source of the host it runs on: the desktop's or the Mac's
 * mic, never the Thor's. While Y is held this streams the phone's mic over the session's SSH
 * connection into `~/.local/bin/thor-mic` on the host, which makes its virtual source
 * "thor_mic" the default until the stream ends and then puts the previous one back.
 *
 * The stream starts on Y down, before Y counts as held and Space starts repeating, so the
 * host has switched sources when Claude Code opens its recorder; it ends [TAIL_MS] after Y
 * comes up, so the last words reach the recorder. Main thread calls; capture runs on its own thread.
 */
class PadMic(private val context: Context) {
    @Volatile
    private var worker: Worker? = null

    private val main = android.os.Handler(android.os.Looper.getMainLooper())
    private val stopLater = Runnable { worker?.finish() }

    /** Whether the app may record (the user granted the mic). */
    fun allowed(): Boolean = ContextCompat.checkSelfPermission(context, android.Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    /** Y went down (true) or up (false) while [bridge] shows. */
    fun talk(bridge: TerminalBridge?, down: Boolean) {
        if (down) {
            main.removeCallbacks(stopLater)
            if (worker?.alive == true) return
            val ssh = bridge?.transport as? SSH ?: return
            if (!allowed()) {
                Timber.w("Thor mic: no RECORD_AUDIO permission")
                return
            }
            worker = Worker(ssh).also { it.start() }
        } else {
            main.removeCallbacks(stopLater)
            main.postDelayed(stopLater, TAIL_MS)
        }
    }

    /** Stop at once (the console went away). */
    fun stopNow() {
        main.removeCallbacks(stopLater)
        worker?.finish()
        worker = null
    }

    private class Worker(private val ssh: SSH) : Thread("thor-mic") {
        @Volatile
        var alive = true
            private set

        fun finish() {
            alive = false
        }

        @SuppressLint("MissingPermission") // checked by allowed() before the worker starts
        override fun run() {
            val minBuf = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
            val chunk = RATE / CHUNKS_PER_SECOND * 2
            var record: AudioRecord? = null
            try {
                record = AudioRecord(
                    MediaRecorder.AudioSource.VOICE_RECOGNITION,
                    RATE,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    maxOf(minBuf, chunk * 4),
                )
                if (record.state != AudioRecord.STATE_INITIALIZED) {
                    Timber.w("Thor mic: AudioRecord did not initialize")
                    return
                }
                ssh.openExecStream(HOST_COMMAND).use { stream ->
                    record.startRecording()
                    val buf = ByteArray(chunk)
                    while (alive) {
                        val n = record.read(buf, 0, buf.size)
                        if (n > 0) stream.stdin.write(buf, 0, n)
                        if (n < 0) break
                    }
                    stream.stdin.flush()
                }
            } catch (e: Exception) {
                Timber.w(e, "Thor mic stream ended")
            } finally {
                alive = false
                runCatching { record?.stop() }
                record?.release()
            }
        }
    }

    companion object {
        /** On every host: `~/.local/bin/thor-mic` (see mercurio-thor/host); exec, so EOF ends it cleanly. */
        const val HOST_COMMAND = "exec \"\$HOME/.local/bin/thor-mic\""
        const val RATE = 48_000
        const val TAIL_MS = 700L
        private const val CHUNKS_PER_SECOND = 50
    }
}
