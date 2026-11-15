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
 * Invalidation-driven frame scheduling. Call from the main thread.
 */
public interface BlurRenderScheduler {
    /** Marks content dirty. Multiple calls before the next frame coalesce into one render. */
    public fun invalidate()

    /** Forces one render on the next frame even if nothing is dirty. */
    public fun requestFrame()

    /** While enabled, renders every frame (use during animations). */
    public fun setContinuous(enabled: Boolean)

    public fun pause()
    public fun resume()
    public fun release()
}

/** Called once per frame with the frame's timestamp (System.nanoTime base, like Choreographer). */
public fun interface FrameCallback {
    public fun doFrame(frameTimeNanos: Long)
}

/**
 * Source of frame callbacks (Choreographer on Android, a fake in tests). At most one callback
 * is pending: posting again replaces it.
 */
public interface FrameClock {
    /** Schedules [callback] for the next frame. Must not invoke it synchronously. */
    public fun postFrame(callback: FrameCallback)

    /** Drops the pending callback, if any. */
    public fun cancel()
}

/**
 * Default scheduler: coalesces invalidations, renders at most once per frame, and
 * stops posting frames when idle, paused, or released.
 */
public class FrameDrivenScheduler(
    private val clock: FrameClock,
    private val onRender: () -> Unit,
) : BlurRenderScheduler {
    private var dirty = false
    private var continuous = false
    private var paused = false
    private var released = false
    private var framePosted = false

    private val frameCallback = FrameCallback {
        framePosted = false
        if (!released && !paused && (dirty || continuous)) {
            dirty = false
            onRender()
            if (continuous) scheduleIfNeeded()
        }
    }

    override fun invalidate() {
        if (released) return
        dirty = true
        scheduleIfNeeded()
    }

    override fun requestFrame(): Unit = invalidate()

    override fun setContinuous(enabled: Boolean) {
        if (released) return
        continuous = enabled
        scheduleIfNeeded()
    }

    override fun pause() {
        paused = true
        if (framePosted) {
            clock.cancel()
            framePosted = false
        }
    }

    override fun resume() {
        if (released) return
        paused = false
        scheduleIfNeeded()
    }

    override fun release() {
        released = true
        dirty = false
        continuous = false
        if (framePosted) {
            clock.cancel()
            framePosted = false
        }
    }

    private fun scheduleIfNeeded() {
        if (released || paused || framePosted || !(dirty || continuous)) return
        framePosted = true
        clock.postFrame(frameCallback)
    }
}
