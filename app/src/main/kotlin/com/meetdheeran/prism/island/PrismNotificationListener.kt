package com.meetdheeran.prism.island

import android.app.Notification
import android.graphics.Bitmap
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import androidx.core.graphics.drawable.toBitmap
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.concurrent.ConcurrentHashMap

/**
 * Notification access is granted by the user for ONE reason: it unlocks MediaSessionManager
 * (what is playing) and lets the island show call/timer-style activities. This class keeps:
 *  - album art from media-style notifications (players that don't put art in the session),
 *  - a minimal list of "live" notifications for the island (ongoing calls, timers, navigation).
 * It never stores notification text beyond that list, and nothing is forwarded to any server.
 * The user did not select notification summaries or auto-replies; do not add them here.
 */
class PrismNotificationListener : NotificationListenerService() {

    override fun onListenerConnected() {
        super.onListenerConnected()
        runCatching { activeNotifications?.forEach(::observe) }
        MediaWatcher.refresh()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) = observe(sbn)

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        live.remove(sbn.key)
        _activities.value = live.values.toList()
    }

    private fun observe(sbn: StatusBarNotification) {
        val n = sbn.notification ?: return
        if (n.extras.containsKey(Notification.EXTRA_MEDIA_SESSION)) {
            val icon = n.getLargeIcon()
            val bmp = icon?.let { runCatching { it.loadDrawable(this)?.toBitmap() }.getOrNull() }
            if (bmp != null) {
                art[sbn.packageName] = bmp
                MediaWatcher.refresh()
            }
            return
        }
        // Ongoing (non-dismissable) notifications with a chronometer or call category are island material.
        val category = n.category
        val ongoing = sbn.isOngoing
        val isCall = category == Notification.CATEGORY_CALL
        val isTimer = n.extras.getBoolean(Notification.EXTRA_SHOW_CHRONOMETER, false) || category == Notification.CATEGORY_ALARM
        val isNav = category == Notification.CATEGORY_NAVIGATION
        if (ongoing && (isCall || isTimer || isNav)) {
            val title = n.extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
            val text = n.extras.getCharSequence(Notification.EXTRA_TEXT)?.toString().orEmpty()
            live[sbn.key] = LiveActivity(
                key = sbn.key,
                packageName = sbn.packageName,
                kind = when { isCall -> LiveActivity.Kind.CALL; isNav -> LiveActivity.Kind.NAVIGATION; else -> LiveActivity.Kind.TIMER },
                title = title,
                text = text,
                whenMs = n.`when`,
                chronometerBase = if (isTimer) n.`when` else 0L,
                countDown = n.extras.getBoolean(Notification.EXTRA_CHRONOMETER_COUNT_DOWN, false),
                contentIntent = n.contentIntent,
            )
            _activities.value = live.values.toList()
        }
    }

    companion object {
        private val art = ConcurrentHashMap<String, Bitmap>()
        private val live = ConcurrentHashMap<String, LiveActivity>()
        private val _activities = MutableStateFlow<List<LiveActivity>>(emptyList())
        val activities: StateFlow<List<LiveActivity>> = _activities
        fun artFor(pkg: String): Bitmap? = art[pkg]
    }
}

data class LiveActivity(
    val key: String,
    val packageName: String,
    val kind: Kind,
    val title: String,
    val text: String,
    val whenMs: Long,
    val chronometerBase: Long,
    val countDown: Boolean,
    val contentIntent: android.app.PendingIntent?,
) {
    enum class Kind { CALL, TIMER, NAVIGATION }
}
