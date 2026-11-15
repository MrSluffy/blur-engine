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

package dev.blurengine.mrsluffy.capture

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class CaptureGeometryTest {
    @Test fun bitmapSizeRoundsUp() {
        assertEquals(270, CaptureGeometry.bitmapSize(1080, 4))
        assertEquals(26, CaptureGeometry.bitmapSize(101, 4))
        assertEquals(1, CaptureGeometry.bitmapSize(1, 8))
        assertEquals(37, CaptureGeometry.bitmapSize(37, 1))
        assertFailsWith<IllegalArgumentException> { CaptureGeometry.bitmapSize(10, 0) }
    }

    @Test fun paddingIsLimitedBySourceAndAligned() {
        assertEquals(60, CaptureGeometry.padding(desired = 60, available = 500, downsample = 4))
        assertEquals(0, CaptureGeometry.padding(desired = 60, available = 0, downsample = 4)) // at the source edge
        assertEquals(20, CaptureGeometry.padding(desired = 60, available = 22, downsample = 4)) // rounded down
        assertEquals(0, CaptureGeometry.padding(desired = 60, available = -30, downsample = 4)) // target sticks out
        assertEquals(56, CaptureGeometry.padding(desired = 59, available = 500, downsample = 8))
    }

    @Test fun translationIncludesOffsetAndSourceScroll() {
        assertEquals(-300f, CaptureGeometry.translation(offset = 300, scroll = 0))
        // Source scrolled down 500 px: its content is drawn 500 px higher, so shift further.
        assertEquals(-800f, CaptureGeometry.translation(offset = 300, scroll = 500))
        assertEquals(20f, CaptureGeometry.translation(offset = -20, scroll = 0))
    }
}
