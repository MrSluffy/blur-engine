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
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.blurengine.mrsluffy.core.BlurAlgorithm
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.abs
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Waits until the test activity's BlurView has drawn with a renderer (backends warm up in the
 * background) or skipped capture because nothing needs blurring.
 */
fun awaitRendered(instrumentation: android.app.Instrumentation, timeoutMs: Long = 5000) {
    val deadline = SystemClock.uptimeMillis() + timeoutMs
    while (SystemClock.uptimeMillis() < deadline) {
        instrumentation.waitForIdleSync()
        var ready = false
        instrumentation.runOnMainSync {
            val activity = BlurTestActivity.current ?: return@runOnMainSync
            val v = activity.blurView
            ready = !v.isShown || v.skippedCapture || v.activeRenderer != null
        }
        if (ready) return
        SystemClock.sleep(50)
    }
}

/**
 * Frame-rate checks are on by default. CI emulators that use software GL (swiftshader) can't
 * hold frame rates, so CI passes `-Pandroid.testInstrumentationRunnerArguments.timingAssertions=false`.
 * Correctness checks always run.
 */
fun timingAssertions(): Boolean =
    androidx.test.platform.app.InstrumentationRegistry.getArguments().getString("timingAssertions") != "false"

/** Left half red, right half blue, with a BlurView centred over the boundary. */
class BlurTestActivity : Activity() {
    lateinit var left: View
    lateinit var right: View
    lateinit var blurView: BlurView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = FrameLayout(this)
        val halves = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        left = View(this).apply { setBackgroundColor(Color.RED) }
        right = View(this).apply { setBackgroundColor(Color.BLUE) }
        halves.addView(left, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f))
        halves.addView(right, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f))
        root.addView(halves, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        blurView = BlurView(this).apply {
            blurRadius = 40f
            downsample = 4
        }
        root.addView(blurView, FrameLayout.LayoutParams(600, 400, Gravity.CENTER))
        setContentView(root)
        current = this
    }

    override fun onDestroy() {
        if (current === this) current = null
        super.onDestroy()
    }

    companion object {
        @Volatile var current: BlurTestActivity? = null
    }
}

@RunWith(AndroidJUnit4::class)
class BlurViewScreenTest {
    @get:org.junit.Rule val noWindowAnimations = NoWindowAnimations()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val tag = "BlurEngineTest"

    private fun screenshot(): Bitmap {
        awaitRendered(instrumentation)
        SystemClock.sleep(400) // let a few frames reach the screen
        return assertNotNull(instrumentation.uiAutomation.takeScreenshot())
    }

    private fun red(c: Int) = Color.red(c)
    private fun blue(c: Int) = Color.blue(c)

    /** Screen rect of the BlurView: left, top, right, bottom. */
    private fun bounds(s: ActivityScenario<BlurTestActivity>): IntArray {
        val out = IntArray(4)
        s.onActivity {
            val loc = IntArray(2)
            it.blurView.getLocationOnScreen(loc)
            out[0] = loc[0]; out[1] = loc[1]; out[2] = loc[0] + it.blurView.width; out[3] = loc[1] + it.blurView.height
        }
        return out
    }

    private fun boundaryX(s: ActivityScenario<BlurTestActivity>): Int {
        var x = 0
        s.onActivity { val loc = IntArray(2); it.right.getLocationOnScreen(loc); x = loc[0] }
        return x
    }

    /** Writes [shot] to the test app's external files dir so a failing screenshot can be pulled with adb. */
    private fun save(shot: Bitmap, name: String) {
        val dir = instrumentation.targetContext.getExternalFilesDir(null) ?: return
        java.io.File(dir, "$name.png").outputStream().use { shot.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private fun checkBlur(algorithm: BlurAlgorithm, expectedRenderer: String?) {
        ActivityScenario.launch(BlurTestActivity::class.java).use { s ->
            s.onActivity { it.blurView.algorithm = algorithm }
            val shot = screenshot()
            save(shot, "checkBlur-$algorithm")
            val (l, t, r, b) = bounds(s).toList()
            val edge = boundaryX(s)
            val midY = (t + b) / 2
            var renderer: String? = null
            var first = 0f
            var steady = 0f
            s.onActivity {
                renderer = it.blurView.activeRenderer
                first = it.blurView.lastUpdateMillis
                // Steady state: repeat the capture + blur and average.
                var total = 0f
                repeat(20) { _ -> it.blurView.invalidateBlur(); total += it.blurView.lastUpdateMillis }
                steady = total / 20
            }
            Log.i(tag, "api=${Build.VERSION.SDK_INT} $algorithm renderer=$renderer last=%.2f ms steady=%.2f ms".format(first, steady))
            if (expectedRenderer != null) assertEquals(expectedRenderer, renderer)

            // Above the BlurView the boundary is sharp.
            val above = t - 20
            assertEquals(Color.RED, shot.getPixel(edge - 2, above) or 0xFF000000.toInt())
            assertEquals(Color.BLUE, shot.getPixel(edge + 1, above) or 0xFF000000.toInt())

            // Inside, the boundary is a gradient, and the crossover sits on the real boundary.
            val atEdge = shot.getPixel(edge, midY)
            assertTrue(red(atEdge) in 60..200 && blue(atEdge) in 60..200, "not blurred at boundary: ${Integer.toHexString(atEdge)}")
            var crossover = -1
            for (x in l until r) {
                val p = shot.getPixel(x, midY)
                if (blue(p) >= red(p)) { crossover = x; break }
            }
            assertTrue(abs(crossover - edge) <= 6, "blur misaligned: crossover=$crossover boundary=$edge")

            // Far from the boundary, inside the view, the colour is still the colour behind it.
            val farLeft = shot.getPixel(l + 20, midY)
            assertTrue(red(farLeft) > 230 && blue(farLeft) < 25, "left side wrong: ${Integer.toHexString(farLeft)}")

            // Stable across frames: no feedback from capturing its own output.
            s.onActivity { it.blurView.invalidate() }
            val again = screenshot()
            for (x in listOf(l + 20, edge - 15, edge, edge + 15, r - 20)) {
                val d = abs(red(shot.getPixel(x, midY)) - red(again.getPixel(x, midY))) +
                    abs(blue(shot.getPixel(x, midY)) - blue(again.getPixel(x, midY)))
                assertTrue(d <= 4, "output drifted at x=$x by $d")
            }

            // Follows content changes: swap the colours behind.
            s.onActivity { it.left.setBackgroundColor(Color.BLUE); it.right.setBackgroundColor(Color.RED) }
            val swapped = screenshot()
            val newLeft = swapped.getPixel(l + 20, midY)
            assertTrue(blue(newLeft) > 230 && red(newLeft) < 25, "did not follow content: ${Integer.toHexString(newLeft)}")
        }
    }

    @Test fun autoBlursBehindView() {
        val expected = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) "render-effect" else "gles"
        checkBlur(BlurAlgorithm.AUTO, expected)
    }

    @Test fun dualKawaseUsesGles() = checkBlur(BlurAlgorithm.DUAL_KAWASE, "gles")

    @Test fun boxUsesSoftware() = checkBlur(BlurAlgorithm.BOX, "software")

    /**
     * Regression: content just outside the view must bleed in, as a real blur would. Capturing
     * only the pixels under the view made edges clamp, so a view entirely over red stayed red
     * even with blue 10 px away.
     */
    @Test fun contentOutsideViewBleedsIntoEdges() {
        for (algorithm in listOf(BlurAlgorithm.AUTO, BlurAlgorithm.DUAL_KAWASE)) {
            ActivityScenario.launch(BlurTestActivity::class.java).use { s ->
                s.onActivity {
                    it.blurView.algorithm = algorithm
                    // A 300 px view centred on the boundary, moved fully into the red half so its
                    // right edge is 10 px from the blue.
                    it.blurView.layoutParams = it.blurView.layoutParams.apply { width = 300 }
                    it.blurView.translationX = -(150f + 10f)
                }
                val shot = screenshot()
                val (l, t, r, b) = bounds(s).toList()
                val midY = (t + b) / 2
                assertTrue(r < boundaryX(s), "view should end before the boundary")
                val nearEdge = shot.getPixel(r - 3, midY)
                assertTrue(blue(nearEdge) > 40, "$algorithm: no blue near right edge: ${Integer.toHexString(nearEdge)}")
                val farSide = shot.getPixel(l + 20, midY)
                assertTrue(blue(farSide) < 25, "$algorithm: far side should stay red: ${Integer.toHexString(farSide)}")
            }
        }
    }

    @Test fun tintAndCornersApply() {
        ActivityScenario.launch(BlurTestActivity::class.java).use { s ->
            s.onActivity {
                it.blurView.setTint(Color.WHITE, 1f)
                it.blurView.cornerRadius = 100f
            }
            val shot = screenshot()
            val (l, t, r, b) = bounds(s).toList()
            assertEquals(Color.WHITE, shot.getPixel((l + r) / 2, (t + b) / 2) or 0xFF000000.toInt())
            // The very corner is outside the rounded outline, so the red content shows there.
            assertEquals(Color.RED, shot.getPixel(l + 2, t + 2) or 0xFF000000.toInt())
        }
    }

    @Test fun detachAndReattachKeepsWorking() {
        ActivityScenario.launch(BlurTestActivity::class.java).use { s ->
            screenshot()
            s.onActivity {
                val parent = it.blurView.parent as FrameLayout
                val lp = it.blurView.layoutParams
                parent.removeView(it.blurView)
                parent.addView(it.blurView, lp)
            }
            val shot = screenshot()
            val (l, t, r, b) = bounds(s).toList()
            val atEdge = shot.getPixel(boundaryX(s), (t + b) / 2)
            assertTrue(red(atEdge) in 60..200 && blue(atEdge) in 60..200, "not blurred after reattach")
            assertTrue(l < r && t < b)
        }
    }

    @Test fun renderEffectPathOnlyOnApi31() {
        assumeTrue(Build.VERSION.SDK_INT < Build.VERSION_CODES.S)
        ActivityScenario.launch(BlurTestActivity::class.java).use { s ->
            screenshot()
            var renderer: String? = null
            s.onActivity { renderer = it.blurView.activeRenderer }
            assertEquals("gles", renderer)
        }
    }
}
