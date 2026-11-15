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

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.Bundle
import android.os.SystemClock
import android.view.Gravity
import android.widget.FrameLayout
import android.widget.ImageView
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.lang.ref.WeakReference
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** An ImageView showing a HARDWARE bitmap (red left half, blue right half) behind a BlurView. */
class HardwareBitmapActivity : Activity() {
    lateinit var blurView: BlurView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val software = Bitmap.createBitmap(400, 400, Bitmap.Config.ARGB_8888)
        Canvas(software).apply {
            val p = Paint()
            p.color = Color.RED; drawRect(0f, 0f, 200f, 400f, p)
            p.color = Color.BLUE; drawRect(200f, 0f, 400f, 400f, p)
        }
        val hardware = software.copy(Bitmap.Config.HARDWARE, false)
        val root = FrameLayout(this)
        root.addView(ImageView(this).apply {
            setImageBitmap(hardware)
            scaleType = ImageView.ScaleType.FIT_XY
        }, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        blurView = BlurView(this).apply { blurRadius = 40f }
        root.addView(blurView, FrameLayout.LayoutParams(600, 400, Gravity.CENTER))
        setContentView(root)
    }
}

/** Several BlurViews in one window. */
class MultiBlurActivity : Activity() {
    val blurViews = ArrayList<BlurView>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = FrameLayout(this)
        root.setBackgroundColor(Color.RED)
        root.addView(android.view.View(this).apply { setBackgroundColor(Color.BLUE) },
            FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, 600, Gravity.BOTTOM))
        for (i in 0 until 4) {
            val v = BlurView(this).apply { blurRadius = 30f; if (i % 2 == 1) algorithm = dev.blurengine.mrsluffy.core.BlurAlgorithm.DUAL_KAWASE }
            blurViews.add(v)
            root.addView(v, FrameLayout.LayoutParams(300, 300, Gravity.CENTER_VERTICAL).apply { leftMargin = 20 + i * 260 })
        }
        setContentView(root)
    }
}

/** Control for the leak test: same layout without any BlurView. */
class PlainActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(FrameLayout(this).apply { setBackgroundColor(Color.RED) })
    }
}

/** Recreates [T] once and reports whether the first instance is still reachable after GC. */
fun <T : Activity> leaksAfterRecreate(cls: Class<T>, setup: (T) -> Unit = {}): Boolean {
    val instrumentation = InstrumentationRegistry.getInstrumentation()
    ActivityScenario.launch(cls).use { s ->
        instrumentation.waitForIdleSync()
        SystemClock.sleep(300)
        var ref: WeakReference<Activity>? = null
        s.onActivity { ref = WeakReference(it); setup(it) }
        s.recreate()
        instrumentation.waitForIdleSync()
        SystemClock.sleep(300)
        for (i in 0 until 20) {
            Runtime.getRuntime().gc()
            System.runFinalization()
            SystemClock.sleep(100)
            if (ref!!.get() == null) return false
        }
        return true
    }
}

@RunWith(AndroidJUnit4::class)
class BlurViewRobustnessTest {
    @get:Rule val noWindowAnimations = NoWindowAnimations()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    private fun screenshot(): Bitmap {
        instrumentation.waitForIdleSync()
        return stableScreenshot(instrumentation)
    }

    private fun mixed(p: Int) = Color.red(p) in 40..215 && Color.blue(p) in 40..215

    /** Image loaders (Coil, Glide) use HARDWARE bitmaps; software drawing alone throws on them. */
    @Test fun hardwareBitmapsBehindTheViewAreBlurred() {
        ActivityScenario.launch(HardwareBitmapActivity::class.java).use { s ->
            var view: BlurView? = null
            s.onActivity { view = it.blurView }
            val settled = awaitOnMain(instrumentation) { view!!.isSettled.also { if (!it) view!!.invalidate() } }
            var renderer: String? = null
            s.onActivity { renderer = it.blurView.activeRenderer }
            assertTrue(settled, "capture never succeeded (renderer=$renderer)")
            val shot = screenshot()
            var cx = 0; var cy = 0
            s.onActivity {
                val loc = IntArray(2); it.blurView.getLocationOnScreen(loc)
                cx = loc[0] + it.blurView.width / 2; cy = loc[1] + it.blurView.height / 2
            }
            val p = shot.getPixel(cx, cy)
            assertTrue(mixed(p), "image behind not blurred: ${Integer.toHexString(p)}")
        }
    }

    @Test fun controlActivityDoesNotLeak() {
        assertTrue(!leaksAfterRecreate(PlainActivity::class.java), "the test method itself retains activities")
    }

    @Test fun recreatingTheActivityDoesNotLeakIt() {
        val leaked = leaksAfterRecreate(BlurTestActivity::class.java) {
            // Also leave an animation running across the recreation.
            it.blurView.animateBlur { radius = 10f; duration = 2000L }
        }
        assertTrue(!leaked, "the destroyed activity is still reachable")
    }

    @Test fun recreatingWithAGlesBlurViewDoesNotLeakIt() {
        val leaked = leaksAfterRecreate(BlurTestActivity::class.java) {
            it.blurView.algorithm = dev.blurengine.mrsluffy.core.BlurAlgorithm.DUAL_KAWASE
        }
        assertTrue(!leaked, "the destroyed activity is still reachable")
    }

    @Test fun backgroundThenForegroundKeepsBlurring() {
        ActivityScenario.launch(BlurTestActivity::class.java).use { s ->
            awaitRendered(instrumentation)
            s.moveToState(Lifecycle.State.CREATED) // stopped: window not drawn, no capture work
            SystemClock.sleep(300)
            s.moveToState(Lifecycle.State.RESUMED)
            awaitRendered(instrumentation)
            val shot = screenshot()
            var x = 0; var y = 0
            s.onActivity {
                val loc = IntArray(2); it.right.getLocationOnScreen(loc); x = loc[0]
                it.blurView.getLocationOnScreen(loc); y = loc[1] + it.blurView.height / 2
            }
            assertTrue(mixed(shot.getPixel(x, y)), "not blurred after returning to the foreground")
        }
    }

    @Test fun severalBlurViewsAllRender() {
        ActivityScenario.launch(MultiBlurActivity::class.java).use { s ->
            var views: List<BlurView> = emptyList()
            s.onActivity { a -> views = a.blurViews.toList() }
            // Four backends warm up one after another on the shared background thread.
            val all = awaitOnMain(instrumentation) {
                views.forEach { if (!it.isSettled) it.invalidate() }
                views.all { it.isSettled }
            }
            var renderers: List<String?> = emptyList()
            s.onActivity { renderers = views.map { it.activeRenderer } }
            assertTrue(views.size == 4 && all && renderers.none { it == null }, "not all rendered: $renderers")
        }
    }

    @Test fun detachingWhileTheBackendWarmsUpIsSafe() {
        ActivityScenario.launch(BlurTestActivity::class.java).use { s ->
            awaitRendered(instrumentation)
            s.onActivity { a ->
                val parent = a.blurView.parent as FrameLayout
                // Repeatedly attach a fresh GLES BlurView and remove it before its warm-up can finish.
                repeat(5) {
                    val v = BlurView(a).apply { algorithm = dev.blurengine.mrsluffy.core.BlurAlgorithm.DUAL_KAWASE }
                    parent.addView(v, FrameLayout.LayoutParams(200, 200))
                    parent.removeView(v)
                }
            }
            SystemClock.sleep(1500) // let every warm-up finish and be discarded
            assertEquals(Lifecycle.State.RESUMED, s.state, "activity crashed or closed")
            awaitRendered(instrumentation)
        }
    }
}
