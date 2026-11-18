package com.illiuoner.blockmsg.en

import android.os.Build
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt
import kotlinx.coroutines.launch

@Composable
internal fun PermissionPanel(focus: FocusStatus, music: MusicState, device: DeviceStatus, activity: MainActivity) {
    PermissionRow(
        "Do Not Disturb",
        when (focus) {
            FocusStatus.NEEDS_ACCESS -> "Not allowed"
            FocusStatus.RESTORE, FocusStatus.OFF, FocusStatus.INTERRUPTED -> "Check Android settings"
            else -> "Allowed"
        },
        "Silences notification sounds, banners, and call alerts during a workout.",
    ) { activity.openSettings(SystemPage.FOCUS) }
    PermissionRow(
        "Notification access", if (music.access) "Allowed" else "Not allowed",
        "Required for media controls. This permission gives access to notifications from other apps. " +
            "blockmsg does not store their content or send any data.",
    ) { activity.openSettings(SystemPage.MUSIC) }
    if (Build.VERSION.SDK_INT >= 31) {
        PermissionRow(
            "Exact alarms", if (device.exactAlarms) "Allowed" else "Not allowed",
            "Helps the timer sound on time when the screen is off.",
        ) { activity.openSettings(SystemPage.ALARMS) }
    }
    PermissionRow(
        "App pinning", if (device.pinned) "Pinned" else "Check manually",
        "Look for App pinning in Android's security settings. You can still use Android's gesture to unpin the app.",
    ) { activity.openSettings(SystemPage.SECURITY) }

    if (device.xiaomi) {
        PermissionRow(
            "Xiaomi notification lighting", "Check manually",
            "In Settings, look for Notification effect under Always-on display & Lock screen " +
                "and select None. Turn off Wake lock screen for notifications. " +
                "If Beam for notifications is available, turn it off too.",
        ) { activity.openSettings(SystemPage.GENERAL) }
        PermissionRow(
            "Xiaomi battery settings", "Check manually",
            "If the alert is delayed, open the battery settings for blockmsg and select No restrictions, " +
                "if available.",
        ) { activity.openSettings(SystemPage.APP) }
    }
    SecondaryAction("Check settings", modifier = Modifier.fillMaxWidth(), onClick = activity::checkSettings)
}

@Composable
private fun PermissionRow(title: String, status: String, description: String, open: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        Text(status, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
        Muted(description)
        TextButton(onClick = open) { Text("Open settings") }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    }
}

@Composable
internal fun OptionsPanel(state: SessionState, workouts: WorkoutRepository, onDiagnostics: () -> Unit) {
    val context = LocalContext.current
    @Suppress("DEPRECATION")
    val version = remember(context) {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName.orEmpty()
    }
    val options = state.options
    val scope = rememberCoroutineScope()
    var volume by remember(options.volume) { mutableFloatStateOf(options.volume.toFloat()) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Timer alert", style = MaterialTheme.typography.titleMedium)
        TimerSound.entries.forEach { sound ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                RadioButton(selected = options.sound == sound,
                    onClick = { scope.launch { workouts.updateOptions { it.copy(sound = sound) } } })
                Text(sound.label)
            }
        }
        Text("Alert volume ${volume.roundToInt()}%")
        Slider(value = volume, onValueChange = { volume = it }, valueRange = 0f..100f,
            onValueChangeFinished = {
                val selected = volume.roundToInt()
                scope.launch { workouts.updateOptions { it.copy(volume = selected) } }
            })
        SecondaryAction("Preview sound") {
            scope.launch { workouts.preview(options.copy(volume = volume.roundToInt())) }
        }
        OptionToggle("Vibrate when the timer ends", options.vibration) { value ->
            scope.launch { workouts.updateOptions { it.copy(vibration = value) } }
        }
    }
    PresetsPanel(state.presets, state.timer.seconds, workouts)
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Screen", style = MaterialTheme.typography.titleMedium)
        OptionToggle("Prompt to pin the app", options.pin) { value ->
            scope.launch { workouts.updateOptions { it.copy(pin = value) } }
        }
        OptionToggle("Keep the screen on during workouts", options.keepScreenOn) { value ->
            scope.launch { workouts.updateOptions { it.copy(keepScreenOn = value) } }
        }
        SecondaryAction("Permissions and diagnostics", modifier = Modifier.fillMaxWidth(), onClick = onDiagnostics)
    }
    Muted("Version $version")
}

@Composable
private fun OptionToggle(label: String, selected: Boolean, change: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(label, Modifier.weight(1f))
        Switch(checked = selected, onCheckedChange = change)
    }
}
