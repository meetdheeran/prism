package com.meetdheeran.prism.ui.glass

import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Shader
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathOperation
import androidx.compose.ui.graphics.RenderEffect
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.asComposeRenderEffect
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.addOutline
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.node.DrawModifierNode
import androidx.compose.ui.node.GlobalPositionAwareModifierNode
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.node.invalidateDraw
import androidx.compose.ui.node.requireGraphicsContext
import androidx.compose.ui.platform.InspectorInfo
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.meetdheeran.prism.ui.motion.LocalTilt
import kotlin.math.ceil
import kotlin.math.roundToInt

/**
 * How a pane of glass is made. Two presets mirror Apple's "regular" and "clear" variants.
 *
 * Ingredients, back to front: blurred + saturated backdrop; a refraction ring (the backdrop
 * drawn again, magnified, only in a band along the edge, so the content bends at the rim);
 * a tint; a soft top light; an inner shadow along the bottom; and a hairline rim whose bright
 * spot follows device tilt.
 */
data class GlassStyle(
    val blurRadius: Dp = 22.dp,
    val saturation: Float = 1.35f,
    val brightness: Float = 1.0f,
    val tint: Color = Color.White,
    val tintAlpha: Float = 0.10f,
    val highlightAlpha: Float = 0.16f,
    val rimAlpha: Float = 0.55f,
    val rimWidth: Dp = 10.dp,
    /** 0 = no lensing; 0.06 = subtle; 0.12 = obvious. Magnification of the backdrop in the rim band. */
    val refraction: Float = 0.07f,
    val innerShadowAlpha: Float = 0.18f,
    val elevation: Dp = 18.dp,
) {
    companion object {
        val Regular = GlassStyle()
        val Clear = GlassStyle(blurRadius = 10.dp, tintAlpha = 0.04f, highlightAlpha = 0.10f, refraction = 0.10f, saturation = 1.2f)
        val Dark = GlassStyle(tint = Color.Black, tintAlpha = 0.28f, brightness = 0.85f, highlightAlpha = 0.10f, refraction = 0.05f)
        /** Cheap: no refraction ring; for many small tiles. */
        val Tile = GlassStyle(blurRadius = 18.dp, refraction = 0f, elevation = 10.dp, tintAlpha = 0.12f)
        val Island = GlassStyle(blurRadius = 16.dp, tint = Color.Black, tintAlpha = 0.55f, brightness = 0.7f, highlightAlpha = 0.08f, refraction = 0f, elevation = 12.dp)
    }
}

/**
 * Turns any composable into liquid glass over [state]'s backdrop. Content is clipped to [shape].
 * Pass [tilt] (from [LocalTilt]) to make the rim highlight slide as the phone moves.
 */
fun Modifier.liquidGlass(
    state: BackdropState,
    shape: Shape = RoundedCornerShape(28.dp),
    style: GlassStyle = GlassStyle.Regular,
    tilt: Offset = Offset.Zero,
): Modifier = this
    .shadow(style.elevation, shape, clip = false, ambientColor = Color.Black.copy(alpha = 0.45f), spotColor = Color.Black.copy(alpha = 0.45f))
    .then(GlassElement(state, shape, style, tilt))
    .clip(shape)

@Composable
fun LiquidGlass(
    state: BackdropState,
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(28.dp),
    style: GlassStyle = GlassStyle.Regular,
    content: @Composable BoxScope.() -> Unit,
) {
    val tilt = LocalTilt.current
    Box(modifier.liquidGlass(state, shape, style, tilt), content = content)
}

private data class GlassElement(val state: BackdropState, val shape: Shape, val style: GlassStyle, val tilt: Offset) :
    ModifierNodeElement<GlassNode>() {
    override fun create() = GlassNode(state, shape, style, tilt)
    override fun update(node: GlassNode) {
        val stateChanged = node.state !== state
        if (stateChanged) {
            node.state.nodes.remove(node)
            node.state = state
            state.nodes.add(node)
        }
        node.shape = shape
        node.style = style
        node.tilt = tilt
        node.invalidateDraw()
    }
    override fun InspectorInfo.inspectableProperties() { name = "liquidGlass" }
}

private class GlassNode(var state: BackdropState, var shape: Shape, var style: GlassStyle, var tilt: Offset) :
    Modifier.Node(), DrawModifierNode, GlobalPositionAwareModifierNode {

    private var blurLayer: GraphicsLayer? = null
    private var ringLayer: GraphicsLayer? = null
    private var position = Offset.Zero
    private var effectKey: Triple<Float, Float, Float>? = null
    private var effect: RenderEffect? = null
    private var ringEffect: RenderEffect? = null

    override fun onAttach() {
        val gc = requireGraphicsContext()
        blurLayer = gc.createGraphicsLayer()
        ringLayer = gc.createGraphicsLayer()
        state.nodes.add(this)
    }

    override fun onDetach() {
        state.nodes.remove(this)
        val gc = requireGraphicsContext()
        blurLayer?.let { gc.releaseGraphicsLayer(it) }
        ringLayer?.let { gc.releaseGraphicsLayer(it) }
        blurLayer = null
        ringLayer = null
    }

    override fun onGloballyPositioned(coordinates: LayoutCoordinates) {
        val p = coordinates.positionInRoot()
        if (p != position) {
            position = p
            invalidateDraw()
        }
    }

    private fun effectFor(radiusPx: Float): RenderEffect? {
        if (radiusPx <= 0f) return null
        val key = Triple(radiusPx, style.saturation, style.brightness)
        if (key == effectKey && effect != null) return effect
        val blur = android.graphics.RenderEffect.createBlurEffect(radiusPx, radiusPx, Shader.TileMode.CLAMP)
        val cm = ColorMatrix().apply { setSaturation(style.saturation) }
        if (style.brightness != 1f) {
            val b = style.brightness
            cm.postConcat(ColorMatrix(floatArrayOf(b, 0f, 0f, 0f, 0f, 0f, b, 0f, 0f, 0f, 0f, 0f, b, 0f, 0f, 0f, 0f, 0f, 1f, 0f)))
        }
        effect = android.graphics.RenderEffect.createColorFilterEffect(ColorMatrixColorFilter(cm), blur).asComposeRenderEffect()
        effectKey = key
        return effect
    }

    private fun ringEffectFor(): RenderEffect {
        ringEffect?.let { return it }
        // The rim keeps detail: a light blur so the magnified band still reads as "bent" content.
        val blur = android.graphics.RenderEffect.createBlurEffect(3f, 3f, Shader.TileMode.CLAMP)
        val cm = ColorMatrix().apply { setSaturation(1.5f) }
        return android.graphics.RenderEffect.createColorFilterEffect(ColorMatrixColorFilter(cm), blur).asComposeRenderEffect().also { ringEffect = it }
    }

    override fun ContentDrawScope.draw() {
        val outline = shape.createOutline(size, layoutDirection, this)
        val path = Path().apply { addOutline(outline) }
        val src = state.layer
        val bl = blurLayer
        val rel = position - state.sourceOffset
        val w = size.width
        val h = size.height

        if (src != null && bl != null && w > 0f && h > 0f) {
            val r = style.blurRadius.toPx()
            val pad = ceil(r * 2f).toInt()
            bl.renderEffect = effectFor(r)
            bl.record(IntSize(w.roundToInt() + pad * 2, h.roundToInt() + pad * 2)) {
                translate(pad - rel.x, pad - rel.y) { drawLayer(src) }
            }
            clipPath(path) {
                translate(-pad.toFloat(), -pad.toFloat()) { drawLayer(bl) }
            }

            val rl = ringLayer
            if (style.refraction > 0f && rl != null) {
                val inset = style.rimWidth.toPx()
                if (w > inset * 2 && h > inset * 2) {
                    val innerOutline = shape.createOutline(Size(w - inset * 2, h - inset * 2), layoutDirection, this)
                    val inner = Path().apply { addOutline(innerOutline); translate(Offset(inset, inset)) }
                    val ring = Path.combine(PathOperation.Difference, path, inner)
                    rl.renderEffect = ringEffectFor()
                    rl.record(IntSize(w.roundToInt(), h.roundToInt())) {
                        scale(1f + style.refraction, pivot = Offset(w / 2f, h / 2f)) {
                            translate(-rel.x, -rel.y) { drawLayer(src) }
                        }
                    }
                    clipPath(ring) { drawLayer(rl) }
                }
            }
        } else {
            // No backdrop yet (first frame, or glass used outside a source): a plain dark sheet.
            drawPath(path, Color.Black.copy(alpha = 0.35f))
        }

        // Tint sheet.
        if (style.tintAlpha > 0f) drawPath(path, style.tint.copy(alpha = style.tintAlpha))

        // Soft light along the top, under the content so text never looks veiled.
        drawPath(
            path,
            Brush.verticalGradient(0f to Color.White.copy(alpha = style.highlightAlpha), 0.42f to Color.Transparent, endY = h),
        )

        // Inner shadow creeping up from the bottom edge: gives the sheet thickness.
        if (style.innerShadowAlpha > 0f) {
            drawPath(
                path,
                Brush.verticalGradient(0.72f to Color.Transparent, 1f to Color.Black.copy(alpha = style.innerShadowAlpha), endY = h),
            )
        }

        // Hairline rim: a bright spot that slides with tilt, dim elsewhere, so the edge catches light.
        val lx = (0.25f + 0.35f * tilt.x).coerceIn(0f, 1f)
        val ly = (0.10f + 0.30f * -tilt.y).coerceIn(0f, 1f)
        drawPath(
            path,
            Brush.linearGradient(
                0f to Color.White.copy(alpha = style.rimAlpha),
                0.45f to Color.White.copy(alpha = 0.10f),
                1f to Color.White.copy(alpha = 0.30f),
                start = Offset(w * lx, h * ly),
                end = Offset(w * (1f - lx), h * (1f - ly + 0.2f)),
            ),
            style = Stroke(width = 1.dp.toPx()),
        )

        drawContent()
    }
}
