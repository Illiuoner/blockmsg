package com.illiuoner.blockmsg.en

import android.app.ActivityManager
import android.app.NotificationManager
import android.content.ActivityNotFoundException
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

internal data class DeviceStatus(
    val exactAlarms: Boolean = false,
    val pinned: Boolean = false,
    val xiaomi: Boolean = false,
)

internal enum class SystemPage(val action: String) {
    FOCUS(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS),
    MUSIC(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS),
    ALARMS(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM),
    SECURITY(Settings.ACTION_SECURITY_SETTINGS),
    APP(Settings.ACTION_APPLICATION_DETAILS_SETTINGS),
    GENERAL(Settings.ACTION_SETTINGS),
}

class MainActivity : ComponentActivity() {
    private lateinit var music: MusicControl
    private val device = MutableStateFlow(DeviceStatus())
    private val systemChanges = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            workouts.refreshFocus()
            updateDevice()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        music = MusicControl(this)
        setContent {
            WorkoutApp(this, workouts, music, device)
        }
    }

    override fun onStart() {
        super.onStart()
        val filter = IntentFilter().apply {
            addAction(NotificationManager.ACTION_INTERRUPTION_FILTER_CHANGED)
            addAction(NotificationManager.ACTION_NOTIFICATION_POLICY_CHANGED)
            addAction(NotificationManager.ACTION_NOTIFICATION_POLICY_ACCESS_GRANTED_CHANGED)
            addAction(NotificationManager.ACTION_AUTOMATIC_ZEN_RULE_STATUS_CHANGED)
        }
        ContextCompat.registerReceiver(this, systemChanges, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
    }

    override fun onResume() {
        super.onResume()
        checkSettings()
    }

    override fun onPause() {
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        super.onPause()
    }

    override fun onStop() {
        unregisterReceiver(systemChanges)
        music.disconnect()
        super.onStop()
    }

    internal fun checkSettings() {
        music.refresh()
        updateDevice()
        workouts.jobs.launch { workouts.reconcile(reschedule = true) }
    }

    internal fun keepScreenOn(enabled: Boolean) {
        if (enabled) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    internal fun pinWorkout() {
        if (isPinned()) return
        runCatching { startLockTask() }
        updateDevice()
    }

    internal fun releasePin() {
        if (isPinned()) runCatching { stopLockTask() }
        updateDevice()
    }

    internal fun closeWorkout() {
        releasePin()
        finishAndRemoveTask()
    }

    internal fun openSettings(page: SystemPage) {
        if (page == SystemPage.ALARMS && Build.VERSION.SDK_INT < 31) return
        val intent = Intent(page.action)
        if (page == SystemPage.APP || page == SystemPage.ALARMS) {
            intent.data = Uri.fromParts("package", packageName, null)
        }
        try {
            startActivity(intent)
        } catch (_: ActivityNotFoundException) {
            startActivity(Intent(Settings.ACTION_SETTINGS))
        }
    }

    private fun updateDevice() {
        val manufacturer = Build.MANUFACTURER.lowercase()
        device.value = DeviceStatus(
            exactAlarms = workouts.canScheduleExactly(),
            pinned = isPinned(),
            xiaomi = manufacturer in setOf("xiaomi", "redmi", "poco"),
        )
        val state = workouts.state.value
        keepScreenOn(state?.workout != null && state.options.keepScreenOn)
    }

    private fun isPinned() =
        getSystemService(ActivityManager::class.java).lockTaskModeState == ActivityManager.LOCK_TASK_MODE_PINNED
}
