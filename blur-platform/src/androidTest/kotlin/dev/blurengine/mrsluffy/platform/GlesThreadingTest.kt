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

package dev.blurengine.mrsluffy.platform

import android.graphics.Bitmap
import android.os.Build
import android.os.SystemClock
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.blurengine.mrsluffy.core.BlurAlgorithm
import dev.blurengine.mrsluffy.core.BlurConfig
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.Executors
import kotlin.test.assertTrue

/**
 * Regression tests for the GLES backend's threading. BlurView initializes and primes the backend
 * on a background thread, then renders on the main thread. On the emulator, sharing one EGL
 * context across threads failed with GL_INVALID_VALUE, broke the GL objects once the creating
 * thread exited, and cost about 300 ms the first time each thread used GL.
 */
@RunWith(AndroidJUnit4::class)
class GlesThreadingTest {
    private fun ms(start: Long) = (SystemClock.elapsedRealtimeNanos() - start) / 1e6
    private val config = BlurConfig(radius = 10f, downsample = 1, algorithm = BlurAlgorithm.DUAL_KAWASE)
    private fun frame(b: GlesBlurBackend) =
        b.render(BitmapImage(Images.source(182, 132)), BitmapImage(Images.blank(182, 132)))

    @Test fun renderOnAnotherThreadThanInitialize() {
        val b = GlesBlurBackend()
        val pool = Executors.newSingleThreadExecutor()
        try {
            pool.submit {
                b.initialize(config)
                b.render(BitmapImage(Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888)),
                    BitmapImage(Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888)))
            }.get()
            // Images are made before timing: the first software draw of text and gradients is slow too.
            val inputs = (1..3).map { BitmapImage(Images.source(182, 132)) to BitmapImage(Images.blank(182, 132)) }
            val times = inputs.map { (i, o) -> val t = SystemClock.elapsedRealtimeNanos(); b.render(i, o); ms(t) }
            Log.i("BlurEngineTest", "api=${Build.VERSION.SDK_INT} gles cross-thread frames ms=$times")
            // The first frame on the new thread must not pay EGL's per-thread setup (~300 ms here).
            // A timing check, so CI emulators with software GL can turn it off.
            if (InstrumentationRegistry.getArguments().getString("timingAssertions") != "false") {
                assertTrue(times[0] < 100.0, "first cross-thread frame took ${times[0]} ms")
            }
        } finally {
            b.release()
            pool.shutdown()
        }
    }

    @Test fun renderAfterTheInitializingThreadExited() {
        val b = GlesBlurBackend()
        try {
            val worker = Thread { b.initialize(config) }
            worker.start(); worker.join()
            frame(b) // threw "framebuffer incomplete" before the backend owned its GL thread
        } finally {
            b.release()
        }
    }

    @Test fun releaseThenInitializeAgainFromAnotherThread() {
        val b = GlesBlurBackend()
        b.initialize(config)
        frame(b)
        b.release()
        val worker = Thread { b.initialize(config); frame(b) }
        var failure: Throwable? = null
        worker.setUncaughtExceptionHandler { _, e -> failure = e }
        worker.start(); worker.join()
        b.release()
        assertTrue(failure == null, "re-initialize failed: $failure")
    }
}
