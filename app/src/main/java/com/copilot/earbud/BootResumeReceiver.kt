package com.copilot.earbud

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import com.copilot.earbud.data.local.SettingsPreferences
import com.copilot.earbud.ui.MainActivity

/**
 * After a reboot the copilot cannot resume silently: since Android 14 an app may not start a
 * microphone foreground service from the background (BOOT_COMPLETED is not a while-in-use
 * exemption), and attempting it throws. Instead of dying quietly or crashing, this posts a
 * one-tap "resume listening" notification - once, and only when the user had it running.
 */
class BootResumeReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        if (action != Intent.ACTION_BOOT_COMPLETED &&
            action != "android.intent.action.QUICKBOOT_POWERON"
        ) {
            return
        }

        val prefs = SettingsPreferences.get(context)
        if (!prefs.resumeAfterBoot) return
        if (prefs.configIssue() != null) return

        val openApp = PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(context, CopilotApplication.NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle(context.getString(R.string.app_name))
            .setContentText("Tap to resume ambient listening after the reboot.")
            .setAutoCancel(true)
            .setContentIntent(openApp)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()

        runCatching {
            val manager = context.getSystemService(NotificationManager::class.java)
            manager.notify(RESUME_NOTIFICATION_ID, notification)
        }
    }

    companion object {
        private const val RESUME_NOTIFICATION_ID = 1002
    }
}
