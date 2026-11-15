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

private class FakeClock : FrameClock {
    var callback: FrameCallback? = null
    var posts = 0
    override fun postFrame(callback: FrameCallback) { this.callback = callback; posts++ }
    override fun cancel() { callback = null }
    fun tick() { val c = callback; callback = null; c?.doFrame(0L) }
}

class FrameDrivenSchedulerTest {
    private val clock = FakeClock()
    private var renders = 0
    private val scheduler = FrameDrivenScheduler(clock) { renders++ }

    @Test fun coalescesInvalidations() {
        repeat(5) { scheduler.invalidate() }
        assertEquals(1, clock.posts)
        clock.tick()
        assertEquals(1, renders)
        clock.tick()
        assertEquals(1, renders)
        assertEquals(1, clock.posts)
    }

    @Test fun idleWhenNothingChanges() {
        scheduler.resume()
        assertEquals(0, clock.posts)
    }

    @Test fun continuousRendersEveryFrameUntilDisabled() {
        scheduler.setContinuous(true)
        clock.tick(); clock.tick(); clock.tick()
        assertEquals(3, renders)
        scheduler.setContinuous(false)
        clock.tick()
        assertEquals(3, renders)
        assertEquals(null, clock.callback)
    }

    @Test fun pauseCancelsAndResumeRestoresDirtyWork() {
        scheduler.invalidate()
        scheduler.pause()
        assertEquals(null, clock.callback)
        scheduler.invalidate()
        assertEquals(1, clock.posts)
        scheduler.resume()
        clock.tick()
        assertEquals(1, renders)
    }

    @Test fun releaseStopsEverything() {
        scheduler.invalidate()
        scheduler.release()
        scheduler.invalidate()
        scheduler.setContinuous(true)
        clock.tick()
        assertEquals(0, renders)
    }

    @Test fun invalidateDuringRenderSchedulesAnotherFrame() {
        lateinit var s: FrameDrivenScheduler
        var n = 0
        s = FrameDrivenScheduler(clock) { n++; if (n == 1) s.invalidate() }
        s.invalidate()
        clock.tick()
        clock.tick()
        assertEquals(2, n)
    }
}
