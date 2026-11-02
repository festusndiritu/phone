package dev.mizzenmast.dialer

import android.Manifest
import android.app.role.RoleManager
import android.content.Context
import android.os.Build
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.SavedStateHandle

/**
 * Runtime permissions grouped by what the app does with them, so a screen asks for exactly the feature it needs
 * and nothing it doesn't (a favorite star never triggers the call-log prompt, and so on).
 */
enum class Feature(
    val permissions: List<String>,
    /** Shown when an action was waiting on this feature and it was denied. */
    val deniedNotice: String,
    /** What an action needs before it can go ahead; the rest of [permissions] only improves it. */
    val required: List<String> = permissions
) {
    Contacts(listOf(Manifest.permission.READ_CONTACTS), "Contacts permission needed"),
    EditContacts(listOf(Manifest.permission.WRITE_CONTACTS), "Contacts permission needed"),
    CallLog(listOf(Manifest.permission.READ_CALL_LOG), "Call history permission needed"),
    EditCallLog(listOf(Manifest.permission.WRITE_CALL_LOG), "Call history permission needed"),
    /** Placing a call needs CALL_PHONE; READ_PHONE_STATE adds SIM names and the SIM choice. */
    Phone(PHONE_PERMS.toList(), "Phone permission needed", required = listOf(Manifest.permission.CALL_PHONE)),
    /** Runtime permission from Android 13; nothing to ask for before that. */
    Notifications(if (Build.VERSION.SDK_INT >= 33) listOf(Manifest.permission.POST_NOTIFICATIONS) else emptyList(), "Notification permission needed");

    fun granted(ctx: Context) = permissions.all { ctx.granted(it) }
    fun ready(ctx: Context) = required.all { ctx.granted(it) }
    fun missing(ctx: Context) = permissions.filterNot { ctx.granted(it) }
}

/** Something the person asked for that had to wait for a permission. Plain data, so it can be saved. */
sealed interface PendingAction {
    data class Call(val number: String) : PendingAction
    data class Star(val contactId: Long, val starred: Boolean) : PendingAction
    data class DeleteLogs(val ids: List<Long>) : PendingAction
    data object ClearLog : PendingAction

    fun encode(): String = when (this) {
        is Call -> "call:$number"
        is Star -> "star:$contactId:${if (starred) 1 else 0}"
        is DeleteLogs -> "dellog:" + ids.joinToString(",")
        ClearLog -> "clearlog"
    }

    companion object {
        fun decode(s: String): PendingAction? {
            if (s.startsWith("call:")) return Call(s.removePrefix("call:"))
            if (s.startsWith("star:")) {
                val p = s.removePrefix("star:").split(':')
                val id = p.getOrNull(0)?.toLongOrNull() ?: return null
                return Star(id, p.getOrNull(1) == "1")
            }
            if (s.startsWith("dellog:")) {
                val ids = s.removePrefix("dellog:").split(',').mapNotNull { it.toLongOrNull() }
                return if (ids.isEmpty()) null else DeleteLogs(ids)
            }
            if (s == "clearlog") return ClearLog
            return null
        }
    }
}

/** One ask: these features, and what to do if they come through ([then] is null for a plain "Allow access" button). */
data class PermissionRequest(val features: List<Feature>, val then: PendingAction? = null) {
    fun satisfied(ctx: Context) = features.all { it.ready(ctx) }

    fun encode(): String = features.joinToString(",") { it.name } + ";" + (then?.encode() ?: "")

    companion object {
        fun decode(s: String): PermissionRequest? {
            val parts = s.split(';', limit = 2)
            val features = parts[0].split(',').mapNotNull { n -> Feature.entries.firstOrNull { it.name == n } }
            if (features.isEmpty()) return null
            return PermissionRequest(features, parts.getOrNull(1)?.takeIf { it.isNotEmpty() }?.let(PendingAction::decode))
        }
    }
}

/**
 * The permission requests waiting for the system dialog, oldest first, and whether the first one is on screen right now.
 * Android shows one dialog at a time, so requests line up instead of overlapping. Kept in the ViewModel's saved state,
 * so an answer that arrives after a rotation, or after the process was recreated, still finds the action it was for.
 */
class PermissionQueue(private val saved: SavedStateHandle) {
    private val waiting = ArrayDeque<PermissionRequest>().also { q ->
        saved.get<ArrayList<String>>(KEY)?.mapNotNullTo(q) { PermissionRequest.decode(it) }
    }
    private var onScreen: Boolean = saved.get<Boolean>(ON_SCREEN) ?: false

    /** An identical request already waiting (or on screen) is not added twice, so a double tap asks once. */
    fun add(r: PermissionRequest) {
        if (r in waiting) return
        waiting.addLast(r)
        persist()
    }

    /** The request to show now, or null when one is already on screen or none is waiting. */
    fun takeNext(): PermissionRequest? {
        if (onScreen) return null
        val r = waiting.firstOrNull() ?: return null
        onScreen = true
        persist()
        return r
    }

    /** The system answered the request that was on screen; returns it. */
    fun finish(): PermissionRequest? {
        if (!onScreen) return null
        onScreen = false
        val r = waiting.removeFirstOrNull()
        persist()
        return r
    }

    private fun persist() {
        saved[KEY] = ArrayList(waiting.map { it.encode() })
        saved[ON_SCREEN] = onScreen
    }

    private companion object {
        const val KEY = "perm_requests"
        const val ON_SCREEN = "perm_on_screen"
    }
}

/**
 * Shows the system permission dialog for the head of the [PermissionQueue], then carries out whatever was waiting on it.
 * Create it as an Activity property: the result launcher has to be registered before the Activity starts.
 */
class PermissionRequester(
    private val activity: ComponentActivity,
    private val queue: () -> PermissionQueue,
    private val onChanged: () -> Unit,
    private val perform: (PendingAction) -> Unit
) {
    private val launcher = activity.registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { onAnswer() }

    fun request(feature: Feature, then: PendingAction? = null) {
        request(listOf(feature), then)
    }

    /** Carries out [then] right away if the features are already allowed; otherwise asks first. */
    fun request(features: List<Feature>, then: PendingAction? = null) {
        val r = PermissionRequest(features, then)
        if (then != null && r.satisfied(activity)) { perform(then); return }
        if (then == null && features.all { it.granted(activity) }) { onChanged(); return }
        queue().add(r)
        pump()
    }

    private fun pump() {
        val q = queue()
        while (true) {
            val r = q.takeNext() ?: return
            val missing = r.features.flatMap { it.missing(activity) }.distinct()
            if (missing.isNotEmpty()) {
                try { launcher.launch(missing.toTypedArray()); return } catch (e: Exception) { Diag.fail("permission_launch", e) }
            }
            // Already allowed in the meantime (from Settings), or the dialog couldn't be shown: settle it now.
            q.finish()
            onChanged()
            settle(r)
        }
    }

    private fun onAnswer() {
        val r = queue().finish()
        onChanged()
        if (r != null) settle(r)
        pump()
    }

    private fun settle(r: PermissionRequest) {
        val then = r.then ?: return
        if (r.satisfied(activity)) perform(then)
        else Notices.show((r.features.firstOrNull { !it.ready(activity) } ?: r.features.first()).deniedNotice)
    }
}

fun roleHeld(ctx: Context): Boolean =
    ctx.getSystemService(RoleManager::class.java)?.isRoleHeld(RoleManager.ROLE_DIALER) == true
