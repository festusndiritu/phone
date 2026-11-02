package dev.mizzenmast.dialer

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow

object Prefs {
    private var sp: SharedPreferences? = null
    val theme = MutableStateFlow(0) // 0 system, 1 light, 2 dark
    val haptics = MutableStateFlow(true)
    val keypadTones = MutableStateFlow(true)
    val gestureHints = MutableStateFlow(true)
    val directLaunch = MutableStateFlow(true)
    val diagnostics = MutableStateFlow(true)
    val showFavorites = MutableStateFlow(true)
    val silenceUnknown = MutableStateFlow(false)
    val vibrateOnAnswer = MutableStateFlow(false)
    val replyViaWhatsApp = MutableStateFlow(false)
    /** Contacts filter, persisted: "all", "fav" or "src:<where it's saved>". */
    val contactFilter = MutableStateFlow("all")
    /** True once the setup walkthrough has been finished. Everyone sees the walkthrough once; after that setup only returns if an essential is revoked. */
    val onboarded = MutableStateFlow(false)

    @Synchronized
    fun init(dp: Context) {
        if (sp != null) return
        val p = dp.getSharedPreferences("dialer_prefs", Context.MODE_PRIVATE)
        sp = p
        theme.value = p.getInt("theme", 0)
        haptics.value = p.getBoolean("haptics", true)
        keypadTones.value = p.getBoolean("keypad_tones", true)
        gestureHints.value = p.getBoolean("gesture_hints", true)
        directLaunch.value = p.getBoolean("direct_launch", true)
        diagnostics.value = p.getBoolean("diagnostics", true)
        showFavorites.value = p.getBoolean("show_favorites_row", true)
        silenceUnknown.value = p.getBoolean("silence_unknown", false)
        vibrateOnAnswer.value = p.getBoolean("vibrate_on_answer", false)
        replyViaWhatsApp.value = p.getBoolean("reply_whatsapp", false)
        contactFilter.value = p.getString("contact_filter", "all") ?: "all"
        onboarded.value = p.getBoolean("onboarded_v2", false)
    }

    fun setContactFilter(v: String) {
        contactFilter.value = v
        sp?.edit {
            putString("contact_filter", v)
        }
    }

    fun setOnboarded(v: Boolean) {
        onboarded.value = v
        sp?.edit {
            putBoolean("onboarded_v2", v)
        }
    }

    fun setTheme(v: Int) {
        theme.value = v
        sp?.edit {
            putInt("theme", v)
        }
    }

    fun set(key: String, flow: MutableStateFlow<Boolean>, v: Boolean) {
        flow.value = v
        sp?.edit {
            putBoolean(key, v)
        }
    }

    private fun numKey(number: String) = PhoneKey.of(number).takeIf { it.isNotEmpty() }
    /** Before [PhoneKey], choices were stored under the last nine digits. Still read, and replaced on the next save. */
    private fun legacyNumKey(number: String) = number.filter(Char::isDigit).takeLast(9).takeIf { it.isNotEmpty() }

    /** SIM account key (see [SimLabels.key]) this number should always be called from, or null for the system default. */
    fun simFor(number: String): String? {
        val p = sp ?: return null
        return numKey(number)?.let { p.getString("sim_$it", null) } ?: legacyNumKey(number)?.let { p.getString("sim_$it", null) }
    }

    fun setSimFor(number: String, key: String?) {
        val k = numKey(number) ?: return
        val old = legacyNumKey(number)
        sp?.edit {
            if (old != null) remove("sim_$old")
            if (key == null) remove("sim_$k") else putString("sim_$k", key)
        }
    }

    /**
     * Secret that only our own PendingIntents carry (see [DialerIntents]); other apps can't guess it. Made on first use and kept for
     * the life of the install, so notifications posted earlier keep working. Written with commit so it can't be lost before the intent is used.
     */
    @Synchronized
    fun intentKey(): String {
        val p = sp ?: return ""
        p.getString("intent_key", null)?.let { return it }
        val k = java.util.UUID.randomUUID().toString()
        p.edit(commit = true) { putString("intent_key", k) }
        return k
    }

    fun isIntentKey(candidate: String?): Boolean {
        val k = intentKey()
        return k.isNotEmpty() && candidate != null && java.security.MessageDigest.isEqual(k.toByteArray(), candidate.toByteArray())
    }

    /** Persisted so call ids stay unique across process restarts (diagnostic log is persisted). */
    @Synchronized
    fun nextCallId(): Int {
        val p = sp ?: return (System.currentTimeMillis() % 100000).toInt()
        val n = p.getInt("call_seq", 0) + 1
        p.edit { putInt("call_seq", n) }
        return n
    }
}
