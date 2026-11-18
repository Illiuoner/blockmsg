package com.illiuoner.blockmsg

import android.text.format.DateFormat
import androidx.activity.compose.BackHandler
import androidx.annotation.DrawableRes
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SecondaryTabRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import java.util.Date
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

private enum class Page { WORKOUT, SETUP, SETTINGS }

@Composable
internal fun WorkoutApp(
    activity: MainActivity,
    workouts: WorkoutRepository,
    music: MusicControl,
    device: StateFlow<DeviceStatus>,
) {
    val state by workouts.state.collectAsStateWithLifecycle()
    val focus by workouts.focusStatus.collectAsStateWithLifecycle()
    val issue by workouts.issue.collectAsStateWithLifecycle()
    val player by music.state.collectAsStateWithLifecycle()
    val deviceStatus by device.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val haptics = LocalHapticFeedback.current
    val pager = rememberPagerState { 2 }
    var selectedPage by rememberSaveable { mutableStateOf<Page?>(null) }
    var confirmStop by rememberSaveable { mutableStateOf(false) }
    var pinInfo by rememberSaveable { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    val clock = remember(workouts) { mutableStateOf(workouts.clock) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val deadline = state?.timer?.deadline

    LaunchedEffect(lifecycle, deadline) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (isActive) {
                val latest = workouts.clock
                clock.value = latest
                val remaining = deadline?.remaining(latest)
                if (remaining == 0L) workouts.jobs.launch { workouts.reconcile() }
                delay((remaining?.takeIf { it > 0 } ?: 1000L).coerceAtMost(
                    (1000L - latest.elapsed % 1000).coerceAtLeast(40),
                ))
            }
        }
    }

    WorkoutTheme {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            val current = state
            if (current == null) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    if (issue == null) CircularProgressIndicator(Modifier.size(32.dp))
                }
            } else {
                val page = selectedPage ?: if (current.options.setupSeen) Page.WORKOUT else Page.SETUP
                val active = current.workout != null
                BackHandler(enabled = page != Page.WORKOUT || active) {
                    if (page != Page.WORKOUT && current.options.setupSeen) {
                        selectedPage = Page.WORKOUT
                    } else if (page == Page.WORKOUT && pager.currentPage == 1) {
                        scope.launch { pager.animateScrollToPage(0) }
                    }
                }
                LaunchedEffect(active, current.options.keepScreenOn) {
                    activity.keepScreenOn(active && current.options.keepScreenOn)
                }
                Column(
                    modifier = Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    if (page == Page.WORKOUT) {
                        AppHeader(
                            clock = clock,
                            showSettings = !active,
                            onSettings = { selectedPage = Page.SETTINGS },
                        )
                        SecondaryTabRow(
                            selectedTabIndex = pager.currentPage,
                            modifier = Modifier.widthIn(max = 560.dp),
                            containerColor = MaterialTheme.colorScheme.background,
                        ) {
                            listOf("Тренировка", "Подходы").forEachIndexed { index, title ->
                                Tab(pager.currentPage == index, onClick = {
                                    scope.launch { pager.animateScrollToPage(index) }
                                }, text = { Text(title) })
                            }
                        }
                        HorizontalPager(pager, modifier = Modifier.weight(1f).fillMaxWidth()) { index ->
                            ScreenColumn {
                                if (index == 0) {
                                    WorkoutPanel(
                                        current, clock, focus, deviceStatus, saving,
                                        onStart = {
                                            scope.launch {
                                                saving = true
                                                try {
                                                    if (workouts.startWorkout()) {
                                                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                                        pinInfo = current.options.pin
                                                    }
                                                } finally { saving = false }
                                            }
                                        },
                                        onStop = {
                                            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                            confirmStop = true
                                        },
                                        onPin = { pinInfo = true },
                                        onRestore = {
                                            if (focus == FocusStatus.NEEDS_ACCESS) {
                                                activity.openSettings(SystemPage.FOCUS)
                                            } else {
                                                scope.launch {
                                                    if (current.focusEnding) workouts.reconcile()
                                                    else workouts.restoreFocus()
                                                }
                                            }
                                        },
                                        onDiagnostics = { selectedPage = Page.SETUP },
                                    )
                                } else {
                                    CountdownPanel(current, clock, deviceStatus, workouts, activity)
                                }
                                MusicPanel(player, music) { selectedPage = Page.SETUP }
                            }
                        }
                    } else {
                        val setup = page == Page.SETUP
                        SettingsHeader(
                            if (setup) "Настройка защиты" else "Настройки",
                            back = (current.options.setupSeen || !setup),
                            onBack = { selectedPage = Page.WORKOUT },
                        )
                        ScreenColumn {
                            if (setup) {
                                PermissionPanel(focus, player, deviceStatus, activity)
                                PrimaryAction("К тренировке", enabled = !saving) {
                                    scope.launch {
                                        saving = true
                                        try {
                                            workouts.updateOptions { it.copy(setupSeen = true) }
                                            if (workouts.state.value?.options?.setupSeen == true) {
                                                selectedPage = Page.WORKOUT
                                            }
                                        } finally { saving = false }
                                    }
                                }
                            } else {
                                OptionsPanel(current, workouts, onDiagnostics = { selectedPage = Page.SETUP })
                            }
                        }
                    }
                }
            }

            if (confirmStop) {
                AlertDialog(
                    onDismissRequest = { if (!saving) confirmStop = false },
                    title = { Text("Завершить тренировку?") },
                    text = { Text("Тренировка будет завершена, а режим концентрации отключён") },
                    dismissButton = {
                        TextButton(onClick = { confirmStop = false }, enabled = !saving) { Text("Продолжить") }
                    },
                    confirmButton = {
                        TextButton(enabled = !saving, onClick = {
                            scope.launch {
                                saving = true
                                try {
                                    val stopped = workouts.stopWorkout()
                                    confirmStop = false
                                    if (stopped) activity.closeWorkout()
                                    else if (workouts.state.value?.workout == null) activity.releasePin()
                                } finally { saving = false }
                            }
                        }) { Text("Завершить") }
                    },
                )
            }

            if (pinInfo) {
                AlertDialog(
                    onDismissRequest = { pinInfo = false },
                    title = { Text("Закрепить экран?") },
                    text = {
                        Text("Включите закрепление экрана в настройках безопасности Android. " +
                            "После нажатия подтвердите запрос системы. Android покажет, как снять закрепление вручную.")
                    },
                    dismissButton = { TextButton(onClick = { pinInfo = false }) { Text("Пропустить") } },
                    confirmButton = {
                        TextButton(onClick = {
                            pinInfo = false
                            activity.pinWorkout()
                        }) { Text("Закрепить") }
                    },
                )
            }

            issue?.let { message ->
                AlertDialog(
                    onDismissRequest = workouts::clearIssue,
                    title = { Text("Проверьте настройки") },
                    text = { Text(message) },
                    dismissButton = if (focus == FocusStatus.NEEDS_ACCESS || focus == FocusStatus.RESTORE) {
                        {
                            TextButton(onClick = {
                                workouts.clearIssue()
                                activity.openSettings(SystemPage.FOCUS)
                            }) { Text("Настройки Android") }
                        }
                    } else null,
                    confirmButton = {
                        TextButton(onClick = {
                            workouts.clearIssue()
                            if (state == null) activity.checkSettings()
                        }) { Text("Понятно") }
                    },
                )
            }
        }
    }
}

@Composable
private fun AppHeader(clock: State<ClockReading>, showSettings: Boolean, onSettings: () -> Unit) {
    Row(
        Modifier.widthIn(max = 560.dp).fillMaxWidth().padding(start = 24.dp, end = 16.dp, top = 12.dp, bottom = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text("blockmsg", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Medium)
            Muted("by illiuoner")
        }
        WallClock(clock)
        if (showSettings) GlyphButton(R.drawable.ic_settings, "Настройки", onClick = onSettings)
    }
}

@Composable
private fun WallClock(clock: State<ClockReading>) {
    val context = LocalContext.current
    val formatter = remember(context) { DateFormat.getTimeFormat(context) }
    Text(formatter.format(Date(clock.value.wall)), fontFamily = FontFamily.Monospace, fontSize = 15.sp)
}

@Composable
private fun SettingsHeader(title: String, back: Boolean, onBack: () -> Unit) {
    Row(
        Modifier.widthIn(max = 560.dp).fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (back) GlyphButton(R.drawable.ic_back, "Назад", onClick = onBack)
        Text(title, style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(start = 8.dp))
    }
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
}

@Composable
private fun ScreenColumn(content: @Composable () -> Unit) {
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
        Column(
            Modifier.widthIn(max = 560.dp).fillMaxWidth().verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 24.dp),
            verticalArrangement = Arrangement.spacedBy(24.dp),
        ) { content() }
    }
}

@Composable
private fun WorkoutPanel(
    state: SessionState,
    clock: State<ClockReading>,
    focus: FocusStatus,
    device: DeviceStatus,
    busy: Boolean,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onPin: () -> Unit,
    onRestore: () -> Unit,
    onDiagnostics: () -> Unit,
) {
    val active = state.workout != null
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        val status = when (focus) {
            FocusStatus.ACTIVE -> "Концентрация включена"
            FocusStatus.READY -> "Готово к тренировке"
            FocusStatus.OFF -> "Концентрация выключена"
            FocusStatus.LIMITED -> "Концентрация ограничена системой"
            FocusStatus.NEEDS_ACCESS -> "Нужен доступ к «Не беспокоить»"
            FocusStatus.INTERRUPTED -> "Концентрация прервана"
            FocusStatus.RESTORE -> "Ожидается восстановление настроек"
        }
        val tone by animateColorAsState(
            if (focus == FocusStatus.ACTIVE) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.outline,
            tween(180),
            label = "Focus indicator",
        )
        Row(
            Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Box(Modifier.size(8.dp).background(tone, CircleShape))
            Text(status, style = MaterialTheme.typography.bodyMedium)
        }

        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            WorkoutReadout(state, clock)
            Muted(if (active) "Время тренировки" else if (state.lastWorkoutMillis > 0) "Последняя тренировка" else "Секундомер")
        }
        PrimaryAction(
            if (active) "Остановить" else "Начать тренировку",
            enabled = !busy && (active || focus == FocusStatus.READY),
            onClick = if (active) onStop else onStart,
        )
        if (active && state.options.pin && !device.pinned && focus == FocusStatus.ACTIVE) {
            TextButton(onClick = onPin) { Text("Закрепить экран") }
        }
        if (!active && focus == FocusStatus.NEEDS_ACCESS) {
            TextButton(onClick = onDiagnostics) { Text("Настроить доступ") }
        }
        if (active && focus in setOf(FocusStatus.NEEDS_ACCESS, FocusStatus.INTERRUPTED, FocusStatus.OFF) ||
            focus == FocusStatus.RESTORE
        ) {
            TextButton(onClick = onRestore) {
                Text(if (focus == FocusStatus.RESTORE) "Восстановить настройки" else "Вернуть концентрацию")
            }
        }
        if (focus == FocusStatus.LIMITED) {
            Muted("Проверьте другие режимы «Не беспокоить» и настройки уведомлений Android")
        }
    }
}

@Composable
private fun WorkoutReadout(state: SessionState, clock: State<ClockReading>) {
    DigitalTime(timerText(state.workoutMillis(clock.value)))
}

@Composable
internal fun DigitalTime(value: String, highlighted: Boolean = false) {
    val color by animateColorAsState(
        if (highlighted) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onBackground,
        tween(180),
        label = "Timer state",
    )
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val fontScale = LocalDensity.current.fontScale.coerceAtLeast(1f)
        val scale = (maxWidth.value / (value.length * 0.65f * fontScale)).coerceAtMost(64f)
        Text(
            value, color = color, fontFamily = FontFamily.Monospace, fontSize = scale.sp,
            lineHeight = (scale + 6).sp, fontWeight = FontWeight.Medium, maxLines = 1,
        )
    }
}

@Composable
private fun MusicPanel(state: MusicState, music: MusicControl, onAccess: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        Text(state.source.ifEmpty { "Музыка" }, style = MaterialTheme.typography.titleMedium)
        when {
            !state.access -> {
                Muted("Разрешите доступ для управления музыкой из этого экрана")
                TextButton(onClick = onAccess) { Text("Открыть настройки") }
            }
            !state.connected -> Muted("Запустите музыку в YouTube Music или другом плеере")
            else -> Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(state.title.ifEmpty { if (state.playing) "Воспроизведение" else "Пауза" },
                        maxLines = 2, overflow = TextOverflow.Ellipsis)
                    if (state.artist.isNotEmpty()) Muted(state.artist)
                }
                GlyphButton(R.drawable.ic_previous, "Предыдущий трек", state.canPrevious, music::previous)
                GlyphButton(if (state.playing) R.drawable.ic_pause else R.drawable.ic_play,
                    if (state.playing) "Пауза" else "Воспроизвести", state.canToggle, music::toggle)
                GlyphButton(R.drawable.ic_next, "Следующий трек", state.canNext, music::next)
            }
        }
    }
}

@Composable
internal fun PrimaryAction(label: String, enabled: Boolean = true, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
        shape = ControlShape,
    ) { Text(label, style = MaterialTheme.typography.titleMedium) }
}

@Composable
internal fun SecondaryAction(label: String, modifier: Modifier = Modifier, enabled: Boolean = true, onClick: () -> Unit) {
    OutlinedButton(
        onClick = onClick, modifier = modifier.heightIn(min = 48.dp), enabled = enabled,
        shape = ControlShape,
        contentPadding = ButtonDefaults.ButtonWithIconContentPadding,
    ) { Text(label) }
}

@Composable
internal fun GlyphButton(@DrawableRes icon: Int, label: String, enabled: Boolean = true, onClick: () -> Unit) {
    IconButton(onClick = onClick, enabled = enabled) {
        Icon(painterResource(icon), contentDescription = label, modifier = Modifier.size(22.dp))
    }
}

@Composable
internal fun Muted(text: String) {
    Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
}
