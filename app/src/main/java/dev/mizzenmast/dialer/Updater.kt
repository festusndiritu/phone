package dev.mizzenmast.dialer

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import androidx.core.content.IntentCompat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/** What the latest GitHub release offers. [sha256] is only known when GitHub reports a digest for the asset. */
data class UpdateInfo(val version: String, val notes: String, val url: String, val size: Long, val sha256: String?)

sealed interface UpdateState {
    data object Idle : UpdateState
    data object Checking : UpdateState
    data object UpToDate : UpdateState
    data class Available(val info: UpdateInfo) : UpdateState
    /** [progress] is 0..1, or -1 while the size isn't known. */
    data class Downloading(val info: UpdateInfo, val progress: Float) : UpdateState
    /** Downloaded and handed to Android; waiting for the person to confirm in the system dialog. */
    data class Installing(val info: UpdateInfo) : UpdateState
    /** "Install unknown apps" isn't allowed for this app yet. */
    data class NeedsPermission(val info: UpdateInfo) : UpdateState
    data class Failed(val message: String, val info: UpdateInfo?) : UpdateState
}

/**
 * Self-updates from the GitHub Releases of [REPO]: the newest release's tag ("v2.2.4") is compared with the installed versionName,
 * and the first .apk attached to it is downloaded and installed. Android refuses any APK that isn't signed with the installed
 * app's key, so a tampered download can't be installed. The repo must be public.
 *
 * Nothing here ever interrupts the person: the check runs quietly and the result shows up in Settings.
 */
object Updater {
    private const val REPO = "festusndiritu/phone"
    private const val CHECK_EVERY_MS = 12 * 60 * 60 * 1000L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _state = MutableStateFlow<UpdateState>(UpdateState.Idle)
    val state: StateFlow<UpdateState> = _state
    private var job: Job? = null

    fun installedVersion(ctx: Context): String = try {
        ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName.orEmpty()
    } catch (_: Exception) { "" }

    /** Quiet background check, at most every 12 hours. Failures stay silent. */
    fun checkIfDue(ctx: Context) {
        val app = ctx.applicationContext
        val prefs = app.getSharedPreferences("updater", Context.MODE_PRIVATE)
        if (System.currentTimeMillis() - prefs.getLong("last_check", 0L) < CHECK_EVERY_MS) return
        check(app, silent = true)
    }

    /** [silent]: don't show "Checking…" or errors. A manual check shows both. */
    fun check(ctx: Context, silent: Boolean = false) {
        if (job?.isActive == true) return
        val app = ctx.applicationContext
        job = scope.launch {
            if (!silent) _state.value = UpdateState.Checking
            try {
                val latest = fetchLatest()
                app.getSharedPreferences("updater", Context.MODE_PRIVATE).edit().putLong("last_check", System.currentTimeMillis()).apply()
                _state.value = if (latest != null && isNewer(latest.version, installedVersion(app))) UpdateState.Available(latest) else UpdateState.UpToDate
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Diag.log("update_check_failed", 0, e.javaClass.simpleName)
                _state.value = if (silent) UpdateState.Idle else UpdateState.Failed("Couldn't check for updates. Check your connection and try again.", null)
            }
        }
    }

    fun install(ctx: Context, info: UpdateInfo) {
        if (job?.isActive == true) return
        val app = ctx.applicationContext
        if (!app.packageManager.canRequestPackageInstalls()) {
            _state.value = UpdateState.NeedsPermission(info)
            return
        }
        job = scope.launch {
            try {
                downloadAndCommit(app, info)
            } catch (e: CancellationException) {
                _state.value = UpdateState.Available(info)
                throw e
            } catch (e: Exception) {
                Diag.log("update_install_failed", 0, e.javaClass.simpleName)
                _state.value = UpdateState.Failed(
                    if (e is ChecksumMismatch) "The download didn't match the published file, so it was discarded. Try again."
                    else "Couldn't download the update. Check your connection and try again.",
                    info
                )
            }
        }
    }

    fun cancel() { job?.cancel() }

    /** Called by [UpdateReceiver] with what the installer reported. */
    internal fun onInstallResult(status: Int, message: String?) {
        val info = when (val s = _state.value) {
            is UpdateState.Installing -> s.info
            is UpdateState.Downloading -> s.info
            else -> return
        }
        when (status) {
            // Person backed out of the system dialog: not an error.
            PackageInstaller.STATUS_FAILURE_ABORTED -> _state.value = UpdateState.Available(info)
            PackageInstaller.STATUS_SUCCESS -> Unit
            else -> {
                Diag.log("update_install_status", 0, "$status ${message.orEmpty()}")
                _state.value = UpdateState.Failed("Android couldn't install the update.", info)
            }
        }
    }

    private class ChecksumMismatch : IOException()

    private fun open(url: String): HttpURLConnection = (URL(url).openConnection() as HttpURLConnection).apply {
        connectTimeout = 15_000
        readTimeout = 20_000
        setRequestProperty("Accept", "application/vnd.github+json")
    }

    private fun fetchLatest(): UpdateInfo? {
        val c = open("https://api.github.com/repos/$REPO/releases/latest")
        try {
            if (c.responseCode == 404) return null // no release published yet
            if (c.responseCode != 200) throw IOException("HTTP ${c.responseCode}")
            val json = JSONObject(c.inputStream.bufferedReader().use { it.readText() })
            val assets = json.optJSONArray("assets") ?: return null
            for (i in 0 until assets.length()) {
                val a = assets.getJSONObject(i)
                if (!a.optString("name").endsWith(".apk", ignoreCase = true)) continue
                val digest = a.optString("digest")
                return UpdateInfo(
                    version = json.getString("tag_name").removePrefix("v").removePrefix("V"),
                    notes = cleanNotes(json.optString("body")),
                    url = a.getString("browser_download_url"),
                    size = a.optLong("size", -1L),
                    sha256 = digest.takeIf { it.startsWith("sha256:") }?.removePrefix("sha256:")
                )
            }
            return null
        } finally { c.disconnect() }
    }

    private fun downloadAndCommit(app: Context, info: UpdateInfo) {
        val installer = app.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(app.packageName)
            if (info.size > 0) setSize(info.size)
            if (Build.VERSION.SDK_INT >= 31) setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
        }
        val id = installer.createSession(params)
        val session = installer.openSession(id)
        var committed = false
        try {
            _state.value = UpdateState.Downloading(info, if (info.size > 0) 0f else -1f)
            val digest = MessageDigest.getInstance("SHA-256")
            val c = open(info.url)
            try {
                if (c.responseCode != 200) throw IOException("HTTP ${c.responseCode}")
                c.inputStream.use { input ->
                    session.openWrite("update.apk", 0, info.size.takeIf { it > 0 } ?: -1L).use { out ->
                        val buf = ByteArray(64 * 1024)
                        var done = 0L
                        var lastPct = -1
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n)
                            digest.update(buf, 0, n)
                            done += n
                            if (info.size > 0) {
                                val pct = (done * 100 / info.size).toInt()
                                if (pct != lastPct) { lastPct = pct; _state.value = UpdateState.Downloading(info, done.toFloat() / info.size) }
                            }
                            if (job?.isActive == false) throw CancellationException()
                        }
                        session.fsync(out)
                    }
                }
            } finally { c.disconnect() }

            val got = digest.digest().joinToString("") { "%02x".format(it) }
            if (info.sha256 != null && !got.equals(info.sha256, ignoreCase = true)) throw ChecksumMismatch()

            _state.value = UpdateState.Installing(info)
            val pending = PendingIntent.getBroadcast(
                app, id,
                Intent(app, UpdateReceiver::class.java).setPackage(app.packageName),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
            )
            // Lets [AppUpdatedReceiver] know that the coming "package replaced" is our own update.
            app.getSharedPreferences("updater", Context.MODE_PRIVATE).edit().putLong("relaunch_until", System.currentTimeMillis() + 15 * 60 * 1000L).apply()
            session.commit(pending.intentSender)
            committed = true
        } finally {
            if (!committed) session.abandon()
            session.close()
        }
    }

    /** Release notes are Markdown; the dialog shows plain text. Keeps the words, drops the syntax and GitHub's auto-generated boilerplate. */
    private fun cleanNotes(body: String): String = body.lineSequence()
        .map { it.trim() }
        .filterNot { it.startsWith("**Full Changelog**", ignoreCase = true) }
        .map { line ->
            line.replace(Regex("^#+\\s*"), "")
                .replace(Regex("^[*-]\\s+"), "• ")
                .replace("**", "")
                .replace("`", "")
        }
        .joinToString("\n")
        .replace(Regex("\n{3,}"), "\n\n")
        .trim()

    /** True when [candidate] ("2.2.10") is a higher dotted version than [installed] ("2.2.3"). */
    internal fun isNewer(candidate: String, installed: String): Boolean {
        val a = candidate.split(Regex("[^0-9]+")).filter { it.isNotEmpty() }.map { it.toLongOrNull() ?: 0L }
        val b = installed.split(Regex("[^0-9]+")).filter { it.isNotEmpty() }.map { it.toLongOrNull() ?: 0L }
        if (a.isEmpty()) return false
        for (i in 0 until maxOf(a.size, b.size)) {
            val x = a.getOrElse(i) { 0L }
            val y = b.getOrElse(i) { 0L }
            if (x != y) return x > y
        }
        return false
    }
}

/** Receives the installer's progress for the session [Updater] committed. */
class UpdateReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
            // Android wants the person to confirm; the system dialog comes from this intent.
            IntentCompat.getParcelableExtra(intent, Intent.EXTRA_INTENT, Intent::class.java)
                ?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                ?.let { context.startActivity(it) }
        } else {
            Updater.onInstallResult(status, intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE))
        }
    }
}
