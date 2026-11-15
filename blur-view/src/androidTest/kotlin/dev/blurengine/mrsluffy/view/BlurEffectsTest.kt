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
import android.view.Gravity
import android.widget.FrameLayout
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.blurengine.mrsluffy.core.BlurGradient
import dev.blurengine.mrsluffy.core.GlassStyle
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Progressive blur, stacked (multi-layer) blur and liquid glass, checked on real screenshots. */
@RunWith(AndroidJUnit4::class)
class BlurEffectsTest {
    @get:Rule val noWindowAnimations = NoWindowAnimations()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val tag = "BlurEngineTest"

    /** Waits until [count] frames have been committed after now, so recent changes are on screen. */
    private fun awaitFrames(count: Int = 2) {
        repeat(count) {
            val done = java.util.concurrent.CountDownLatch(1)
            instrumentation.runOnMainSync {
                val view = BlurTestActivity.current?.window?.decorView
                if (view == null) {
                    done.countDown()
                } else {
                    view.viewTreeObserver.registerFrameCommitCallback { done.countDown() }
                    view.invalidate()
                }
            }
            done.await(3, java.util.concurrent.TimeUnit.SECONDS)
        }
    }

    private fun screenshot(name: String): Bitmap {
        awaitRendered(instrumentation)
        awaitFrames()
        val shot = stableScreenshot(instrumentation)
        instrumentation.targetContext.getExternalFilesDir(null)?.let { dir ->
            java.io.File(dir, "$name.png").outputStream().use { shot.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
        return shot
    }

    private fun mixed(p: Int) = Color.red(p) in 40..215 && Color.blue(p) in 40..215

    private class Box(var l: Int = 0, var t: Int = 0, var r: Int = 0, var b: Int = 0, var boundary: Int = 0)

    private fun geometry(s: ActivityScenario<BlurTestActivity>, view: (BlurTestActivity) -> BlurView = { it.blurView }): Box {
        val g = Box()
        s.onActivity { a ->
            val v = view(a)
            val loc = IntArray(2)
            v.getLocationOnScreen(loc)
            g.l = loc[0]; g.t = loc[1]; g.r = loc[0] + v.width; g.b = loc[1] + v.height
            a.right.getLocationOnScreen(loc)
            g.boundary = loc[0]
        }
        return g
    }

    @Test fun progressiveBlurIsSharpAtTheStartAndBlurredAtTheEnd() {
        ActivityScenario.launch(BlurTestActivity::class.java).use { s ->
            s.onActivity { it.blurView.gradient = BlurGradient.TopToBottom }
            val shot = screenshot("gradient")
            val g = geometry(s)
            val nearTop = shot.getPixel(g.boundary, g.t + 6)
            val middle = shot.getPixel(g.boundary, (g.t + g.b) / 2)
            val nearBottom = shot.getPixel(g.boundary, g.b - 6)
            Log.i(tag, "api=${Build.VERSION.SDK_INT} gradient top=${hex(nearTop)} mid=${hex(middle)} bottom=${hex(nearBottom)}")
            assertTrue(!mixed(nearTop), "top of a top-to-bottom gradient should be sharp: ${hex(nearTop)}")
            assertTrue(mixed(nearBottom), "bottom should be blurred: ${hex(nearBottom)}")
            // Monotonic: the boundary blurs more as intensity grows, so it gets closer to an even mix.
            fun spread(p: Int) = kotlin.math.abs(Color.red(p) - Color.blue(p))
            assertTrue(spread(nearTop) >= spread(middle) && spread(middle) >= spread(nearBottom) - 10,
                "blur should increase downward: top=${spread(nearTop)} mid=${spread(middle)} bottom=${spread(nearBottom)}")
        }
    }

    /** A white glass panel below the view must show up in the view's blur. */
    @Test fun stackedBlurViewsShowTheOneBelow() {
        ActivityScenario.launch(BlurTestActivity::class.java).use { s ->
            s.onActivity { a ->
                val root = a.blurView.parent as FrameLayout
                val below = BlurView(a).apply { blurRadius = 20f; setTint(Color.WHITE, 1f) }
                // Index 1: after the red/blue background, before the existing BlurView.
                root.addView(below, 1, FrameLayout.LayoutParams(600, 400, Gravity.CENTER))
            }
            val shot = screenshot("stacked")
            val g = geometry(s)
            val center = shot.getPixel((g.l + g.r) / 2, (g.t + g.b) / 2)
            Log.i(tag, "api=${Build.VERSION.SDK_INT} stacked center=${hex(center)}")
            // Without stacking, the top view would show blurred red and blue here (green near 0).
            assertTrue(Color.green(center) > 200 && Color.red(center) > 200 && Color.blue(center) > 200,
                "panel below is missing from the blur: ${hex(center)}")
            // And the result is stable: no feedback between the two views.
            val again = screenshot("stacked-again")
            val p = again.getPixel((g.l + g.r) / 2, (g.t + g.b) / 2)
            assertTrue(kotlin.math.abs(Color.green(p) - Color.green(center)) <= 4, "stacked output drifted")
        }
    }

    /** Moves the view fully over the red half, its right edge 10 px from the blue. */
    private fun placeOverRed(a: BlurTestActivity) {
        a.blurView.layoutParams = a.blurView.layoutParams.apply { width = 300 }
        a.blurView.translationX = -(150f + 10f)
    }

    /**
     * The view sits over red with its rightmost 40 px over blue. The rim samples inward, so the
     * pixel 6 px inside the right edge (blue under it) shows the red further in.
     */
    @Test fun glassRefractionBendsContentFromInsideTheRim() {
        assumeTrue("refraction needs API 33", Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
        ActivityScenario.launch(BlurTestActivity::class.java).use { s ->
            s.onActivity { a ->
                a.blurView.layoutParams = a.blurView.layoutParams.apply { width = 300 }
                a.blurView.translationX = -(150f - 40f)
                a.blurView.blurRadius = 2f
            }
            val plain = screenshot("glass-off")
            val g = geometry(s)
            val y = (g.t + g.b) / 2
            val before = plain.getPixel(g.r - 6, y)
            s.onActivity {
                it.blurView.glass = GlassStyle(refraction = 40f, edgeWidth = 30f, dispersion = 0f, highlight = 0f, gloss = 0f)
            }
            val shot = screenshot("glass-on")
            val after = shot.getPixel(g.r - 6, y)
            var refracting = false
            s.onActivity { refracting = it.blurView.isRefracting }
            Log.i(tag, "api=${Build.VERSION.SDK_INT} glass rim before=${hex(before)} after=${hex(after)} refracting=$refracting")
            assertTrue(refracting, "refraction should be active on API 33+")
            assertTrue(Color.red(before) < 40, "without glass the rim shows the blue under it: ${hex(before)}")
            assertTrue(Color.red(after) > 120, "glass rim should show the red further inside: ${hex(after)}")
            // Away from the rim nothing moves.
            val center = shot.getPixel((g.l + g.r) / 2, y)
            assertTrue(Color.red(center) > 200 && Color.blue(center) < 40, "center should stay red: ${hex(center)}")
        }
    }

    @Test fun glassLightingBrightensTheLitRim() {
        ActivityScenario.launch(BlurTestActivity::class.java).use { s ->
            s.onActivity { a ->
                placeOverRed(a)
                a.blurView.glass = GlassStyle(refraction = 0f, highlight = 1f, gloss = 0.3f, lightAngle = 180f)
            }
            val shot = screenshot("glass-light")
            val g = geometry(s)
            val y = (g.t + g.b) / 2
            // Light from the left (180°): the left rim is lit, the center is not.
            val rim = shot.getPixel(g.l + 2, y)
            val center = shot.getPixel((g.l + g.r) / 2, y)
            Log.i(tag, "api=${Build.VERSION.SDK_INT} glass light rim=${hex(rim)} center=${hex(center)}")
            assertTrue(Color.green(rim) > Color.green(center) + 60,
                "lit rim should be brighter than the center: rim=${hex(rim)} center=${hex(center)}")
        }
    }

    @Test fun glassFadesOutWithHide() {
        ActivityScenario.launch(BlurTestActivity::class.java).use { s ->
            s.onActivity { a ->
                placeOverRed(a)
                a.blurView.glass = GlassStyle(refraction = 0f, highlight = 1f, gloss = 0.3f, lightAngle = 180f)
                a.blurView.setBlurProgress(0f)
            }
            val shot = screenshot("glass-progress0")
            val g = geometry(s)
            val rim = shot.getPixel(g.l + 2, (g.t + g.b) / 2)
            assertTrue(Color.green(rim) < 30, "lighting should be gone at progress 0: ${hex(rim)}")
        }
    }

    private fun hex(p: Int) = Integer.toHexString(p)
}
