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

import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.RenderEffect
import android.graphics.Shader
import android.os.Build
import androidx.annotation.RequiresApi
import dev.blurengine.mrsluffy.algorithms.ColorAdjust
import dev.blurengine.mrsluffy.core.BlurConfig
import dev.blurengine.mrsluffy.core.BlurMath

/** Builds platform [RenderEffect]s that match the engine's blur and color conventions. */
@RequiresApi(Build.VERSION_CODES.S)
public object BlurRenderEffects {
    /**
     * Blur (sigma = [radius] / 2, edges clamped) followed by saturation, brightness and tint.
     * Returns null when the config changes nothing.
     *
     * [radius] defaults to [BlurConfig.radius] for effects applied to a full-size view. Pass
     * [BlurConfig.effectiveRadius] when drawing a downsampled image.
     */
    public fun create(config: BlurConfig, radius: Float = config.radius): RenderEffect? {
        val platformRadius = platformRadiusFor(radius)
        val blur = if (platformRadius > 0f) {
            RenderEffect.createBlurEffect(platformRadius, platformRadius, Shader.TileMode.CLAMP)
        } else {
            null
        }
        val matrix = colorMatrix(config) ?: return blur
        val filter = ColorMatrixColorFilter(matrix)
        return if (blur != null) RenderEffect.createColorFilterEffect(filter, blur) else RenderEffect.createColorFilterEffect(filter)
    }

    /**
     * Converts an engine radius to the value [RenderEffect.createBlurEffect] expects. The platform
     * turns its argument into sigma with Skia's legacy rule sigma = 0.57735 * r + 0.5, so this
     * inverts that to hit sigma = radius / 2.
     */
    public fun platformRadiusFor(radius: Float): Float {
        val sigma = BlurMath.sigmaForRadius(radius)
        return if (sigma > 0.5f) (sigma - 0.5f) / 0.57735f else 0f
    }

    /**
     * Saturation (Rec. 709 luma), then brightness, then tint, as one matrix. Matches
     * [ColorAdjust.apply]. Returns null if the matrix would be the identity.
     */
    public fun colorMatrix(config: BlurConfig): ColorMatrix? {
        if (ColorAdjust.isNoop(config.saturation, config.brightness, config.tintAlpha)) return null
        val s = config.saturation
        val keep = config.brightness * (1f - config.tintAlpha)
        val lr = 0.2126f * (1f - s)
        val lg = 0.7152f * (1f - s)
        val lb = 0.0722f * (1f - s)
        val ta = config.tintAlpha
        val tr = (config.tintColor shr 16 and 0xFF) * ta
        val tg = (config.tintColor shr 8 and 0xFF) * ta
        val tb = (config.tintColor and 0xFF) * ta
        return ColorMatrix(
            floatArrayOf(
                (lr + s) * keep, lg * keep, lb * keep, 0f, tr,
                lr * keep, (lg + s) * keep, lb * keep, 0f, tg,
                lr * keep, lg * keep, (lb + s) * keep, 0f, tb,
                0f, 0f, 0f, 1f, 0f,
            ),
        )
    }
}
