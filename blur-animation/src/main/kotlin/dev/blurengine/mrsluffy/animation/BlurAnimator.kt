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

package dev.blurengine.mrsluffy.animation

import dev.blurengine.mrsluffy.core.FrameCallback
import dev.blurengine.mrsluffy.core.FrameClock
import kotlin.math.abs

/**
 * Frame-driven animator for [BlurValues]. One clock drives all properties; each property can
 * run its own tween or spring, so starting a tint animation does not stop a radius animation.
 *
 * - Interruptible: a new animation on a property starts from its current value, and springs
 *   keep the current velocity (tweens hand over their measured velocity too).
 * - No allocation per frame: state lives in fixed arrays and [values] is updated in place.
 * - Reduced motion: [durationScale] is read when an animation starts. 0 (animations off in
 *   system settings) snaps to the target; other values scale tween durations.
 *
 * Not thread-safe; use on the thread that owns [clock] (the UI thread on Android). [onUpdate]
 * runs on that thread once per frame while anything is animating.
 */
public class BlurAnimator(
    private val clock: FrameClock,
    initial: BlurValues = BlurValues(),
    private val durationScale: () -> Float = { 1f },
    private val onUpdate: (BlurValues) -> Unit,
) {
    /** Current values. Updated in place every frame; copy them if you need to keep a snapshot. */
    public val values: BlurValues = BlurValues().apply { setFrom(initial) }

    private val n = BlurProperty.COUNT
    private val kind = IntArray(n)                 // IDLE, TWEEN or SPRING
    private val from = FloatArray(n)
    private val to = FloatArray(n)
    private val velocity = FloatArray(n)           // units per second
    private val startNanos = LongArray(n)          // -1 until the first frame
    private val durationNanos = LongArray(n)
    private val interpolators = arrayOfNulls<BlurInterpolator>(n)
    private val damping = FloatArray(n)
    private val stiffness = FloatArray(n)
    private val owner = arrayOfNulls<Run>(n)
    private val springOut = FloatArray(2)

    private var lastFrameNanos = -1L
    private var framePosted = false
    private val ended = ArrayList<Run>(4)

    private class Run(val listener: BlurAnimationListener?, var remaining: Int) {
        var interrupted = false
    }

    private val frameCallback = FrameCallback { frameTimeNanos -> onFrame(frameTimeNanos) }

    /** True while any property is animating. */
    public val isRunning: Boolean get() = kind.any { it != IDLE }

    /** True while [property] is animating. */
    public fun isAnimating(property: BlurProperty): Boolean = kind[property.ordinal] != IDLE

    /** Starts [animation], replacing any running animation of the same properties. */
    public fun start(animation: BlurAnimation) {
        val mask = animation.mask
        if (mask == 0) {
            animation.listener?.onEnd(true)
            return
        }
        val scale = durationScale()
        val spec = animation.spec
        val run = Run(animation.listener, Integer.bitCount(mask))

        if (spec is BlurAnimationSpec.Snap || scale <= 0f ||
            (spec is BlurAnimationSpec.Tween && spec.durationMillis == 0L)
        ) {
            for (p in BlurProperty.ALL) {
                if (mask and p.bit == 0) continue
                release(p.ordinal, interrupted = true)
                values.v[p.ordinal] = animation.to[p.ordinal]
                velocity[p.ordinal] = 0f
            }
            onUpdate(values)
            animation.listener?.onEnd(true)
            dispatchEnded()
            return
        }

        for (p in BlurProperty.ALL) {
            if (mask and p.bit == 0) continue
            val i = p.ordinal
            release(i, interrupted = true)
            if (animation.fromMask and p.bit != 0) {
                values.v[i] = animation.from[i]
                velocity[i] = 0f
            }
            from[i] = values.v[i]
            to[i] = animation.to[i]
            owner[i] = run
            when (spec) {
                is BlurAnimationSpec.Tween -> {
                    kind[i] = TWEEN
                    startNanos[i] = -1L
                    durationNanos[i] = (spec.durationMillis * scale * 1_000_000.0).toLong().coerceAtLeast(1L)
                    interpolators[i] = spec.interpolator
                }
                is BlurAnimationSpec.Spring -> {
                    kind[i] = SPRING
                    damping[i] = spec.dampingRatio
                    stiffness[i] = spec.stiffness
                }
                BlurAnimationSpec.Snap -> Unit
            }
        }
        dispatchEnded()
        scheduleFrame()
    }

    /** Builds and starts an animation with the [blurAnimation] DSL. */
    public fun animate(block: BlurAnimationBuilder.() -> Unit): Unit = start(blurAnimation(block))

    /**
     * Sets [property] immediately, stopping its animation (its listener gets finished = false).
     * Does not call onUpdate.
     */
    public fun snap(property: BlurProperty, value: Float) {
        release(property.ordinal, interrupted = true)
        values.v[property.ordinal] = value
        velocity[property.ordinal] = 0f
        dispatchEnded()
        stopFramesIfIdle()
    }

    /** Sets all values immediately, stopping every animation. Does not call onUpdate. */
    public fun snapTo(newValues: BlurValues) {
        for (i in 0 until n) release(i, interrupted = true)
        values.setFrom(newValues)
        velocity.fill(0f)
        dispatchEnded()
        stopFramesIfIdle()
    }

    /** Jumps every running animation to its target. Listeners get finished = true. */
    public fun end() {
        if (!isRunning) return
        for (i in 0 until n) {
            if (kind[i] == IDLE) continue
            values.v[i] = to[i]
            finish(i)
        }
        onUpdate(values)
        dispatchEnded()
        stopFramesIfIdle()
    }

    /** Stops everything where it is. Listeners get finished = false. */
    public fun cancel() {
        for (i in 0 until n) release(i, interrupted = true)
        velocity.fill(0f)
        dispatchEnded()
        stopFramesIfIdle()
    }

    private fun onFrame(frameTimeNanos: Long) {
        framePosted = false
        val dt = if (lastFrameNanos < 0) 0f
            else ((frameTimeNanos - lastFrameNanos) / 1e9f).coerceIn(0f, MAX_STEP_SECONDS)
        lastFrameNanos = frameTimeNanos
        val v = values.v

        for (i in 0 until n) {
            when (kind[i]) {
                TWEEN -> {
                    if (startNanos[i] < 0) startNanos[i] = frameTimeNanos
                    val fraction = ((frameTimeNanos - startNanos[i]).toFloat() / durationNanos[i]).coerceIn(0f, 1f)
                    val previous = v[i]
                    v[i] = if (fraction >= 1f) to[i]
                        else from[i] + (to[i] - from[i]) * interpolators[i]!!.interpolate(fraction)
                    if (dt > 0f) velocity[i] = (v[i] - previous) / dt
                    if (fraction >= 1f) finish(i)
                }
                SPRING -> {
                    if (dt > 0f) {
                        BlurSpring.step(v[i] - to[i], velocity[i], dt, damping[i], stiffness[i], springOut)
                        v[i] = to[i] + springOut[0]
                        velocity[i] = springOut[1]
                    }
                    val threshold = THRESHOLDS[i]
                    if (abs(v[i] - to[i]) < threshold && abs(velocity[i]) < threshold * VELOCITY_MULTIPLIER) {
                        v[i] = to[i]
                        finish(i)
                    }
                }
            }
        }
        onUpdate(values)
        dispatchEnded()
        if (isRunning) scheduleFrame() else lastFrameNanos = -1L
    }

    private fun finish(i: Int) {
        kind[i] = IDLE
        velocity[i] = 0f
        interpolators[i] = null
        val run = owner[i] ?: return
        owner[i] = null
        run.remaining--
        if (run.remaining == 0) ended.add(run)
    }

    /** Detaches property [i] from its running animation without moving it. */
    private fun release(i: Int, interrupted: Boolean) {
        val run = owner[i]
        kind[i] = IDLE
        interpolators[i] = null
        if (run == null) return
        owner[i] = null
        if (interrupted) run.interrupted = true
        run.remaining--
        if (run.remaining == 0) ended.add(run)
    }

    /**
     * Listeners may start new animations, which re-enter here. Each run is removed before its
     * listener is called, so nested calls never deliver the same run twice.
     */
    private fun dispatchEnded() {
        while (ended.isNotEmpty()) {
            val run = ended.removeAt(0)
            run.listener?.onEnd(!run.interrupted)
        }
    }

    private fun scheduleFrame() {
        if (framePosted) return
        framePosted = true
        clock.postFrame(frameCallback)
    }

    private fun stopFramesIfIdle() {
        if (!isRunning && framePosted) {
            clock.cancel()
            framePosted = false
            lastFrameNanos = -1L
        }
    }

    private companion object {
        const val IDLE = 0
        const val TWEEN = 1
        const val SPRING = 2

        /** Longest step after a stalled frame, so springs do not jump after a hitch. */
        const val MAX_STEP_SECONDS = 0.1f

        /** Settle thresholds per property: radius px, alpha/saturation/brightness, color channel. */
        val THRESHOLDS = floatArrayOf(0.05f, 0.001f, 0.001f, 0.001f, 0.25f, 0.25f, 0.25f)

        /** Same rule as AndroidX: velocity threshold = value threshold * 1000 / 16. */
        const val VELOCITY_MULTIPLIER = 62.5f
    }
}
