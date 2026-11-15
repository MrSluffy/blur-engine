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

import dev.blurengine.mrsluffy.core.BlurBackend
import dev.blurengine.mrsluffy.core.BlurBackendException
import dev.blurengine.mrsluffy.core.BlurBackendFactory
import dev.blurengine.mrsluffy.core.BlurConfig
import dev.blurengine.mrsluffy.core.DeviceCapabilities
import dev.blurengine.mrsluffy.platform.GlesBlurBackend
import dev.blurengine.mrsluffy.platform.RenderEffectBackendFactory
import dev.blurengine.mrsluffy.platform.SoftwareBlurBackend

/**
 * Picks a backend by walking factories in order and taking the first that supports the config
 * and device. Order encodes preference.
 *
 * Default order: RenderEffect (API 31+, Gaussian or AUTO), then GLES (Dual Kawase or Gaussian),
 * then software (everything).
 */
public class BlurBackendSelector(
    factories: List<BlurBackendFactory> = DEFAULT_FACTORIES,
) {
    private val factories: List<BlurBackendFactory> = factories.toList()

    init { require(this.factories.isNotEmpty()) { "at least one backend factory is required" } }

    /** Factories that claim support, in preference order. */
    public fun candidates(config: BlurConfig, capabilities: DeviceCapabilities): List<BlurBackendFactory> =
        factories.filter { it.isSupported(config, capabilities) }

    /** Returns a new, uninitialized backend from the first supporting factory. */
    public fun select(config: BlurConfig, capabilities: DeviceCapabilities): BlurBackend {
        val factory = factories.firstOrNull { it.isSupported(config, capabilities) }
            ?: error("No blur backend supports this configuration/device")
        return factory.create()
    }

    /**
     * Creates and initializes the first supporting backend. If initialization throws
     * [BlurBackendException] (for example, EGL is unavailable), it is released and the next
     * candidate is tried.
     *
     * @throws IllegalStateException if no candidate initializes; earlier failures are attached as suppressed.
     */
    public fun selectAndInitialize(config: BlurConfig, capabilities: DeviceCapabilities): BlurBackend {
        val failures = ArrayList<Throwable>()
        for (factory in candidates(config, capabilities)) {
            val backend = factory.create()
            try {
                backend.initialize(config)
                return backend
            } catch (e: BlurBackendException) {
                backend.release()
                failures.add(e)
            }
        }
        throw IllegalStateException("No blur backend could be initialized for $config").apply {
            failures.forEach { addSuppressed(it) }
        }
    }

    public companion object {
        public val DEFAULT_FACTORIES: List<BlurBackendFactory> = listOf(
            RenderEffectBackendFactory,
            GlesBlurBackend.factory,
            SoftwareBlurBackend.factory,
        )
    }
}
