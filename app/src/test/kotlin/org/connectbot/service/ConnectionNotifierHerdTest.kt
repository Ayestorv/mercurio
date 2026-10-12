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

package org.connectbot.service

import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.connectbot.data.entity.Host
import org.connectbot.ui.MainActivity
import org.connectbot.usage.UsageActions
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import solutions.ormus.logos.herd.HerdAnswer
import solutions.ormus.logos.herd.HerdAnswerOutcome
import solutions.ormus.logos.herd.HerdAttention.Notice
import solutions.ormus.logos.herd.HerdrAgentInfo
import solutions.ormus.logos.herd.HerdrAgentStatus

/** The Herdr "Needs you" and "Finished" notifications and their channels. */
@RunWith(AndroidJUnit4::class)
class ConnectionNotifierHerdTest {
    class HostService : Service() {
        override fun onBind(intent: Intent?): IBinder? = null
    }

    private val service = Robolectric.buildService(HostService::class.java).create().get()
    private val manager = service.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    private val host = Host(id = 7, nickname = "Sun", hostname = "100.100.1.1", postLogin = "herdr")
    private val agent = HerdrAgentInfo(
        paneId = "w1:p1",
        agent = "claude",
        agentStatus = HerdrAgentStatus.BLOCKED,
        terminalTitleStripped = "Docs site build",
    )

    @Test
    fun needsYou_onTheHighPriorityAgentChannel_keyedToThePane() {
        ConnectionNotifier().showHerdNotification(service, host, Notice.NeedsYou(agent, "~Demo", "1"))

        val posted = shadowOf(manager).allNotifications.single()
        assertEquals("logos_agent_channel", posted.channelId)
        assertEquals(NotificationManager.IMPORTANCE_HIGH, manager.getNotificationChannel("logos_agent_channel").importance)
        val extras = posted.extras
        assertEquals("claude needs you on Sun", extras.getCharSequence("android.title").toString())
        assertEquals("~Demo / 1 · Docs site build", extras.getCharSequence("android.text").toString())

        val tap = shadowOf(posted.contentIntent).savedIntent
        assertEquals("w1:p1", tap.getStringExtra(MainActivity.EXTRA_HERD_PANE))
        assertEquals(UsageActions.NOTIFY_NEEDS_YOU_OPEN, tap.getStringExtra(MainActivity.EXTRA_HERD_NOTICE))
        assertEquals(host.getUri(), tap.data)
    }

    @Test
    fun finished_onItsOwnDefaultChannel_andClearsNeedsYou() {
        val notifier = ConnectionNotifier()
        notifier.showHerdNotification(service, host, Notice.NeedsYou(agent, "~Demo", "1"))
        notifier.showHerdNotification(service, host, Notice.Finished(agent.copy(agentStatus = HerdrAgentStatus.DONE), "~Demo", "1"))

        val posted = shadowOf(manager).allNotifications.single()
        assertEquals("logos_agent_finished_channel", posted.channelId)
        assertEquals(
            NotificationManager.IMPORTANCE_DEFAULT,
            manager.getNotificationChannel("logos_agent_finished_channel").importance,
        )
        assertEquals("claude finished on Sun", posted.extras.getCharSequence("android.title").toString())
        assertEquals(
            UsageActions.NOTIFY_FINISHED_OPEN,
            shadowOf(posted.contentIntent).savedIntent.getStringExtra(MainActivity.EXTRA_HERD_NOTICE),
        )
    }

    @Test
    fun differentPanes_keepSeparateNotifications() {
        val notifier = ConnectionNotifier()
        notifier.showHerdNotification(service, host, Notice.NeedsYou(agent, "~Demo", "1"))
        notifier.showHerdNotification(service, host, Notice.NeedsYou(agent.copy(paneId = "w1:p2"), "~Demo", "2"))

        assertTrue(shadowOf(manager).allNotifications.size == 2)
    }

    @Test
    fun samePaneInAnotherSession_keepsItsOwnNotification_andCarriesItsSession() {
        val notifier = ConnectionNotifier()
        notifier.showHerdNotification(service, host, Notice.NeedsYou(agent, "~Demo", "1"))
        notifier.showHerdNotification(service, host, Notice.NeedsYou(agent, "ops", "1", session = "work"))

        val posted = shadowOf(manager).allNotifications
        assertEquals(2, posted.size)
        val work = posted.single { it.extras.getCharSequence("android.text").toString().startsWith("ops") }
        assertEquals("work", shadowOf(work.contentIntent).savedIntent.getStringExtra(MainActivity.EXTRA_HERD_SESSION))
        assertEquals("work", shadowOf(work.actions[0].actionIntent).savedIntent.getStringExtra(ConnectionNotifier.EXTRA_HERD_SESSION))
        assertTrue(notifier.isHerdNoticeShowing(service, host.id, Notice.NeedsYou(agent, null, null, session = "work")))

        // Answering the work session's pane clears its notice only.
        notifier.showHerdAnswer(service, host, "work", "w1:p1", "claude", HerdAnswerOutcome.Sent)
        assertEquals("~Demo / 1 · Docs site build", shadowOf(manager).allNotifications.single().extras.getCharSequence("android.text").toString())
    }

    @Test
    fun needsYou_provenAgent_approveAndDenyAskForTheUnlock_andShowThePane() {
        val tail = "Do you want to proceed?\n\u276F 1. Yes\n  2. No"
        ConnectionNotifier().showHerdNotification(service, host, Notice.NeedsYou(agent, "~Demo", "1", paneTail = tail))

        val posted = shadowOf(manager).allNotifications.single()
        assertEquals("~Demo / 1 · Docs site build\n\n$tail", posted.extras.getCharSequence("android.bigText").toString())
        assertEquals(android.app.Notification.VISIBILITY_PRIVATE, posted.visibility)
        assertEquals(listOf("Approve", "Deny"), posted.actions.map { it.title.toString() })
        posted.actions.forEach { assertTrue("${it.title} asks for the unlock", it.isAuthenticationRequired) }

        val approve = shadowOf(posted.actions[0].actionIntent).savedIntent
        assertEquals(TerminalManager::class.java.name, approve.component?.className)
        assertEquals(ConnectionNotifier.ACTION_HERD_ANSWER, approve.action)
        assertEquals(7L, approve.getLongExtra(ConnectionNotifier.EXTRA_HERD_HOST, 0))
        assertEquals("w1:p1", approve.getStringExtra(ConnectionNotifier.EXTRA_HERD_PANE))
        assertEquals(HerdAnswer.APPROVE.name, approve.getStringExtra(ConnectionNotifier.EXTRA_HERD_ANSWER))
        assertEquals(HerdAnswer.DENY.name, shadowOf(posted.actions[1].actionIntent).savedIntent.getStringExtra(ConnectionNotifier.EXTRA_HERD_ANSWER))
    }

    @Test
    fun needsYou_unprovenAgent_hasNoActions() {
        ConnectionNotifier().showHerdNotification(service, host, Notice.NeedsYou(agent.copy(agent = "grok"), "~Demo", "1"))

        assertTrue(shadowOf(manager).allNotifications.single().actions.isNullOrEmpty())
    }

    @Test
    fun answerSent_clearsTheNeedsYou() {
        val notifier = ConnectionNotifier()
        notifier.showHerdNotification(service, host, Notice.NeedsYou(agent, "~Demo", "1"))
        notifier.showHerdAnswer(service, host, "default", "w1:p1", "claude", HerdAnswerOutcome.Sent)

        assertTrue(shadowOf(manager).allNotifications.isEmpty())
    }

    @Test
    fun answerNotSent_replacesTheNeedsYouWithWhy_withoutActions() {
        val notifier = ConnectionNotifier()
        notifier.showHerdNotification(service, host, Notice.NeedsYou(agent, "~Demo", "1"))
        notifier.showHerdAnswer(service, host, "default", "w1:p1", "claude", HerdAnswerOutcome.NotBlocked)

        val notBlocked = shadowOf(manager).allNotifications.single()
        assertEquals("claude on Sun is no longer waiting", notBlocked.extras.getCharSequence("android.title").toString())
        assertEquals("Nothing was sent.", notBlocked.extras.getCharSequence("android.text").toString())
        assertTrue(notBlocked.actions.isNullOrEmpty())

        notifier.showHerdAnswer(service, host, "default", "w1:p1", "claude", HerdAnswerOutcome.Failed("pane w1:p1 not found"))
        val failed = shadowOf(manager).allNotifications.single()
        assertEquals("Could not answer claude on Sun", failed.extras.getCharSequence("android.title").toString())
        assertEquals("pane w1:p1 not found", failed.extras.getCharSequence("android.text").toString())
        assertFalse(failed.actions?.isNotEmpty() == true)
        assertEquals("w1:p1", shadowOf(failed.contentIntent).savedIntent.getStringExtra(MainActivity.EXTRA_HERD_PANE))
    }
}
