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

import android.view.Choreographer
import dev.blurengine.mrsluffy.core.FrameCallback
import dev.blurengine.mrsluffy.core.FrameClock

/**
 * [FrameClock] backed by [Choreographer], so callbacks follow the display's refresh rate
 * (60/90/120 Hz). Create and use on a thread with a Looper (the UI thread).
 * Uses a single Choreographer callback, so posting every frame does not allocate.
 */
public class ChoreographerFrameClock(
    private val choreographer: Choreographer = Choreographer.getInstance(),
) : FrameClock {
    private var pending: FrameCallback? = null

    private val dispatcher = Choreographer.FrameCallback { frameTimeNanos ->
        val callback = pending
        pending = null
        callback?.doFrame(frameTimeNanos)
    }

    override fun postFrame(callback: FrameCallback) {
        if (pending == null) choreographer.postFrameCallback(dispatcher)
        pending = callback
    }

    override fun cancel() {
        if (pending != null) {
            choreographer.removeFrameCallback(dispatcher)
            pending = null
        }
    }
}
