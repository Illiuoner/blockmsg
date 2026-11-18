package com.illiuoner.blockmsg.en

import java.util.Locale

internal data class ClockReading(val elapsed: Long, val wall: Long, val boot: Int)

internal data class TimeMark(val elapsed: Long, val wall: Long, val boot: Int) {
    fun elapsedAt(now: ClockReading): Long? = when {
        boot >= 0 && now.boot >= 0 && boot != now.boot -> null
        else -> elapsed
    }

    fun age(now: ClockReading): Long =
        (elapsedAt(now)?.let { now.elapsed - it } ?: (now.wall - wall)).coerceAtLeast(0)

    fun remaining(now: ClockReading): Long =
        (elapsedAt(now)?.let { it - now.elapsed } ?: (wall - now.wall)).coerceAtLeast(0)

    companion object {
        fun at(now: ClockReading, ahead: Long = 0) =
            TimeMark(now.elapsed + ahead, now.wall + ahead, now.boot)
    }
}

internal data class Preset(val id: String, val name: String, val seconds: Int)

internal enum class TimerSound(val label: String) {
    DOUBLE("Double beep"),
    RISE("Rising tone"),
    BELL("Bell"),
}

internal data class Options(
    val pin: Boolean = true,
    val keepScreenOn: Boolean = false,
    val volume: Int = 75,
    val sound: TimerSound = TimerSound.DOUBLE,
    val vibration: Boolean = false,
    val setupSeen: Boolean = false,
)

internal data class Countdown(
    val seconds: Int = 90,
    val deadline: TimeMark? = null,
    val pausedMillis: Long? = null,
    val finished: Boolean = false,
) {
    fun remaining(now: ClockReading): Long =
        deadline?.remaining(now) ?: pausedMillis ?: if (finished) 0 else seconds * 1000L
}

internal data class LegacyPolicy(
    val filter: Int,
    val categories: Int,
    val calls: Int,
    val messages: Int,
    val effects: Int,
)

internal data class FocusChange(
    val ruleUri: String? = null,
    val prior: LegacyPolicy? = null,
)

internal data class SessionState(
    val workout: TimeMark? = null,
    val lastWorkoutMillis: Long = 0,
    val timer: Countdown = Countdown(),
    val options: Options = Options(),
    val presets: List<Preset> = defaultPresets,
    val focus: FocusChange? = null,
    val focusEnding: Boolean = false,
) {
    fun workoutMillis(now: ClockReading) = workout?.age(now) ?: lastWorkoutMillis
}

internal const val MAX_TIMER_SECONDS = 99 * 3600 + 59 * 60 + 59

private val defaultPresets = listOf(
    Preset("30s", "30 seconds", 30),
    Preset("1m", "1 minute", 60),
    Preset("90s", "1 minute 30 seconds", 90),
    Preset("3m", "3 minutes", 180),
)

internal fun timerText(millis: Long, countDown: Boolean = false): String {
    val total = (millis.coerceAtLeast(0) + if (countDown) 999 else 0) / 1000
    return String.format(Locale.ROOT, "%02d:%02d:%02d", total / 3600, total / 60 % 60, total % 60)
}
