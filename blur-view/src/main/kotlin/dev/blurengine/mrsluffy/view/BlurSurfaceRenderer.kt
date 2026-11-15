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

package dev.blurengine.mrsluffy.view

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RenderEffect
import android.graphics.RenderNode
import android.os.Build
import android.os.SystemClock
import android.util.Log
import android.view.View
import android.view.ViewTreeObserver
import android.view.animation.AnimationUtils
import dev.blurengine.mrsluffy.capture.ReusableBitmap
import dev.blurengine.mrsluffy.capture.ViewCapture
import dev.blurengine.mrsluffy.core.BlurAlgorithm
import dev.blurengine.mrsluffy.core.BlurBackend
import dev.blurengine.mrsluffy.core.BlurBackendException
import dev.blurengine.mrsluffy.core.BlurConfig
import dev.blurengine.mrsluffy.platform.BitmapImage
import dev.blurengine.mrsluffy.platform.BlurRenderEffects
import dev.blurengine.mrsluffy.platform.DeviceCapabilitiesDetector
import dev.blurengine.mrsluffy.platform.GlassRenderEffects
import dev.blurengine.mrsluffy.platform.GlesBlurBackend
import dev.blurengine.mrsluffy.platform.SoftwareBlurBackend
import dev.blurengine.mrsluffy.render.BlurBackendSelector

/** Where a blur surface is and how to redraw it. Implemented by [BlurView] and the Compose modifier. */
public interface BlurTarget {
    /** Size in pixels. */
    public val targetWidth: Int
    public val targetHeight: Int

    /** Writes the top-left position in window coordinates into [out] (x, y). */
    public fun getTargetLocationInWindow(out: IntArray)

    /** False when the surface is not on screen; updates are skipped. */
    public val isTargetShown: Boolean

    /** Requests a redraw of the surface, e.g. after a backend finished warming up. */
    public fun invalidateTarget()
}

/**
 * Background-blur engine for one surface: captures what is behind [target], blurs it, and keeps
 * the result in a [RenderNode] that the target draws with [draw]. Runs on the main thread, once
 * per frame, from a pre-draw listener on [host]'s window.
 *
 * Render paths:
 * - RenderEffect (API 31+, hardware-accelerated, Gaussian or AUTO): the capture is drawn into a
 *   node whose RenderEffect blurs it on the GPU. No readback.
 * - Bitmap: the GLES backend (or software, for BOX or when GLES fails) blurs the capture into a
 *   second bitmap, which is drawn into the node. Backends are created on a background thread.
 *
 * Effects on top of the blur (see [BlurConfig.gradient] and [BlurConfig.glass]):
 * - Progressive blur: alpha masks over the blur, with a second, half-strength level on the
 *   RenderEffect path for a smooth ramp.
 * - Liquid glass: refraction as a GPU shader chained after the blur (API 33+, any backend), and
 *   Canvas lighting on every API level.
 * - Stacking: surfaces below this one appear in its capture with their last frame
 *   (see [dev.blurengine.mrsluffy.capture.CaptureGuard]).
 *
 * The node is re-recorded during pre-draw, inside the frame that is about to be drawn, and the
 * target is not invalidated from there, so updating the blur does not schedule extra frames.
 *
 * The target's draw must do nothing while
 * `CaptureGuard.shouldSkip(canvas, target)` is true, or the surface would blur its own output.
 *
 * @param host any attached view in the target's window; used for the window's pre-draw events,
 *   hardware-acceleration state, and as the default capture source (its root view).
 */
public class BlurSurfaceRenderer(
    private val host: View,
    private val target: BlurTarget,
) : ViewTreeObserver.OnPreDrawListener {
    public var config: BlurConfig = BlurConfig()
        set(value) {
            if (value == field) return
            val pathChanged = value.algorithm != field.algorithm
            field = value
            effectDirty = true
            backendDirty = true
            // Get a bitmap backend ready before it is needed, even while the radius is still 0.
            if (pathChanged && observer != null && !useRenderEffect(value)) warmUp(value)
        }

    /** What to blur; null means [host]'s root view (the whole window). Must be in the same window. */
    public var source: View? = null

    /** Which path produced the current frame: "render-effect", "gles", "software", or null. */
    public var activePath: String? = null
        private set

    /** Main-thread time of the last capture + blur, for diagnostics. */
    public var lastUpdateMillis: Float = 0f
        private set

    /**
     * True when the last update skipped capture because the blur was a no-op. Not set while a
     * backend is still warming up: then the tint is drawn alone, but capture wasn't skipped by choice.
     */
    public val skippedCapture: Boolean get() = skippedAsNoOp

    public val isAttached: Boolean get() = observer != null

    /** True when the render path for [config] can draw right away, with no backend still warming up. */
    internal val isReady: Boolean get() = useRenderEffect(config) || (backend != null && !warming)

    /**
     * True when the last frame shows [config] the way it will stay: drawn by the path [config]
     * calls for, or skipped because [config] is a no-op. False while a backend warms up, and in
     * the frame after the path changed. Tests wait on this instead of sleeping.
     */
    internal val isSettled: Boolean
        get() {
            if (isNoOp(config)) return skippedAsNoOp
            if (!isReady || !hasContent) return false
            return activePath == if (useRenderEffect(config)) PATH_RENDER_EFFECT else backend?.id
        }

    /** True when the last frame applied glass refraction (API 33+ and a style with refraction). */
    internal val refractionActive: Boolean get() = refracting

    private val capture = ViewCapture()

    /** Full-strength blur of the capture, at capture resolution; carries the RenderEffect. */
    private val node = RenderNode("BlurContent")

    /** Half-strength blur, used only by progressive blur on the RenderEffect path. */
    private val midNode = RenderNode("BlurContentMid")

    /** What callers draw: the blur levels scaled into place and clipped, plus lighting. Re-recorded each update. */
    private val surface = RenderNode("BlurSurface")
    private val filterPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val drawRect = Rect()
    private val location = IntArray(2)
    private val lighting = GlassLighting()
    private val masks = GradientMasks()
    private var glassEffects: Any? = null // GlassRenderEffects on API 33+, kept untyped for older APIs

    private var observer: ViewTreeObserver? = null

    /** Surfaces drawn below this one in its last capture; they update first each frame. */
    private val below = ArrayList<BlurSurfaceRenderer>(2)
    private var lastFrame = Long.MIN_VALUE
    private var hasContent = false
    private var tintOnly = false

    /** The last update skipped capture because [config] was a no-op (see [isNoOp]). */
    private var skippedAsNoOp = false
    private var refracting = false
    private var contentWidth = 0
    private var contentHeight = 0
    private var regionLeft = 0
    private var regionTop = 0
    private var regionWidth = 0
    private var regionHeight = 0

    private var effect: RenderEffect? = null
    private var midEffect: RenderEffect? = null
    private var effectDirty = true

    /** True when the current [effect] includes refraction. */
    private var effectsRefract = false

    /** Capture geometry the effects were built for; refraction depends on it. */
    private var effectW = -1
    private var effectH = -1
    private var effectRegion = IntArray(4)

    private var backend: BlurBackend? = null
    private var backendDirty = true
    private var glesFailed = false
    private var blurred: Bitmap? = null
    private var captureImage: BitmapImage? = null
    private var blurredImage: BitmapImage? = null
    private var loggedCaptureFailure = false

    /** Software-frame color filter for the RenderEffect path; rebuilt when the config changes. */
    private var snapshotFilterFor: BlurConfig? = null
    private val snapshotPaint = Paint(Paint.FILTER_BITMAP_FLAG)

    private val capabilities by lazy { DeviceCapabilitiesDetector.detect(host.context) }

    /** Bumped on release, so a warm-up that finishes late is discarded. */
    private var warmGeneration = 0
    private var warming = false

    /** Starts following [host]'s window. Call when the target is attached. */
    public fun attach() {
        if (observer != null) return
        val vto = host.viewTreeObserver
        vto.addOnPreDrawListener(this)
        observer = vto
        REGISTRY[target] = this
        // Create the bitmap-path backend early, off the main thread: EGL setup and shader
        // compilation take 100-400 ms and would otherwise stall the first blurred frame,
        // which is often the first frame of an animation.
        if (!useRenderEffect(config)) warmUp(config)
    }

    /** Stops following the window and releases all resources. */
    public fun detach() {
        observer?.takeIf { it.isAlive }?.removeOnPreDrawListener(this)
        observer = null
        REGISTRY.remove(target)
        below.clear()
        release()
    }

    override fun onPreDraw(): Boolean {
        // AnimationUtils' clock is locked to the frame time while a frame is being produced, so
        // it identifies the frame for every pre-draw listener in it.
        updateForFrame(AnimationUtils.currentAnimationTimeMillis())
        return true
    }

    /**
     * Updates at most once per frame. Surfaces below this one (found in its last capture) update
     * first, so this capture sees their current frame instead of the previous one.
     */
    private fun updateForFrame(frame: Long) {
        if (frame == lastFrame) return
        lastFrame = frame // set first: also stops any accidental cycle
        for (i in below.indices) {
            val other = below[i]
            if (other.isAttached) other.updateForFrame(frame)
        }
        update()
    }

    /** Captures and blurs now. Normally called from pre-draw. */
    public fun update() {
        val w = target.targetWidth
        val h = target.targetHeight
        if (!target.isTargetShown || w <= 0 || h <= 0) return
        updateContent(w, h)
        recordSurface(w, h)
    }

    /**
     * Re-records [surface]: the blur levels positioned for the captured region (target plus
     * margin) and clipped to the target, the tint alone, or nothing; then the glass lighting.
     */
    private fun recordSurface(w: Int, h: Int) {
        surface.setPosition(0, 0, w, h) // clips to bounds by default
        val c = surface.beginRecording(w, h)
        try {
            drawContent(c, w, h, hardware = true)
        } finally {
            surface.endRecording()
        }
    }

    /** Shared by the hardware recording and software frames. [c]'s origin is the target's top-left. */
    private fun drawContent(c: Canvas, w: Int, h: Int, hardware: Boolean) {
        val cfg = config
        if (hasContent) {
            val gradient = cfg.gradient
            if (gradient == null) {
                drawLevel(c, full = true, hardware = hardware)
            } else {
                val levels = if (hardware && activePath == PATH_RENDER_EFFECT) 2 else 1
                masks.prepare(w, h, gradient, levels)
                if (levels == 2) drawMasked(c, w, h, masks.mid, full = false, hardware = true)
                drawMasked(c, w, h, masks.full, full = true, hardware = hardware)
            }
        } else if (tintOnly && cfg.tintAlpha > 0f && cfg.gradient == null) {
            c.drawColor(tintArgb())
        }
        val glass = cfg.glass
        // Lighting fades in over the first few pixels of blur, so hide() and progress 0 remove it too.
        if (glass != null && (hasContent || tintOnly)) {
            lighting.draw(c, w, h, cfg.cornerRadius, glass, (cfg.radius / LIGHT_FADE_RADIUS).coerceIn(0f, 1f))
        }
    }

    private fun drawMasked(c: Canvas, w: Int, h: Int, mask: Paint, full: Boolean, hardware: Boolean) {
        val layer = c.saveLayer(0f, 0f, w.toFloat(), h.toFloat(), null)
        drawLevel(c, full, hardware)
        c.drawRect(0f, 0f, w.toFloat(), h.toFloat(), mask)
        c.restoreToCount(layer)
    }

    /** Draws one blur level, mapped from capture space onto the target. */
    private fun drawLevel(c: Canvas, full: Boolean, hardware: Boolean) {
        val save = c.save()
        if (hardware) {
            c.translate(regionLeft.toFloat(), regionTop.toFloat())
            c.scale(regionWidth / contentWidth.toFloat(), regionHeight / contentHeight.toFloat())
            c.drawRenderNode(if (full) node else midNode)
        } else {
            drawSoftwareLevel(c)
        }
        c.restoreToCount(save)
    }

    /**
     * Software canvases (another surface's capture, or View.draw into a Bitmap) cannot draw
     * RenderNodes or effects. The bitmap path has its blurred bitmap. The RenderEffect path only has
     * the unblurred capture, drawn with the tint and color adjustment applied: inside another
     * surface's capture it gets blurred again by that surface anyway.
     */
    private fun drawSoftwareLevel(c: Canvas) {
        drawRect.set(regionLeft, regionTop, regionLeft + regionWidth, regionTop + regionHeight)
        val b = blurred
        if (activePath != PATH_RENDER_EFFECT && b != null) {
            c.drawBitmap(b, null, drawRect, filterPaint)
            return
        }
        val captured = capture.bitmap ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (snapshotFilterFor != config) {
                snapshotPaint.colorFilter = BlurRenderEffects.colorMatrix(config)?.let { ColorMatrixColorFilter(it) }
                snapshotFilterFor = config
            }
        }
        c.drawBitmap(captured, null, drawRect, snapshotPaint)
    }

    private fun updateContent(w: Int, h: Int) {
        val start = SystemClock.elapsedRealtimeNanos()
        val c = config
        refracting = false
        if (isNoOp(c)) {
            hasContent = false
            tintOnly = true
            skippedAsNoOp = true
            lastUpdateMillis = (SystemClock.elapsedRealtimeNanos() - start) / 1_000_000f
            return
        }
        tintOnly = false
        skippedAsNoOp = false
        val src = source ?: host.rootView
        // 3 sigma of margin (sigma = radius / 2) so the blur near the edges sees real content.
        // Glass refraction samples inward, so it needs no extra. Rounded up to MARGIN_STEP so an
        // animating radius does not resize buffers every frame.
        val wanted = Math.ceil(c.radius * 1.5).toInt()
        val margin = minOf((wanted + MARGIN_STEP - 1) / MARGIN_STEP * MARGIN_STEP, MAX_MARGIN)
        target.getTargetLocationInWindow(location)
        val x = location[0]
        val y = location[1]
        var captured = capture.capture(src, x, y, w, h, c.downsample, margin, owner = target)
        // Drawing the source runs any pending layout (Compose lays out inside its draw), which can
        // move the target itself. If it moved, capture again at the new position.
        target.getTargetLocationInWindow(location)
        if (captured != null && (location[0] != x || location[1] != y)) {
            captured = capture.capture(src, location[0], location[1], w, h, c.downsample, margin, owner = target)
        }
        if (captured == null) {
            reportCaptureFailure()
            hasContent = false
            return
        }
        trackSurfacesBelow()
        checkGeometry(captured)
        val radius = c.radius / c.downsample
        val result = if (useRenderEffect(c)) renderEffectPath(captured, c, radius) else bitmapPath(captured, c, radius)
        if (result == null) {
            // Backend still warming up: keep showing the previous frame, or the tint if there is none.
            if (!hasContent) tintOnly = true
            lastUpdateMillis = (SystemClock.elapsedRealtimeNanos() - start) / 1_000_000f
            return
        }
        hasContent = result
        if (result) {
            contentWidth = captured.width
            contentHeight = captured.height
            regionLeft = capture.regionLeft
            regionTop = capture.regionTop
            regionWidth = capture.regionWidth
            regionHeight = capture.regionHeight
        }
        lastUpdateMillis = (SystemClock.elapsedRealtimeNanos() - start) / 1_000_000f
    }

    /**
     * Remembers which surfaces were drawn below this one in the capture just made. When that set
     * changes, the capture may have shown them before they had rendered (pre-draw order is not
     * drawing order), so one more frame is requested; from then on they update first. Only a
     * change in the stack requests a frame, so this never loops.
     */
    private fun trackSurfacesBelow() {
        val found = capture.surfacesBelow
        var same = found.size == below.size
        if (same) {
            for (i in found.indices) if (REGISTRY[found[i]] !== below[i]) { same = false; break }
        }
        if (same) return
        below.clear()
        for (i in found.indices) REGISTRY[found[i]]?.let { below.add(it) }
        if (below.isNotEmpty()) MAIN.post { if (isAttached) target.invalidateTarget() }
    }

    /** Refraction is positioned in capture pixels, so its effect is rebuilt when the geometry changes. */
    private fun checkGeometry(captured: Bitmap) {
        if (config.glass == null) return
        val r = effectRegion
        if (captured.width != effectW || captured.height != effectH || capture.regionLeft != r[0] ||
            capture.regionTop != r[1] || capture.regionWidth != r[2] || capture.regionHeight != r[3]
        ) {
            effectW = captured.width; effectH = captured.height
            r[0] = capture.regionLeft; r[1] = capture.regionTop; r[2] = capture.regionWidth; r[3] = capture.regionHeight
            effectDirty = true
        }
    }

    /**
     * Draws the blurred content into [canvas], whose origin is the target's top-left.
     *
     * On hardware canvases this only draws [surface], a node that [update] re-records every frame.
     * The caller's recording therefore never goes stale. This matters for Compose, which may
     * record the caller once (sometimes during a capture) and reuse that recording.
     */
    public fun draw(canvas: Canvas) {
        if (canvas.isHardwareAccelerated) {
            canvas.drawRenderNode(surface)
            return
        }
        val w = target.targetWidth
        val h = target.targetHeight
        val save = canvas.save()
        canvas.clipRect(0, 0, w, h)
        drawContent(canvas, w, h, hardware = false)
        canvas.restoreToCount(save)
    }

    private fun tintArgb(): Int =
        (Math.round(config.tintAlpha * 255) shl 24) or (config.tintColor and 0xFFFFFF)

    private fun release() {
        warmGeneration++ // discard any warm-up still in flight
        node.discardDisplayList()
        midNode.discardDisplayList()
        surface.discardDisplayList()
        backend?.release()
        backend = null
        backendDirty = true
        capture.release()
        blurred = null
        captureImage = null
        blurredImage = null
        hasContent = false
        skippedAsNoOp = false
        refracting = false
        activePath = null
        effectDirty = true
        effectW = -1
    }

    /**
     * Nothing to blur or recolor: the content behind already looks right, so capture is skipped
     * and only the tint is drawn. Covers the end of hide(), progress 0, and a gradient that is
     * zero everywhere.
     */
    private fun isNoOp(c: BlurConfig): Boolean =
        (c.radius < 0.5f && c.saturation == 1f && c.brightness == 1f) || c.gradient?.isInvisible == true

    private fun useRenderEffect(c: BlurConfig): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && host.isHardwareAccelerated &&
            (c.algorithm == BlurAlgorithm.AUTO || c.algorithm == BlurAlgorithm.GAUSSIAN)

    /**
     * Initializes the bitmap backend for [c] on a background thread, then installs it on the main
     * thread. Safe because backends only need to be used from one thread at a time, and the GLES
     * backend runs its GL work on a thread it owns.
     */
    private fun warmUp(c: BlurConfig) {
        if (warming || backend != null) return
        warming = true
        val generation = warmGeneration
        val factories = if (glesFailed) listOf(SoftwareBlurBackend.fastFactory)
            else listOf(GlesBlurBackend.factory, SoftwareBlurBackend.fastFactory)
        val caps = capabilities
        val backendConfig = backendConfig(c, c.radius / c.downsample)
        WARM_UP.execute {
            val created = runCatching {
                BlurBackendSelector(factories).selectAndInitialize(backendConfig, caps).also { prime(it, backendConfig) }
            }
                .onFailure { Log.w(TAG, "no blur backend available", it) }
                .getOrNull()
            MAIN.post {
                warming = false
                if (generation != warmGeneration || backend != null) {
                    created?.release()
                    return@post
                }
                backend = created
                backendDirty = true // apply the config current by now
                target.invalidateTarget()
            }
        }
    }

    /**
     * Renders one tiny frame on the warm-up thread. GPU drivers often finish compiling shaders on
     * the first draw rather than at link time, so without this the first real frame, on the main
     * thread, would still stall.
     */
    private fun prime(b: BlurBackend, config: BlurConfig) {
        val input = Bitmap.createBitmap(PRIME_SIZE, PRIME_SIZE, Bitmap.Config.ARGB_8888)
        val output = Bitmap.createBitmap(PRIME_SIZE, PRIME_SIZE, Bitmap.Config.ARGB_8888)
        try {
            // A real radius, so the blur passes (not just copy and finish) are drawn once.
            b.updateConfig(config.copy(radius = maxOf(config.radius, PRIME_RADIUS)))
            b.render(BitmapImage(input), BitmapImage(output))
            b.updateConfig(config)
        } catch (e: BlurBackendException) {
            Log.w(TAG, "${b.id} failed to prime", e)
        } finally {
            input.recycle()
            output.recycle()
        }
    }

    private fun renderEffectPath(captured: Bitmap, c: BlurConfig, radius: Float): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return false
        if (effectDirty) {
            effectsRefract = false
            effect = withGlass(BlurRenderEffects.create(c, radius), captured)
            midEffect = if (c.gradient != null) withGlass(BlurRenderEffects.create(c, radius * MID_LEVEL), captured) else null
            effectDirty = false
        }
        refracting = effectsRefract
        node.setRenderEffect(effect)
        record(node, captured)
        if (c.gradient != null) {
            midNode.setRenderEffect(midEffect)
            record(midNode, captured)
        }
        activePath = PATH_RENDER_EFFECT
        return true
    }

    /** Chains glass refraction after [input] on API 33+; otherwise returns [input] unchanged. */
    private fun withGlass(input: RenderEffect?, captured: Bitmap): RenderEffect? {
        val glass = config.glass ?: return input
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU || glass.refraction <= 0f) return input
        val effects = (glassEffects as? GlassRenderEffects) ?: GlassRenderEffects().also { glassEffects = it }
        val sx = captured.width / capture.regionWidth.toFloat()
        val sy = captured.height / capture.regionHeight.toFloat()
        val left = -capture.regionLeft * sx
        val top = -capture.regionTop * sy
        val right = left + target.targetWidth * sx
        val bottom = top + target.targetHeight * sy
        val result = effects.create(glass, input, captured.width.toFloat(), captured.height.toFloat(),
            left, top, right, bottom, config.cornerRadius, scale = (sx + sy) / 2f)
        effectsRefract = true
        return result
    }

    /** True if drawn, false if it failed, null if the backend is not ready yet. */
    private fun bitmapPath(captured: Bitmap, c: BlurConfig, radius: Float): Boolean? {
        val b = ensureBackend(c, radius) ?: return null
        val out = ReusableBitmap.fit(blurred, captured.width, captured.height)
        if (out !== blurred) {
            blurred = out
            blurredImage = BitmapImage(out)
        }
        val input = captureImage?.takeIf { it.bitmap === captured } ?: BitmapImage(captured).also { captureImage = it }
        try {
            b.render(input, checkNotNull(blurredImage))
        } catch (e: BlurBackendException) {
            Log.w(TAG, "${b.id} failed, falling back", e)
            if (b.id == GlesBlurBackend.ID) glesFailed = true
            b.release()
            backend = null
            return false
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            // The blur is already in the bitmap; on API 33+ the node only refracts it.
            if (effectDirty) {
                effectsRefract = false
                effect = withGlass(null, captured)
                effectDirty = false
            }
            refracting = effectsRefract
            node.setRenderEffect(effect)
        }
        record(node, out)
        activePath = b.id
        return true
    }

    /**
     * The bitmap backend works on the already-downsampled capture, so it gets downsample = 1. It
     * only blurs and recolors: gradient and glass are applied by this renderer.
     */
    private fun backendConfig(c: BlurConfig, radius: Float): BlurConfig =
        c.copy(radius = radius, downsample = 1, gradient = null, glass = null)

    /** Returns null while a backend is being created in the background. */
    private fun ensureBackend(c: BlurConfig, radius: Float): BlurBackend? {
        val current = backend
        if (current == null) {
            warmUp(c)
            return null
        }
        if (!backendDirty) return current
        try {
            current.updateConfig(backendConfig(c, radius))
            backendDirty = false
            return current
        } catch (_: IllegalArgumentException) {
            // This backend can't do the new algorithm (e.g. GLES and BOX): pick another, off the main thread.
            current.release()
            backend = null
            warmUp(c)
            return null
        }
    }

    private fun record(target: RenderNode, bitmap: Bitmap) {
        target.setPosition(0, 0, bitmap.width, bitmap.height)
        val canvas = target.beginRecording(bitmap.width, bitmap.height)
        try {
            canvas.drawBitmap(bitmap, 0f, 0f, filterPaint)
        } finally {
            target.endRecording()
        }
    }

    private fun reportCaptureFailure() {
        val failure = capture.lastFailure ?: return
        if (loggedCaptureFailure) return
        loggedCaptureFailure = true
        Log.w(TAG, "capture failed; the source contains something software drawing can't render " +
            "(often a HARDWARE bitmap in a shader). Blur is off until a capture succeeds.", failure)
    }

    public companion object {
        public const val PATH_RENDER_EFFECT: String = "render-effect"

        /** Upper bound on captured margin per side, in source pixels, to bound capture cost. */
        private const val MAX_MARGIN = 600

        /** Margin granularity in source pixels. */
        private const val MARGIN_STEP = 64

        /** Size of the warm-up frame; big enough to run every pass (Dual Kawase needs >= 2 px per level). */
        private const val PRIME_SIZE = 64
        private const val PRIME_RADIUS = 4f

        /** Strength of the middle level of a progressive blur, as a fraction of the full radius. */
        private const val MID_LEVEL = 0.5f

        /** Blur radius (px) at which glass lighting reaches full strength. */
        private const val LIGHT_FADE_RADIUS = 8f

        private val MAIN = android.os.Handler(android.os.Looper.getMainLooper())

        /** Attached renderers by target, to resolve the surfaces a capture found below. Main thread only. */
        private val REGISTRY = java.util.IdentityHashMap<Any, BlurSurfaceRenderer>()

        /** One shared background thread for backend warm-up. */
        private val WARM_UP = java.util.concurrent.Executors.newSingleThreadExecutor { r ->
            Thread(r, "BlurEngine-warmup").apply { isDaemon = true }
        }
        private const val TAG = "BlurEngine"
    }
}
