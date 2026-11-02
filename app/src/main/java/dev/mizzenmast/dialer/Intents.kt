package dev.mizzenmast.dialer

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri

/** What an intent sent to [MainActivity] is asking for, once it has been checked. */
sealed interface Launch {
    data class ShowTab(val index: Int) : Launch
    data object OpenDialer : Launch
    /** A number from a tel: link. It only fills the dialer; it never places a call. */
    data class Prefill(val number: String) : Launch
    /** "Call back" from one of our own notifications. */
    data class CallBack(val number: String) : Launch
}

/**
 * Builds the intents we send ourselves and reads the ones [MainActivity] receives.
 *
 * [MainActivity] is exported (it answers tel: links), so any app can send it an intent. Navigation extras are harmless,
 * but placing a call is not: "call back" only works with the action and secret key that [callBack] puts on it.
 */
object DialerIntents {
    const val EXTRA_TAB = "tab"
    const val EXTRA_OPEN_DIALER = "open_dialer"
    const val ACTION_CALL_BACK = "dev.mizzenmast.dialer.action.CALL_BACK"
    private const val EXTRA_CALL_NUMBER = "call_number"
    private const val EXTRA_KEY = "internal_key"
    private const val PI_FLAGS = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
    private const val NEW_TASK_TOP = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP

    /** Opens the main screen on the Recents tab (the missed-call notification). */
    fun openRecents(ctx: Context, requestCode: Int): PendingIntent = PendingIntent.getActivity(
        ctx, requestCode,
        Intent(ctx, MainActivity::class.java).putExtra(EXTRA_TAB, 0).addFlags(NEW_TASK_TOP), PI_FLAGS
    )

    /** Opens the main screen with the dialpad up (from the call screen). */
    fun openDialer(ctx: Context): Intent =
        Intent(ctx, MainActivity::class.java).putExtra(EXTRA_OPEN_DIALER, true).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    /** Calls [number] when tapped. The tel: data only keeps one PendingIntent per number and request code; it is not what authorizes the call. */
    fun callBack(ctx: Context, number: String, requestCode: Int): PendingIntent = PendingIntent.getActivity(
        ctx, requestCode,
        Intent(ctx, MainActivity::class.java)
            .setAction(ACTION_CALL_BACK)
            .setData(Uri.fromParts("tel", number, "r$requestCode"))
            .putExtra(EXTRA_CALL_NUMBER, number)
            .putExtra(EXTRA_KEY, Prefs.intentKey())
            .addFlags(NEW_TASK_TOP), PI_FLAGS
    )

    fun parse(i: Intent): List<Launch> {
        // Reopening the app from Recents hands back the intent the task started with. Its action already ran once; don't repeat it.
        if ((i.flags and Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY) != 0) return emptyList()
        val out = ArrayList<Launch>(2)
        if (i.hasExtra(EXTRA_TAB)) out += Launch.ShowTab(i.getIntExtra(EXTRA_TAB, 0).coerceIn(0, 2))
        if (i.getBooleanExtra(EXTRA_OPEN_DIALER, false)) out += Launch.OpenDialer
        if (i.action == ACTION_CALL_BACK) {
            val number = i.getStringExtra(EXTRA_CALL_NUMBER)?.trim()
            if (!number.isNullOrEmpty() && Prefs.isIntentKey(i.getStringExtra(EXTRA_KEY))) out += Launch.CallBack(number)
            else Diag.log("intent_rejected", 0, "call_back")
        } else if (i.hasExtra(EXTRA_CALL_NUMBER)) {
            // The old unauthenticated form: from an app that isn't us, or a notification from before this check existed.
            Diag.log("intent_rejected", 0, "call_extra")
        }
        val data = i.data
        if ((i.action == Intent.ACTION_DIAL || i.action == Intent.ACTION_VIEW) && data?.scheme == "tel") {
            out += Launch.Prefill(Uri.decode(data.schemeSpecificPart.orEmpty()))
        }
        return out
    }

    /** Strips what [parse] acted on, so the same intent can't place the call a second time. */
    fun consume(i: Intent) {
        if (i.action == ACTION_CALL_BACK) {
            i.action = null
            i.removeExtra(EXTRA_CALL_NUMBER)
            i.removeExtra(EXTRA_KEY)
        }
    }
}
