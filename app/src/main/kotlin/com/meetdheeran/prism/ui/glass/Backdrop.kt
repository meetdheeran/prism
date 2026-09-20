package com.meetdheeran.prism.ui.glass

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.node.DrawModifierNode
import androidx.compose.ui.node.GlobalPositionAwareModifierNode
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.node.invalidateDraw
import androidx.compose.ui.node.requireGraphicsContext
import androidx.compose.ui.platform.InspectorInfo

/**
 * The thing glass looks through.
 *
 * Compose's graphicsLayer render effects only apply to a layer's OWN content, so "blur what is
 * behind me" has to be built by hand: the background is recorded into a [GraphicsLayer] by
 * [backdropSource], and every [liquidGlass] node re-draws that recording (offset to its own
 * position) into a second layer that carries the blur + saturation effect, clipped to its shape.
 * Same idea as the Haze library, kept in-house so it works on API 31 with our pinned Compose.
 *
 * Rules: the glass must NOT be a descendant of the source (put the background first in a Box and
 * the glass UI as a later sibling), and there is one source per state.
 */
class BackdropState {
    internal var layer: GraphicsLayer? = null
    internal var sourceOffset: Offset = Offset.Zero
    internal val nodes = LinkedHashSet<DrawModifierNode>()
    internal var frame: Long = 0L

    internal fun invalidateGlass() {
        frame++
        nodes.forEach { it.invalidateDraw() }
    }
}

@Composable
fun rememberBackdropState(): BackdropState = remember { BackdropState() }

/** Marks the composable whose pixels the glass refracts. Draws normally, and records itself. */
fun Modifier.backdropSource(state: BackdropState): Modifier = this then BackdropSourceElement(state)

private data class BackdropSourceElement(val state: BackdropState) : ModifierNodeElement<BackdropSourceNode>() {
    override fun create() = BackdropSourceNode(state)
    override fun update(node: BackdropSourceNode) { node.state = state }
    override fun InspectorInfo.inspectableProperties() { name = "backdropSource" }
}

private class BackdropSourceNode(var state: BackdropState) : Modifier.Node(), DrawModifierNode, GlobalPositionAwareModifierNode {
    private var layer: GraphicsLayer? = null

    override fun onAttach() {
        layer = requireGraphicsContext().createGraphicsLayer().also { state.layer = it }
    }

    override fun onDetach() {
        val l = layer
        if (l != null) {
            if (state.layer === l) state.layer = null
            requireGraphicsContext().releaseGraphicsLayer(l)
        }
        layer = null
    }

    override fun onGloballyPositioned(coordinates: LayoutCoordinates) {
        val p = coordinates.positionInRoot()
        if (p != state.sourceOffset) {
            state.sourceOffset = p
            state.invalidateGlass()
        }
    }

    override fun ContentDrawScope.draw() {
        val l = layer
        if (l == null) {
            drawContent()
            return
        }
        l.record { this@draw.drawContent() }
        drawLayer(l)
        // Whatever animates back here must be re-sampled by every pane of glass.
        state.invalidateGlass()
    }
}
