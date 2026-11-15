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

package dev.blurengine.mrsluffy.capture

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.view.View

/**
 * Decides which blur surfaces appear in a capture. Main thread only.
 *
 * A capture draws the source in normal drawing order, so it meets the surfaces below the capturing
 * one first, then the capturing surface itself, then the surfaces above it. Surfaces below draw
 * their last blurred frame, which gives stacked (multi-layer) glass. The capturing surface and
 * everything above it draw nothing, so no surface ever sees its own output and two surfaces can
 * never feed into each other.
 */
public object CaptureGuard {
    private var depth = 0
    private var owner: Any? = null
    private var reachedOwner = false

    public val isCapturing: Boolean get() = depth > 0

    /**
     * True if the blur surface [self], drawing into [canvas], should draw nothing because it is the
     * capturing surface or lies above it. Surfaces below draw normally, so they show up in the blur.
     *
     * Only the capture's software canvas is affected. Hardware canvases are drawn normally even
     * mid-capture: Compose refreshes its pending display lists when a capture draws it, and keeps
     * those recordings for the real frame, so skipping there would drop the surface from the screen.
     *
     * Pass the same object as the capture's owner (for [dev.blurengine.mrsluffy.capture.ViewCapture],
     * the `owner` argument).
     */
    public fun shouldSkip(canvas: Canvas, self: Any): Boolean {
        if (depth == 0 || canvas.isHardwareAccelerated) return false
        if (self === owner) {
            reachedOwner = true
            return true
        }
        // Without an owner (a capture not made for a surface), leave every surface out, as before.
        if (owner == null || reachedOwner) return true
        below?.let { if (!it.containsIdentity(self)) it.add(self) }
        return false
    }

    private var below: MutableList<Any>? = null

    private fun List<Any>.containsIdentity(item: Any): Boolean {
        for (i in indices) if (this[i] === item) return true
        return false
    }

    /** Leaves every blur surface out of the capture. For surfaces that can't draw a software frame. */
    public fun shouldSkip(canvas: Canvas): Boolean = depth > 0 && !canvas.isHardwareAccelerated

    /** Runs [block] as a capture for [captureOwner], collecting the surfaces drawn below it into [surfacesBelow]. */
    internal inline fun <T> during(captureOwner: Any?, surfacesBelow: MutableList<Any>?, block: () -> T): T {
        val previousOwner = owner
        val previousReached = reachedOwner
        val previousBelow = below
        depth++
        owner = captureOwner
        reachedOwner = false
        below = surfacesBelow
        try {
            return block()
        } finally {
            depth--
            owner = previousOwner
            reachedOwner = previousReached
            below = previousBelow
        }
    }
}

/**
 * Where to draw the source so that the region under the target lands at the bitmap origin.
 * Pure arithmetic, separated for testing.
 */
public object CaptureGeometry {
    /** Bitmap side length for a view side of [size] px at [downsample], rounded up so edges are kept. */
    public fun bitmapSize(size: Int, downsample: Int): Int {
        require(downsample >= 1) { "downsample must be >= 1" }
        return maxOf(1, (size + downsample - 1) / downsample)
    }

    /**
     * Translation (in source pixels, applied after scaling) that maps the target's top-left to
     * the canvas origin. [offset] is the target's window position minus the source's, and [scroll]
     * is the source's own scroll, which [View.draw] does not apply.
     */
    public fun translation(offset: Int, scroll: Int): Float = -(offset + scroll).toFloat()

    /**
     * Extra capture margin on one side of the target: [desired] px, limited to the [available]
     * source content on that side, and rounded down to a multiple of [downsample] so captured
     * pixels stay aligned with the view.
     */
    public fun padding(desired: Int, available: Int, downsample: Int): Int {
        require(downsample >= 1) { "downsample must be >= 1" }
        val p = minOf(maxOf(desired, 0), maxOf(available, 0))
        return p / downsample * downsample
    }
}

/**
 * Captures the part of a source view that lies under a target view, at reduced resolution, by
 * drawing the source into a bitmap with a software canvas.
 *
 * Limits of software drawing:
 * - SurfaceView and TextureView content (video, camera, maps) is not captured.
 * - Bitmap.Config.HARDWARE bitmaps (the default in Coil and Glide) are supported when drawn with
 *   drawBitmap, which covers ImageView and Compose Image: each is copied once at capture
 *   resolution and cached. Inside a BitmapShader they still make the capture fail, and
 *   [lastFailure] holds the exception.
 *
 * Reuses one bitmap while the size stays the same. Main thread only.
 */
public class ViewCapture {
    private val canvas = CaptureCanvas()
    private val sourceLocation = IntArray(2)
    private val targetLocation = IntArray(2)

    /** The most recent capture result, reused between frames. */
    public var bitmap: Bitmap? = null
        private set

    /** Why the last capture failed, or null if it succeeded. */
    private val below = ArrayList<Any>(4)

    /**
     * Blur surfaces (their `self` objects) drawn into the last capture because they lie below its
     * owner, in drawing order. Reused between captures; copy it to keep it.
     */
    public val surfacesBelow: List<Any> get() = below

    public var lastFailure: Throwable? = null
        private set

    /**
     * Captured region relative to the target's top-left, in target pixels. [regionLeft] and
     * [regionTop] are <= 0 when margin was captured. The bitmap covers this region.
     */
    public var regionLeft: Int = 0
        private set
    public var regionTop: Int = 0
        private set
    public var regionWidth: Int = 0
        private set
    public var regionHeight: Int = 0
        private set

    /**
     * Draws the region of [source] under [target], plus up to [margin] px on each side, into
     * [bitmap] at 1/[downsample] resolution. The margin gives a blur real content to sample near
     * the target's edges. It is limited to what the source covers, so nothing outside the source
     * is captured. Views drawn while this runs see [CaptureGuard.isCapturing] = true.
     *
     * [owner] identifies the blur surface the capture is for. Blur surfaces drawn before it appear
     * in the capture; it and those after it don't (see [CaptureGuard.shouldSkip]). With no owner,
     * every blur surface is left out.
     *
     * @return the bitmap, or null if either view has no size or the source threw while drawing.
     */
    public fun capture(source: View, target: View, downsample: Int, margin: Int = 0, owner: Any? = null): Bitmap? {
        target.getLocationInWindow(targetLocation)
        return capture(source, targetLocation[0], targetLocation[1], target.width, target.height, downsample, margin, owner)
    }

    /**
     * Same as the View overload, for a target that is not a View (for example a composable):
     * [targetLeft] and [targetTop] are its position in window coordinates, [w] x [h] its size.
     */
    public fun capture(
        source: View,
        targetLeft: Int,
        targetTop: Int,
        w: Int,
        h: Int,
        downsample: Int,
        margin: Int = 0,
        owner: Any? = null,
    ): Bitmap? {
        if (w <= 0 || h <= 0 || source.width <= 0 || source.height <= 0) return null

        source.getLocationInWindow(sourceLocation)
        val dx = targetLeft - sourceLocation[0]
        val dy = targetTop - sourceLocation[1]
        val padLeft = CaptureGeometry.padding(margin, dx, downsample)
        val padTop = CaptureGeometry.padding(margin, dy, downsample)
        val padRight = CaptureGeometry.padding(margin, source.width - (dx + w), downsample)
        val padBottom = CaptureGeometry.padding(margin, source.height - (dy + h), downsample)
        val rw = w + padLeft + padRight
        val rh = h + padTop + padBottom

        val bw = CaptureGeometry.bitmapSize(rw, downsample)
        val bh = CaptureGeometry.bitmapSize(rh, downsample)
        val bmp = ensureBitmap(bw, bh)

        bmp.eraseColor(Color.TRANSPARENT)
        canvas.setBitmap(bmp)
        canvas.copyScale = 1f / downsample
        val save = canvas.save()
        try {
            canvas.scale(bw / rw.toFloat(), bh / rh.toFloat())
            canvas.translate(
                CaptureGeometry.translation(dx - padLeft, source.scrollX),
                CaptureGeometry.translation(dy - padTop, source.scrollY),
            )
            regionLeft = -padLeft
            regionTop = -padTop
            regionWidth = rw
            regionHeight = rh
            below.clear()
            CaptureGuard.during(owner, below) { source.draw(canvas) }
            lastFailure = null
            return bmp
        } catch (e: RuntimeException) {
            // Typically "Software rendering doesn't support hardware bitmaps".
            lastFailure = e
            return null
        } finally {
            canvas.restoreToCount(save)
            canvas.setBitmap(null)
        }
    }

    /** Drops the bitmap. It is not recycled, because a display list may still reference it. */
    public fun release() {
        bitmap = null
        canvas.clearCache()
    }

    private fun ensureBitmap(w: Int, h: Int): Bitmap = ReusableBitmap.fit(bitmap, w, h).also { bitmap = it }
}

/**
 * Keeps one bitmap's memory across size changes. When a blur radius animates, the capture margin
 * (and so the bitmap size) changes every frame; reconfiguring avoids allocating a bitmap per frame.
 */
public object ReusableBitmap {
    /** Extra capacity when a new bitmap has to be allocated, so small growth reuses it. */
    private const val HEADROOM = 1.25f

    /**
     * Returns [current] if it is already [w] x [h], [current] reconfigured to that size if its memory
     * is large enough, or a new mutable ARGB_8888 bitmap otherwise. Contents are undefined.
     */
    public fun fit(current: Bitmap?, w: Int, h: Int): Bitmap {
        require(w > 0 && h > 0) { "size must be positive: ${w}x$h" }
        if (current != null && !current.isRecycled) {
            if (current.width == w && current.height == h) return current
            if (current.isMutable && current.allocationByteCount >= w.toLong() * h * 4) {
                current.reconfigure(w, h, Bitmap.Config.ARGB_8888)
                return current
            }
        }
        val fresh = Bitmap.createBitmap((w * HEADROOM).toInt(), (h * HEADROOM).toInt(), Bitmap.Config.ARGB_8888)
        fresh.reconfigure(w, h, Bitmap.Config.ARGB_8888)
        return fresh
    }
}
