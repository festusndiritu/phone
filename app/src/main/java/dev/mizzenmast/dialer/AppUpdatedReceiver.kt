package dev.mizzenmast.dialer

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat

/**
 * Android stops the app while it installs its own update. When the new version is in place this tries to reopen it, and always posts
 * a "tap to open" notice as the fallback, because Android may silently refuse to start a screen from the background.
 * Only acts after an update that [Updater] started, so installs from a computer or from Android Studio aren't affected.
 */
class AppUpdatedReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        val prefs = context.getSharedPreferences("updater", Context.MODE_PRIVATE)
        if (System.currentTimeMillis() > prefs.getLong("relaunch_until", 0L)) return
        prefs.edit().remove("relaunch_until").apply()
        Core.init(context)

        val open = Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        try {
            context.startActivity(open)
            Diag.log("update_relaunch", 0, "attempted")
        } catch (e: Exception) {
            Diag.log("update_relaunch", 0, "blocked ${e.javaClass.simpleName}")
        }

        val nm = context.getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(NotificationChannel(CHANNEL, "App updates", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "Tells you when an update has finished installing."
            })
        }
        val tap = PendingIntent.getActivity(context, 0, open, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val n = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_call_notification)
            .setContentTitle("Updated to ${Updater.installedVersion(context)}")
            .setContentText("Tap to open")
            .setContentIntent(tap)
            .setAutoCancel(true)
            .setTimeoutAfter(10 * 60 * 1000L)
            .build()
        try { NotificationManagerCompat.from(context).notify(NOTICE_ID, n) } catch (_: SecurityException) { }
    }

    companion object {
        private const val CHANNEL = "app_updates_v1"
        const val NOTICE_ID = 4106

        fun clear(context: Context) = NotificationManagerCompat.from(context).cancel(NOTICE_ID)
    }
}
