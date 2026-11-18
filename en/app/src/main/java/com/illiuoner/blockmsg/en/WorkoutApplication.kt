package com.illiuoner.blockmsg.en

import android.app.Application
import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.PowerManager
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import java.io.File
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal class WorkoutApplication : Application() {
    val workouts by lazy { WorkoutRepository(this) }
}

internal val Context.workouts: WorkoutRepository
    get() = (applicationContext as WorkoutApplication).workouts

internal class WorkoutRepository(private val context: Context) {
    val clock: ClockReading
        get() = ClockReading(SystemClock.elapsedRealtime(), System.currentTimeMillis(), boot)

    private val boot = Settings.Global.getInt(context.contentResolver, Settings.Global.BOOT_COUNT, -1)
    private val storage = StateFile(File(context.filesDir, "workout-state.json"))
    private val lock = Mutex()
    private val mutableState = MutableStateFlow<SessionState?>(null)
    private val mutableFocus = MutableStateFlow(FocusStatus.OFF)
    private val mutableIssue = MutableStateFlow<String?>(null)
    private val alarms = context.getSystemService(AlarmManager::class.java)
    private val focus = FocusController(context)
    private val signal = TimerSignal(context)

    val state = mutableState.asStateFlow()
    val focusStatus = mutableFocus.asStateFlow()
    val issue = mutableIssue.asStateFlow()
    val jobs = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun canScheduleExactly(): Boolean =
        Build.VERSION.SDK_INT < 31 || alarms.canScheduleExactAlarms()

    fun refreshFocus() {
        mutableState.value?.let { mutableFocus.value = focus.status(it) }
    }

    fun clearIssue() {
        mutableIssue.value = null
    }

    suspend fun reconcile(reschedule: Boolean = false) {
        val completion = serial<Options?>(null) { snapshot ->
            var current = snapshot
            val now = clock
            val workout = current.workout
            val recovered = workout != null && workout.elapsedAt(now) == null
            if (recovered) {
                current = save(current.copy(
                    workout = TimeMark.at(now, -checkNotNull(workout).age(now)),
                    focusEnding = current.focus != null,
                ))
            }
            if (current.focusEnding) {
                val restored = current.focus?.let(focus::disable) ?: true
                if (restored) current = save(current.copy(focus = null, focusEnding = false))
            }

            val deadline = current.timer.deadline
            if (deadline == null) {
                if (reschedule) cancelAlarm()
                return@serial null
            }
            val remaining = deadline.remaining(now)
            val rebootedTimer = deadline.elapsedAt(now) == null
            if (remaining == 0L) {
                save(current.copy(timer = current.timer.copy(deadline = null, pausedMillis = null, finished = true)))
                cancelAlarm()
                return@serial current.options.takeUnless { rebootedTimer }
            }
            if (rebootedTimer) {
                current = save(current.copy(timer = current.timer.copy(deadline = TimeMark.at(now, remaining))))
            }
            if (reschedule || rebootedTimer) current.timer.deadline?.let(::schedule)
            null
        }
        completion?.let { signal.play(it) }
    }

    suspend fun startWorkout(): Boolean = serial(false) { current ->
        if (current.workout != null || current.focusEnding) return@serial false
        enableFocus(current, TimeMark.at(clock))
    }

    suspend fun restoreFocus(): Boolean = serial(false) { current ->
        val start = current.workout ?: return@serial false
        if (current.focus != null) {
            if (!focus.disable(current.focus)) return@serial false
            save(current.copy(focus = null, focusEnding = false))
        }
        enableFocus(checkNotNull(mutableState.value), start)
    }

    private fun enableFocus(current: SessionState, start: TimeMark): Boolean {
        if (!focus.hasAccess()) {
            mutableIssue.value = "Allow this app to control Do Not Disturb."
            return false
        }
        if (focus.legacyModeBlocksMedia()) {
            mutableIssue.value = "Your current Do Not Disturb mode blocks music. Change it before starting a workout."
            return false
        }

        val change = focus.prepare()
        save(current.copy(focus = change, focusEnding = true))
        return try {
            focus.enable(change)
            save(current.copy(workout = start, focus = change, focusEnding = false))
            true
        } catch (error: Exception) {
            Log.w("Workout", "Could not activate focus", error)
            if (focus.disable(change)) save(current.copy(focus = null, focusEnding = false))
            mutableIssue.value = "Android could not activate workout focus. Check your system settings."
            false
        }
    }

    suspend fun stopWorkout(): Boolean = serial(false) { current ->
        val ending = save(current.copy(
            workout = null,
            lastWorkoutMillis = current.workoutMillis(clock),
            timer = current.timer.copy(deadline = null, pausedMillis = null, finished = false),
            focusEnding = current.focus != null,
        ))
        cancelAlarm()
        if (ending.focus?.let(focus::disable) == false) {
            mutableIssue.value = "Turn off the Workout rule in Android settings or grant Do Not Disturb access to restore your settings."
            return@serial false
        }
        save(ending.copy(focus = null, focusEnding = false))
        true
    }

    suspend fun startTimer() = serial(Unit) { current ->
        val timer = current.timer
        if (timer.deadline != null) return@serial
        val duration = timer.pausedMillis ?: (timer.seconds * 1000L)
        val deadline = TimeMark.at(clock, duration.coerceAtLeast(1))
        save(current.copy(timer = timer.copy(deadline = deadline, pausedMillis = null, finished = false)))
        schedule(deadline)
    }

    suspend fun pauseTimer() = serial(Unit) { current ->
        val deadline = current.timer.deadline ?: return@serial
        val remaining = deadline.remaining(clock)
        if (remaining == 0L) return@serial
        save(current.copy(timer = current.timer.copy(deadline = null, pausedMillis = remaining)))
        cancelAlarm()
    }

    suspend fun selectTimer(seconds: Int) = serial(Unit) { current ->
        save(current.copy(timer = Countdown(seconds.coerceIn(1, MAX_TIMER_SECONDS))))
        cancelAlarm()
    }

    suspend fun resetTimer() = serial(Unit) { current ->
        save(current.copy(timer = Countdown(current.timer.seconds)))
        cancelAlarm()
    }

    suspend fun updateOptions(transform: (Options) -> Options) = serial(Unit) { current ->
        save(current.copy(options = transform(current.options)))
    }

    suspend fun storePreset(id: String?, name: String, seconds: Int) = serial(Unit) { current ->
        val replacement = Preset(id ?: UUID.randomUUID().toString(), name.trim().take(48),
            seconds.coerceIn(1, MAX_TIMER_SECONDS))
        val entries = if (id == null) current.presets + replacement else
            current.presets.map { if (it.id == id) replacement else it }
        save(current.copy(presets = entries))
    }

    suspend fun deletePreset(id: String) = serial(Unit) { current ->
        save(current.copy(presets = current.presets.filterNot { it.id == id }))
    }

    suspend fun preview(options: Options) = signal.play(options, preview = true)

    private suspend fun <T> serial(fallback: T, action: (SessionState) -> T): T =
        kotlinx.coroutines.withContext(Dispatchers.IO) {
            lock.withLock {
                try {
                    val current = mutableState.value ?: storage.read().also { mutableState.value = it }
                    action(current)
                } catch (error: Exception) {
                    if (error is kotlinx.coroutines.CancellationException) throw error
                    Log.e("Workout", "Workout operation failed", error)
                    mutableIssue.value = "Could not save your workout changes. Try again."
                    fallback
                } finally {
                    refreshFocus()
                }
            }
        }

    private fun save(state: SessionState): SessionState {
        storage.write(state)
        mutableState.value = state
        return state
    }

    private fun alarmIntent() = PendingIntent.getBroadcast(
        context, 0, Intent(context, TimerReceiver::class.java).setAction(TimerReceiver.TIMER_ELAPSED),
        PendingIntent.FLAG_IMMUTABLE,
    )

    private fun cancelAlarm() = alarms.cancel(alarmIntent())

    private fun schedule(deadline: TimeMark) {
        val trigger = deadline.elapsedAt(clock) ?: return
        val pending = alarmIntent()
        if (canScheduleExactly()) {
            try {
                alarms.setExactAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, trigger, pending)
                return
            } catch (_: SecurityException) {
                // Access can be revoked between the permission check and scheduling.
            }
        }
        alarms.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, trigger, pending)
    }
}

internal class TimerReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action !in setOf(TIMER_ELAPSED, Intent.ACTION_BOOT_COMPLETED,
                AlarmManager.ACTION_SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED)) return
        val pending = goAsync()
        val wakeLock = context.getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "blockmsg:timer")
        wakeLock.acquire(8_000)
        context.workouts.jobs.launch {
            try {
                context.workouts.reconcile(reschedule = true)
            } finally {
                if (wakeLock.isHeld) wakeLock.release()
                pending.finish()
            }
        }
    }

    companion object {
        const val TIMER_ELAPSED = "com.illiuoner.blockmsg.en.TIMER_ELAPSED"
    }
}
