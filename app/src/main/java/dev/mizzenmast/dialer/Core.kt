package dev.mizzenmast.dialer

import android.content.Context

/** Idempotent entry-point init. Call from every Activity/Service/Receiver entry (no Application subclass needed). */
object Core {
    fun init(ctx: Context) {
        val app = ctx.applicationContext
        // Device-protected storage is readable before the first unlock after boot (Direct Boot),
        // which is what lets the in-call UI work with a lock-screen password after a restart.
        val dp = app.createDeviceProtectedStorageContext()
        Prefs.init(dp)
        Diag.init(dp)
        PhoneKey.init(app)
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
