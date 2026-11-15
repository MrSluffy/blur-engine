/*
 * Copyright 2026 MrSluffy
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package dev.blurengine.mrsluffy.compose

import android.annotation.SuppressLint
import android.view.View
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.node.CompositionLocalConsumerModifierNode
import androidx.compose.ui.node.DrawModifierNode
import androidx.compose.ui.node.GlobalPositionAwareModifierNode
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.node.ObserverModifierNode
import androidx.compose.ui.node.currentValueOf
import androidx.compose.ui.node.invalidateDraw
import androidx.compose.ui.node.observeReads
import androidx.compose.ui.platform.InspectorInfo
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import dev.blurengine.mrsluffy.capture.CaptureGuard
import dev.blurengine.mrsluffy.view.BlurSurfaceRenderer
import dev.blurengine.mrsluffy.view.BlurTarget
import kotlin.math.roundToInt

/**
 * Draws a blurred copy of what is behind this element, then the element's content on top.
 *
 * ```
 * Box(
 *     Modifier.blurSurface {
 *         radius = 25.dp
 *         tint(Color.White.copy(alpha = 0.15f))
 *         cornerRadius(24.dp)
 *     }
 * )
 * ```
 *
 * [block] runs during the frame and re-runs when the Compose state it reads changes, so reading
 * animated state there (`radius = animatedRadius`) animates the blur without recomposition.
 *
 * Uses the same engine as `BlurView`: the window behind the element is captured each frame and
 * blurred with RenderEffect (API 31+), GLES or software. The same limits apply: SurfaceView and
 * TextureView are not captured, nor are HARDWARE bitmaps used inside a shader. Blur surfaces drawn
 * before this one appear in its blur.
 */
public fun Modifier.blurSurface(block: BlurSurfaceScope.() -> Unit): Modifier =
    this then BlurSurfaceElement(block)

/** A [Box] with [blurSurface] applied. */
@Composable
public fun BlurContainer(
    blur: BlurSurfaceScope.() -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit,
) {
    Box(modifier = modifier.blurSurface(blur), content = content)
}

private class BlurSurfaceElement(val block: BlurSurfaceScope.() -> Unit) : ModifierNodeElement<BlurSurfaceNode>() {
    override fun create() = BlurSurfaceNode(block)

    override fun update(node: BlurSurfaceNode) {
        node.block = block
        node.applySettings()
    }

    override fun InspectorInfo.inspectableProperties() {
        name = "blurSurface"
    }

    // Lambdas are compared by identity: a new lambda re-runs update(), which re-reads the settings.
    override fun equals(other: Any?) = other is BlurSurfaceElement && other.block === block
    override fun hashCode() = block.hashCode()
}

private class BlurSurfaceNode(var block: BlurSurfaceScope.() -> Unit) :
    Modifier.Node(),
    DrawModifierNode,
    GlobalPositionAwareModifierNode,
    CompositionLocalConsumerModifierNode,
    ObserverModifierNode,
    BlurTarget {

    private val scope = BlurSurfaceScope()
    private var renderer: BlurSurfaceRenderer? = null
    private var hostView: View? = null
    private var coordinates: LayoutCoordinates? = null
    private var shape: Shape? = null
    private val clip = Path()

    private val attachListener = object : View.OnAttachStateChangeListener {
        override fun onViewAttachedToWindow(v: View) { renderer?.attach() }
        override fun onViewDetachedFromWindow(v: View) { renderer?.detach() }
    }

    // LocalView cannot change while this node stays attached: it only differs if the node moves to
    // another composition, which detaches it and calls onAttach again. So reading it here is correct.
    @SuppressLint("SuspiciousCompositionLocalModifierRead")
    override fun onAttach() {
        val view = currentValueOf(LocalView)
        hostView = view
        renderer = BlurSurfaceRenderer(view, this)
        applySettings()
        view.addOnAttachStateChangeListener(attachListener)
        if (view.isAttachedToWindow) renderer?.attach()
    }

    override fun onDetach() {
        hostView?.removeOnAttachStateChangeListener(attachListener)
        renderer?.detach()
        renderer = null
        hostView = null
        coordinates = null
    }

    override fun onObservedReadsChanged() = applySettings()

    /** Runs the settings block, observing the state it reads. */
    fun applySettings() {
        val r = renderer ?: return
        observeReads {
            scope.reset()
            scope.block()
        }
        r.config = scope.toConfig(currentValueOf(LocalDensity))
        r.source = scope.source
        shape = scope.shape
        invalidateDraw()
    }

    override fun onGloballyPositioned(coordinates: LayoutCoordinates) {
        this.coordinates = coordinates
    }

    override fun ContentDrawScope.draw() {
        // Leave this surface and its content out of every capture, including its own.
        // Surfaces below the capturing one still draw, so stacked glass shows through.
        if (CaptureGuard.shouldSkip(drawContext.canvas.nativeCanvas, this@BlurSurfaceNode)) return
        val r = renderer
        val s = shape
        if (s == null) {
            if (r != null) drawIntoCanvas { r.draw(it.nativeCanvas) }
            drawContent()
            return
        }
        clip.reset()
        when (val outline = s.createOutline(size, layoutDirection, this)) {
            is Outline.Rectangle -> clip.addRect(outline.rect)
            is Outline.Rounded -> clip.addRoundRect(outline.roundRect)
            is Outline.Generic -> clip.addPath(outline.path)
        }
        clipPath(clip) {
            if (r != null) drawIntoCanvas { r.draw(it.nativeCanvas) }
            this@draw.drawContent()
        }
    }

    // BlurTarget: the element's own bounds, in window coordinates.
    override val targetWidth: Int get() = coordinates?.takeIf { it.isAttached }?.size?.width ?: 0
    override val targetHeight: Int get() = coordinates?.takeIf { it.isAttached }?.size?.height ?: 0

    override fun getTargetLocationInWindow(out: IntArray) {
        val c = coordinates
        if (c == null || !c.isAttached) {
            out[0] = 0; out[1] = 0
            return
        }
        val p = c.positionInWindow()
        out[0] = p.x.roundToInt()
        out[1] = p.y.roundToInt()
    }

    override val isTargetShown: Boolean
        get() = isAttached && hostView?.isShown == true && coordinates?.isAttached == true

    override fun invalidateTarget() {
        if (isAttached) invalidateDraw()
    }
}
