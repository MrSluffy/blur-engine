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

import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Damped spring, solved in closed form for each step (same equations as AndroidX SpringForce),
 * so it is stable at any frame interval. Mass is 1.
 */
public object BlurSpring {
    /**
     * Advances a spring by [dt] seconds. [displacement] is value - target; results are written to
     * [out] as (new displacement, new velocity) to avoid allocating.
     */
    public fun step(
        displacement: Float,
        velocity: Float,
        dt: Float,
        dampingRatio: Float,
        stiffness: Float,
        out: FloatArray,
    ) {
        val x0 = displacement.toDouble()
        val v0 = velocity.toDouble()
        val t = dt.toDouble()
        val w = sqrt(stiffness.toDouble())
        val z = dampingRatio.toDouble()
        val x: Double
        val v: Double
        when {
            z > 1.0 -> {
                val root = w * sqrt(z * z - 1)
                val gPlus = -z * w + root
                val gMinus = -z * w - root
                val b = (gMinus * x0 - v0) / (gMinus - gPlus)
                val a = x0 - b
                val eMinus = exp(gMinus * t)
                val ePlus = exp(gPlus * t)
                x = a * eMinus + b * ePlus
                v = a * gMinus * eMinus + b * gPlus * ePlus
            }
            z == 1.0 -> {
                val a = x0
                val b = v0 + w * x0
                val e = exp(-w * t)
                x = (a + b * t) * e
                v = (b - w * (a + b * t)) * e
            }
            else -> {
                val wd = w * sqrt(1 - z * z)
                val cosC = x0
                val sinC = (z * w * x0 + v0) / wd
                val e = exp(-z * w * t)
                val c = cos(wd * t)
                val s = sin(wd * t)
                x = e * (cosC * c + sinC * s)
                v = -z * w * x + e * (-wd * cosC * s + wd * sinC * c)
            }
        }
        out[0] = x.toFloat()
        out[1] = v.toFloat()
    }
}
