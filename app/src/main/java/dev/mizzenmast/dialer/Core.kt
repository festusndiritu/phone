package dev.mizzenmast.dialer

import android.content.Context
import android.content.SharedPreferences
import android.os.SystemClock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import java.io.File
import java.util.concurrent.Executors
import androidx.core.content.edit

/** Idempotent entry-point init. Call from every Activity/Service/Receiver entry (no Application subclass needed). */
object Core {
    fun init(ctx: Context) {
        val app = ctx.applicationContext
        // Device-protected storage is readable before the first unlock after boot (Direct Boot),
        // which is what lets the in-call UI work with a lock-screen password after a restart.
        val dp = app.createDeviceProtectedStorageContext()
        Prefs.init(dp)
        Diag.init(dp)
        hookCrashes()
    }

    private var hooked = false
    /** If the process dies mid-call, Telecom falls back to the system dialer. Leave a breadcrumb so the report shows why. */
    @Synchronized
    private fun hookCrashes() {
        if (hooked) return
        hooked = true
        val prev = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            try {
                val frames = e.stackTrace.take(4).joinToString(" < ") { it.toString() }
                Diag.logSync("crash", 0, "${e.javaClass.simpleName}: ${e.message?.take(80)} @ $frames")
            } catch (_: Throwable) { }
            prev?.uncaughtException(t, e)
        }
    }
}

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
        sp?.edit()?.putString("contact_filter", v)?.apply()
    }
    fun setOnboarded(v: Boolean) {
        onboarded.value = v
        sp?.edit()?.putBoolean("onboarded_v2", v)?.apply()
    }
    fun setTheme(v: Int) {
        theme.value = v
        sp?.edit()?.putInt("theme", v)?.apply()
    }
    fun set(key: String, flow: MutableStateFlow<Boolean>, v: Boolean) {
        flow.value = v
        sp?.edit()?.putBoolean(key, v)?.apply()
    }

    private fun numKey(number: String) = number.filter(Char::isDigit).takeLast(9).takeIf { it.isNotEmpty() }
    /** SIM account key (see [SimLabels.key]) this number should always be called from, or null for the system default. */
    fun simFor(number: String): String? = numKey(number)?.let { sp?.getString("sim_$it", null) }
    fun setSimFor(number: String, key: String?) {
        val k = numKey(number) ?: return
        sp?.edit { if (key == null) remove("sim_$k") else putString("sim_$k", key) }
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

/**
 * Structured, local-only diagnostics. Timestamps use SystemClock.elapsedRealtime() (monotonic).
 * No phone numbers or names are ever logged; calls are identified by a local sequence id.
 */
object Diag {
    data class Event(val t: Long, val wall: Long, val name: String, val callId: Int, val detail: String)

    private const val MAX = 600
    private val _events = MutableStateFlow<List<Event>>(emptyList())
    val events: StateFlow<List<Event>> = _events
    private var file: File? = null
    private val io = Executors.newSingleThreadExecutor()

    /** "1.0 (1)" from the installed package; set once in [init]. Bump versionCode/versionName in build.gradle.kts per build you hand out. */
    @Volatile var version = "unknown"
        private set

    @Synchronized
    fun init(ctx: Context) {
        if (file != null) return
        val f = File(ctx.filesDir, "diag.log")
        file = f
        version = try {
            val i = ctx.packageManager.getPackageInfo(ctx.packageName, 0)
            "${i.versionName} (${i.longVersionCode})" // minSdk is 28, so longVersionCode is always available
        } catch (_: Exception) { "unknown" }
        log("process_start", 0, "v=$version")
        io.execute {
            try {
                if (f.exists()) {
                    val lines = f.readLines().takeLast(MAX)
                    val parsed = lines.mapNotNull(::parse)
                    _events.update { (parsed + it).takeLast(MAX) }
                    f.writeText(lines.joinToString("\n", postfix = "\n"))
                }
            } catch (_: Exception) { }
        }
    }

    private fun parse(line: String): Event? {
        val p = line.split('|', limit = 5)
        if (p.size < 5) return null
        return try { Event(p[0].toLong(), p[1].toLong(), p[2], p[3].toInt(), p[4]) } catch (_: Exception) { null }
    }

    fun log(name: String, callId: Int = 0, detail: String = "") {
        if (!Prefs.diagnostics.value) return
        val e = Event(SystemClock.elapsedRealtime(), System.currentTimeMillis(), name, callId,
            detail.replace('|', '/').replace('\n', ' '))
        _events.update { (it + e).takeLast(MAX) }
        val f = file ?: return
        io.execute {
            try { f.appendText("${e.t}|${e.wall}|${e.name}|${e.callId}|${e.detail}\n") } catch (_: Exception) { }
        }
    }

    /** A handled failure. [where] names the call site; the detail is the exception class and a short message with long digit runs masked. */
    fun fail(where: String, e: Throwable, callId: Int = 0) =
        log("err_$where", callId, e.javaClass.simpleName + (e.message?.let { " " + it.replace(Regex("\\d{4,}"), "#").take(80) } ?: ""))

    /** Blocking append, for the crash handler only. */
    fun logSync(name: String, callId: Int, detail: String) {
        if (!Prefs.diagnostics.value) return
        val f = file ?: return
        val d = detail.replace('|', '/').replace('\n', ' ')
        try { f.appendText("${SystemClock.elapsedRealtime()}|${System.currentTimeMillis()}|$name|$callId|$d\n") } catch (_: Exception) { }
    }

    fun clear() {
        _events.value = emptyList()
        io.execute { try { file?.delete() } catch (_: Exception) { } }
    }

    fun report(all: List<Event>): String {
        val sb = StringBuilder("Dialer diagnostics\n")
        sb.append("App version: $version\n")
        sb.append("Device: ${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}, Android ${android.os.Build.VERSION.RELEASE} (API ${android.os.Build.VERSION.SDK_INT})\n\n")
        sb.append("== Event log (crashes and call lifecycle; t = ms since first event) ==\n")
        val t0 = all.firstOrNull()?.t ?: 0L
        all.forEach { sb.append("+${it.t - t0} ${it.name} call=${it.callId} ${it.detail}\n") }
        return sb.toString()
    }
}