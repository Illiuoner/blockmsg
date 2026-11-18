package com.illiuoner.blockmsg

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

internal class TimerSignal(private val context: Context) {
    private val lock = Mutex()

    suspend fun play(options: Options, preview: Boolean = false) = withContext(Dispatchers.Main) {
        lock.withLock {
            if (options.vibration && !preview) vibrate()
            if (options.volume == 0) return@withLock
            val resource = when (options.sound) {
                TimerSound.DOUBLE -> R.raw.timer_double
                TimerSound.RISE -> R.raw.timer_rise
                TimerSound.BELL -> R.raw.timer_bell
            }
            val attributes = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()
            // Use the current media route and mix with music without taking its audio focus.
            val player = runCatching {
                MediaPlayer.create(context, resource, attributes, AudioManager.AUDIO_SESSION_ID_GENERATE)
            }.getOrNull()
            if (player == null) {
                Log.w("Workout", "Could not create timer audio player")
                return@withLock
            }
            try {
                val gain = options.volume / 100f
                player.setVolume(gain, gain)
                player.start()
                delay(player.duration.toLong().coerceIn(0, 2500) + 80)
            } finally {
                player.release()
            }
        }
    }

    private fun vibrate() {
        @Suppress("DEPRECATION")
        val vibrator = if (Build.VERSION.SDK_INT >= 31) {
            context.getSystemService(VibratorManager::class.java).defaultVibrator
        } else {
            context.getSystemService(Vibrator::class.java)
        }
        if (vibrator.hasVibrator()) {
            vibrator.vibrate(VibrationEffect.createOneShot(240, VibrationEffect.DEFAULT_AMPLITUDE))
        }
    }
}
