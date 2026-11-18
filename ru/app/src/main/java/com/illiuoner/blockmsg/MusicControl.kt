package com.illiuoner.blockmsg

import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.service.notification.NotificationListenerService
import android.view.KeyEvent
import androidx.core.app.NotificationManagerCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

internal class MusicAccessService : NotificationListenerService()

internal data class MusicState(
    val access: Boolean = false,
    val connected: Boolean = false,
    val source: String = "",
    val title: String = "",
    val artist: String = "",
    val playing: Boolean = false,
    val canToggle: Boolean = false,
    val canPrevious: Boolean = false,
    val canNext: Boolean = false,
)

internal class MusicControl(private val context: Context) {
    private val listener = ComponentName(context, MusicAccessService::class.java)
    private val manager = context.getSystemService(MediaSessionManager::class.java)
    private val handler = Handler(Looper.getMainLooper())
    private val mutableState = MutableStateFlow(MusicState())
    private var subscribed = false
    private var control: MediaController? = null

    val state = mutableState.asStateFlow()
    private val sessionsListener = MediaSessionManager.OnActiveSessionsChangedListener { refresh() }
    private val callback = object : MediaController.Callback() {
        override fun onMetadataChanged(metadata: MediaMetadata?) = publish()
        override fun onPlaybackStateChanged(state: PlaybackState?) = publish()
        override fun onSessionDestroyed() = refresh()
    }

    fun hasAccess(): Boolean = if (Build.VERSION.SDK_INT >= 27) {
        context.getSystemService(NotificationManager::class.java).isNotificationListenerAccessGranted(listener)
    } else {
        context.packageName in NotificationManagerCompat.getEnabledListenerPackages(context)
    }

    fun refresh() {
        val access = hasAccess()
        if (!access) {
            disconnect()
            return
        }

        val sessions = runCatching {
            if (!subscribed) {
                manager.addOnActiveSessionsChangedListener(sessionsListener, listener, handler)
                subscribed = true
            }
            manager.getActiveSessions(listener)
        }.getOrElse {
            disconnect()
            mutableState.value = MusicState(access = hasAccess())
            return
        }

        val selected = sessions.firstOrNull { it.packageName == YOUTUBE_MUSIC }
            ?: sessions.firstOrNull { it.playbackState?.state == PlaybackState.STATE_PLAYING }
            ?: sessions.firstOrNull()
        if (selected?.sessionToken != control?.sessionToken) {
            control?.unregisterCallback(callback)
            control = selected
            selected?.registerCallback(callback, handler)
        }
        publish()
    }

    fun disconnect() {
        if (subscribed) manager.removeOnActiveSessionsChangedListener(sessionsListener)
        subscribed = false
        control?.unregisterCallback(callback)
        control = null
        mutableState.value = MusicState(access = hasAccess())
    }

    fun previous() = command { if (state.value.canPrevious) it.skipToPrevious() }
    fun next() = command { if (state.value.canNext) it.skipToNext() }
    fun toggle() {
        val controller = control ?: return
        val playback = state.value
        if (!playback.canToggle) return
        val action = if (playback.playing) PlaybackState.ACTION_PAUSE else PlaybackState.ACTION_PLAY
        val actions = controller.playbackState?.actions ?: 0
        if (actions and action != 0L) {
            command { if (playback.playing) it.pause() else it.play() }
        } else {
            runCatching {
                controller.dispatchMediaButtonEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE))
                controller.dispatchMediaButtonEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE))
            }.onFailure { refresh() }
        }
    }

    private fun command(action: (MediaController.TransportControls) -> Unit) {
        val controller = control ?: return
        if (runCatching { action(controller.transportControls) }.isFailure) refresh()
    }

    private fun publish() {
        val selected = control
        if (selected == null) {
            mutableState.value = MusicState(access = hasAccess())
            return
        }

        val metadata = selected.metadata
        val playback = selected.playbackState
        val playing = playback?.state in listOf(PlaybackState.STATE_PLAYING, PlaybackState.STATE_BUFFERING)
        val actions = playback?.actions ?: 0
        val toggleAction = if (playing) PlaybackState.ACTION_PAUSE else PlaybackState.ACTION_PLAY
        mutableState.value = MusicState(
            access = true,
            connected = true,
            source = if (selected.packageName == YOUTUBE_MUSIC) "YouTube Music" else "Медиаплеер",
            title = metadata?.description?.title?.toString().orEmpty(),
            artist = (metadata?.getText(MediaMetadata.METADATA_KEY_ARTIST)
                ?: metadata?.description?.subtitle)?.toString().orEmpty(),
            playing = playing,
            canToggle = actions and (PlaybackState.ACTION_PLAY_PAUSE or toggleAction) != 0L,
            canPrevious = actions and PlaybackState.ACTION_SKIP_TO_PREVIOUS != 0L,
            canNext = actions and PlaybackState.ACTION_SKIP_TO_NEXT != 0L,
        )
    }

    companion object {
        private const val YOUTUBE_MUSIC = "com.google.android.apps.youtube.music"
    }
}
