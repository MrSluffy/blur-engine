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

package dev.blurengine.mrsluffy.platform

import android.graphics.RenderEffect
import android.graphics.RuntimeShader
import android.os.Build
import androidx.annotation.RequiresApi
import dev.blurengine.mrsluffy.core.GlassStyle

/**
 * Liquid glass refraction as a [RenderEffect] (API 33+, AGSL).
 *
 * Inside a rim of [GlassStyle.edgeWidth] around a rounded rectangle, each pixel samples its input
 * further in along the shape's normal, so the rim shows the content just inside it compressed
 * toward the edge, like the edge of a thick convex lens. The bend grows smoothly toward the outer
 * edge, and red and blue are sampled at slightly different distances for dispersion. Pixels away
 * from the rim pass through unchanged.
 *
 * Samples stay inside the rectangle on purpose. The platform only guarantees an effect's input
 * where its output is drawn, so content beside a clipped surface can come back wrong (seen as
 * colored streaks along the rim).
 *
 * Coordinates are in the pixels of the node the effect is applied to. Create one instance per
 * surface: it reuses one compiled [RuntimeShader] and only builds a new [RenderEffect] when called.
 */
@RequiresApi(Build.VERSION_CODES.TIRAMISU)
public class GlassRenderEffects {
    private val shader = RuntimeShader(SHADER)

    /**
     * Refraction for a glass rectangle ([left], [top], [right], [bottom]) inside a node of
     * [nodeWidth] x [nodeHeight]. [scale] converts [style]'s pixel values and [cornerRadius] to node
     * pixels, for example 0.25 when the node is captured at downsample 4.
     *
     * @param input the effect to refract, typically the blur; null refracts the node's content as is.
     * @return null when [style] has no refraction.
     */
    public fun create(
        style: GlassStyle,
        input: RenderEffect?,
        nodeWidth: Float,
        nodeHeight: Float,
        left: Float,
        top: Float,
        right: Float,
        bottom: Float,
        cornerRadius: Float,
        scale: Float,
    ): RenderEffect? {
        if (style.refraction <= 0f) return input
        shader.setFloatUniform("size", nodeWidth, nodeHeight)
        shader.setFloatUniform("rect", left, top, right, bottom)
        shader.setFloatUniform("radius", cornerRadius * scale)
        shader.setFloatUniform("refraction", style.refraction * scale)
        shader.setFloatUniform("edge", maxOf(style.edgeWidth * scale, 1f))
        shader.setFloatUniform("dispersion", style.dispersion * DISPERSION_SCALE)
        val glass = RenderEffect.createRuntimeShaderEffect(shader, "content")
        // Chain: the inner (input) effect runs first, then the refraction reads its result.
        return if (input != null) RenderEffect.createChainEffect(glass, input) else glass
    }

    private companion object {
        /** GlassStyle.dispersion 1 separates red and blue by +-35% of the bend. */
        const val DISPERSION_SCALE = 0.35f

        const val SHADER = """
uniform shader content;
uniform float2 size;
uniform float4 rect;
uniform float radius;
uniform float refraction;
uniform float edge;
uniform float dispersion;

float sdRoundRect(float2 p, float2 c, float2 h, float r) {
    float2 q = abs(p - c) - h + float2(r, r);
    return min(max(q.x, q.y), 0.0) + length(max(q, float2(0.0, 0.0))) - r;
}

half4 sampleAt(float2 p) {
    return content.eval(clamp(p, float2(0.5, 0.5), size - float2(0.5, 0.5)));
}

half4 main(float2 p) {
    float2 c = (rect.xy + rect.zw) * 0.5;
    float2 h = (rect.zw - rect.xy) * 0.5;
    float r = min(radius, min(h.x, h.y));
    float d = sdRoundRect(p, c, h, r);
    if (d >= 0.0) return content.eval(p);
    float t = clamp(1.0 + d / edge, 0.0, 1.0);
    if (t <= 0.0) return content.eval(p);
    float2 n = float2(
        sdRoundRect(p + float2(1.0, 0.0), c, h, r) - sdRoundRect(p - float2(1.0, 0.0), c, h, r),
        sdRoundRect(p + float2(0.0, 1.0), c, h, r) - sdRoundRect(p - float2(0.0, 1.0), c, h, r));
    float len = length(n);
    if (len < 0.0001) return content.eval(p);
    n = n / len;
    float bend = t * t * (3.0 - 2.0 * t);
    float2 off = -n * (refraction * bend);
    half4 g = sampleAt(p + off);
    if (dispersion <= 0.0) return g;
    half4 rr = sampleAt(p + off * (1.0 + dispersion));
    half4 bb = sampleAt(p + off * (1.0 - dispersion));
    return half4(rr.r, g.g, bb.b, g.a);
}
"""
    }
}
