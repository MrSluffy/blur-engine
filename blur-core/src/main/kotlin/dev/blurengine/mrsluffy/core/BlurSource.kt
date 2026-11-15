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

/**
 * Content provider for the blur. Capture implementations (View, region, platform) live in
 * later modules; the engine only depends on this contract.
 */
public interface BlurSource {
    /** Current content size, or null if nothing is available yet. */
    public fun size(): Pair<Int, Int>?

    /**
     * Writes the current content into [target] (resized as needed).
     * @return false if capture failed or is not possible right now.
     */
    public fun capture(target: PixelBuffer): Boolean

    /** Registers a callback fired when content changes. Returns a handle to unregister. */
    public fun addInvalidationListener(listener: () -> Unit): AutoCloseable
}
