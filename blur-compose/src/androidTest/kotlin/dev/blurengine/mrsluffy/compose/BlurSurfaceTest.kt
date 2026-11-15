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

package dev.blurengine.mrsluffy.compose

import android.graphics.Bitmap
import android.os.Build
import android.os.SystemClock
import android.util.Log
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.blurengine.mrsluffy.core.BlurAlgorithm
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.abs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** How long to wait for a backend to warm up; software GL on CI emulators needs seconds. */
private const val WARM_UP_TIMEOUT_MS = 30_000L

/**
 * Takes screenshots until two in a row match (within 4 per channel, on a sparse grid) and returns
 * the last, so nothing still changing on screen, such as a starting window fading out after
 * launch on API 31+, ends up in the pixels a test checks.
 */
private fun stableScreenshot(timeoutMs: Long = 10_000): Bitmap {
    val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
    val deadline = SystemClock.uptimeMillis() + timeoutMs
    var previous = assertNotNull(automation.takeScreenshot())
    while (true) {
        SystemClock.sleep(150)
        val current = assertNotNull(automation.takeScreenshot())
        val stable = sameOnGrid(previous, current)
        previous.recycle() // full-screen screenshots are large; don't leave them to the GC
        if (stable || SystemClock.uptimeMillis() >= deadline) return current
        previous = current
    }
}

private fun sameOnGrid(a: Bitmap, b: Bitmap, tolerance: Int = 4): Boolean {
    if (a.width != b.width || a.height != b.height) return false
    for (y in 12 until a.height step 24) {
        for (x in 12 until a.width step 24) {
            val p = a.getPixel(x, y)
            val q = b.getPixel(x, y)
            if (abs(android.graphics.Color.red(p) - android.graphics.Color.red(q)) > tolerance ||
                abs(android.graphics.Color.green(p) - android.graphics.Color.green(q)) > tolerance ||
                abs(android.graphics.Color.blue(p) - android.graphics.Color.blue(q)) > tolerance
            ) {
                return false
            }
        }
    }
    return true
}

@RunWith(AndroidJUnit4::class)
class BlurSurfaceTest {
    // Order 0: animations must be off before the compose rule launches its activity.
    @get:Rule(order = 0) val noWindowAnimations = NoWindowAnimations()
    @get:Rule(order = 1) val rule = createComposeRule()
    private val tag = "BlurEngineTest"

    /** Surface bounds and red/blue boundary, in screen pixels. */
    private class Geometry {
        var left = 0; var top = 0; var right = 0; var bottom = 0; var boundary = 0
        val midY get() = (top + bottom) / 2
    }

    private val geometry = Geometry()

    /** Red left half, blue right half, a 220x150 dp surface centred on the boundary. */
    @Composable
    private fun Scene(
        surface: Modifier,
        content: @Composable () -> Unit = {},
    ) {
        val view = LocalView.current
        Box(Modifier.fillMaxSize()) {
            Row(Modifier.fillMaxSize()) {
                Box(Modifier.weight(1f).fillMaxHeight().background(Color.Red))
                Box(Modifier.weight(1f).fillMaxHeight().background(Color.Blue)
                    .onGloballyPositioned { geometry.boundary = it.boundsInWindow().left.toInt() + windowX(view) })
            }
            Box(
                Modifier.align(Alignment.Center).size(220.dp, 150.dp)
                    .onGloballyPositioned {
                        val b = it.boundsInWindow()
                        geometry.left = b.left.toInt() + windowX(view); geometry.right = b.right.toInt() + windowX(view)
                        geometry.top = b.top.toInt() + windowY(view); geometry.bottom = b.bottom.toInt() + windowY(view)
                    }
                    .then(surface),
                contentAlignment = Alignment.Center,
            ) { content() }
        }
    }

    private fun windowX(v: android.view.View): Int { val a = IntArray(2); val b = IntArray(2); v.getLocationOnScreen(a); v.getLocationInWindow(b); return a[0] - b[0] }
    private fun windowY(v: android.view.View): Int { val a = IntArray(2); val b = IntArray(2); v.getLocationOnScreen(a); v.getLocationInWindow(b); return a[1] - b[1] }

    private fun screenshot(): Bitmap {
        rule.waitForIdle()
        val shot = stableScreenshot()
        // Keep the latest screenshot on the device for debugging failures (adb pull).
        InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir(null)?.let { dir ->
            java.io.File(dir, "last.png").outputStream().use { shot.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
        Log.i(tag, "geometry l=${geometry.left} t=${geometry.top} r=${geometry.right} b=${geometry.bottom} boundary=${geometry.boundary}")
        return shot
    }

    private fun isMixed(p: Int) = android.graphics.Color.red(p) in 40..215 && android.graphics.Color.blue(p) in 40..215

    /**
     * Backends warm up in the background, so allow time for the first blurred frame. Software GL
     * (SwiftShader on CI emulators) can take several seconds per backend.
     */
    private fun awaitBlurred(): Bitmap {
        val deadline = SystemClock.uptimeMillis() + WARM_UP_TIMEOUT_MS
        var shot = screenshot()
        while (!isMixed(shot.getPixel(geometry.boundary, geometry.midY)) && SystemClock.uptimeMillis() < deadline) {
            SystemClock.sleep(200)
            shot = screenshot()
        }
        return shot
    }

    private fun checkBlur(algorithm: BlurAlgorithm) {
        rule.setContent { Scene(Modifier.blurSurface { radius = 16.dp; this.algorithm = algorithm }) }
        val shot = awaitBlurred()
        val g = geometry
        val red = android.graphics.Color.RED
        val blue = android.graphics.Color.BLUE
        // Outside the surface the boundary is sharp; inside it is a gradient centred on it.
        kotlin.test.assertEquals(red, shot.getPixel(g.boundary - 2, g.top - 20) or 0xFF000000.toInt())
        kotlin.test.assertEquals(blue, shot.getPixel(g.boundary + 1, g.top - 20) or 0xFF000000.toInt())
        assertTrue(isMixed(shot.getPixel(g.boundary, g.midY)), "not blurred at the boundary")
        var crossover = -1
        for (x in g.left until g.right) {
            val p = shot.getPixel(x, g.midY)
            if (android.graphics.Color.blue(p) >= android.graphics.Color.red(p)) { crossover = x; break }
        }
        assertTrue(abs(crossover - g.boundary) <= 6, "misaligned: crossover=$crossover boundary=${g.boundary}")
        val farLeft = shot.getPixel(g.left + 15, g.midY)
        assertTrue(android.graphics.Color.red(farLeft) > 230 && android.graphics.Color.blue(farLeft) < 25)
        Log.i(tag, "api=${Build.VERSION.SDK_INT} compose $algorithm ok")
    }

    @Test fun blursBehindTheSurface() = checkBlur(BlurAlgorithm.AUTO)

    @Test fun blursBehindTheSurfaceWithGles() = checkBlur(BlurAlgorithm.DUAL_KAWASE)

    @Test fun animatedRadiusDoesNotRecompose() {
        var target by mutableStateOf(0.dp)
        var compositions = 0
        rule.setContent {
            val radius = animateDpAsState(target, spring(stiffness = 300f), label = "radius")
            SideEffect { compositions++ }
            // radius.value is read only inside the block, i.e. during the frame, not in composition.
            Scene(Modifier.blurSurface { this.radius = radius.value })
        }
        val before = screenshot()
        assertTrue(!isMixed(before.getPixel(geometry.boundary, geometry.midY)), "radius 0 should be sharp")
        val startCompositions = compositions
        rule.runOnIdle { target = 16.dp }
        val after = awaitBlurred()
        assertTrue(isMixed(after.getPixel(geometry.boundary, geometry.midY)), "not blurred after the animation")
        val extra = compositions - startCompositions
        Log.i(tag, "api=${Build.VERSION.SDK_INT} compose animation extra compositions=$extra")
        // Changing the target recomposes once; the animation frames themselves must not.
        assertTrue(extra <= 2, "recomposed $extra times during the animation")
    }

    @Test fun progressZeroShowsContentSharp() {
        var progress by mutableStateOf(0f)
        rule.setContent { Scene(Modifier.blurSurface { radius = 16.dp; this.progress = progress }) }
        val sharp = screenshot()
        val p = sharp.getPixel(geometry.boundary, geometry.midY)
        assertTrue(!isMixed(p), "progress 0 should not blur: ${Integer.toHexString(p)}")
        rule.runOnIdle { progress = 1f }
        assertTrue(isMixed(awaitBlurred().getPixel(geometry.boundary, geometry.midY)))
    }

    @Test fun progressiveBlurAndGlassFromTheScope() {
        rule.setContent {
            Scene(Modifier.blurSurface {
                radius = 16.dp
                gradient = dev.blurengine.mrsluffy.core.BlurGradient.TopToBottom
                cornerRadius(24.dp)
                glass(refraction = 0.dp, highlight = 1f, gloss = 0f, lightAngle = 270f)
            })
        }
        val g = geometry
        val shot = awaitBlurredAt { it.getPixel(g.boundary, g.bottom - 8) }
        val top = shot.getPixel(g.boundary, g.top + 30)
        val bottom = shot.getPixel(g.boundary, g.bottom - 8)
        Log.i(tag, "api=${Build.VERSION.SDK_INT} compose gradient top=${Integer.toHexString(top)} bottom=${Integer.toHexString(bottom)}")
        assertTrue(!isMixed(top), "top should be sharp: ${Integer.toHexString(top)}")
        assertTrue(isMixed(bottom), "bottom should be blurred: ${Integer.toHexString(bottom)}")
        // Light from the top (270°): the top rim is lit, so it is whiter than just below it.
        val x = g.left + (g.boundary - g.left) / 2
        val rim = shot.getPixel(x, g.top + 1)
        val below = shot.getPixel(x, g.top + 30)
        assertTrue(android.graphics.Color.green(rim) > android.graphics.Color.green(below) + 50,
            "top rim should be lit: rim=${Integer.toHexString(rim)} below=${Integer.toHexString(below)}")
    }

    /** Like awaitBlurred, but waits for the pixel [probe] picks to be mixed. */
    private fun awaitBlurredAt(probe: (Bitmap) -> Int): Bitmap {
        val deadline = SystemClock.uptimeMillis() + WARM_UP_TIMEOUT_MS
        var shot = screenshot()
        while (!isMixed(probe(shot)) && SystemClock.uptimeMillis() < deadline) {
            SystemClock.sleep(200)
            shot = screenshot()
        }
        return shot
    }

    @Test fun shapeClipsTheBlur() {
        rule.setContent {
            Scene(Modifier.blurSurface { radius = 16.dp; tint(Color.White); cornerRadius(40.dp) })
        }
        SystemClock.sleep(500)
        val shot = screenshot()
        val g = geometry
        kotlin.test.assertEquals(android.graphics.Color.WHITE, shot.getPixel((g.left + g.right) / 2, g.midY) or 0xFF000000.toInt())
        kotlin.test.assertEquals(android.graphics.Color.RED, shot.getPixel(g.left + 2, g.top + 2) or 0xFF000000.toInt(),
            "the corner is outside the rounded shape and should show the content")
    }

    @Test fun contentIsDrawnOnTopButNotBlurred() {
        rule.setContent {
            Scene(Modifier.blurSurface { radius = 24.dp }) {
                Box(Modifier.size(40.dp).background(Color.Green))
            }
        }
        val shot = awaitBlurred()
        val g = geometry
        val cx = (g.left + g.right) / 2
        kotlin.test.assertEquals(android.graphics.Color.GREEN, shot.getPixel(cx, g.midY) or 0xFF000000.toInt())
        // Just outside the green child: if the child were captured, its green would bleed in here.
        val density = InstrumentationRegistry.getInstrumentation().targetContext.resources.displayMetrics.density
        val near = shot.getPixel(cx - (20 * density).toInt() - 12, g.midY)
        assertTrue(android.graphics.Color.green(near) < 30, "child leaked into the blur: ${Integer.toHexString(near)}")
        // Stable across frames (no feedback).
        val again = screenshot()
        for (x in listOf(g.left + 10, g.boundary - 20, g.boundary + 20, g.right - 10)) {
            val d = abs(android.graphics.Color.red(shot.getPixel(x, g.midY)) - android.graphics.Color.red(again.getPixel(x, g.midY)))
            assertTrue(d <= 4, "drifted at $x by $d")
        }
    }
}
