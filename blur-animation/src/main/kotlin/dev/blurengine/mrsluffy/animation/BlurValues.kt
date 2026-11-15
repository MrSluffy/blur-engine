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

/** One animatable property. Each is animated independently. */
public enum class BlurProperty {
    RADIUS, TINT_ALPHA, SATURATION, BRIGHTNESS, TINT_RED, TINT_GREEN, TINT_BLUE;

    internal val bit: Int get() = 1 shl ordinal

    internal companion object {
        val ALL: Array<BlurProperty> = values()
        const val COUNT = 7
        const val TINT_COLOR_MASK = (1 shl 4) or (1 shl 5) or (1 shl 6)
    }
}

/**
 * The animatable part of a blur: radius, tint, saturation and brightness. Mutable and reused
 * between frames, so animation does not allocate. Tint color channels are animated separately
 * in sRGB (0..255).
 *
 * Values may overshoot their valid range while a spring settles; [toConfig] clamps them.
 */
public class BlurValues(
    radius: Float = 0f,
    tintColor: Int = 0,
    tintAlpha: Float = 0f,
    saturation: Float = 1f,
    brightness: Float = 1f,
) {
    internal val v = FloatArray(BlurProperty.COUNT)

    public var radius: Float
        get() = v[BlurProperty.RADIUS.ordinal]
        set(value) { v[BlurProperty.RADIUS.ordinal] = value }

    public var tintAlpha: Float
        get() = v[BlurProperty.TINT_ALPHA.ordinal]
        set(value) { v[BlurProperty.TINT_ALPHA.ordinal] = value }

    public var saturation: Float
        get() = v[BlurProperty.SATURATION.ordinal]
        set(value) { v[BlurProperty.SATURATION.ordinal] = value }

    public var brightness: Float
        get() = v[BlurProperty.BRIGHTNESS.ordinal]
        set(value) { v[BlurProperty.BRIGHTNESS.ordinal] = value }

    /** Opaque RGB color; the alpha byte is ignored (use [tintAlpha]). */
    public var tintColor: Int
        get() = (0xFF shl 24) or (channel(BlurProperty.TINT_RED) shl 16) or
            (channel(BlurProperty.TINT_GREEN) shl 8) or channel(BlurProperty.TINT_BLUE)
        set(value) {
            v[BlurProperty.TINT_RED.ordinal] = (value shr 16 and 0xFF).toFloat()
            v[BlurProperty.TINT_GREEN.ordinal] = (value shr 8 and 0xFF).toFloat()
            v[BlurProperty.TINT_BLUE.ordinal] = (value and 0xFF).toFloat()
        }

    init {
        this.radius = radius
        this.tintColor = tintColor
        this.tintAlpha = tintAlpha
        this.saturation = saturation
        this.brightness = brightness
    }

    public operator fun get(property: BlurProperty): Float = v[property.ordinal]

    public operator fun set(property: BlurProperty, value: Float) {
        v[property.ordinal] = value
    }

    public fun setFrom(other: BlurValues) {
        System.arraycopy(other.v, 0, v, 0, v.size)
    }

    public fun setFrom(config: BlurConfig) {
        radius = config.radius
        tintColor = config.tintColor
        tintAlpha = config.tintAlpha
        saturation = config.saturation
        brightness = config.brightness
    }

    /** [base] with these values applied, clamped to valid ranges. */
    public fun toConfig(base: BlurConfig): BlurConfig = base.copy(
        radius = radius.coerceAtLeast(0f),
        tintColor = tintColor,
        tintAlpha = tintAlpha.coerceIn(0f, 1f),
        saturation = saturation.coerceAtLeast(0f),
        brightness = brightness.coerceAtLeast(0f),
    )

    private fun channel(p: BlurProperty): Int = (v[p.ordinal] + 0.5f).toInt().coerceIn(0, 255)

    override fun toString(): String =
        "BlurValues(radius=$radius, tint=#${Integer.toHexString(tintColor)}, tintAlpha=$tintAlpha, " +
            "saturation=$saturation, brightness=$brightness)"

    public companion object {
        public fun from(config: BlurConfig): BlurValues = BlurValues().apply { setFrom(config) }
    }
}
