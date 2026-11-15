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

package dev.blurengine.mrsluffy.view

import android.graphics.Bitmap
import android.graphics.Color
import android.os.Build
import android.os.SystemClock
import android.util.Log
import android.view.Choreographer
import android.view.View
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.blurengine.mrsluffy.animation.BlurAnimationListener
import dev.blurengine.mrsluffy.core.BlurAlgorithm
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@RunWith(AndroidJUnit4::class)
class BlurViewAnimationTest {
    @get:org.junit.Rule val noWindowAnimations = NoWindowAnimations()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val tag = "BlurEngineTest"

    private fun screenshot(): Bitmap {
        awaitRendered(instrumentation)
        return stableScreenshot(instrumentation)
    }

    /** Boundary pixel inside the BlurView: mixed when blurred, pure red or blue when sharp. */
    private fun boundaryIsBlurred(s: ActivityScenario<BlurTestActivity>): Boolean {
        val shot = screenshot()
        var x = 0; var y = 0
        s.onActivity {
            val loc = IntArray(2)
            it.right.getLocationOnScreen(loc); x = loc[0]
            it.blurView.getLocationOnScreen(loc); y = loc[1] + it.blurView.height / 2
        }
        val p = shot.getPixel(x, y)
        return Color.red(p) in 40..215 && Color.blue(p) in 40..215
    }

    /** Records displayedConfig.radius every frame until [done] counts down. */
    private class Recorder(private val view: BlurView) : Choreographer.FrameCallback {
        val radii = ArrayList<Float>()
        val updateMillis = ArrayList<Float>()
        @Volatile var running = true
        override fun doFrame(frameTimeNanos: Long) {
            radii.add(view.displayedConfig.radius)
            updateMillis.add(view.lastUpdateMillis)
            if (running) Choreographer.getInstance().postFrameCallback(this)
        }
    }

    private fun runAnimation(algorithm: BlurAlgorithm) {
        ActivityScenario.launch(BlurTestActivity::class.java).use { s ->
            val done = CountDownLatch(1)
            var finished = false
            lateinit var recorder: Recorder
            s.onActivity {
                it.blurView.algorithm = algorithm
                it.blurView.blurRadius = 0f
            }
            screenshot()
            // Like an app whose BlurView has been on screen a moment: the backend has finished
            // warming up before the animation starts. Changing the algorithm just now restarts it.
            val isReady = awaitOnMain(instrumentation) { BlurTestActivity.current?.blurView?.isRendererReady == true }
            assertTrue(isReady, "backend never became ready")
            s.onActivity {
                recorder = Recorder(it.blurView)
                Choreographer.getInstance().postFrameCallback(recorder)
                it.blurView.animateBlur {
                    radius = 40f
                    duration = 400L
                    onEnd = BlurAnimationListener { f -> finished = f; done.countDown() }
                }
            }
            assertTrue(done.await(5, TimeUnit.SECONDS), "animation did not end")
            // Stop and copy on the main thread, which is where the recorder appends.
            var radii: List<Float> = emptyList()
            var updateMillis: List<Float> = emptyList()
            s.onActivity {
                recorder.running = false
                radii = ArrayList(recorder.radii)
                updateMillis = ArrayList(recorder.updateMillis)
            }
            assertTrue(finished)
            val distinct = radii.distinct().size
            Log.i(tag, "api=${Build.VERSION.SDK_INT} animate $algorithm radii=$radii updateMs=$updateMillis")
            // Smoothness depends on the device; CI emulators with software GL turn this check off.
            if (timingAssertions()) {
                assertTrue(distinct >= 10, "only $distinct distinct frames: $radii; update ms per frame: $updateMillis")
            }
            for (i in 1 until radii.size) assertTrue(radii[i] >= radii[i - 1] - 1e-3f, "radius went backwards: $radii")
            var renderer: String? = null
            var config = 0f
            s.onActivity { renderer = it.blurView.activeRenderer; config = it.blurView.config.radius }
            assertEquals(40f, config)
            val avg = updateMillis.filter { it > 0f }.drop(2).average()
            Log.i(tag, "api=${Build.VERSION.SDK_INT} animate $algorithm renderer=$renderer frames=${radii.size} " +
                "distinct=$distinct avgUpdate=%.2f ms".format(avg))
            assertTrue(boundaryIsBlurred(s), "not blurred after animation")
        }
    }

    @Test fun animateRadiusIsFrameByFrame() = runAnimation(BlurAlgorithm.AUTO)

    @Test fun animateRadiusIsFrameByFrameOnGles() = runAnimation(BlurAlgorithm.DUAL_KAWASE)

    @Test fun hideThenShow() {
        ActivityScenario.launch(BlurTestActivity::class.java).use { s ->
            assertTrue(boundaryIsBlurred(s))
            val hidden = CountDownLatch(1)
            s.onActivity { it.blurView.hide(200L) { hidden.countDown() } }
            assertTrue(hidden.await(3, TimeUnit.SECONDS))
            s.onActivity { assertEquals(View.INVISIBLE, it.blurView.visibility) }
            assertTrue(!boundaryIsBlurred(s), "still blurred after hide")
            val shown = CountDownLatch(1)
            s.onActivity { it.blurView.show(200L) { shown.countDown() } }
            assertTrue(shown.await(3, TimeUnit.SECONDS))
            s.onActivity {
                assertEquals(View.VISIBLE, it.blurView.visibility)
                assertEquals(40f, it.blurView.displayedConfig.radius)
            }
            assertTrue(boundaryIsBlurred(s), "not blurred after show")
        }
    }

    @Test fun showInterruptsHide() {
        ActivityScenario.launch(BlurTestActivity::class.java).use { s ->
            screenshot()
            var hideFinished: Boolean? = null
            val shown = CountDownLatch(1)
            var radiusAtInterrupt = 0f
            s.onActivity { activity ->
                val view = activity.blurView
                view.hide(600L) { f -> hideFinished = f }
                // Interrupt from a frame callback once the hide is visibly under way, so main-thread
                // load on the emulator cannot shift when the interruption happens.
                Choreographer.getInstance().postFrameCallback(object : Choreographer.FrameCallback {
                    override fun doFrame(frameTimeNanos: Long) {
                        val r = view.displayedConfig.radius
                        if (r < 30f) {
                            radiusAtInterrupt = r
                            view.show(300L) { shown.countDown() }
                        } else {
                            Choreographer.getInstance().postFrameCallback(this)
                        }
                    }
                })
            }
            assertTrue(shown.await(5, TimeUnit.SECONDS))
            assertEquals(false, hideFinished)
            assertTrue(radiusAtInterrupt in 1f..39f, "interrupt should happen mid-way, radius=$radiusAtInterrupt")
            s.onActivity {
                assertEquals(View.VISIBLE, it.blurView.visibility)
                assertEquals(40f, it.blurView.displayedConfig.radius)
            }
            assertTrue(boundaryIsBlurred(s))
        }
    }

    @Test fun progressDrivesBlurAndZeroSkipsCapture() {
        ActivityScenario.launch(BlurTestActivity::class.java).use { s ->
            s.onActivity { it.blurView.setTint(Color.WHITE, 0f) }
            s.onActivity { it.blurView.setBlurProgress(0f) }
            assertTrue(!boundaryIsBlurred(s), "progress 0 should show content sharp")
            s.onActivity { assertTrue(it.blurView.skippedCapture, "progress 0 should skip capture") }
            s.onActivity {
                it.blurView.setBlurProgress(0.5f)
                assertEquals(20f, it.blurView.displayedConfig.radius)
                assertEquals(40f, it.blurView.config.radius, "progress must not change the configured radius")
            }
            s.onActivity { it.blurView.setBlurProgress(1f) }
            assertTrue(boundaryIsBlurred(s))
        }
    }

    @Test fun animationsOffJumpsToTheEnd() {
        val ui = instrumentation.uiAutomation
        fun shell(cmd: String) = ui.executeShellCommand(cmd).close()
        shell("settings put global animator_duration_scale 0")
        try {
            ActivityScenario.launch(BlurTestActivity::class.java).use { s ->
                screenshot()
                s.onActivity {
                    it.blurView.animateBlur { radius = 12f; spring() }
                    assertEquals(12f, it.blurView.displayedConfig.radius, "should apply immediately")
                    assertTrue(!it.blurView.isAnimating)
                }
            }
        } finally {
            shell("settings put global animator_duration_scale 1")
        }
    }
}
