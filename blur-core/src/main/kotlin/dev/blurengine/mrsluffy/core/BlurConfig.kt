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

package dev.blurengine.mrsluffy.core

/**
 * Immutable blur configuration. Colors are ARGB ints (same layout as android.graphics.Color).
 *
 * @property radius blur radius in pixels of the source content (before downsampling).
 * @property downsample integer factor applied to the source before blurring (1 = none).
 * @property saturation 1 = unchanged, 0 = grayscale.
 * @property brightness multiplier on RGB, 1 = unchanged.
 * @property cornerRadius clip radius in pixels; applied by the view layer, carried here for convenience.
 */
public data class BlurConfig(
    val radius: Float = 20f,
    val algorithm: BlurAlgorithm = BlurAlgorithm.AUTO,
    val quality: BlurQuality = BlurQuality.BALANCED,
    val downsample: Int = 4,
    val tintColor: Int = 0x00000000,
    val tintAlpha: Float = 0f,
    val saturation: Float = 1f,
    val brightness: Float = 1f,
    val cornerRadius: Float = 0f,
    /** Progressive blur; null means uniform. */
    val gradient: BlurGradient? = null,
    /** Liquid glass material; null means plain frosted blur. */
    val glass: GlassStyle? = null,
) {
    init {
        require(radius >= 0f && radius.isFinite()) { "radius must be >= 0, was $radius" }
        require(downsample >= 1) { "downsample must be >= 1, was $downsample" }
        require(tintAlpha in 0f..1f) { "tintAlpha must be in 0..1, was $tintAlpha" }
        require(saturation >= 0f && saturation.isFinite()) { "saturation must be >= 0, was $saturation" }
        require(brightness >= 0f && brightness.isFinite()) { "brightness must be >= 0, was $brightness" }
        require(cornerRadius >= 0f && cornerRadius.isFinite()) { "cornerRadius must be >= 0, was $cornerRadius" }
    }

    /** Radius in downsampled-buffer pixels, which is what the blur kernels operate on. */
    public val effectiveRadius: Float get() = radius / downsample

    /** Resolves [BlurAlgorithm.AUTO] to a concrete algorithm. Never returns AUTO. */
    public fun resolvedAlgorithm(): BlurAlgorithm = when (algorithm) {
        BlurAlgorithm.AUTO ->
            if (quality >= BlurQuality.HIGH && effectiveRadius <= 12f) BlurAlgorithm.GAUSSIAN
            else BlurAlgorithm.DUAL_KAWASE
        else -> algorithm
    }
}

/** DSL builder for [BlurConfig]. */
public class BlurConfigBuilder internal constructor(base: BlurConfig) {
    public var radius: Float = base.radius
    public var algorithm: BlurAlgorithm = base.algorithm
    public var quality: BlurQuality = base.quality
    public var downsample: Int = base.downsample
    public var tintColor: Int = base.tintColor
    public var tintAlpha: Float = base.tintAlpha
    public var saturation: Float = base.saturation
    public var brightness: Float = base.brightness
    public var cornerRadius: Float = base.cornerRadius
    public var gradient: BlurGradient? = base.gradient
    public var glass: GlassStyle? = base.glass

    public fun overlay(block: OverlayScope.() -> Unit) {
        val s = OverlayScope(tintColor, tintAlpha).apply(block)
        tintColor = s.color
        tintAlpha = s.alpha
    }

    public fun tint(color: Int, alpha: Float) {
        tintColor = color
        tintAlpha = alpha
    }

    public fun shape(block: ShapeScope.() -> Unit) {
        cornerRadius = ShapeScope(cornerRadius).apply(block).cornerRadius
    }

    public fun performance(block: PerformanceScope.() -> Unit) {
        val s = PerformanceScope(quality, downsample).apply(block)
        quality = s.quality
        downsample = s.downsample
    }

    internal fun build(): BlurConfig = BlurConfig(
        radius, algorithm, quality, downsample, tintColor, tintAlpha, saturation, brightness, cornerRadius,
        gradient, glass,
    )
}

public class OverlayScope internal constructor(public var color: Int, public var alpha: Float)
public class ShapeScope internal constructor(public var cornerRadius: Float)
public class PerformanceScope internal constructor(public var quality: BlurQuality, public var downsample: Int)

/** Builds a [BlurConfig] using the DSL, starting from [base]. */
public fun blurConfig(base: BlurConfig = BlurConfig(), block: BlurConfigBuilder.() -> Unit): BlurConfig =
    BlurConfigBuilder(base).apply(block).build()

