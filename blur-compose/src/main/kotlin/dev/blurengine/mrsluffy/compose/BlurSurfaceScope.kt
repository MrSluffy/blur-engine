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

import android.view.View
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.blurengine.mrsluffy.core.BlurAlgorithm
import dev.blurengine.mrsluffy.core.BlurConfig
import dev.blurengine.mrsluffy.core.BlurGradient
import dev.blurengine.mrsluffy.core.BlurQuality
import dev.blurengine.mrsluffy.core.GlassStyle

/**
 * Settings for [blurSurface]. The block that fills this in runs during the frame, not during
 * composition, and re-runs when Compose state it reads changes. Animated values
 * (`animateDpAsState`, `Animatable`) therefore update the blur without recomposing.
 */
public class BlurSurfaceScope internal constructor() {
    /** Blur radius; sigma = radius / 2. */
    public var radius: Dp = 20.dp

    /** Tint drawn over the blur. Its alpha is the tint opacity. */
    public var tint: Color = Color.Transparent

    /** 1 = unchanged, 0 = grayscale. */
    public var saturation: Float = 1f

    /** RGB multiplier, 1 = unchanged. */
    public var brightness: Float = 1f

    /**
     * Clips the blur and the content. Null means no clipping. Liquid glass needs a rounded
     * rectangle for its rim; set it with [cornerRadius].
     */
    public var shape: Shape? = null

    public var algorithm: BlurAlgorithm = BlurAlgorithm.AUTO
    public var quality: BlurQuality = BlurQuality.BALANCED

    /** Capture and blur at 1/downsample resolution. Higher is faster and softer. */
    public var downsample: Int = 4

    /**
     * Fraction of [radius] and tint opacity actually shown, 0..1, for gesture-driven effects.
     * 0 shows the content behind unblurred and skips the capture entirely.
     */
    public var progress: Float = 1f

    /** What to blur. Null (the default) means the whole window. */
    public var source: View? = null

    /**
     * Progressive blur, e.g. `BlurGradient.BottomToTop` for a scrim that fades out downward.
     * Null (the default) blurs uniformly.
     */
    public var gradient: BlurGradient? = null

    private var cornerRadiusDp: Dp = 0.dp
    private var glassSpec: GlassSpec? = null

    public fun tint(color: Color) {
        tint = color
    }

    public fun cornerRadius(radius: Dp) {
        shape = RoundedCornerShape(radius)
        cornerRadiusDp = radius
    }

    /**
     * Liquid glass material: rim highlight, gloss and edge shading on every API level, plus
     * refraction and dispersion on API 33+. The rim follows [cornerRadius].
     *
     * @param refraction how far the rim bends the content toward the edge (API 33+).
     * @param edgeWidth width of the refracting, highlighted rim.
     * @param dispersion 0..1 color separation at the rim (API 33+).
     * @param highlight 0..1 strength of the bright rim on the lit side.
     * @param lightAngle degrees the light comes from: 0 = right, 90 = bottom, 180 = left, 270 = top.
     * @param gloss 0..1 strength of the sheen on the lit half.
     */
    public fun glass(
        refraction: Dp = 10.dp,
        edgeWidth: Dp = 12.dp,
        dispersion: Float = 0.3f,
        highlight: Float = 0.6f,
        lightAngle: Float = 225f,
        gloss: Float = 0.12f,
    ) {
        glassSpec = GlassSpec(refraction, edgeWidth, dispersion, highlight, lightAngle, gloss)
    }

    private class GlassSpec(
        val refraction: Dp,
        val edgeWidth: Dp,
        val dispersion: Float,
        val highlight: Float,
        val lightAngle: Float,
        val gloss: Float,
    )

    internal fun reset() {
        radius = 20.dp; tint = Color.Transparent; saturation = 1f; brightness = 1f; shape = null
        algorithm = BlurAlgorithm.AUTO; quality = BlurQuality.BALANCED; downsample = 4; progress = 1f
        source = null; gradient = null; cornerRadiusDp = 0.dp; glassSpec = null
    }

    internal fun toConfig(density: Density): BlurConfig {
        val p = progress.coerceIn(0f, 1f)
        val px = with(density) { radius.toPx() }.coerceAtLeast(0f)
        val glass = glassSpec?.let { g ->
            with(density) {
                GlassStyle(
                    refraction = g.refraction.toPx().coerceAtLeast(0f),
                    edgeWidth = g.edgeWidth.toPx().coerceAtLeast(1f),
                    dispersion = g.dispersion,
                    highlight = g.highlight,
                    lightAngle = g.lightAngle,
                    gloss = g.gloss,
                )
            }
        }
        return BlurConfig(
            radius = px * p,
            algorithm = algorithm,
            quality = quality,
            downsample = downsample,
            tintColor = tint.copy(alpha = 1f).toArgb(),
            tintAlpha = (tint.alpha * p).coerceIn(0f, 1f),
            saturation = saturation,
            brightness = brightness,
            cornerRadius = with(density) { cornerRadiusDp.toPx() }.coerceAtLeast(0f),
            gradient = gradient,
            glass = glass,
        )
    }
}
