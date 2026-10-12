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
        Prefs.init(app, dp)
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
                // Full frames ("a.b.c(SourceFile:12)"): in a release build the names are obfuscated, and `retrace mapping.txt` needs
                // class + method + line to map them back.
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
    val onboarded = MutableStateFlow(false)

    @Synchronized
    fun init(app: Context, dp: Context) {
        if (sp != null) return
        // One-time move of prefs saved by older builds in credential-protected storage.
        try {
            if (app.getSystemService(android.os.UserManager::class.java)?.isUserUnlocked == true)
                dp.moveSharedPreferencesFrom(app, "dialer_prefs")
        } catch (_: Exception) { }
        val p = dp.getSharedPreferences("dialer_prefs", Context.MODE_PRIVATE)
        sp = p
        theme.value = p.getInt("theme", 0)
        haptics.value = p.getBoolean("haptics", true)
        keypadTones.value = p.getBoolean("keypad_tones", true)
        gestureHints.value = p.getBoolean("gesture_hints", true)
        directLaunch.value = p.getBoolean("direct_launch", true)
        diagnostics.value = p.getBoolean("diagnostics", true)
        // New key: the old "show_favorites" could have been left off by an earlier build whose Settings toggle no longer exists.
        showFavorites.value = p.getBoolean("show_favorites_row", true)
        silenceUnknown.value = p.getBoolean("silence_unknown", false)
        onboarded.value = p.getBoolean("onboarded", false)
    }

    fun setTheme(v: Int) { theme.value = v; sp?.edit()?.putInt("theme", v)?.apply() }
    fun set(key: String, flow: MutableStateFlow<Boolean>, v: Boolean) {
        flow.value = v; sp?.edit()?.putBoolean(key, v)?.apply()
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
    data class Event(val t: Long, val wall: Long, val name: String, val callId: Int, val detail: String) {
        fun kv(key: String): String? = detail.split(' ').firstOrNull { it.startsWith("$key=") }?.substringAfter('=')
    }

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
        val sb = StringBuilder("Dialer diagnostics (no numbers/names logged)\n")
        sb.append("App version: $version\n")
        sb.append("Device: ${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}, Android ${android.os.Build.VERSION.RELEASE} (API ${android.os.Build.VERSION.SDK_INT})\n\n")
        sb.append("== Event log (crashes and call lifecycle; t = ms since first event) ==\n")
        val t0 = all.firstOrNull()?.t ?: 0L
        all.forEach { sb.append("+${it.t - t0} ${it.name} call=${it.callId} ${it.detail}\n") }
        return sb.toString()
    }
}