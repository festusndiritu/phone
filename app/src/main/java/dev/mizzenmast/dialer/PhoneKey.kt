package dev.mizzenmast.dialer

import android.content.Context
import android.telephony.PhoneNumberUtils
import android.telephony.TelephonyManager
import java.util.Locale

/**
 * One canonical form per phone number, for matching contacts, call-log rows and saved SIM choices.
 * "0712 345 678" and "+254 712-345-678" become the same E.164 key once the home country is known;
 * short codes and anything that can't be placed in a country keep just their digits.
 */
object PhoneKey {
    private const val MEMO_LIMIT = 4096
    private val memo = HashMap<String, String>()
    private var region = Locale.getDefault().country

    /** Reads the home country (SIM first, then network, then locale). Cheap; the memo is dropped only if the country changed. */
    @Synchronized
    fun init(ctx: Context) {
        val tm = ctx.getSystemService(TelephonyManager::class.java)
        val iso = tm?.simCountryIso?.takeIf { it.isNotBlank() } ?: tm?.networkCountryIso?.takeIf { it.isNotBlank() }
        val now = (iso ?: Locale.getDefault().country).uppercase(Locale.ROOT)
        if (now != region) { region = now; memo.clear() }
    }

    /** Empty when [number] has no digits. */
    @Synchronized
    fun of(number: String): String {
        memo[number]?.let { return it }
        val key = compute(number)
        if (memo.size >= MEMO_LIMIT) memo.clear()
        memo[number] = key
        return key
    }

    private fun compute(number: String): String {
        val n = PhoneNumberUtils.normalizeNumber(number)
        if (n.none { it.isDigit() }) return ""
        // Short codes and service codes (*#06#, 112) are compared literally.
        if (n.length < 5 || number.any { it == '*' || it == '#' }) return n
        return PhoneNumberUtils.formatNumberToE164(n, region.ifEmpty { null }) ?: n
    }
}
