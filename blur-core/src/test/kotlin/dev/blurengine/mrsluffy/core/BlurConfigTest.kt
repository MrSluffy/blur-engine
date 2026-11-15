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
import kotlin.test.assertNotEquals

class BlurConfigTest {
    @Test fun rejectsInvalidValues() {
        assertFailsWith<IllegalArgumentException> { BlurConfig(radius = -1f) }
        assertFailsWith<IllegalArgumentException> { BlurConfig(downsample = 0) }
        assertFailsWith<IllegalArgumentException> { BlurConfig(tintAlpha = 1.5f) }
        assertFailsWith<IllegalArgumentException> { BlurConfig(radius = Float.NaN) }
    }

    @Test fun dslBuildsConfig() {
        val c = blurConfig {
            radius = 24f
            algorithm = BlurAlgorithm.DUAL_KAWASE
            overlay { color = 0xFFFFFF; alpha = 0.18f }
            shape { cornerRadius = 12f }
            performance { quality = BlurQuality.HIGH; downsample = 2 }
        }
        assertEquals(24f, c.radius)
        assertEquals(0.18f, c.tintAlpha)
        assertEquals(12f, c.cornerRadius)
        assertEquals(BlurQuality.HIGH, c.quality)
        assertEquals(2, c.downsample)
        assertEquals(12f, c.effectiveRadius)
    }

    @Test fun autoNeverResolvesToAuto() {
        for (q in BlurQuality.values()) for (r in listOf(0f, 4f, 30f, 200f)) {
            assertNotEquals(BlurAlgorithm.AUTO, BlurConfig(radius = r, quality = q).resolvedAlgorithm())
        }
    }

    @Test fun explicitAlgorithmIsKept() {
        assertEquals(BlurAlgorithm.BOX, BlurConfig(algorithm = BlurAlgorithm.BOX).resolvedAlgorithm())
    }
}
