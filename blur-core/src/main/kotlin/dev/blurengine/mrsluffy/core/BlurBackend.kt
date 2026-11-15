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

/** What the current device can do. Built by the platform layer. */
public data class DeviceCapabilities(
    val sdkInt: Int,
    val supportsRenderEffect: Boolean,
    val supportsGles: Boolean,
    val hardwareAccelerated: Boolean = true,
)

/**
 * Pixels a backend reads from. Concrete types are platform-specific (for example a Bitmap
 * wrapper in blur-platform); [PixelBufferImage] is the pure-JVM one.
 */
public interface BlurInput {
    public val width: Int
    public val height: Int
}

/** Pixels a backend writes to. Its size must match the input. */
public interface BlurOutput {
    public val width: Int
    public val height: Int
}

/** A [PixelBuffer] usable as both input and output. */
public class PixelBufferImage(public val buffer: PixelBuffer) : BlurInput, BlurOutput {
    override val width: Int get() = buffer.width
    override val height: Int get() = buffer.height
}

/** Thrown when a backend cannot initialize or render on this device, e.g. EGL setup failed. */
public class BlurBackendException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

/**
 * A blur implementation. Backends own their scratch resources, so [render] should not allocate
 * once the input size is stable. Instances are not thread-safe: use them from one thread at a time.
 */
public interface BlurBackend {
    /** Stable identifier, e.g. "software". */
    public val id: String

    /** @throws BlurBackendException if the backend cannot work on this device. */
    public fun initialize(config: BlurConfig)

    /**
     * Applies [config] without rebuilding resources when possible.
     * @throws IllegalArgumentException if this backend cannot render [config] (see [BlurBackendFactory.isSupported]).
     */
    public fun updateConfig(config: BlurConfig)

    /** True if [render] accepts this pair of image types. */
    public fun supports(input: BlurInput, output: BlurOutput): Boolean

    /**
     * Blurs [input] into [output]: downsample, blur, upscale, color adjust, tint.
     * @throws IllegalArgumentException for unsupported image types or mismatched sizes.
     * @throws BlurBackendException if rendering failed.
     */
    public fun render(input: BlurInput, output: BlurOutput)

    public fun release()
}

/** Creates a [BlurBackend] if it can handle the situation. Selectors walk factories in order. */
public interface BlurBackendFactory {
    public val id: String
    public fun isSupported(config: BlurConfig, capabilities: DeviceCapabilities): Boolean
    public fun create(): BlurBackend
}

/** Shared conventions so every backend gives the same blur for the same config. */
public object BlurMath {
    /** Gaussian standard deviation for a blur radius. All backends target sigma = radius / 2. */
    public fun sigmaForRadius(radius: Float): Float = radius / 2f

    /** @throws IllegalArgumentException if [output] does not match [input]'s size. */
    public fun requireSameSize(input: BlurInput, output: BlurOutput) {
        require(input.width == output.width && input.height == output.height) {
            "output ${output.width}x${output.height} must match input ${input.width}x${input.height}"
        }
    }
}
