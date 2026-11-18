package com.illiuoner.blockmsg

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SessionStateTest {
    private val start = ClockReading(elapsed = 15_000, wall = 1_000_000, boot = 17)

    @Test
    fun sameBootIgnoresWallClockChanges() {
        val started = TimeMark.at(start)
        val deadline = TimeMark.at(start, 90_000)
        val adjusted = ClockReading(elapsed = 32_000, wall = 20, boot = 17)

        assertEquals(17_000L, started.age(adjusted))
        assertEquals(73_000L, deadline.remaining(adjusted))
    }

    @Test
    fun rebootUsesWallClockInsteadOfPriorUptime() {
        val started = TimeMark.at(start)
        val deadline = TimeMark.at(start, 90_000)
        val restarted = ClockReading(elapsed = 4_000, wall = 1_060_000, boot = 18)

        assertNull(started.elapsedAt(restarted))
        assertEquals(60_000L, started.age(restarted))
        assertEquals(30_000L, deadline.remaining(restarted))
        assertEquals(34_000L, TimeMark.at(restarted, deadline.remaining(restarted)).elapsed)
    }

    @Test
    fun countdownElapsedTimeDoesNotResetWorkout() {
        val now = start.copy(elapsed = start.elapsed + 91_000)
        val active = SessionState(
            workout = TimeMark.at(start),
            timer = Countdown(seconds = 90, deadline = TimeMark.at(start, 90_000)),
        )
        val paused = active.copy(timer = active.timer.copy(deadline = null, pausedMillis = 16_500))
        val reset = active.copy(timer = Countdown(seconds = 90))

        assertEquals(0L, active.timer.remaining(now))
        assertEquals(16_500L, paused.timer.remaining(now))
        assertEquals(90_000L, reset.timer.remaining(now))
        assertEquals(91_000L, active.workoutMillis(now))
        assertEquals(active.workoutMillis(now), reset.workoutMillis(now))
    }

    @Test
    fun finishedTimerAndRepeatedTimerUseDifferentReadouts() {
        val finished = Countdown(seconds = 90, finished = true)

        assertEquals("00:00:00", timerText(finished.remaining(start), countDown = true))
        assertEquals("00:01:30", timerText(finished.copy(finished = false).remaining(start), countDown = true))
    }

    @Test
    fun readoutRoundsCountdownUpAndStopwatchDown() {
        assertEquals("00:00:01", timerText(1, countDown = true))
        assertEquals("00:00:00", timerText(999))
        assertEquals("01:01:01", timerText(3_661_000))
        assertEquals("100:00:00", timerText(360_000_000))
    }

    @Test
    fun expiredDeadlineStaysAtZeroAfterReboot() {
        val deadline = TimeMark.at(start, 90_000)
        val restarted = ClockReading(elapsed = 4_000, wall = 1_200_000, boot = 18)

        assertEquals(0L, deadline.remaining(restarted))
    }
}
