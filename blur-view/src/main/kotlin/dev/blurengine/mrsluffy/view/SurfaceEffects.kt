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

import android.graphics.BlendMode
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import dev.blurengine.mrsluffy.core.BlurGradient
import dev.blurengine.mrsluffy.core.GlassStyle
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Liquid glass lighting, drawn with Canvas on every API level: a bright rim on the lit side, a
 * fainter back-lit rim opposite, a gloss across the lit half, and soft shading inside the far edge.
 *
 * Shaders are rebuilt only when the size, corner radius or style changes. Fading (during hide()
 * or progress) uses paint alpha, so animated frames do not allocate.
 */
internal class GlassLighting {
    private val rim = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val shade = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val gloss = Paint(Paint.ANTI_ALIAS_FLAG)

    private var shadeWidth = 1f

    private var keyW = -1
    private var keyH = -1
    private var keyRadius = Float.NaN
    private var keyStyle: GlassStyle? = null

    /** Draws the lighting into a [w] x [h] surface. [fade] 0..1 scales every part. */
    fun draw(canvas: Canvas, w: Int, h: Int, cornerRadius: Float, style: GlassStyle, fade: Float) {
        if (fade <= 0f || w <= 0 || h <= 0) return
        prepare(w, h, cornerRadius, style)
        val r = min(cornerRadius, min(w, h) / 2f)
        val alpha = (fade * 255).roundToInt().coerceIn(0, 255)

        if (style.gloss > 0f) {
            gloss.alpha = alpha
            canvas.drawRoundRect(0f, 0f, w.toFloat(), h.toFloat(), r, r, gloss)
        }
        // Strokes are inset by half their width so they lie inside the shape, matching the clip.
        // The shade is SHADE_STEPS nested strokes, all starting at the edge and each one wider,
        // so it builds up toward the edge and fades out inward. A single stroke would leave a hard
        // inner edge that reads as a second, smaller panel.
        shade.alpha = (alpha / SHADE_STEPS.toFloat()).roundToInt().coerceAtLeast(1)
        for (i in 1..SHADE_STEPS) {
            val width = shadeWidth * i / SHADE_STEPS
            val inset = width / 2f
            shade.strokeWidth = width
            canvas.drawRoundRect(inset, inset, w - inset, h - inset,
                maxOf(r - inset, 0f), maxOf(r - inset, 0f), shade)
        }
        if (style.highlight > 0f) {
            val rimInset = rim.strokeWidth / 2f
            rim.alpha = alpha
            canvas.drawRoundRect(rimInset, rimInset, w - rimInset, h - rimInset,
                maxOf(r - rimInset, 0f), maxOf(r - rimInset, 0f), rim)
        }
    }

    private fun prepare(w: Int, h: Int, cornerRadius: Float, style: GlassStyle) {
        if (w == keyW && h == keyH && cornerRadius == keyRadius && style == keyStyle) return
        keyW = w; keyH = h; keyRadius = cornerRadius; keyStyle = style

        // Light comes from lightAngle: the lit point is on that side, the far point opposite.
        val a = Math.toRadians(style.lightAngle.toDouble())
        val reach = hypot(w.toDouble(), h.toDouble()) / 2
        val cx = w / 2f
        val cy = h / 2f
        val lx = (cx + cos(a) * reach).toFloat()
        val ly = (cy + sin(a) * reach).toFloat()
        val fx = (cx - cos(a) * reach).toFloat()
        val fy = (cy - sin(a) * reach).toFloat()

        val hl = style.highlight
        rim.strokeWidth = (style.edgeWidth * RIM_FRACTION).coerceIn(1f, MAX_RIM)
        rim.shader = LinearGradient(lx, ly, fx, fy,
            intArrayOf(white(0.85f * hl), white(0.25f * hl), white(0f), white(0.35f * hl)),
            floatArrayOf(0f, 0.3f, 0.6f, 1f), Shader.TileMode.CLAMP)

        shadeWidth = maxOf(style.edgeWidth * SHADE_FRACTION, 1f)
        shade.shader = LinearGradient(lx, ly, fx, fy,
            intArrayOf(black(0f), black(0f), black(0.10f + 0.12f * hl)),
            floatArrayOf(0f, 0.55f, 1f), Shader.TileMode.CLAMP)

        gloss.shader = LinearGradient(lx, ly, cx, cy,
            intArrayOf(white(style.gloss), white(style.gloss * 0.35f), white(0f)),
            floatArrayOf(0f, 0.55f, 1f), Shader.TileMode.CLAMP)
    }

    private fun white(a: Float) = ((a.coerceIn(0f, 1f) * 255).roundToInt() shl 24) or 0xFFFFFF
    private fun black(a: Float) = (a.coerceIn(0f, 1f) * 255).roundToInt() shl 24

    private companion object {
        const val RIM_FRACTION = 0.12f
        const val MAX_RIM = 6f
        const val SHADE_FRACTION = 0.5f

        /** Nested strokes in the shade; at the default strength each step is about 2% darker. */
        const val SHADE_STEPS = 8
    }
}

/**
 * Alpha masks for progressive blur. Each blur level is drawn into a layer, then the mask is
 * drawn with DST_IN so the level shows only as strongly as the gradient says.
 *
 * With two levels (mid and full), intensity 0..0.5 fades from clear to the mid blur and 0.5..1
 * from mid to full, which avoids the double image a single clear-to-full crossfade shows halfway.
 * Masks are rebuilt only when the size or gradient changes.
 */
internal class GradientMasks {
    val full = Paint().apply { blendMode = BlendMode.DST_IN }
    val mid = Paint().apply { blendMode = BlendMode.DST_IN }

    private var keyW = -1
    private var keyH = -1
    private var keyGradient: BlurGradient? = null
    private var keyLevels = -1

    /** Prepares masks for [levels] = 1 (full blur only) or 2 (mid and full). */
    fun prepare(w: Int, h: Int, gradient: BlurGradient, levels: Int) {
        if (w == keyW && h == keyH && gradient == keyGradient && levels == keyLevels) return
        keyW = w; keyH = h; keyGradient = gradient; keyLevels = levels
        val x0 = gradient.startX * w
        val y0 = gradient.startY * h
        val x1 = gradient.endX * w
        val y1 = gradient.endY * h
        val positions = FloatArray(STOPS) { it / (STOPS - 1f) }
        val fullColors = IntArray(STOPS)
        val midColors = IntArray(STOPS)
        for (i in 0 until STOPS) {
            val intensity = gradient.intensityAt(positions[i])
            if (levels == 1) {
                fullColors[i] = alpha(intensity)
            } else {
                midColors[i] = alpha(intensity * 2f)
                fullColors[i] = alpha(intensity * 2f - 1f)
            }
        }
        full.shader = LinearGradient(x0, y0, x1, y1, fullColors, positions, Shader.TileMode.CLAMP)
        if (levels == 2) mid.shader = LinearGradient(x0, y0, x1, y1, midColors, positions, Shader.TileMode.CLAMP)
    }

    private fun alpha(a: Float) = ((a.coerceIn(0f, 1f) * 255).roundToInt() shl 24) or 0xFFFFFF

    private companion object {
        /** Stops per mask: enough for the kinks at intensity 0.5 to stay sharp. */
        const val STOPS = 17
    }
}
