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

import dev.blurengine.mrsluffy.core.BlurConfig
import dev.blurengine.mrsluffy.core.FrameCallback
import dev.blurengine.mrsluffy.core.FrameClock
import java.lang.management.ManagementFactory
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Manual clock: [frame] advances time by one refresh interval and runs the pending callback. */
private class TestClock(private val intervalNanos: Long = 16_666_667L) : FrameClock {
    var now = 1_000_000_000L
    var pending: FrameCallback? = null
    var posts = 0

    override fun postFrame(callback: FrameCallback) { pending = callback; posts++ }
    override fun cancel() { pending = null }

    fun frame(): Boolean {
        val c = pending ?: return false
        pending = null
        now += intervalNanos
        c.doFrame(now)
        return true
    }

    fun runToEnd(maxFrames: Int = 10_000): Int {
        var frames = 0
        while (frame()) { frames++; check(frames < maxFrames) { "did not settle" } }
        return frames
    }
}

class BlurAnimatorTest {
    private val clock = TestClock()
    private var updates = 0
    private val animator = BlurAnimator(clock, BlurValues(radius = 0f)) { updates++ }

    @Test fun tweenReachesTargetAtDurationAndEndsOnce() {
        var ends = 0
        var finished = false
        animator.animate {
            radius = 30f
            duration = 300L
            interpolator = BlurInterpolators.LINEAR
            onEnd = BlurAnimationListener { ends++; finished = it }
        }
        clock.frame() // first frame fixes the start time: value still at the start
        assertEquals(0f, animator.values.radius)
        repeat(9) { clock.frame() } // 150 ms in
        assertEquals(15f, animator.values.radius, 0.2f)
        val frames = clock.runToEnd()
        assertEquals(30f, animator.values.radius)
        assertEquals(1, ends)
        assertTrue(finished)
        assertTrue(frames in 8..10, "frames=$frames")
        assertFalse(animator.isRunning)
        assertEquals(null, clock.pending, "must stop posting frames when idle")
    }

    @Test fun retargetingATweenStartsFromTheCurrentValue() {
        animator.animate { radius = 40f; duration = 400L; interpolator = BlurInterpolators.LINEAR }
        repeat(7) { clock.frame() }
        val mid = animator.values.radius
        var firstEnd: Boolean? = null
        animator.animate { radius = 0f; duration = 200L; interpolator = BlurInterpolators.LINEAR }
        clock.frame()
        assertTrue(abs(animator.values.radius - mid) < 0.01f, "jumped from $mid to ${animator.values.radius}")
        clock.runToEnd()
        assertEquals(0f, animator.values.radius)
        assertEquals(null, firstEnd)
    }

    @Test fun replacedAnimationReportsNotFinished() {
        var first: Boolean? = null
        var second: Boolean? = null
        animator.animate { radius = 40f; onEnd = BlurAnimationListener { first = it } }
        clock.frame(); clock.frame()
        animator.animate { radius = 10f; onEnd = BlurAnimationListener { second = it } }
        assertEquals(false, first)
        clock.runToEnd()
        assertEquals(true, second)
    }

    @Test fun springSettlesExactlyOnTarget() {
        var finished = false
        animator.animate { radius = 50f; spring(); onEnd = BlurAnimationListener { finished = it } }
        val frames = clock.runToEnd()
        assertEquals(50f, animator.values.radius)
        assertTrue(finished)
        assertTrue(frames in 10..200, "frames=$frames")
    }

    @Test fun lowDampingOvershootsAndCriticalDampingDoesNot() {
        fun peak(damping: Float): Float {
            val clock = TestClock()
            val a = BlurAnimator(clock, BlurValues()) {}
            a.animate { radius = 100f; spring { dampingRatio = damping; stiffness = 300f } }
            var max = 0f
            while (clock.frame()) max = maxOf(max, a.values.radius)
            return max
        }
        assertTrue(peak(0.3f) > 110f, "underdamped should overshoot")
        assertTrue(peak(1f) <= 100.01f, "critically damped must not overshoot")
        assertTrue(peak(2f) <= 100.01f, "overdamped must not overshoot")
    }

    @Test fun retargetingASpringKeepsVelocity() {
        animator.animate { radius = 100f; spring { dampingRatio = 1f; stiffness = 200f } }
        repeat(6) { clock.frame() }
        val before = animator.values.radius
        clock.frame()
        val step = animator.values.radius - before
        assertTrue(step > 0f)
        // Retarget behind the current position: the value must keep moving forward briefly.
        animator.animate { radius = 0f; spring { dampingRatio = 1f; stiffness = 200f } }
        val atRetarget = animator.values.radius
        clock.frame()
        assertTrue(animator.values.radius > atRetarget, "lost momentum: ${animator.values.radius} <= $atRetarget")
        clock.runToEnd()
        assertEquals(0f, animator.values.radius)
    }

    @Test fun propertiesAnimateIndependently() {
        animator.animate { radius = 40f; duration = 500L }
        repeat(5) { clock.frame() }
        animator.animate { tintAlpha = 0.5f; duration = 100L }
        assertTrue(animator.isAnimating(BlurProperty.RADIUS), "tint animation must not stop radius")
        clock.runToEnd()
        assertEquals(40f, animator.values.radius)
        assertEquals(0.5f, animator.values.tintAlpha)
    }

    @Test fun tintColorInterpolatesPerChannel() {
        val a = BlurAnimator(clock, BlurValues(tintColor = 0xFF000000.toInt())) {}
        a.animate { tintColor = 0xFFFF8000.toInt(); duration = 200L; interpolator = BlurInterpolators.LINEAR }
        clock.frame()
        repeat(6) { clock.frame() } // 100 ms: halfway
        val c = a.values.tintColor
        assertTrue(abs((c shr 16 and 0xFF) - 128) <= 2 && abs((c shr 8 and 0xFF) - 64) <= 2 && (c and 0xFF) == 0,
            Integer.toHexString(c))
        clock.runToEnd()
        assertEquals(0xFFFF8000.toInt(), a.values.tintColor)
    }

    @Test fun explicitStartValueIsUsed() {
        animator.snap(BlurProperty.RADIUS, 20f)
        animator.animate { radius(0f, 30f); duration = 100L }
        assertEquals(0f, animator.values.radius)
        clock.runToEnd()
        assertEquals(30f, animator.values.radius)
    }

    @Test fun animationsOffSnapsWithoutFrames() {
        val clock = TestClock()
        var finished = false
        var updates = 0
        val a = BlurAnimator(clock, BlurValues(), durationScale = { 0f }) { updates++ }
        a.animate { radius = 25f; spring(); onEnd = BlurAnimationListener { finished = it } }
        assertEquals(25f, a.values.radius)
        assertTrue(finished)
        assertEquals(1, updates)
        assertEquals(0, clock.posts)
    }

    @Test fun durationScaleStretchesTweens() {
        val clock = TestClock()
        val a = BlurAnimator(clock, BlurValues(), durationScale = { 2f }) {}
        a.animate { radius = 10f; duration = 300L }
        val frames = clock.runToEnd()
        assertTrue(frames in 36..38, "600 ms at 60 Hz should be about 37 frames, was $frames")
    }

    @Test fun followsHighRefreshRates() {
        val clock120 = TestClock(intervalNanos = 8_333_333L)
        val a = BlurAnimator(clock120, BlurValues()) {}
        a.animate { radius = 10f; duration = 300L }
        val frames = clock120.runToEnd()
        assertTrue(frames in 36..38, "300 ms at 120 Hz should be about 37 frames, was $frames")
    }

    @Test fun cancelKeepsCurrentValuesAndStopsFrames() {
        var finished: Boolean? = null
        animator.animate { radius = 40f; onEnd = BlurAnimationListener { finished = it } }
        repeat(5) { clock.frame() }
        val at = animator.values.radius
        animator.cancel()
        assertEquals(false, finished)
        assertEquals(null, clock.pending)
        assertEquals(at, animator.values.radius)
    }

    @Test fun endJumpsToTargetsAndReportsFinished() {
        var finished: Boolean? = null
        animator.animate { radius = 40f; tintAlpha = 0.3f; spring(); onEnd = BlurAnimationListener { finished = it } }
        clock.frame(); clock.frame()
        animator.end()
        assertEquals(40f, animator.values.radius)
        assertEquals(0.3f, animator.values.tintAlpha)
        assertEquals(true, finished)
        assertEquals(null, clock.pending)
    }

    @Test fun listenerCanStartTheNextAnimation() {
        animator.animate {
            radius = 10f; duration = 50L
            onEnd = BlurAnimationListener { animator.animate { radius = 0f; duration = 50L } }
        }
        clock.runToEnd()
        assertEquals(0f, animator.values.radius)
    }

    @Test fun valuesConvertToClampedConfig() {
        val c = BlurValues(radius = -3f, tintAlpha = 1.2f, saturation = -0.1f).toConfig(BlurConfig(downsample = 2))
        assertEquals(0f, c.radius); assertEquals(1f, c.tintAlpha); assertEquals(0f, c.saturation); assertEquals(2, c.downsample)
    }

    @Test fun framesDoNotAllocate() {
        val bean = ManagementFactory.getThreadMXBean() as com.sun.management.ThreadMXBean
        val thread = Thread.currentThread().id
        val a = BlurAnimator(clock, BlurValues()) {}
        // Warm up so class loading and JIT are out of the measurement.
        repeat(3) {
            a.animate { radius = 100f; tintAlpha = 1f; spring { dampingRatio = 0.2f; stiffness = 20f } }
            repeat(200) { clock.frame() }
        }
        a.animate { radius = 0f; tintAlpha = 0f; saturation = 0.5f; spring { dampingRatio = 0.2f; stiffness = 20f } }
        val before = bean.getThreadAllocatedBytes(thread)
        repeat(1000) { clock.frame() }
        val allocated = bean.getThreadAllocatedBytes(thread) - before
        assertTrue(allocated < 1024, "1000 frames allocated $allocated bytes")
    }
}

class BlurInterpolatorsTest {
    @Test fun endpointsAndMonotonic() {
        for (i in listOf(BlurInterpolators.LINEAR, BlurInterpolators.EASE_IN_OUT, BlurInterpolators.DECELERATE,
            BlurInterpolators.FAST_OUT_SLOW_IN)) {
            assertEquals(0f, i.interpolate(0f), 1e-4f)
            assertEquals(1f, i.interpolate(1f), 1e-4f)
            var last = 0f
            for (k in 1..100) {
                val v = i.interpolate(k / 100f)
                assertTrue(v >= last - 1e-4f)
                last = v
            }
        }
    }

    @Test fun fastOutSlowInMatchesKnownValue() {
        // cubic-bezier(0.4, 0, 0.2, 1) at x = 0.5 is about 0.7748.
        assertEquals(0.7748f, BlurInterpolators.FAST_OUT_SLOW_IN.interpolate(0.5f), 0.003f)
    }
}
