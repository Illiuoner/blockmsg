package com.illiuoner.blockmsg

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
        "Режим «Не беспокоить»",
        when (focus) {
            FocusStatus.NEEDS_ACCESS -> "Не разрешено"
            FocusStatus.RESTORE, FocusStatus.OFF, FocusStatus.INTERRUPTED -> "Проверьте в настройках"
            else -> "Разрешено"
        },
        "Подавляет звуки, баннеры и оповещения о звонках во время тренировки",
    ) { activity.openSettings(SystemPage.FOCUS) }
    PermissionRow(
        "Доступ к уведомлениям", if (music.access) "Разрешено" else "Не разрешено",
        "Нужен для управления плеером. Android даёт возможность читать уведомления других приложений. " +
            "blockmsg не сохраняет их содержимое и не отправляет данные.",
    ) { activity.openSettings(SystemPage.MUSIC) }
    if (Build.VERSION.SDK_INT >= 31) {
        PermissionRow(
            "Точные таймеры", if (device.exactAlarms) "Разрешено" else "Не разрешено",
            "Помогает воспроизвести сигнал вовремя, когда экран выключен",
        ) { activity.openSettings(SystemPage.ALARMS) }
    }
    PermissionRow(
        "Закрепление экрана", if (device.pinned) "Закреплено" else "Требуется ручная проверка",
        "В разделе безопасности найдите «Закрепление экрана». Системный жест снятия закрепления остаётся доступен.",
    ) { activity.openSettings(SystemPage.SECURITY) }

    if (device.xiaomi) {
        PermissionRow(
            "Подсветка Xiaomi", "Требуется ручная проверка",
            "В настройках найдите «Эффект уведомлений» в разделе «Активный экран и блокировка» " +
                "и выберите «Нет». Выключите «Пробуждать экран при уведомлениях». " +
                "Если есть «Вспышка при уведомлениях», выключите её тоже.",
        ) { activity.openSettings(SystemPage.GENERAL) }
        PermissionRow(
            "Энергосбережение Xiaomi", "Требуется ручная проверка",
            "Если сигнал запаздывает, откройте настройки батареи для blockmsg и выберите «Без ограничений», " +
                "если этот пункт доступен.",
        ) { activity.openSettings(SystemPage.APP) }
    }
    SecondaryAction("Проверить настройки", modifier = Modifier.fillMaxWidth(), onClick = activity::checkSettings)
}

@Composable
private fun PermissionRow(title: String, status: String, description: String, open: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        Text(status, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
        Muted(description)
        TextButton(onClick = open) { Text("Открыть настройки") }
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
        Text("Сигнал таймера", style = MaterialTheme.typography.titleMedium)
        TimerSound.entries.forEach { sound ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                RadioButton(selected = options.sound == sound,
                    onClick = { scope.launch { workouts.updateOptions { it.copy(sound = sound) } } })
                Text(sound.label)
            }
        }
        Text("Громкость сигнала ${volume.roundToInt()}%")
        Slider(value = volume, onValueChange = { volume = it }, valueRange = 0f..100f,
            onValueChangeFinished = {
                val selected = volume.roundToInt()
                scope.launch { workouts.updateOptions { it.copy(volume = selected) } }
            })
        SecondaryAction("Прослушать") {
            scope.launch { workouts.preview(options.copy(volume = volume.roundToInt())) }
        }
        OptionToggle("Вибрация при завершении", options.vibration) { value ->
            scope.launch { workouts.updateOptions { it.copy(vibration = value) } }
        }
    }
    PresetsPanel(state.presets, state.timer.seconds, workouts)
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Экран", style = MaterialTheme.typography.titleMedium)
        OptionToggle("Предлагать закрепление", options.pin) { value ->
            scope.launch { workouts.updateOptions { it.copy(pin = value) } }
        }
        OptionToggle("Не гасить экран на тренировке", options.keepScreenOn) { value ->
            scope.launch { workouts.updateOptions { it.copy(keepScreenOn = value) } }
        }
        SecondaryAction("Разрешения и диагностика", modifier = Modifier.fillMaxWidth(), onClick = onDiagnostics)
    }
    Muted("Версия $version")
}

@Composable
private fun OptionToggle(label: String, selected: Boolean, change: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(label, Modifier.weight(1f))
        Switch(checked = selected, onCheckedChange = change)
    }
}
