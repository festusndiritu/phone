package dev.mizzenmast.dialer

import android.content.Context
import android.os.SystemClock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import java.io.File
import java.util.concurrent.Executors

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
            "${i.versionName} (${i.longVersionCode})"
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
