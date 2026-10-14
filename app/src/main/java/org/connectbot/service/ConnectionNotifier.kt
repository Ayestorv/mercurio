/*
 * ConnectBot: simple, powerful, open-source SSH client for Android
 * Copyright 2025-2026 Kenny Root
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

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Color
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.core.app.NotificationCompat
import org.connectbot.R
import org.connectbot.data.entity.Host
import org.connectbot.ui.MainActivity
import org.connectbot.usage.UsageActions
import org.connectbot.util.HostConstants
import solutions.ormus.logos.herd.HerdAnswer
import solutions.ormus.logos.herd.HerdAnswerOutcome
import solutions.ormus.logos.herd.HerdApproval
import solutions.ormus.logos.herd.HerdAttention
import solutions.ormus.logos.herd.HerdrAgentInfo
import solutions.ormus.logos.herd.HerdrSessions
import solutions.ormus.logos.herd.title
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Manages notifications for ConnectBot connections.
 *
 * @author Kenny Root
 *
 * Based on the concept from jasta's blog post.
 */
@Singleton
class ConnectionNotifier @Inject constructor() {
    private val pendingIntentFlags: Int = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
    } else {
        PendingIntent.FLAG_UPDATE_CURRENT
    }

    private fun getNotificationManager(context: Context): NotificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    private fun newNotificationBuilder(context: Context, id: String): NotificationCompat.Builder {
        val builder = NotificationCompat.Builder(context, id)
            .setSmallIcon(R.drawable.notification_icon)
            .setWhen(System.currentTimeMillis())

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            createNotificationChannel(context, id)
        }

        return builder
    }

    @RequiresApi(Build.VERSION_CODES.O)
    private fun createNotificationChannel(context: Context, id: String) {
        // "Needs you" keeps the original agent channel id so existing settings carry over;
        // "Finished" is its own, quieter channel so each can be tuned in Android settings.
        val importance = if (id == AGENT_CHANNEL) {
            NotificationManager.IMPORTANCE_HIGH
        } else {
            NotificationManager.IMPORTANCE_DEFAULT
        }
        val name = when (id) {
            AGENT_CHANNEL -> context.getString(R.string.notification_agent_channel)
            FINISHED_CHANNEL -> context.getString(R.string.notification_finished_channel)
            else -> context.getString(R.string.app_name)
        }
        val nc = NotificationChannel(id, name, importance)
        getNotificationManager(context).createNotificationChannel(nc)
    }

    private fun newAgentNotification(
        context: Context,
        host: Host,
        reason: TerminalBridge.AttentionReason,
    ): Notification {
        val builder = newNotificationBuilder(context, AGENT_CHANNEL)
        val res = context.resources

        val contentText = when (reason) {
            TerminalBridge.AttentionReason.BELL ->
                res.getString(R.string.notification_agent_bell, host.nickname)

            TerminalBridge.AttentionReason.IDLE ->
                res.getString(R.string.notification_agent_waiting, host.nickname)
        }

        val notificationIntent = Intent(context, MainActivity::class.java).apply {
            action = Intent.ACTION_VIEW
            data = host.getUri()
        }

        val contentIntent = PendingIntent.getActivity(
            context,
            host.id.toInt(),
            notificationIntent,
            pendingIntentFlags,
        )

        builder.setContentTitle(host.nickname)
            .setContentText(contentText)
            .setContentIntent(contentIntent)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setDefaults(Notification.DEFAULT_ALL)

        val ledColor = when (host.color) {
            HostConstants.COLOR_RED -> Color.RED
            HostConstants.COLOR_GREEN -> Color.GREEN
            HostConstants.COLOR_BLUE -> Color.BLUE
            else -> Color.WHITE
        }
        builder.setLights(ledColor, 300, 1000)

        return builder.build()
    }

    /**
     * A Herdr agent needs the user or finished. Keyed to the pane in its Herdr session,
     * so one agent's notices replace each other; tapping opens that host's console
     * focused on the agent ([MainActivity.EXTRA_HERD_PANE]).
     *
     * A "Needs you" shows the end of the pane when expanded, and, for agent kinds
     * where it was proven ([HerdApproval]), Approve (Enter) and Deny (Esc). Both
     * ask for the unlock first: they type into a session on the host.
     */
    fun showHerdNotification(context: Context, host: Host, notice: HerdAttention.Notice) {
        val agent = notice.agent
        val tag = herdTag(host.id, notice.session, agent.paneId)
        val needsYou = notice is HerdAttention.Notice.NeedsYou
        val res = context.resources
        val name = agentName(context, agent.agent)
        val place = listOfNotNull(notice.workspaceLabel, notice.tabLabel).joinToString(" / ").ifEmpty { null }
        val title = if (needsYou) {
            res.getString(R.string.notification_herd_needs_you, name, host.nickname)
        } else {
            res.getString(R.string.notification_herd_finished, name, host.nickname)
        }
        val text = listOfNotNull(place, agent.title()).joinToString(" · ")
        val tail = (notice as? HerdAttention.Notice.NeedsYou)?.paneTail
        val usageId = if (needsYou) UsageActions.NOTIFY_NEEDS_YOU_OPEN else UsageActions.NOTIFY_FINISHED_OPEN

        val builder = newNotificationBuilder(context, if (needsYou) AGENT_CHANNEL else FINISHED_CHANNEL)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(listOfNotNull(text.ifEmpty { null }, tail).joinToString("\n\n")))
            .setContentIntent(openPaneIntent(context, host, notice.session, agent.paneId, usageId, "$tag:$needsYou"))
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setPriority(if (needsYou) NotificationCompat.PRIORITY_HIGH else NotificationCompat.PRIORITY_DEFAULT)
            // The default, kept on purpose: a lock screen that hides sensitive content hides the pane too.
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setAutoCancel(true)

        if (needsYou && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && HerdApproval.offersAnswers(agent)) {
            builder.addAction(answerAction(context, host, notice.session, agent, HerdAnswer.APPROVE, R.string.notification_herd_approve))
            builder.addAction(answerAction(context, host, notice.session, agent, HerdAnswer.DENY, R.string.notification_herd_deny))
        }

        val manager = getNotificationManager(context)
        // A finished agent no longer waits on the user.
        if (!needsYou) manager.cancel(tag, HERD_NEEDS_YOU_NOTIFICATION)
        manager.notify(tag, if (needsYou) HERD_NEEDS_YOU_NOTIFICATION else HERD_FINISHED_NOTIFICATION, builder.build())
    }

    /**
     * What came of Approve or Deny on [session]'s [paneId]: sent clears the "Needs you";
     * otherwise it is replaced, without actions, by why nothing (or nothing known) reached the pane.
     */
    fun showHerdAnswer(context: Service, host: Host, session: String, paneId: String, agentKind: String?, outcome: HerdAnswerOutcome) {
        val tag = herdTag(host.id, session, paneId)
        val manager = getNotificationManager(context)
        val res = context.resources
        val name = agentName(context, agentKind)
        val (title, text) = when (outcome) {
            HerdAnswerOutcome.Sent -> {
                manager.cancel(tag, HERD_NEEDS_YOU_NOTIFICATION)
                return
            }

            HerdAnswerOutcome.NotBlocked, HerdAnswerOutcome.PromptChanged ->
                res.getString(R.string.notification_herd_not_waiting, name, host.nickname) to
                    res.getString(R.string.notification_herd_nothing_sent)

            is HerdAnswerOutcome.Failed ->
                res.getString(R.string.notification_herd_answer_failed, name, host.nickname) to outcome.reason
        }
        val builder = newNotificationBuilder(context, AGENT_CHANNEL)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(openPaneIntent(context, host, session, paneId, UsageActions.NOTIFY_NEEDS_YOU_OPEN, "$tag:answer"))
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setSilent(true)
            .setAutoCancel(true)
        manager.notify(tag, HERD_NEEDS_YOU_NOTIFICATION, builder.build())
    }

    /** Whether [notice]'s kind of notification is already showing for its pane on [hostId]. */
    fun isHerdNoticeShowing(context: Context, hostId: Long, notice: HerdAttention.Notice): Boolean {
        val tag = herdTag(hostId, notice.session, notice.agent.paneId)
        val id = if (notice is HerdAttention.Notice.NeedsYou) HERD_NEEDS_YOU_NOTIFICATION else HERD_FINISHED_NOTIFICATION
        return getNotificationManager(context).activeNotifications.any { it.tag == tag && it.id == id }
    }

    // Pane ids repeat across Herdr sessions; the default session keeps its old tags.
    private fun herdTag(hostId: Long, session: String, paneId: String) = if (HerdrSessions.isDefault(session)) "herd:$hostId:$paneId" else "herd:$hostId:$session/$paneId"

    private fun agentName(context: Context, kind: String?) = kind?.ifBlank { null } ?: context.getString(R.string.herd_agent_unnamed)

    private fun openPaneIntent(context: Context, host: Host, session: String, paneId: String, usageId: String, key: String): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            action = Intent.ACTION_VIEW
            data = host.getUri()
            putExtra(MainActivity.EXTRA_HERD_PANE, paneId)
            putExtra(MainActivity.EXTRA_HERD_SESSION, session)
            putExtra(MainActivity.EXTRA_HERD_NOTICE, usageId)
        }
        return PendingIntent.getActivity(context, key.hashCode(), intent, pendingIntentFlags)
    }

    @RequiresApi(Build.VERSION_CODES.S)
    private fun answerAction(context: Context, host: Host, session: String, agent: HerdrAgentInfo, answer: HerdAnswer, label: Int): NotificationCompat.Action {
        val intent = Intent(context, TerminalManager::class.java).apply {
            action = ACTION_HERD_ANSWER
            putExtra(EXTRA_HERD_HOST, host.id)
            putExtra(EXTRA_HERD_SESSION, session)
            putExtra(EXTRA_HERD_PANE, agent.paneId)
            putExtra(EXTRA_HERD_AGENT, agent.agent)
            putExtra(EXTRA_HERD_ANSWER, answer.name)
        }
        val code = "${herdTag(host.id, session, agent.paneId)}:${answer.name}".hashCode()
        val pending = PendingIntent.getService(context, code, intent, pendingIntentFlags)
        return NotificationCompat.Action.Builder(null, context.getString(label), pending)
            .setAuthenticationRequired(true)
            .build()
    }

    private fun newRunningNotification(context: Context): Notification {
        val builder = newNotificationBuilder(context, NOTIFICATION_CHANNEL)
        val res = context.resources

        val pendingIntent = PendingIntent.getActivity(
            context,
            ONLINE_NOTIFICATION,
            Intent(context, MainActivity::class.java),
            pendingIntentFlags,
        )

        val disconnectIntent = Intent(context, MainActivity::class.java).apply {
            action = MainActivity.DISCONNECT_ACTION
        }

        val disconnectPendingIntent = PendingIntent.getActivity(
            context,
            ONLINE_DISCONNECT_NOTIFICATION,
            disconnectIntent,
            pendingIntentFlags,
        )

        builder.setOngoing(true)
            .setWhen(0)
            .setSilent(true)
            .setContentIntent(pendingIntent)
            .setContentTitle(res.getString(R.string.app_name))
            .setContentText(res.getString(R.string.app_is_running))
            .addAction(
                android.R.drawable.ic_menu_close_clear_cancel,
                res.getString(R.string.list_host_disconnect),
                disconnectPendingIntent,
            )

        return builder.build()
    }

    fun showAgentNotification(context: Service, host: Host, reason: TerminalBridge.AttentionReason) {
        getNotificationManager(context).notify(
            ACTIVITY_NOTIFICATION + host.id.toInt(),
            newAgentNotification(context, host, reason),
        )
    }

    fun showRunningNotification(context: Service) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            showRunningNotificationWithType(context)
            return
        }

        context.startForeground(ONLINE_NOTIFICATION, newRunningNotification(context))
    }

    @RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
    private fun showRunningNotificationWithType(context: Service) {
        context.startForeground(
            ONLINE_NOTIFICATION,
            newRunningNotification(context),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_REMOTE_MESSAGING,
        )
    }

    fun hideRunningNotification(context: Service) {
        context.stopForeground(Service.STOP_FOREGROUND_REMOVE)
    }

    companion object {
        private const val ONLINE_NOTIFICATION = 1
        private const val ACTIVITY_NOTIFICATION = 2
        private const val ONLINE_DISCONNECT_NOTIFICATION = 3
        private const val NOTIFICATION_CHANNEL = "my_connectbot_channel"
        private const val AGENT_CHANNEL = "logos_agent_channel"
        private const val FINISHED_CHANNEL = "logos_agent_finished_channel"

        // Herdr notices are tagged per host and pane; these ids tell the kinds apart.
        private const val HERD_NEEDS_YOU_NOTIFICATION = 20
        private const val HERD_FINISHED_NOTIFICATION = 21

        // Approve and Deny on a "Needs you", handled by TerminalManager.onStartCommand.
        const val ACTION_HERD_ANSWER = "solutions.ormus.logos.action.HERD_ANSWER"
        const val EXTRA_HERD_HOST = "solutions.ormus.logos.extra.HERD_HOST"
        const val EXTRA_HERD_SESSION = "solutions.ormus.logos.extra.HERD_ANSWER_SESSION"
        const val EXTRA_HERD_PANE = "solutions.ormus.logos.extra.HERD_ANSWER_PANE"
        const val EXTRA_HERD_AGENT = "solutions.ormus.logos.extra.HERD_AGENT"
        const val EXTRA_HERD_ANSWER = "solutions.ormus.logos.extra.HERD_ANSWER"
    }
}
