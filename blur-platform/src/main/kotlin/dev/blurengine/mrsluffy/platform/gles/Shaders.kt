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

package dev.blurengine.mrsluffy.platform.gles

/**
 * GLSL ES 1.00 shaders. Each mirrors a CPU pass in blur-algorithms so both paths produce the
 * same image: texture coordinates follow the same pixel-centre conventions and offsets are in
 * source texels. Images stay premultiplied throughout.
 */
internal object Shaders {
    /** Max Gaussian taps per side (plus the centre), bounded by uniform array size. */
    const val MAX_TAPS = 32

    const val VERTEX = """
attribute vec2 aPos;
varying vec2 vTex;
void main() {
    vTex = aPos * 0.5 + 0.5;
    gl_Position = vec4(aPos, 0.0, 1.0);
}
"""

    // highp keeps texture coordinates exact on large images; mediump has only 10 mantissa bits.
    private const val HEADER = """
#ifdef GL_FRAGMENT_PRECISION_HIGH
precision highp float;
#else
precision mediump float;
#endif
varying vec2 vTex;
uniform sampler2D uTex;
"""

    /** Area average for downsampling: four bilinear taps each averaging 2x2 texels. uOffset = factor/4 texels. */
    const val DOWNSAMPLE = HEADER + """
uniform vec2 uOffset;
void main() {
    gl_FragColor = 0.25 * (
        texture2D(uTex, vTex + vec2(-uOffset.x, -uOffset.y)) +
        texture2D(uTex, vTex + vec2( uOffset.x, -uOffset.y)) +
        texture2D(uTex, vTex + vec2(-uOffset.x,  uOffset.y)) +
        texture2D(uTex, vTex + vec2( uOffset.x,  uOffset.y)));
}
"""

    /** Dual Kawase downsample: centre x4 plus four diagonal taps, / 8. uOffset = offset in source texels. */
    const val KAWASE_DOWN = HEADER + """
uniform vec2 uOffset;
void main() {
    vec4 sum = texture2D(uTex, vTex) * 4.0;
    sum += texture2D(uTex, vTex - uOffset);
    sum += texture2D(uTex, vTex + uOffset);
    sum += texture2D(uTex, vTex + vec2(uOffset.x, -uOffset.y));
    sum += texture2D(uTex, vTex + vec2(-uOffset.x, uOffset.y));
    gl_FragColor = sum / 8.0;
}
"""

    /** Dual Kawase upsample: four axis taps (weight 1) and four half-offset diagonals (weight 2), / 12. */
    const val KAWASE_UP = HEADER + """
uniform vec2 uOffset;
void main() {
    vec2 d = uOffset * 0.5;
    vec4 sum = texture2D(uTex, vTex + vec2(-uOffset.x, 0.0));
    sum += texture2D(uTex, vTex + vec2(uOffset.x, 0.0));
    sum += texture2D(uTex, vTex + vec2(0.0, -uOffset.y));
    sum += texture2D(uTex, vTex + vec2(0.0, uOffset.y));
    sum += texture2D(uTex, vTex + vec2(-d.x, -d.y)) * 2.0;
    sum += texture2D(uTex, vTex + vec2(d.x, -d.y)) * 2.0;
    sum += texture2D(uTex, vTex + vec2(-d.x, d.y)) * 2.0;
    sum += texture2D(uTex, vTex + vec2(d.x, d.y)) * 2.0;
    gl_FragColor = sum / 12.0;
}
"""

    /** One separable Gaussian pass. Taps merge neighbouring kernel weights and rely on bilinear filtering. */
    const val GAUSSIAN = HEADER + """
uniform vec2 uStep;
uniform float uWeights[$MAX_TAPS];
uniform float uOffsets[$MAX_TAPS];
uniform int uCount;
void main() {
    vec4 sum = texture2D(uTex, vTex) * uWeights[0];
    for (int i = 1; i < $MAX_TAPS; i++) {
        if (i >= uCount) break;
        vec2 o = uStep * uOffsets[i];
        sum += (texture2D(uTex, vTex + o) + texture2D(uTex, vTex - o)) * uWeights[i];
    }
    gl_FragColor = sum;
}
"""

    /** Bilinear upscale plus saturation, brightness and tint (same order and luma weights as ColorAdjust). */
    const val FINISH = HEADER + """
uniform float uSaturation;
uniform float uBrightness;
uniform vec3 uTint;
uniform float uTintAlpha;
void main() {
    vec4 c = texture2D(uTex, vTex);
    float luma = dot(c.rgb, vec3(0.2126, 0.7152, 0.0722));
    c.rgb = mix(vec3(luma), c.rgb, uSaturation) * uBrightness;
    c.rgb = mix(c.rgb, uTint * c.a, uTintAlpha);
    gl_FragColor = clamp(c, 0.0, 1.0);
}
"""
}
