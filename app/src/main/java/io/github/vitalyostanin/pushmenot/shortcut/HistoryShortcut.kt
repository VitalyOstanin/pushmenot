package io.github.vitalyostanin.pushmenot.shortcut

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.provider.Settings
import io.github.vitalyostanin.pushmenot.MainActivity
import io.github.vitalyostanin.pushmenot.R

enum class HistoryShortcutState { PERMISSION_OFF, CHANNEL_OFF, NOT_SHOWN, SHOWN }

internal class HistoryShortcut(private val context: Context) {
    private val manager = context.getSystemService(NotificationManager::class.java)

    fun status(): HistoryShortcutState = when {
        !manager.areNotificationsEnabled() -> HistoryShortcutState.PERMISSION_OFF
        manager.getNotificationChannel(CHANNEL)?.importance == NotificationManager.IMPORTANCE_NONE ->
            HistoryShortcutState.CHANNEL_OFF
        manager.activeNotifications.any { it.id == ID && it.notification.channelId == CHANNEL } ->
            HistoryShortcutState.SHOWN
        else -> HistoryShortcutState.NOT_SHOWN
    }

    fun publish(): HistoryShortcutState {
        val before = status()
        if (before == HistoryShortcutState.PERMISSION_OFF || before == HistoryShortcutState.CHANNEL_OFF) return before
        manager.createNotificationChannel(NotificationChannel(
            CHANNEL, context.getString(R.string.history_shortcut_channel), NotificationManager.IMPORTANCE_LOW,
        ).apply {
            setShowBadge(false)
            setSound(null, null)
            enableVibration(false)
        })
        val openHistory = Intent(context, MainActivity::class.java).setAction(ACTION)
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        val notification = Notification.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_launcher_monochrome)
            .setContentTitle(context.getString(R.string.history_shortcut_title))
            .setContentText(context.getString(R.string.history_shortcut_text))
            .setContentIntent(PendingIntent.getActivity(context, ID, openHistory, PendingIntent.FLAG_IMMUTABLE))
            .setShowWhen(false)
            .setAutoCancel(false)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setLocalOnly(true)
            .build()
        try {
            manager.notify(ID, notification)
        } catch (_: SecurityException) {
            // The permission can change between reading the settings and posting.
        }
        return status()
    }

    fun systemSettings(channel: Boolean): Intent = Intent(
        if (channel) Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS else Settings.ACTION_APP_NOTIFICATION_SETTINGS,
    ).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName).apply {
        if (channel) putExtra(Settings.EXTRA_CHANNEL_ID, CHANNEL)
    }

    companion object {
        const val CHANNEL = "history_shortcut"
        const val ID = 0x504D4E
        const val ACTION = "io.github.vitalyostanin.pushmenot.action.SHOW_HISTORY"
    }
}
