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
        showFavorites.value = p.getBoolean("show_favorites", true)
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

    data class Interval(val label: String, val ms: Long?, val note: String)

    private const val MAX = 600
    private val _events = MutableStateFlow<List<Event>>(emptyList())
    val events: StateFlow<List<Event>> = _events
    private var file: File? = null
    private val io = Executors.newSingleThreadExecutor()

    @Synchronized
    fun init(ctx: Context) {
        if (file != null) return
        val f = File(ctx.filesDir, "diag.log")
        file = f
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

    /** Intervals the app can actually observe. Anything before onCallAdded/Telecom creation is invisible to us. */
    fun intervals(callId: Int, all: List<Event>): List<Interval> {
        val m = HashMap<String, Event>()
        all.filter { it.callId == callId }.forEach { m.putIfAbsent(it.name, it) }
        fun d(a: String, b: String): Long? {
            val x = m[a] ?: return null
            val y = m[b] ?: return null
            return y.t - x.t
        }
        fun kv(n: String, k: String) = m[n]?.kv(k)?.toLongOrNull()?.takeIf { it >= 0 }
        return listOf(
            Interval("Telecom created call → app callback", kv("call_added", "lagMs"),
                "Wall clock. Includes process start + service bind. Time before Telecom created the call is not visible."),
            Interval("Service bound → onCallAdded", kv("call_added", "sinceBindMs"), "Monotonic."),
            Interval("onCallAdded → notification posted", d("call_added", "notif_posted"),
                "notify() returned; does not prove it was drawn."),
            Interval("onCallAdded → activity launch requested", d("call_added", "activity_launch_attempt"),
                "startActivity() called; Android may silently block background launches."),
            Interval("onCallAdded → activity onCreate", d("call_added", "activity_created"),
                "Only present if the activity actually started."),
            Interval("Activity onCreate → first frame done", d("activity_created", "first_frame_done"), "FrameMetrics callback."),
            Interval("Activity onCreate → call UI composed + frame", d("activity_created", "ui_frame"), "Compose content drawn."),
            Interval("onCallAdded → call UI frame (app-side total)", d("call_added", "ui_frame"),
                "Largest app-visible delay before pixels."),
            Interval("Answer tap → ACTIVE", d("answer_tapped", "state_ACTIVE"), "Telecom/network answer latency."),
            Interval("Decline tap → DISCONNECTED", d("decline_tapped", "state_DISCONNECTED"), "")
        )
    }

    fun report(all: List<Event>): String {
        val sb = StringBuilder("Dialer diagnostics (no numbers/names logged)\n")
        sb.append("Device: ${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}, Android ${android.os.Build.VERSION.RELEASE} (API ${android.os.Build.VERSION.SDK_INT})\n\n")
        val ids = all.map { it.callId }.filter { it != 0 }.distinct()
        for (id in ids) {
            sb.append("== Call #$id ==\n")
            intervals(id, all).forEach { sb.append("${it.label}: ${it.ms?.let { v -> "$v ms" } ?: "n/a"}\n") }
            sb.append('\n')
        }
        sb.append("== Raw events (t = monotonic ms) ==\n")
        val t0 = all.firstOrNull()?.t ?: 0L
        all.forEach { sb.append("+${it.t - t0} ${it.name} call=${it.callId} ${it.detail}\n") }
        return sb.toString()
    }
}