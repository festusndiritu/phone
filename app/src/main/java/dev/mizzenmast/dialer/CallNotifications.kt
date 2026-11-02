package dev.mizzenmast.dialer

import android.annotation.SuppressLint
import android.app.KeyguardManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.PowerManager
import android.telecom.Call
import android.telecom.TelecomManager
import androidx.core.app.NotificationCompat
import androidx.core.app.Person
import androidx.core.app.ServiceCompat

object CallNotifications {
    const val EXTRA_ACTION = "dialer_action"
    const val ACTION_DECLINE = "dev.mizzenmast.dialer.DECLINE"
    const val ACTION_HANGUP = "dev.mizzenmast.dialer.HANGUP"
    private const val CH_INCOMING = "incoming_call_v1"
    private const val CH_INCOMING_QUIET = "incoming_call_quiet_v1"
    private const val CH_ONGOING = "ongoing_call_v1"
    private const val CH_MISSED = "missed_call_v1"
    private const val ID_MISSED = 4103
    private const val CH_FOLLOWUP = "unanswered_call_v1"
    private const val CH_REMIND = "call_reminder_v1"
    private const val ID_FOLLOWUP = 4104
    private const val ID_REMIND = 4105
    const val ACTION_SNOOZE = "dev.mizzenmast.dialer.SNOOZE"
    const val ACTION_REMIND = "dev.mizzenmast.dialer.REMIND"
    const val EXTRA_NUMBER = "number"
    const val EXTRA_TITLE = "title"
    const val EXTRA_NID = "nid"
    const val ACTION_CLEAR_MISSED = "dev.mizzenmast.dialer.CLEAR_MISSED"
    private val missedCount = HashMap<String, Int>()
    private const val ID_INCOMING = 4101
    private const val ID_ONGOING = 4102
    private val posted = HashSet<Int>()

    /** True while the call screen is on screen (set by InCallActivity). */
    @Volatile var uiResumed = false

    /** Per ringing call: true = we open the call screen ourselves, so the notification must not also pop a heads-up. Decided once per call. */
    private val quiet = HashMap<Int, Boolean>()

    private var fgOn = false
    private var useFg = true // flips off if Android refuses to promote the service
    private var channelsReady = false
    /** What the posted notifications currently say. Telecom fires onDetailsChanged often; an identical notification is not re-posted. */
    private var lastIncoming: String? = null
    private var lastOngoing: String? = null
    private const val FLAGS = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT

    fun uiIntent(ctx: Context) = Intent(ctx, InCallActivity::class.java)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)

    /** Best-effort. On Android 10+ background launches may be silently dropped; the full-screen notification is the documented path. */
    fun launchUi(ctx: Context, callId: Int, reason: String) {
        try {
            ctx.startActivity(uiIntent(ctx).addFlags(Intent.FLAG_ACTIVITY_NO_USER_ACTION))
            Diag.log("activity_launch_attempt", callId, "reason=$reason")
        } catch (e: Exception) {
            Diag.log("activity_launch_failed", callId, e.javaClass.simpleName)
        }
    }

    private fun ensureChannels(nm: NotificationManager) {
        if (channelsReady) return
        channelsReady = true
        if (nm.getNotificationChannel(CH_INCOMING) == null) {
            nm.createNotificationChannel(NotificationChannel(CH_INCOMING, "Incoming calls", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Full-screen incoming call alerts. Ringtone is played by the system."
                setSound(null, null)
                enableVibration(false)
                enableLights(false)
                setShowBadge(false)
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            })
        }
        if (nm.getNotificationChannel(CH_INCOMING_QUIET) == null) {
            nm.createNotificationChannel(NotificationChannel(CH_INCOMING_QUIET, "Incoming calls (screen on)", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Shown quietly while the call screen opens by itself, so there is no heads-up on top of it."
                setSound(null, null)
                enableVibration(false)
                enableLights(false)
                setShowBadge(false)
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            })
        }
        if (nm.getNotificationChannel(CH_FOLLOWUP) == null) {
            nm.createNotificationChannel(NotificationChannel(CH_FOLLOWUP, "Unanswered calls", NotificationManager.IMPORTANCE_LOW).apply {
                lockscreenVisibility = Notification.VISIBILITY_PRIVATE
            })
        }
        if (nm.getNotificationChannel(CH_REMIND) == null) {
            nm.createNotificationChannel(NotificationChannel(CH_REMIND, "Call reminders", NotificationManager.IMPORTANCE_HIGH).apply {
                lockscreenVisibility = Notification.VISIBILITY_PRIVATE
            })
        }
        if (nm.getNotificationChannel(CH_MISSED) == null) {
            nm.createNotificationChannel(NotificationChannel(CH_MISSED, "Missed calls", NotificationManager.IMPORTANCE_DEFAULT).apply {
                setShowBadge(true)
                lockscreenVisibility = Notification.VISIBILITY_PRIVATE
            })
        }
        if (nm.getNotificationChannel(CH_ONGOING) == null) {
            nm.createNotificationChannel(NotificationChannel(CH_ONGOING, "Ongoing calls", NotificationManager.IMPORTANCE_LOW).apply {
                setSound(null, null)
                setShowBadge(false)
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            })
        }
    }

    /**
     * Our own missed-call notification, so it carries this app's name and icon instead of the system's "Call services" one.
     * One notification per number; repeat misses from the same number update it with a count.
     */
    fun missed(ctx: Context, c: CallInfo) {
        val nm = ctx.getSystemService(NotificationManager::class.java) ?: return
        ensureChannels(nm)
        val key = c.number?.takeIf { it.isNotBlank() } ?: "unknown"
        val count = (missedCount[key] ?: 0) + 1
        missedCount[key] = count
        val open = DialerIntents.openRecents(ctx, 30)
        val clear = PendingIntent.getBroadcast(ctx, 31, Intent(ctx, CallActionReceiver::class.java).setAction(ACTION_CLEAR_MISSED), FLAGS)
        val b = NotificationCompat.Builder(ctx, CH_MISSED)
            .setSmallIcon(R.drawable.ic_call_notification)
            .setContentTitle(c.title())
            .setContentText(if (count > 1) "$count missed calls" else "Missed call")
            .setSubText(c.sim)
            .setCategory(NotificationCompat.CATEGORY_MISSED_CALL)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setContentIntent(open)
            .setDeleteIntent(clear)
            .setAutoCancel(true)
            .setWhen(System.currentTimeMillis())
        if (c.number != null && c.presentation == TelecomManager.PRESENTATION_ALLOWED) {
            b.addAction(R.drawable.ic_call_notification, "Call back", callPi(ctx, 32, c.number))
            b.addAction(R.drawable.ic_call_notification, "Message", PendingIntent.getActivity(ctx, 33, Intent(Intent.ACTION_SENDTO, Uri.fromParts("smsto", c.number, null)), FLAGS))
            b.addAction(R.drawable.ic_call_notification, "Remind in 1h", snoozePi(ctx, c.number, c.title(), ID_MISSED))
        }
        try {
            nm.notify(key, ID_MISSED, b.build())
            Diag.log("notif_posted", c.id, "kind=missed")
        } catch (e: SecurityException) { Diag.fail("missed_notify", e, c.id) }
    }

    /** Quiet follow-up after an outgoing call that went unanswered, with one-tap redial and a reminder. */
    fun unanswered(ctx: Context, c: CallInfo) {
        val number = c.number?.takeIf { it.isNotBlank() } ?: return
        val nm = ctx.getSystemService(NotificationManager::class.java) ?: return
        ensureChannels(nm)
        val b = NotificationCompat.Builder(ctx, CH_FOLLOWUP)
            .setSmallIcon(R.drawable.ic_call_notification)
            .setContentTitle(c.title())
            .setContentText(c.endLabel)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setAutoCancel(true)
            .setTimeoutAfter(10 * 60 * 1000L)
            .addAction(R.drawable.ic_call_notification, "Call again", callPi(ctx, 34, number))
            .addAction(R.drawable.ic_call_notification, "Remind in 1h", snoozePi(ctx, number, c.title(), ID_FOLLOWUP))
            .setContentIntent(callPi(ctx, 35, number))
        try { nm.notify(number, ID_FOLLOWUP, b.build()) } catch (e: SecurityException) { Diag.fail("followup_notify", e, c.id) }
    }

    /** "Call back" / "Call again": the intent carries the secret key, so only our own notifications can start a call this way. */
    private fun callPi(ctx: Context, rc: Int, number: String) = DialerIntents.callBack(ctx, number, rc)

    private fun snoozePi(ctx: Context, number: String, title: String, notifId: Int) = PendingIntent.getBroadcast(ctx, 36,
        Intent(ctx, CallActionReceiver::class.java).setAction(ACTION_SNOOZE).setData(Uri.fromParts("tel", number, "n$notifId"))
            .putExtra(EXTRA_NUMBER, number).putExtra(EXTRA_TITLE, title).putExtra(EXTRA_NID, notifId), FLAGS)

    /** One hour from now. Inexact on purpose: no exact-alarm permission, and a few minutes of Doze slack is fine for a reminder. */
    fun snooze(ctx: Context, number: String, title: String, notifId: Int) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm?.cancel(missedKey(number), notifId)
        missedCount.remove(missedKey(number))
        val at = System.currentTimeMillis() + 60 * 60 * 1000L
        val pi = PendingIntent.getBroadcast(ctx, 37,
            Intent(ctx, CallActionReceiver::class.java).setAction(ACTION_REMIND).setData(Uri.fromParts("tel", number, "remind"))
                .putExtra(EXTRA_NUMBER, number).putExtra(EXTRA_TITLE, title), FLAGS)
        try { ctx.getSystemService(android.app.AlarmManager::class.java)?.setAndAllowWhileIdle(android.app.AlarmManager.RTC_WAKEUP, at, pi) }
        catch (e: Exception) { Diag.fail("snooze", e) }
        Diag.log("reminder_set")
    }

    private fun missedKey(number: String) = number.takeIf { it.isNotBlank() } ?: "unknown"

    fun remind(ctx: Context, number: String, title: String) {
        val nm = ctx.getSystemService(NotificationManager::class.java) ?: return
        ensureChannels(nm)
        val b = NotificationCompat.Builder(ctx, CH_REMIND)
            .setSmallIcon(R.drawable.ic_call_notification)
            .setContentTitle("Call back $title")
            .setContentText("Reminder")
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setAutoCancel(true)
            .setContentIntent(callPi(ctx, 38, number))
            .addAction(R.drawable.ic_call_notification, "Call", callPi(ctx, 39, number))
        try { nm.notify(number, ID_REMIND, b.build()) } catch (e: SecurityException) { Diag.fail("remind_notify", e) }
    }

    /** Dismisses the missed-call notification for one number (after calling it back) and leaves the others alone. */
    fun clearMissedFor(ctx: Context, number: String) {
        val key = missedKey(number)
        missedCount.remove(key)
        ctx.getSystemService(NotificationManager::class.java)?.cancel(key, ID_MISSED)
    }

    /** Dismisses our missed-call notifications, and the system's own copy if it posted one. */
    fun clearMissed(ctx: Context) {
        missedCount.clear()
        val nm = ctx.getSystemService(NotificationManager::class.java) ?: return
        nm.activeNotifications.filter { it.id == ID_MISSED }.forEach { nm.cancel(it.tag, it.id) }
        cancelSystemMissed(ctx)
    }

    /** Telecom posts its own missed-call notification when a call ends unanswered. As default dialer we may remove it. */
    fun cancelSystemMissed(ctx: Context) {
        try { ctx.getSystemService(TelecomManager::class.java)?.cancelMissedCallsNotification() }
        catch (e: SecurityException) { Diag.fail("cancel_system_missed", e) }
    }

    /**
     * The incoming notification is ongoing and has no timeout, so if the process died mid-ring nothing else would ever remove it.
     * Called when the app comes to the front.
     */
    fun dismissIfIdle(ctx: Context) {
        if (CallManager.topLevel().none { it.isLive }) clearAll(ctx)
    }

    /** Cancels every call notification this app owns. */
    fun clearAll(ctx: Context) {
        val nm = ctx.getSystemService(NotificationManager::class.java) ?: return
        nm.cancel(ID_INCOMING); nm.cancel(ID_ONGOING)
        posted.clear(); quiet.clear(); fgOn = false; lastIncoming = null; lastOngoing = null
    }

    /** Switches a quiet incoming notification to the loud channel. Returns true if it changed (caller re-syncs). */
    fun escalate(ctx: Context, callId: Int): Boolean {
        if (quiet[callId] != true) return false
        quiet[callId] = false
        posted.remove(callId)
        lastIncoming = null
        ctx.getSystemService(NotificationManager::class.java)?.cancel(ID_INCOMING) // cancel first, or the update stays silent
        Diag.log("notif_escalated", callId, "call screen did not appear")
        return true
    }

    fun sync(ctx: Context, all: List<CallInfo>, svc: android.app.Service?) {
        val nm = ctx.getSystemService(NotificationManager::class.java) ?: return
        ensureChannels(nm)
        val top = all.filter { it.parentId == null && it.isLive }
        val ringing = top.firstOrNull { it.state == Call.STATE_RINGING }
        if (ringing != null) postIncoming(ctx, nm, ringing)
        else if (lastIncoming != null) { nm.cancel(ID_INCOMING); lastIncoming = null }
        val ongoing = CallManager.pickPrimary(top.filter { it.state != Call.STATE_RINGING })?.takeIf { !it.isEnded }
        if (ongoing != null) postOngoing(ctx, nm, ongoing, svc)
        else if (fgOn || lastOngoing != null) {
            if (fgOn) { svc?.let { ServiceCompat.stopForeground(it, ServiceCompat.STOP_FOREGROUND_REMOVE) }; fgOn = false }
            nm.cancel(ID_ONGOING)
            lastOngoing = null
        }
    }

    private fun person(c: CallInfo) = Person.Builder().setName(c.title()).setImportant(true).build()

    private fun subtitle(c: CallInfo) = if (c.name != null) pretty(c.number) else null

    // Full-screen intents are permitted for calling apps; the USE_FULL_SCREEN_INTENT grant is surfaced in Onboarding/Settings.
    @SuppressLint("FullScreenIntentPolicy")
    private fun postIncoming(ctx: Context, nm: NotificationManager, c: CallInfo) {
        val isQuiet = quiet.getOrPut(c.id) {
            val usable = ctx.getSystemService(PowerManager::class.java).isInteractive && !ctx.getSystemService(KeyguardManager::class.java).isKeyguardLocked
            Prefs.directLaunch.value && usable
        }
        val key = "${c.id}|${c.name}|${c.number}|${c.presentation}|${c.isConference}|${c.sim}|$isQuiet"
        if (key == lastIncoming) return
        val open = PendingIntent.getActivity(ctx, 10, uiIntent(ctx), FLAGS)
        val answer = PendingIntent.getActivity(ctx, 11, uiIntent(ctx).putExtra(EXTRA_ACTION, "answer"), FLAGS)
        val decline = PendingIntent.getBroadcast(ctx, 12,
            Intent(ctx, CallActionReceiver::class.java).setAction(ACTION_DECLINE), FLAGS)
        val n = NotificationCompat.Builder(ctx, if (isQuiet) CH_INCOMING_QUIET else CH_INCOMING)
            .setSmallIcon(R.drawable.ic_call_notification)
            .setContentTitle(c.title())
            .setContentText(subtitle(c) ?: "Incoming call")
            .setSubText(c.sim)
            .setStyle(NotificationCompat.CallStyle.forIncomingCall(person(c), decline, answer))
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setContentIntent(open)
            .setFullScreenIntent(open, true) // call style requires one; on the quiet channel Android never launches it
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .build()
        try {
            nm.notify(ID_INCOMING, n)
            lastIncoming = key
            Diag.log(if (posted.add(c.id)) "notif_posted" else "notif_updated", c.id, "kind=incoming")
        } catch (e: SecurityException) { Diag.log("notif_failed", c.id, "SecurityException") }
    }

    /** Android 12+ throws if a CallStyle notification isn't tied to a foreground service, user-initiated job or full-screen intent. We have none for ongoing calls, so on the first rejection we fall back to a plain ongoing notification. */
    private var ongoingCallStyle = true

    private fun postOngoing(ctx: Context, nm: NotificationManager, c: CallInfo, svc: android.app.Service?) {
        val key = "${c.id}|${c.name}|${c.number}|${c.presentation}|${c.isConference}|${c.sim}|${c.state == Call.STATE_HOLDING}|${c.connectedAt}"
        if (key == lastOngoing) return
        val open = PendingIntent.getActivity(ctx, 20, uiIntent(ctx), FLAGS)
        val hangup = PendingIntent.getBroadcast(ctx, 21,
            Intent(ctx, CallActionReceiver::class.java).setAction(ACTION_HANGUP), FLAGS)
        fun build(callStyle: Boolean) = NotificationCompat.Builder(ctx, CH_ONGOING)
            .setSmallIcon(R.drawable.ic_call_notification)
            .setContentTitle(c.title())
            .setContentText(if (c.state == Call.STATE_HOLDING) "On hold" else "Ongoing call")
            .setSubText(c.sim)
            .apply {
                if (callStyle) setStyle(NotificationCompat.CallStyle.forOngoingCall(person(c), hangup))
                else addAction(R.drawable.ic_call_notification, "Hang up", hangup)
            }
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setUsesChronometer(c.connectedAt > 0)
            .setWhen(if (c.connectedAt > 0) c.connectedAt else System.currentTimeMillis())
            .build()
        if (useFg && svc != null) {
            try {
                ServiceCompat.startForeground(svc, ID_ONGOING, build(true), ServiceInfo.FOREGROUND_SERVICE_TYPE_PHONE_CALL)
                fgOn = true
                lastOngoing = key
                return
            } catch (e: Exception) {
                useFg = false
                Diag.log("fg_failed", c.id, e.javaClass.simpleName)
            }
        }
        try {
            nm.notify(ID_ONGOING, build(ongoingCallStyle))
            lastOngoing = key
        } catch (e: IllegalArgumentException) {
            ongoingCallStyle = false
            Diag.log("notif_fallback", c.id, "kind=ongoing plain")
            try { nm.notify(ID_ONGOING, build(false)); lastOngoing = key } catch (e: Exception) { Diag.fail("ongoing_notify", e) }
        } catch (e: SecurityException) { Diag.fail("ongoing_notify", e) }
    }
}

class CallActionReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        Core.init(ctx)
        when (intent.action) {
            CallNotifications.ACTION_DECLINE -> CallManager.rejectRinging("notification")
            CallNotifications.ACTION_HANGUP -> CallManager.endPrimary()
            CallNotifications.ACTION_CLEAR_MISSED -> CallNotifications.clearMissed(ctx)
            CallNotifications.ACTION_SNOOZE -> {
                val n = intent.getStringExtra(CallNotifications.EXTRA_NUMBER) ?: return
                CallNotifications.snooze(ctx, n, intent.getStringExtra(CallNotifications.EXTRA_TITLE) ?: n, intent.getIntExtra(CallNotifications.EXTRA_NID, 0))
            }
            CallNotifications.ACTION_REMIND -> {
                val n = intent.getStringExtra(CallNotifications.EXTRA_NUMBER) ?: return
                CallNotifications.remind(ctx, n, intent.getStringExtra(CallNotifications.EXTRA_TITLE) ?: n)
            }
        }
    }
}
