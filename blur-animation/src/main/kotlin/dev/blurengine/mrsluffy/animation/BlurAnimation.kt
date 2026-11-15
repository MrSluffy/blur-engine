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

/** How properties move to their targets. */
public sealed class BlurAnimationSpec {
    /** Fixed-duration animation. Duration is multiplied by the system animation scale. */
    public data class Tween(
        val durationMillis: Long = 300L,
        val interpolator: BlurInterpolator = BlurInterpolators.FAST_OUT_SLOW_IN,
    ) : BlurAnimationSpec() {
        init { require(durationMillis >= 0) { "durationMillis must be >= 0" } }
    }

    /**
     * Physics animation; keeps the current velocity when retargeted. Lower damping overshoots
     * more; higher stiffness is faster.
     */
    public data class Spring(
        val dampingRatio: Float = 0.75f,
        val stiffness: Float = 350f,
    ) : BlurAnimationSpec() {
        init { require(dampingRatio > 0f && stiffness > 0f) { "dampingRatio and stiffness must be > 0" } }
    }

    /** Jump to the target immediately. */
    public object Snap : BlurAnimationSpec()
}

/** Called when an animation finishes; [finished] is false if it was cancelled or replaced. */
public fun interface BlurAnimationListener {
    public fun onEnd(finished: Boolean)
}

/** An immutable request: which properties to animate, optional start values, targets, spec. */
public class BlurAnimation internal constructor(
    internal val mask: Int,
    internal val fromMask: Int,
    internal val from: FloatArray,
    internal val to: FloatArray,
    public val spec: BlurAnimationSpec,
    public val listener: BlurAnimationListener?,
) {
    /** True if [property] is animated. */
    public fun animates(property: BlurProperty): Boolean = mask and property.bit != 0

    /** Target of [property]; only meaningful when [animates] is true. */
    public fun target(property: BlurProperty): Float = to[property.ordinal]
}

/**
 * DSL for [BlurAnimation]. Assign a target (`radius = 30f`) or a range (`radius(0f, 30f)`).
 * Properties left unset are not touched. Defaults to a 300 ms tween; set [duration] or call
 * [spring] to change it.
 */
public class BlurAnimationBuilder {
    private var mask = 0
    private var fromMask = 0
    private val from = FloatArray(BlurProperty.COUNT)
    private val to = FloatArray(BlurProperty.COUNT)
    private var spec: BlurAnimationSpec? = null

    /** Tween duration in milliseconds. */
    public var duration: Long? = null
    public var interpolator: BlurInterpolator = BlurInterpolators.FAST_OUT_SLOW_IN
    public var onEnd: BlurAnimationListener? = null

    public var radius: Float
        get() = to[BlurProperty.RADIUS.ordinal]
        set(value) { require(value >= 0f) { "radius must be >= 0" }; target(BlurProperty.RADIUS, value) }

    public var tintAlpha: Float
        get() = to[BlurProperty.TINT_ALPHA.ordinal]
        set(value) { require(value in 0f..1f) { "tintAlpha must be in 0..1" }; target(BlurProperty.TINT_ALPHA, value) }

    public var saturation: Float
        get() = to[BlurProperty.SATURATION.ordinal]
        set(value) { require(value >= 0f) { "saturation must be >= 0" }; target(BlurProperty.SATURATION, value) }

    public var brightness: Float
        get() = to[BlurProperty.BRIGHTNESS.ordinal]
        set(value) { require(value >= 0f) { "brightness must be >= 0" }; target(BlurProperty.BRIGHTNESS, value) }

    /** Target tint color (RGB; alpha byte ignored). */
    public var tintColor: Int
        get() = BlurValues().also { v -> System.arraycopy(to, 0, v.v, 0, to.size) }.tintColor
        set(value) {
            target(BlurProperty.TINT_RED, (value shr 16 and 0xFF).toFloat())
            target(BlurProperty.TINT_GREEN, (value shr 8 and 0xFF).toFloat())
            target(BlurProperty.TINT_BLUE, (value and 0xFF).toFloat())
        }

    public fun radius(from: Float, to: Float) {
        radius = to
        require(from >= 0f) { "radius must be >= 0" }
        start(BlurProperty.RADIUS, from)
    }

    public fun tintAlpha(from: Float, to: Float) {
        tintAlpha = to
        require(from in 0f..1f) { "tintAlpha must be in 0..1" }
        start(BlurProperty.TINT_ALPHA, from)
    }

    public fun saturation(from: Float, to: Float) {
        saturation = to
        start(BlurProperty.SATURATION, from)
    }

    public fun brightness(from: Float, to: Float) {
        brightness = to
        start(BlurProperty.BRIGHTNESS, from)
    }

    public fun tween(durationMillis: Long, interpolator: BlurInterpolator = this.interpolator) {
        spec = BlurAnimationSpec.Tween(durationMillis, interpolator)
    }

    public fun spring(block: SpringBuilder.() -> Unit = {}) {
        val s = SpringBuilder().apply(block)
        spec = BlurAnimationSpec.Spring(s.dampingRatio, s.stiffness)
    }

    public fun snap() {
        spec = BlurAnimationSpec.Snap
    }

    private fun target(p: BlurProperty, value: Float) {
        mask = mask or p.bit
        to[p.ordinal] = value
    }

    private fun start(p: BlurProperty, value: Float) {
        fromMask = fromMask or p.bit
        from[p.ordinal] = value
    }

    public fun build(): BlurAnimation {
        val resolved = spec ?: BlurAnimationSpec.Tween(duration ?: 300L, interpolator)
        return BlurAnimation(mask, fromMask, from.copyOf(), to.copyOf(), resolved, onEnd)
    }

    public class SpringBuilder internal constructor() {
        public var dampingRatio: Float = 0.75f
        public var stiffness: Float = 350f
    }

    public companion object {
        /** Damping ratios and stiffness values matching AndroidX SpringForce presets. */
        public const val DAMPING_NO_BOUNCY: Float = 1f
        public const val DAMPING_LOW_BOUNCY: Float = 0.75f
        public const val DAMPING_MEDIUM_BOUNCY: Float = 0.5f
        public const val STIFFNESS_LOW: Float = 200f
        public const val STIFFNESS_MEDIUM: Float = 1500f
    }
}

/** Builds a [BlurAnimation]. */
public fun blurAnimation(block: BlurAnimationBuilder.() -> Unit): BlurAnimation =
    BlurAnimationBuilder().apply(block).build()
