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

package dev.blurengine.mrsluffy.render

import dev.blurengine.mrsluffy.core.BlurAlgorithm
import dev.blurengine.mrsluffy.core.BlurBackend
import dev.blurengine.mrsluffy.core.BlurBackendException
import dev.blurengine.mrsluffy.core.BlurBackendFactory
import dev.blurengine.mrsluffy.core.BlurConfig
import dev.blurengine.mrsluffy.core.DeviceCapabilities
import dev.blurengine.mrsluffy.platform.SoftwareBlurBackend
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class BlurBackendSelectorTest {
    private val api29NoGles = DeviceCapabilities(sdkInt = 29, supportsRenderEffect = false, supportsGles = false)
    private val api29 = DeviceCapabilities(sdkInt = 29, supportsRenderEffect = false, supportsGles = true)

    private fun factory(name: String, ok: Boolean, failInit: Boolean = false, released: MutableList<String>? = null) =
        object : BlurBackendFactory {
            override val id = name
            override fun isSupported(config: BlurConfig, capabilities: DeviceCapabilities) = ok
            override fun create(): BlurBackend = object : BlurBackend by SoftwareBlurBackend() {
                override val id = name
                override fun initialize(config: BlurConfig) {
                    if (failInit) throw BlurBackendException("$name unavailable")
                }
                override fun release() { released?.add(name) }
            }
        }

    @Test fun defaultsFallBackToSoftwareWithoutGpu() {
        assertEquals("software", BlurBackendSelector().select(BlurConfig(), api29NoGles).id)
    }

    @Test fun defaultsPreferGlesOnApi29() {
        assertEquals("gles", BlurBackendSelector().select(BlurConfig(), api29).id)
    }

    @Test fun boxIsOnlyHandledBySoftware() {
        val ids = BlurBackendSelector().candidates(BlurConfig(algorithm = BlurAlgorithm.BOX), api29).map { it.id }
        assertEquals(listOf("software"), ids)
    }

    @Test fun selectorPicksFirstSupportedFactory() {
        val selector = BlurBackendSelector(listOf(factory("a", false), factory("b", true), factory("c", true)))
        assertEquals("b", selector.select(BlurConfig(), api29).id)
    }

    @Test fun selectorFailsWhenNothingSupported() {
        assertFailsWith<IllegalStateException> { BlurBackendSelector(listOf(factory("a", false))).select(BlurConfig(), api29) }
    }

    @Test fun initializeFailureFallsThroughToNextCandidate() {
        val released = mutableListOf<String>()
        val selector = BlurBackendSelector(listOf(factory("gpu", true, failInit = true, released = released), factory("cpu", true)))
        assertEquals("cpu", selector.selectAndInitialize(BlurConfig(), api29).id)
        assertEquals(listOf("gpu"), released)
    }

    @Test fun allInitializeFailuresAreReported() {
        val selector = BlurBackendSelector(listOf(factory("x", true, failInit = true), factory("y", true, failInit = true)))
        val e = assertFailsWith<IllegalStateException> { selector.selectAndInitialize(BlurConfig(), api29) }
        assertTrue(e.suppressed.size == 2)
    }
}
