package com.meetdheeran.prism.ui.motion

import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.SpringSpec
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.clickable
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback

/**
 * Apple-feeling motion. iOS springs are critically-damped-ish with a hint of overshoot;
 * nothing here bounces like Material's default "medium bouncy".
 */
object Motion {
    /** Buttons, toggles, press-scale. */
    fun <T> snappy(): SpringSpec<T> = spring(dampingRatio = 0.86f, stiffness = 520f)

    /** Panels sliding in, island expanding. */
    fun <T> panel(): SpringSpec<T> = spring(dampingRatio = 0.82f, stiffness = 300f)

    /** Slow settle for backgrounds and orbs. */
    fun <T> gentle(): SpringSpec<T> = spring(dampingRatio = 0.95f, stiffness = 120f)

    /** The one place a little bounce is right: the island pill popping. */
    fun <T> pop(): SpringSpec<T> = spring(dampingRatio = 0.62f, stiffness = 380f, visibilityThreshold = null)

    val AppleEase = CubicBezierEasing(0.25f, 0.1f, 0.25f, 1f)
    val EaseOutExpo = CubicBezierEasing(0.16f, 1f, 0.3f, 1f)
    val EaseInOutQuart = CubicBezierEasing(0.76f, 0f, 0.24f, 1f)

    fun <T> fade(ms: Int = 220) = tween<T>(ms, easing = AppleEase)

    const val StiffnessNone = Spring.StiffnessMediumLow
}

/**
 * Press feedback the iOS way: the whole control shrinks a touch and springs back, with a click.
 * The pressed scale reads on glass because the rim highlight moves with it.
 */
fun Modifier.pressable(
    enabled: Boolean = true,
    scaleDown: Float = 0.96f,
    haptic: Boolean = true,
    onClick: () -> Unit,
): Modifier = composed {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed && enabled) scaleDown else 1f, Motion.snappy(), label = "press")
    val hf = LocalHapticFeedback.current
    this
        .graphicsLayer { scaleX = scale; scaleY = scale }
        .clickable(interactionSource = interaction, indication = null, enabled = enabled) {
            if (haptic) hf.performHapticFeedback(HapticFeedbackType.LongPress)
            onClick()
        }
}

/** Device tilt in -1..1 on each axis, used to slide specular highlights across glass. */
val LocalTilt = compositionLocalOf { Offset.Zero }

@Composable
fun rememberDeviceTilt(enabled: Boolean = true): State<Offset> {
    val ctx = LocalContext.current
    val tilt = remember { mutableStateOf(Offset.Zero) }
    DisposableEffect(ctx, enabled) {
        if (!enabled) return@DisposableEffect onDispose { }
        val sm = ctx.getSystemService(SensorManager::class.java)
        val sensor = sm?.getDefaultSensor(Sensor.TYPE_GRAVITY) ?: sm?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        var fx = 0f
        var fy = 0f
        val listener = object : SensorEventListener {
            override fun onSensorChanged(e: SensorEvent) {
                // Low-pass so the highlight glides instead of jittering.
                val x = (-e.values[0] / 9.81f).coerceIn(-1f, 1f)
                val y = (e.values[1] / 9.81f).coerceIn(-1f, 1f)
                fx += (x - fx) * 0.12f
                fy += (y - fy) * 0.12f
                val next = Offset(fx, fy)
                if ((next - tilt.value).getDistance() > 0.004f) tilt.value = next
            }
            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
        }
        if (sensor != null && sm != null) sm.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_UI)
        onDispose { sm?.unregisterListener(listener) }
    }
    return tilt
}
