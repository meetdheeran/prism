package com.meetdheeran.prism.aod

import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.Color
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import com.meetdheeran.prism.core.AppGraph
import com.meetdheeran.prism.ui.theme.PrismTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch

/**
 * The always-on page. Shown over the lock screen, screen held on at minimum brightness, system bars
 * hidden. It goes fully dark (and asks Shizuku to sleep the screen) when the phone is in a pocket
 * or face down, or when night / low battery begins while it's up. Double-tap closes it to the
 * lock screen.
 */
class AodActivity : ComponentActivity(), SensorEventListener {
    private val graph by lazy { AppGraph.get(this) }
    private lateinit var sensors: SensorManager
    private var covered = false
    private var faceDown = false
    private var dark = false
    private var pocketOff = true

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AodState.showing = true
        setShowWhenLocked(true)
        setTurnScreenOn(true)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.attributes = window.attributes.apply { screenBrightness = 0.01f }
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        sensors = getSystemService(SensorManager::class.java)

        setContent {
            val settings by graph.prefs.settings.collectAsState(initial = com.meetdheeran.prism.core.Settings())
            // Brightness is a user setting (1–30 %); the default 2 % is about what OLED AODs use.
            LaunchedEffect(settings.aodBrightness) {
                window.attributes = window.attributes.apply { screenBrightness = settings.aodBrightness.coerceIn(1, 30) / 100f }
            }
            PrismTheme {
                AodScreen(settings, onDismiss = { AodState.suppressed = false; finish() })
            }
        }

        lifecycleScope.launch { AodState.finish.drop(1).collect { finish() } }
        lifecycleScope.launch {
            pocketOff = graph.prefs.current().aodPocketOff
            // Re-check night and battery once a minute while showing.
            while (true) {
                delay(60_000)
                if (AodService.blockedReason(this@AodActivity, graph.prefs.current()) != null) goDark()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        sensors.getDefaultSensor(Sensor.TYPE_PROXIMITY)?.let { sensors.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL) }
        sensors.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)?.let { sensors.registerListener(this, it, SensorManager.SENSOR_DELAY_UI) }
    }

    override fun onPause() {
        sensors.unregisterListener(this)
        super.onPause()
    }

    override fun onDestroy() {
        AodState.showing = false
        super.onDestroy()
    }

    override fun onSensorChanged(e: SensorEvent) {
        when (e.sensor.type) {
            Sensor.TYPE_PROXIMITY -> covered = e.values[0] < e.sensor.maximumRange.coerceAtMost(5f)
            // z strongly negative = screen facing the table.
            Sensor.TYPE_ACCELEROMETER -> faceDown = e.values[2] < -8.5f
        }
        if (pocketOff && (covered || faceDown)) goDark()
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    /** Blank the page, stop holding the screen on, and ask Shizuku to sleep it now. */
    private fun goDark() {
        if (dark) return
        dark = true
        AodState.suppressed = true
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContent { Box(Modifier.fillMaxSize().background(Color.Black)) }
        // Without Shizuku this can't sleep the screen; it stays black until the normal timeout.
        lifecycleScope.launch { AodService.sleepScreen() }
    }
}
