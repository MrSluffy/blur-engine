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

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BlurEffectsTest {
    @Test fun gradientIntensityIsLinearAndClamped() {
        val g = BlurGradient(startIntensity = 0.2f, endIntensity = 1f)
        assertEquals(0.2f, g.intensityAt(0f))
        assertEquals(0.6f, g.intensityAt(0.5f), 1e-6f)
        assertEquals(1f, g.intensityAt(1f))
        assertEquals(0.2f, g.intensityAt(-3f))
        assertEquals(1f, g.intensityAt(7f))
    }

    @Test fun gradientValidation() {
        assertFailsWith<IllegalArgumentException> { BlurGradient(startIntensity = 1.5f) }
        assertFailsWith<IllegalArgumentException> { BlurGradient(startX = 0.3f, startY = 0.3f, endX = 0.3f, endY = 0.3f) }
        assertFailsWith<IllegalArgumentException> { BlurGradient(endY = Float.NaN) }
        assertTrue(BlurGradient(startIntensity = 0f, endIntensity = 0f).isInvisible)
        assertFalse(BlurGradient.TopToBottom.isInvisible)
    }

    @Test fun presetsPointTheRightWay() {
        assertEquals(0f, BlurGradient.TopToBottom.startY)
        assertEquals(1f, BlurGradient.BottomToTop.startY)
        assertEquals(0f, BlurGradient.LeftToRight.startX)
        assertEquals(1f, BlurGradient.RightToLeft.startX)
    }

    @Test fun glassValidation() {
        assertFailsWith<IllegalArgumentException> { GlassStyle(refraction = -1f) }
        assertFailsWith<IllegalArgumentException> { GlassStyle(edgeWidth = 0f) }
        assertFailsWith<IllegalArgumentException> { GlassStyle(dispersion = 2f) }
        assertFailsWith<IllegalArgumentException> { GlassStyle(highlight = -0.1f) }
        GlassStyle(refraction = 0f) // refraction off, lighting only: valid
    }

    @Test fun dslAndCopyKeepEffects() {
        val c = blurConfig {
            radius = 30f
            gradient = BlurGradient.BottomToTop
            glass = GlassStyle(highlight = 0.9f)
        }
        assertEquals(BlurGradient.BottomToTop, c.gradient)
        assertEquals(0.9f, c.glass?.highlight)
        // Animations and setters go through copy(); effects must survive it.
        val animated = c.copy(radius = 12f)
        assertEquals(c.gradient, animated.gradient)
        assertEquals(c.glass, animated.glass)
    }
}
