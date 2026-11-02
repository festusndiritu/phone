package dev.mizzenmast.dialer

import android.content.Context
import android.content.Intent
import androidx.core.net.toUri

/**
 * Dialer secret codes: `*#*#4636#*#*` is not a phone number but a broadcast that other apps (usually the system's
 * testing or service menus) listen for. A phone app is expected to send it; Telecom would reject it as a call.
 */
object SpecialCodes {
    private const val ACTION_SECRET_CODE = "android.provider.Telephony.SECRET_CODE"
    private val SECRET_CODE = Regex("""\*#\*#([^*#]+)#\*#\*""")

    /** The code between the markers, or null if [input] isn't a complete secret code. */
    fun secretCode(input: String): String? = SECRET_CODE.matchEntire(input.trim())?.groupValues?.get(1)

    /** Sends the broadcast for a secret code. Returns false (and does nothing) for anything else. */
    fun dispatch(ctx: Context, input: String): Boolean {
        val code = secretCode(input) ?: return false
        ctx.sendBroadcast(Intent(ACTION_SECRET_CODE, "android_secret_code://$code".toUri()))
        Diag.log("secret_code_sent")
        return true
    }
}
