package dev.mizzenmast.dialer

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog

/**
 * One dialog for the whole update flow: what's new → downloading → confirm in the system dialog.
 * Closing it never cancels a download; "Cancel download" does.
 */
@Composable
fun UpdateDialog(
    state: UpdateState,
    currentVersion: String,
    onDismiss: () -> Unit,
    onUpdate: (UpdateInfo) -> Unit,
    onCancel: () -> Unit,
    onOpenInstallSettings: () -> Unit,
    onRetryCheck: () -> Unit
) {
    val c = LocalColors.current
    val info = when (state) {
        is UpdateState.Available -> state.info
        is UpdateState.Downloading -> state.info
        is UpdateState.Installing -> state.info
        is UpdateState.NeedsPermission -> state.info
        is UpdateState.Failed -> state.info
        else -> null
    }
    val title = when (state) {
        is UpdateState.Downloading -> "Downloading update"
        is UpdateState.Installing -> "Ready to install"
        is UpdateState.NeedsPermission -> "Allow installing updates"
        is UpdateState.Failed -> "Update didn't finish"
        else -> "Update available"
    }

    Dialog(onDismissRequest = onDismiss) {
        Column(Modifier.clip(RoundedCornerShape(24.dp)).background(c.surface).padding(24.dp)) {
            Text(title, color = c.ink, fontSize = 20.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.semantics { heading() })
            Spacer(Modifier.height(8.dp))

            if (info != null && (state is UpdateState.Available || state is UpdateState.Downloading || state is UpdateState.Installing)) {
                val size = if (info.size > 0) " · ${"%.1f".format(info.size / 1_048_576.0)} MB" else ""
                Text("Version $currentVersion → ${info.version}$size", color = c.inkDim, fontSize = 13.sp)
            }

            when (state) {
                is UpdateState.Available -> {
                    if (info != null && info.notes.isNotBlank()) {
                        Spacer(Modifier.height(16.dp))
                        Text("What's new", color = c.ink, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                        Spacer(Modifier.height(6.dp))
                        Text(
                            info.notes, color = c.inkDim, fontSize = 14.sp,
                            modifier = Modifier.heightIn(max = 220.dp).verticalScroll(rememberScrollState())
                        )
                    }
                    Spacer(Modifier.height(12.dp))
                    Text("Android will ask you to confirm before it installs.", color = c.inkDim, fontSize = 13.sp)
                }
                is UpdateState.Downloading -> {
                    Spacer(Modifier.height(20.dp))
                    val pct = if (state.progress >= 0f) "${(state.progress * 100).toInt()}%" else "Downloading"
                    if (state.progress >= 0f) {
                        LinearProgressIndicator(
                            progress = { state.progress },
                            modifier = Modifier.fillMaxWidth().semantics { stateDescription = pct },
                            color = c.accent, trackColor = c.line
                        )
                    } else {
                        LinearProgressIndicator(Modifier.fillMaxWidth(), color = c.accent, trackColor = c.line)
                    }
                    Spacer(Modifier.height(8.dp))
                    Text(pct, color = c.inkDim, fontSize = 13.sp)
                }
                is UpdateState.Installing -> {
                    Spacer(Modifier.height(16.dp))
                    Text("Confirm in the Android prompt to finish. The app restarts when it's done.", color = c.inkDim, fontSize = 14.sp)
                }
                is UpdateState.NeedsPermission -> Text(
                    "Android needs your OK once before this app can install its own updates. Open settings, turn on \"Allow from this source\", then come back and press Update.",
                    color = c.inkDim, fontSize = 14.sp
                )
                is UpdateState.Failed -> Text(state.message, color = c.inkDim, fontSize = 14.sp)
                else -> Unit
            }

            Spacer(Modifier.height(20.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                when (state) {
                    is UpdateState.Available -> {
                        DialogButton("Later", onDismiss)
                        DialogButton("Update", { onUpdate(state.info) })
                    }
                    is UpdateState.Downloading -> {
                        DialogButton("Cancel download", onCancel, danger = true)
                        DialogButton("Hide", onDismiss)
                    }
                    is UpdateState.Installing -> DialogButton("Close", onDismiss)
                    is UpdateState.NeedsPermission -> {
                        DialogButton("Not now", onDismiss)
                        DialogButton("Open settings", onOpenInstallSettings)
                    }
                    is UpdateState.Failed -> {
                        DialogButton("Close", onDismiss)
                        DialogButton("Try again", { if (state.info != null) onUpdate(state.info) else onRetryCheck() })
                    }
                    else -> DialogButton("Close", onDismiss)
                }
            }
        }
    }
}
