package com.illiuoner.blockmsg.en

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

@Composable
internal fun CountdownPanel(
    state: SessionState,
    clock: State<ClockReading>,
    device: DeviceStatus,
    workouts: WorkoutRepository,
    activity: MainActivity,
) {
    val scope = rememberCoroutineScope()
    val haptic = LocalHapticFeedback.current
    val timer = state.timer
    var editTime by rememberSaveable { mutableStateOf(false) }
    val running = timer.deadline != null

    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        if (state.workout != null) WorkoutTotal(state, clock)
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            CountdownReadout(timer, clock)
            Muted(when {
                timer.finished -> "Set complete"
                running -> "Counting down"
                timer.pausedMillis != null -> "Paused"
                else -> "Set timer"
            })
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SecondaryAction("Duration", Modifier.weight(1f), enabled = !running) { editTime = true }
            SecondaryAction("Reset", Modifier.weight(1f)) { scope.launch { workouts.resetTimer() } }
        }
        PrimaryAction(when {
            running -> "Pause"
            timer.pausedMillis != null -> "Resume"
            timer.finished -> "Repeat"
            else -> "Start timer"
        }) {
            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
            scope.launch { if (running) workouts.pauseTimer() else workouts.startTimer() }
        }
        if (!device.exactAlarms) {
            Muted("The alert may be delayed while the screen is off.")
            TextButton(onClick = { activity.openSettings(SystemPage.ALARMS) }) { Text("Allow exact alarms") }
        }
    }

    PresetsPanel(state.presets, timer.seconds, workouts, selectionEnabled = !running,
        onSelect = { scope.launch { workouts.selectTimer(it.seconds) } })

    if (editTime) {
        TimeDialog("Set duration", timer.seconds, null, onDismiss = { editTime = false },
            onSave = { _, seconds ->
                editTime = false
                scope.launch { workouts.selectTimer(seconds) }
            })
    }
}

@Composable
private fun CountdownReadout(timer: Countdown, clock: State<ClockReading>) {
    DigitalTime(timerText(timer.remaining(clock.value), countDown = true), highlighted = timer.finished)
}

@Composable
private fun WorkoutTotal(state: SessionState, clock: State<ClockReading>) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Muted("Workout")
        Text(timerText(state.workoutMillis(clock.value)), fontFamily = FontFamily.Monospace,
            style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
internal fun PresetsPanel(
    presets: List<Preset>,
    duration: Int,
    workouts: WorkoutRepository,
    selectionEnabled: Boolean = true,
    onSelect: ((Preset) -> Unit)? = null,
) {
    val scope = rememberCoroutineScope()
    var editing by rememberSaveable { mutableStateOf<String?>(null) }
    val selected = presets.firstOrNull { it.id == editing }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically) {
            Text("Presets", style = MaterialTheme.typography.titleMedium)
            TextButton(onClick = { editing = "" }) { Text("Add") }
        }
        if (presets.isEmpty()) Muted("Save a duration you want to use again.")
        presets.forEach { preset ->
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Row(
                    Modifier.weight(1f)
                        .clickable(enabled = onSelect != null && selectionEnabled) { onSelect?.invoke(preset) }
                        .padding(vertical = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(preset.name, modifier = Modifier.weight(1f), maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(timerText(preset.seconds * 1000L), fontFamily = FontFamily.Monospace,
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                GlyphButton(R.drawable.ic_edit, "Edit “${preset.name}”") { editing = preset.id }
            }
        }
    }
    if (editing != null) {
        TimeDialog(
            if (selected == null) "New preset" else "Edit preset",
            selected?.seconds ?: duration, selected?.name.orEmpty(),
            onDismiss = { editing = null },
            onDelete = selected?.let { preset ->
                {
                    editing = null
                    scope.launch { workouts.deletePreset(preset.id) }
                }
            },
            onSave = { name, seconds ->
                editing = null
                scope.launch { workouts.storePreset(selected?.id, name, seconds) }
            },
        )
    }
}

@Composable
private fun TimeDialog(
    title: String,
    seconds: Int,
    name: String?,
    onDismiss: () -> Unit,
    onDelete: (() -> Unit)? = null,
    onSave: (String, Int) -> Unit,
) {
    var hours by rememberSaveable { mutableStateOf((seconds / 3600).toString()) }
    var minutes by rememberSaveable { mutableStateOf((seconds / 60 % 60).toString()) }
    var secs by rememberSaveable { mutableStateOf((seconds % 60).toString()) }
    var titleValue by rememberSaveable { mutableStateOf(name.orEmpty()) }
    val min = minutes.toIntOrNull() ?: 0
    val sec = secs.toIntOrNull() ?: 0
    val total = (hours.toIntOrNull() ?: 0) * 3600 + min * 60 + sec
    val valid = min < 60 && sec < 60 && total in 1..MAX_TIMER_SECONDS &&
        (name == null || titleValue.isNotBlank())

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (name != null) {
                    OutlinedTextField(titleValue, { titleValue = it.take(48) },
                        label = { Text("Name") }, singleLine = true)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    DurationField("Hr", hours, Modifier.weight(1f)) { hours = it }
                    DurationField("Min", minutes, Modifier.weight(1f)) { minutes = it }
                    DurationField("Sec", secs, Modifier.weight(1f)) { secs = it }
                }
                if (min >= 60 || sec >= 60) Muted("Minutes and seconds must be between 0 and 59.")
                if (onDelete != null) TextButton(onClick = onDelete) { Text("Delete preset") }
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
        confirmButton = {
            TextButton(enabled = valid, onClick = { onSave(titleValue, total) }) { Text("Save") }
        },
    )
}

@Composable
private fun DurationField(label: String, value: String, modifier: Modifier, onChange: (String) -> Unit) {
    OutlinedTextField(
        value, { candidate -> onChange(candidate.filter { it in '0'..'9' }.take(2)) },
        modifier = modifier, label = { Text(label) }, singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
    )
}
