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
 * Progressive (gradient) blur: blur strength varies linearly along a line, so a surface can fade
 * from sharp to fully blurred, for example under a status bar or above a bottom bar.
 *
 * Points are fractions of the surface (0..1): (0, 0) is the top-left, (1, 1) the bottom-right.
 * Intensity is a fraction of [BlurConfig.radius], and tint and color adjustment fade with it. At
 * intensity 0 the content behind shows through untouched.
 */
public data class BlurGradient(
    val startX: Float = 0.5f,
    val startY: Float = 0f,
    val endX: Float = 0.5f,
    val endY: Float = 1f,
    val startIntensity: Float = 0f,
    val endIntensity: Float = 1f,
) {
    init {
        require(listOf(startX, startY, endX, endY).all { it.isFinite() }) { "gradient points must be finite" }
        require(startIntensity in 0f..1f && endIntensity in 0f..1f) { "intensities must be in 0..1" }
        require(startX != endX || startY != endY) { "start and end must differ" }
    }

    /** Intensity at fraction [t] along the line from start (0) to end (1), clamped. */
    public fun intensityAt(t: Float): Float {
        val c = t.coerceIn(0f, 1f)
        return startIntensity + (endIntensity - startIntensity) * c
    }

    /** True when the blur is zero along the whole line. */
    public val isInvisible: Boolean get() = startIntensity == 0f && endIntensity == 0f

    public companion object {
        /** Sharp at the top, fully blurred at the bottom. */
        public val TopToBottom: BlurGradient = BlurGradient()

        /** Sharp at the bottom, fully blurred at the top: the usual progressive status-bar blur. */
        public val BottomToTop: BlurGradient = BlurGradient(startY = 1f, endY = 0f)
        public val LeftToRight: BlurGradient = BlurGradient(startX = 0f, startY = 0.5f, endX = 1f, endY = 0.5f)
        public val RightToLeft: BlurGradient = BlurGradient(startX = 1f, startY = 0.5f, endX = 0f, endY = 0.5f)
    }
}

/**
 * Liquid glass material drawn on top of the blur: a bright rim on the lit side, a soft gloss and
 * edge shading, and, on API 33+, refraction and color dispersion at the rim.
 *
 * Refraction compresses the content just inside the rim toward the edge, like the edge of a thick
 * lens. It runs as a GPU shader after the blur, so it costs no main-thread time. Below API 33
 * the lighting is drawn without refraction.
 *
 * The surface's corner radius ([BlurConfig.cornerRadius]) sets the shape of the rim.
 *
 * @property refraction how far the rim bends content, in pixels at the outermost edge. API 33+.
 * @property edgeWidth width of the refracting and highlighted rim, in pixels.
 * @property dispersion 0..1, how much red and blue separate at the rim (chromatic aberration). API 33+.
 * @property highlight 0..1, strength of the bright rim on the lit side.
 * @property lightAngle direction the light comes from, in degrees: 0 = right, 90 = bottom,
 *   180 = left, 270 = top (screen coordinates, clockwise). The default is the top-left.
 * @property gloss 0..1, strength of the sheen across the lit half of the surface.
 */
public data class GlassStyle(
    val refraction: Float = 24f,
    val edgeWidth: Float = 28f,
    val dispersion: Float = 0.3f,
    val highlight: Float = 0.6f,
    val lightAngle: Float = 225f,
    val gloss: Float = 0.12f,
) {
    init {
        require(refraction >= 0f && refraction.isFinite()) { "refraction must be >= 0" }
        require(edgeWidth > 0f && edgeWidth.isFinite()) { "edgeWidth must be > 0" }
        require(dispersion in 0f..1f) { "dispersion must be in 0..1" }
        require(highlight in 0f..1f) { "highlight must be in 0..1" }
        require(gloss in 0f..1f) { "gloss must be in 0..1" }
        require(lightAngle.isFinite()) { "lightAngle must be finite" }
    }
}
