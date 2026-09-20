package com.meetdheeran.prism.island

import android.content.ComponentName
import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.SystemClock
import androidx.core.app.NotificationManagerCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * What is playing right now, from whichever app owns the media session. Never talks to
 * Spotify/YouTube Music directly; it asks the system, so every player looks identical.
 * Requires Notification access (the listener's existence unlocks MediaSessionManager).
 */
data class NowPlaying(
    val title: String,
    val artist: String,
    val album: String,
    val art: Bitmap?,
    val isPlaying: Boolean,
    val positionMs: Long,
    val durationMs: Long,
    val positionAtMs: Long,
    val speed: Float,
    val packageName: String,
) {
    fun livePosition(now: Long = SystemClock.elapsedRealtime()): Long {
        if (!isPlaying) return positionMs.coerceAtLeast(0)
        val p = positionMs + ((now - positionAtMs) * speed).toLong()
        return if (durationMs > 0) p.coerceIn(0, durationMs) else p.coerceAtLeast(0)
    }
}

object MediaWatcher {
    private val _now = MutableStateFlow<NowPlaying?>(null)
    val now: StateFlow<NowPlaying?> = _now

    private var manager: MediaSessionManager? = null
    private var component: ComponentName? = null
    private var controller: MediaController? = null
    private var sessionsListenerRegistered = false

    private val controllerCallback = object : MediaController.Callback() {
        override fun onMetadataChanged(metadata: MediaMetadata?) = publish()
        override fun onPlaybackStateChanged(state: PlaybackState?) = publish()
        override fun onSessionDestroyed() { detach(); refresh() }
    }

    private val sessionsListener = MediaSessionManager.OnActiveSessionsChangedListener { list ->
        pick(list ?: emptyList())
    }

    fun hasAccess(ctx: Context): Boolean =
        NotificationManagerCompat.getEnabledListenerPackages(ctx).contains(ctx.packageName)

    fun start(ctx: Context) {
        if (manager == null) {
            manager = ctx.applicationContext.getSystemService(MediaSessionManager::class.java)
            component = ComponentName(ctx.applicationContext, PrismNotificationListener::class.java)
        }
        refresh()
    }

    fun refresh() {
        val m = manager ?: return
        val c = component ?: return
        try {
            if (!sessionsListenerRegistered) {
                m.addOnActiveSessionsChangedListener(sessionsListener, c)
                sessionsListenerRegistered = true
            }
            pick(m.getActiveSessions(c))
        } catch (e: SecurityException) {
            _now.value = null
        }
    }

    private fun pick(list: List<MediaController>) {
        val best = list.firstOrNull { it.playbackState?.state == PlaybackState.STATE_PLAYING }
            ?: list.firstOrNull { it.metadata != null }
        if (best != null && best.sessionToken == controller?.sessionToken) {
            publish()
            return
        }
        detach()
        controller = best?.also { it.registerCallback(controllerCallback) }
        publish()
    }

    private fun detach() {
        controller?.unregisterCallback(controllerCallback)
        controller = null
    }

    fun toggle() {
        val c = controller?.transportControls ?: return
        if (_now.value?.isPlaying == true) c.pause() else c.play()
    }

    fun play() { controller?.transportControls?.play() }
    fun pause() { controller?.transportControls?.pause() }
    fun next() { controller?.transportControls?.skipToNext() }
    fun previous() { controller?.transportControls?.skipToPrevious() }
    fun seekTo(ms: Long) { controller?.transportControls?.seekTo(ms) }

    private fun publish() {
        val c = controller
        val meta = c?.metadata
        if (c == null || meta == null) { _now.value = null; return }
        val state = c.playbackState

        val title = meta.getString(MediaMetadata.METADATA_KEY_TITLE).orEmpty()
        val artist = meta.getString(MediaMetadata.METADATA_KEY_ARTIST)
            ?: meta.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST).orEmpty()
        val album = meta.getString(MediaMetadata.METADATA_KEY_ALBUM).orEmpty()
        val art = meta.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
            ?: meta.getBitmap(MediaMetadata.METADATA_KEY_ART)
            ?: PrismNotificationListener.artFor(c.packageName)

        if (title.isBlank() && artist.isBlank()) { _now.value = null; return }

        _now.value = NowPlaying(
            title = title,
            artist = artist,
            album = album,
            art = art,
            isPlaying = state?.state == PlaybackState.STATE_PLAYING,
            positionMs = state?.position ?: 0L,
            durationMs = meta.getLong(MediaMetadata.METADATA_KEY_DURATION),
            positionAtMs = state?.lastPositionUpdateTime ?: SystemClock.elapsedRealtime(),
            speed = state?.playbackSpeed?.takeIf { it > 0f } ?: 1f,
            packageName = c.packageName,
        )
    }
}
