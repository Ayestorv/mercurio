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

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import org.connectbot.R
import org.connectbot.ui.components.MonoLabel
import org.connectbot.ui.theme.Ormus
import kotlin.math.abs

/** What a released title pull does: nothing, open the session drawer, or ask to disconnect. */
internal enum class TitlePullLevel { NONE, DRAWER, DISCONNECT }

/**
 * A downward pull on the console title bar. Past [threshold] it opens the session drawer;
 * past twice [threshold] it asks to disconnect. [pulled] is how far the finger is down while
 * a pull is on, null otherwise; the mark ([TitlePullMark]) reads it. [onRelease] runs as the
 * finger lifts, with the level it reached.
 */
@Stable
internal class TitlePull(
    val threshold: Dp = DEFAULT_THRESHOLD,
    val onRelease: (TitlePullLevel) -> Unit,
) {
    var pulled: Dp? by mutableStateOf(null)

    fun level(distance: Dp): TitlePullLevel = when {
        distance >= threshold * 2 -> TitlePullLevel.DISCONNECT
        distance >= threshold -> TitlePullLevel.DRAWER
        else -> TitlePullLevel.NONE
    }

    companion object {
        /** About a thumb's travel down from the bar; twice it still fits a phone's upper half. */
        val DEFAULT_THRESHOLD = 72.dp
    }
}

/**
 * Follows [pull]: one finger that moves down past touch slop, more down than across, is a
 * pull. Its events are consumed from then on, so the title's tap does not fire as well. Any
 * other touch (a tap, a sideways drag, an upward one, two fingers) is let go untouched.
 */
internal fun Modifier.titlePull(pull: TitlePull): Modifier = pointerInput(pull) {
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false)
        var distance = 0.dp
        while (true) {
            val event = awaitPointerEvent()
            val finger = event.changes.firstOrNull { it.id == down.id }
            if (finger == null || !finger.pressed) break
            if (pull.pulled == null) {
                if (event.changes.any { it.id != down.id && it.pressed }) return@awaitEachGesture
                val moved = finger.position - down.position
                if (moved.getDistance() <= viewConfiguration.touchSlop) continue
                if (moved.y <= abs(moved.x)) return@awaitEachGesture
            }
            distance = (finger.position.y - down.position.y).coerceAtLeast(0f).toDp()
            pull.pulled = distance
            event.changes.forEach { it.consume() }
        }
        if (pull.pulled == null) return@awaitEachGesture
        pull.pulled = null
        pull.onRelease(pull.level(distance))
    }
}

/**
 * The pull's mark, under the title bar while a pull is on: a hairline track that ends at
 * twice the threshold, with a tick at the threshold, filled as far as the finger has come, and a mono label naming what a lift
 * does now. Gold once a threshold is passed.
 */
@Composable
internal fun TitlePullMark(pull: TitlePull, modifier: Modifier = Modifier) {
    val pulled = pull.pulled ?: return
    val level = pull.level(pulled)
    val active = if (level == TitlePullLevel.NONE) {
        MaterialTheme.colorScheme.onSurfaceVariant
    } else {
        MaterialTheme.colorScheme.primary
    }
    Column(modifier.fillMaxWidth().testTag(TITLE_PULL_MARK_TAG)) {
        BoxWithConstraints(
            Modifier
                .fillMaxWidth()
                .height(3.dp)
                .background(Ormus.extras.hairline),
        ) {
            val fraction = (pulled / (pull.threshold * 2)).coerceIn(0f, 1f)
            Box(Modifier.fillMaxHeight().width(maxWidth * fraction).background(active))
            Box(Modifier.offset(x = maxWidth / 2 - 1.dp).fillMaxHeight().width(2.dp).background(MaterialTheme.colorScheme.onSurface))
        }
        MonoLabel(
            text = stringResource(
                if (level == TitlePullLevel.DISCONNECT) R.string.list_host_disconnect else R.string.drawer_sessions_title,
            ),
            color = active,
            modifier = Modifier.padding(horizontal = Ormus.spacing.lg, vertical = Ormus.spacing.xs),
        )
    }
}

/** Test tag on the title pull's threshold mark. */
const val TITLE_PULL_MARK_TAG = "title_pull_mark"
