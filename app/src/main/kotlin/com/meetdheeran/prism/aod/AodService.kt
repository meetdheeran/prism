package com.meetdheeran.prism.aod

import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioManager
import android.os.BatteryManager
import android.os.IBinder
import android.os.PowerManager
import com.meetdheeran.prism.core.AppGraph
import com.meetdheeran.prism.core.Settings
import com.meetdheeran.prism.overlay.BackgroundNotice
import com.meetdheeran.prism.overlay.OverlayHost
import com.meetdheeran.prism.shizuku.ShizukuBridge
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.time.LocalTime

/**
 * Always-on display. Android gives apps no way to draw on a screen that is really off, so this does
 * what AOD apps do: when the screen turns off, it immediately turns it back on with [AodActivity] —
 * a black, very dim page shown over the lock screen.
 *
 * Screen-off while the AOD is already showing can only mean two things, and they need opposite
 * answers:
 *  - the AOD itself put the phone to sleep (pocket, face down, night, low battery) → stay dark;
 *  - the user pressed power on the AOD → wake to the lock screen, like Samsung does.
 * [AodState.suppressed] tells them apart.
 */
class AodService : Service() {
    private val graph by lazy { AppGraph.get(this) }

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            when (i.action) {
                Intent.ACTION_SCREEN_OFF -> onScreenOff()
                // A real wake (power button, fingerprint, notification) lifts a pocket/night sleep.
                Intent.ACTION_USER_PRESENT -> AodState.suppressed = false
                Intent.ACTION_SCREEN_ON -> if (!AodState.launching) {
                    AodState.suppressed = false
                    cancelCheck() // woken before the lock engaged: no AOD this time
                }
                ACTION_CHECK -> onCheck()
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    /** elapsedRealtime after which a still-unlocked phone means "no AOD this time". */
    private var lockDeadline = 0L

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "stop") {
            stopSelf()
            return START_NOT_STICKY
        }
        BackgroundNotice.start(this)
        return START_STICKY
    }

    override fun onCreate() {
        super.onCreate()
        androidx.core.content.ContextCompat.registerReceiver(this, receiver, IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_USER_PRESENT)
            addAction(ACTION_CHECK)
        }, androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED)
    }

    override fun onDestroy() {
        cancelCheck()
        runCatching { unregisterReceiver(receiver) }
        AodState.finish.value++
        BackgroundNotice.stop(this)
        super.onDestroy()
    }

    private fun onScreenOff() {
        val wasShowing = AodState.showing
        if (AodState.suppressed) {
            // We put it to sleep on purpose; make sure nothing lingers and stay dark.
            if (wasShowing) AodState.finish.value++
            return
        }
        if (wasShowing) {
            // Power pressed on the AOD: close it and wake to the lock screen.
            AodState.finish.value++
            AodState.suppressed = true
            wakeToLockScreen()
            return
        }
        val s = runBlocking { graph.prefs.current() }
        val why = blockedReason(this, s)
        if (why != null) return
        if (!OverlayHost.canDrawOverlays(this)) return // Android 12 forbids background launches without it.
        // Never cover an unlocked phone. After a screen TIMEOUT Android waits a few seconds before
        // locking, and turning the screen back on inside that window cancels the lock — an AOD shown
        // straight away would sit on an unlocked phone and a double-tap would open it. So: show now
        // only if the lock is already up (power button); otherwise come back just after the lock
        // should have engaged. That comeback is an exact alarm, not a coroutine: OxygenOS freezes
        // background apps about a second after the screen goes off, and only a broadcast wakes them.
        val km = getSystemService(android.app.KeyguardManager::class.java)
        if (!km.isDeviceSecure || km.isKeyguardLocked) { launchAod(); return }
        val lockAfter = android.provider.Settings.Secure.getLong(contentResolver, "lock_screen_lock_after_timeout", 5_000L).coerceIn(0L, 60_000L)
        val now = android.os.SystemClock.elapsedRealtime()
        lockDeadline = now + lockAfter + 2_500L
        // First look soon (the power-button lock can land a moment after SCREEN_OFF), then again after the delay.
        scheduleCheck(now + 900L)
    }

    /** The alarm: show the AOD if the screen is still off and the phone is now locked. */
    private fun onCheck() {
        val pm = getSystemService(PowerManager::class.java)
        if (pm.isInteractive || AodState.suppressed || AodState.showing) return
        val km = getSystemService(android.app.KeyguardManager::class.java)
        if (km.isDeviceSecure && !km.isKeyguardLocked) {
            val now = android.os.SystemClock.elapsedRealtime()
            if (now < lockDeadline) scheduleCheck(minOf(lockDeadline, now + 1_500L))
            return // still unlocked at the deadline (e.g. a long lock delay): skip rather than expose the phone
        }
        val s = runBlocking { graph.prefs.current() }
        if (blockedReason(this, s) != null) return
        launchAod()
    }

    private fun launchAod() {
        AodState.launching = true
        runCatching {
            startActivity(Intent(this, AodActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION or Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS))
        }
        graph.scope.launch { kotlinx.coroutines.delay(1500); AodState.launching = false }
    }

    private fun checkIntent(): android.app.PendingIntent = android.app.PendingIntent.getBroadcast(
        this, 4201, Intent(ACTION_CHECK).setPackage(packageName),
        android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE,
    )

    private fun scheduleCheck(atElapsed: Long) {
        val am = getSystemService(android.app.AlarmManager::class.java)
        val pi = checkIntent()
        runCatching { am.setExactAndAllowWhileIdle(android.app.AlarmManager.ELAPSED_REALTIME_WAKEUP, atElapsed, pi) }
            .onFailure { runCatching { am.setAndAllowWhileIdle(android.app.AlarmManager.ELAPSED_REALTIME_WAKEUP, atElapsed, pi) } }
    }

    private fun cancelCheck() {
        lockDeadline = 0L
        runCatching { getSystemService(android.app.AlarmManager::class.java).cancel(checkIntent()) }
    }

    @Suppress("DEPRECATION")
    private fun wakeToLockScreen() {
        val pm = getSystemService(PowerManager::class.java)
        runCatching {
            pm.newWakeLock(PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP, "prism:aod-wake").acquire(1_000)
        }
    }

    companion object {
        private const val ACTION_CHECK = "com.meetdheeran.prism.aod.CHECK"

        /** Null if the AOD may show now, else a short reason. Shared with the activity's once-a-minute check. */
        fun blockedReason(ctx: Context, s: Settings): String? {
            if (!s.aodEnabled) return "off"
            val audio = ctx.getSystemService(AudioManager::class.java)
            if (audio.mode == AudioManager.MODE_IN_CALL || audio.mode == AudioManager.MODE_IN_COMMUNICATION) return "call"
            if (s.aodNightOff && inNight(s.aodNightStart, s.aodNightEnd)) return "night"
            if (s.aodLowBatteryOff) {
                val bm = ctx.getSystemService(BatteryManager::class.java)
                val pct = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
                if (pct in 0 until s.aodLowBatteryPercent && !bm.isCharging) return "battery"
            }
            return null
        }

        /** Handles windows that cross midnight (e.g. 23:00 → 07:00). */
        fun inNight(startMin: Int, endMin: Int, now: LocalTime = LocalTime.now()): Boolean {
            val m = now.hour * 60 + now.minute
            return if (startMin == endMin) false
            else if (startMin < endMin) m in startMin until endMin
            else m >= startMin || m < endMin
        }

        /** Put the screen to sleep for real. Needs Shizuku; without it the screen times out on its own. */
        suspend fun sleepScreen(): Boolean =
            ShizukuBridge.isReady() && ShizukuBridge.exec("input keyevent KEYCODE_SLEEP").isSuccess
    }
}

/** Process-wide AOD flags shared by the service and the activity. */
object AodState {
    @Volatile var showing = false
    /** Set when the AOD sleeps the screen on purpose, so the next screen-off doesn't relaunch it. */
    @Volatile var suppressed = false
    /** True for a moment while our own launch turns the screen on, so that SCREEN_ON isn't a real wake. */
    @Volatile var launching = false
    /** Bumped to ask a showing AodActivity to close. */
    val finish = MutableStateFlow(0)
}
