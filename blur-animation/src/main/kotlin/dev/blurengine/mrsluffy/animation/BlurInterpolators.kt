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

import kotlin.math.abs

/** Maps elapsed fraction (0..1) to progress. Must not allocate. */
public fun interface BlurInterpolator {
    public fun interpolate(fraction: Float): Float
}

public object BlurInterpolators {
    public val LINEAR: BlurInterpolator = BlurInterpolator { it }

    /** Cubic ease in and out. */
    public val EASE_IN_OUT: BlurInterpolator = BlurInterpolator { t ->
        if (t < 0.5f) 4f * t * t * t else 1f - (-2f * t + 2f).let { it * it * it } / 2f
    }

    /** Quadratic ease out. */
    public val DECELERATE: BlurInterpolator = BlurInterpolator { t -> 1f - (1f - t) * (1f - t) }

    /** Material standard easing, cubic-bezier(0.4, 0, 0.2, 1). */
    public val FAST_OUT_SLOW_IN: BlurInterpolator = cubicBezier(0.4f, 0f, 0.2f, 1f)

    /** CSS-style cubic Bézier from (0,0) to (1,1) with control points (x1,y1) and (x2,y2). */
    public fun cubicBezier(x1: Float, y1: Float, x2: Float, y2: Float): BlurInterpolator {
        require(x1 in 0f..1f && x2 in 0f..1f) { "x control points must be in 0..1" }
        return BlurInterpolator { fraction ->
            when {
                fraction <= 0f -> 0f
                fraction >= 1f -> 1f
                else -> bezier(solveT(fraction, x1, x2), y1, y2)
            }
        }
    }

    private fun bezier(t: Float, p1: Float, p2: Float): Float {
        val u = 1f - t
        return 3f * u * u * t * p1 + 3f * u * t * t * p2 + t * t * t
    }

    private fun bezierSlope(t: Float, p1: Float, p2: Float): Float {
        val u = 1f - t
        return 3f * u * u * p1 + 6f * u * t * (p2 - p1) + 3f * t * t * (1f - p2)
    }

    /** Finds t with x(t) = x: Newton steps, falling back to bisection. */
    private fun solveT(x: Float, x1: Float, x2: Float): Float {
        var t = x
        repeat(8) {
            val err = bezier(t, x1, x2) - x
            if (abs(err) < 1e-5f) return t
            val slope = bezierSlope(t, x1, x2)
            if (abs(slope) < 1e-6f) return@repeat
            t -= err / slope
        }
        var lo = 0f
        var hi = 1f
        t = x
        repeat(30) {
            val value = bezier(t, x1, x2)
            if (abs(value - x) < 1e-5f) return t
            if (value < x) lo = t else hi = t
            t = (lo + hi) / 2f
        }
        return t
    }
}
