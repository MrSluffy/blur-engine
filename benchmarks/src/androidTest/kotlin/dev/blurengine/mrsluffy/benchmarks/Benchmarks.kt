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

package dev.blurengine.mrsluffy.benchmarks

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.util.Log
import android.view.Choreographer
import android.view.FrameMetrics
import android.view.Gravity
import android.view.Window
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.benchmark.junit4.BenchmarkRule
import androidx.benchmark.junit4.measureRepeated
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.blurengine.mrsluffy.capture.ViewCapture
import dev.blurengine.mrsluffy.core.BlurAlgorithm
import dev.blurengine.mrsluffy.core.BlurBackend
import dev.blurengine.mrsluffy.core.BlurConfig
import dev.blurengine.mrsluffy.core.BlurGradient
import dev.blurengine.mrsluffy.core.GlassStyle
import dev.blurengine.mrsluffy.platform.BitmapImage
import dev.blurengine.mrsluffy.platform.GlesBlurBackend
import dev.blurengine.mrsluffy.platform.RenderEffectBlurBackend
import dev.blurengine.mrsluffy.platform.SoftwareBlurBackend
import dev.blurengine.mrsluffy.view.BlurView
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.assertTrue

private const val TAG = "BlurBenchmark"

/** A gradient + checker image, the kind of content a background blur sees. */
private fun sourceImage(w: Int, h: Int): Bitmap {
    val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
    val c = Canvas(bmp)
    val p = Paint()
    p.shader = LinearGradient(0f, 0f, w.toFloat(), h.toFloat(),
        intArrayOf(0xFF1E88E5.toInt(), 0xFF8E24AA.toInt(), 0xFFFB8C00.toInt()), null, Shader.TileMode.CLAMP)
    c.drawRect(0f, 0f, w.toFloat(), h.toFloat(), p)
    p.shader = null
    p.color = 0x55FFFFFF
    var y = 0
    while (y < h) { var x = (y / 16 % 2) * 16; while (x < w) { c.drawRect(x.toFloat(), y.toFloat(), x + 16f, y + 16f, p); x += 32 }; y += 16 }
    return bmp
}

/**
 * Backends on a 270x600 buffer: a 1080x2400 screen at downsample 4, which is what BlurView hands
 * the bitmap backends. Radius 6 here corresponds to radius 24 at full resolution.
 */
@RunWith(AndroidJUnit4::class)
class BackendBenchmark {
    @get:Rule val benchmark = BenchmarkRule()

    private val input = sourceImage(270, 600)
    private val output = Bitmap.createBitmap(270, 600, Bitmap.Config.ARGB_8888)

    private fun run(backend: BlurBackend, algorithm: BlurAlgorithm) {
        val config = BlurConfig(radius = 6f, downsample = 1, algorithm = algorithm)
        backend.initialize(config)
        val i = BitmapImage(input)
        val o = BitmapImage(output)
        backend.render(i, o) // warm-up outside the measurement
        benchmark.measureRepeated { backend.render(i, o) }
        backend.release()
    }

    @Test fun software_dualKawase() = run(SoftwareBlurBackend(), BlurAlgorithm.DUAL_KAWASE)
    @Test fun software_gaussian() = run(SoftwareBlurBackend(), BlurAlgorithm.GAUSSIAN)
    @Test fun software_box() = run(SoftwareBlurBackend(), BlurAlgorithm.BOX)
    @Test fun softwareFast_gaussian() = run(SoftwareBlurBackend(fastApproximation = true), BlurAlgorithm.GAUSSIAN)
    @Test fun gles_dualKawase() = run(GlesBlurBackend(), BlurAlgorithm.DUAL_KAWASE)
    @Test fun gles_gaussian() = run(GlesBlurBackend(), BlurAlgorithm.GAUSSIAN)

    @Test fun renderEffect_gaussian() {
        assumeTrue(Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
        run(RenderEffectBlurBackend(), BlurAlgorithm.GAUSSIAN)
    }
}

/** A scrolling list of gradient rows, with an optional frosted top bar and bottom card. */
class ScrollActivity : Activity() {
    lateinit var scroll: ScrollView
    val blurViews = ArrayList<BlurView>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val withBlur = intent.getBooleanExtra(EXTRA_BLUR, true)
        val algorithm = BlurAlgorithm.valueOf(intent.getStringExtra(EXTRA_ALGORITHM) ?: "AUTO")
        val root = FrameLayout(this)
        val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val palette = intArrayOf(0xFFE53935.toInt(), 0xFFFB8C00.toInt(), 0xFFFDD835.toInt(), 0xFF43A047.toInt(),
            0xFF1E88E5.toInt(), 0xFF8E24AA.toInt())
        for (i in 0 until 80) {
            list.addView(TextView(this).apply {
                text = "Row ${i + 1}"
                textSize = 22f
                setTextColor(Color.WHITE)
                gravity = Gravity.CENTER_VERTICAL
                setPadding(48, 0, 0, 0)
                background = GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT,
                    intArrayOf(palette[i % 6], palette[(i + 2) % 6])).apply { cornerRadius = 40f }
            }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 260).apply { setMargins(40, 20, 40, 20) })
        }
        scroll = ScrollView(this).apply { addView(list) }
        root.addView(scroll, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        if (withBlur) {
            val bar = BlurView(this).apply { sourceView = scroll; blurRadius = 80f; setTint(Color.WHITE, 0.35f); this.algorithm = algorithm }
            val card = BlurView(this).apply { sourceView = scroll; blurRadius = 64f; setTint(Color.WHITE, 0.2f); cornerRadius = 70f; this.algorithm = algorithm }
            blurViews += bar
            blurViews += card
            root.addView(bar, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, 250, Gravity.TOP))
            root.addView(card, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, 600, Gravity.BOTTOM).apply {
                setMargins(40, 0, 40, 60)
            })
        }
        setContentView(root)
    }

    companion object {
        const val EXTRA_BLUR = "blur"
        const val EXTRA_ALGORITHM = "algorithm"
    }
}

/** Capture and full BlurView update cost, on the UI thread where they run in an app. */
@RunWith(AndroidJUnit4::class)
class SurfaceBenchmark {
    @get:Rule val benchmark = BenchmarkRule()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    private fun launch(algorithm: BlurAlgorithm): ActivityScenario<ScrollActivity> {
        val intent = android.content.Intent(instrumentation.targetContext, ScrollActivity::class.java)
            .putExtra(ScrollActivity.EXTRA_ALGORITHM, algorithm.name)
        val s = ActivityScenario.launch<ScrollActivity>(intent)
        instrumentation.waitForIdleSync()
        SystemClock.sleep(1500) // backend warm-up
        return s
    }

    /** Capturing the 1080x600-ish region under the card (plus margin) at downsample 4. */
    @Test fun capture_cardRegion() {
        launch(BlurAlgorithm.AUTO).use { s ->
            val capture = ViewCapture()
            s.onActivity { a ->
                val card = a.blurViews[1]
                benchmark.measureRepeated { capture.capture(a.scroll, card, 4, 64) }
            }
        }
    }

    private fun update(algorithm: BlurAlgorithm) {
        launch(algorithm).use { s ->
            s.onActivity { a ->
                val card = a.blurViews[1]
                benchmark.measureRepeated { card.invalidateBlur() }
                Log.i(TAG, "api=${Build.VERSION.SDK_INT} update renderer=${card.activeRenderer}")
            }
        }
    }

    @Test fun blurViewUpdate_auto() = update(BlurAlgorithm.AUTO)
    @Test fun blurViewUpdate_gles() = update(BlurAlgorithm.DUAL_KAWASE)

    /** Steady-state cost of the effects on the default (AUTO) path. */
    private fun updateWith(name: String, setup: (BlurView) -> Unit) {
        launch(BlurAlgorithm.AUTO).use { s ->
            s.onActivity { a ->
                val card = a.blurViews[1]
                setup(card)
                card.invalidateBlur() // build effects and shaders before measuring
                benchmark.measureRepeated { card.invalidateBlur() }
                Log.i(TAG, "api=${Build.VERSION.SDK_INT} update $name renderer=${card.activeRenderer}")
            }
        }
    }

    @Test fun blurViewUpdate_glass() = updateWith("glass") { it.glass = GlassStyle() }
    @Test fun blurViewUpdate_gradient() = updateWith("gradient") { it.gradient = BlurGradient.BottomToTop }
    @Test fun blurViewUpdate_glassAndGradient() = updateWith("glass+gradient") {
        it.glass = GlassStyle()
        it.gradient = BlurGradient.BottomToTop
    }
}

/**
 * Real frame times while scrolling, from FrameMetrics, with and without blur. Not a pass/fail
 * benchmark: logs percentiles and the share of frames over the 60 Hz budget (16.7 ms).
 */
@RunWith(AndroidJUnit4::class)
class ScrollFrameTimes {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    private data class Stats(val frames: Int, val p50: Double, val p90: Double, val p99: Double, val overBudget: Double)

    private fun measure(blur: Boolean, algorithm: BlurAlgorithm = BlurAlgorithm.AUTO): Stats {
        val intent = android.content.Intent(instrumentation.targetContext, ScrollActivity::class.java)
            .putExtra(ScrollActivity.EXTRA_BLUR, blur)
            .putExtra(ScrollActivity.EXTRA_ALGORITHM, algorithm.name)
        ActivityScenario.launch<ScrollActivity>(intent).use { s ->
            instrumentation.waitForIdleSync()
            SystemClock.sleep(1500)
            val durations = ArrayList<Double>()
            val thread = HandlerThread("frame-metrics").apply { start() }
            val listener = Window.OnFrameMetricsAvailableListener { _, metrics, _ ->
                synchronized(durations) { durations += metrics.getMetric(FrameMetrics.TOTAL_DURATION) / 1e6 }
            }
            val done = CountDownLatch(1)
            s.onActivity { a ->
                a.window.addOnFrameMetricsAvailableListener(listener, Handler(thread.looper))
                var frames = 0
                // Scroll 12 px per frame for 240 frames (about 4 s at 60 Hz).
                Choreographer.getInstance().postFrameCallback(object : Choreographer.FrameCallback {
                    override fun doFrame(frameTimeNanos: Long) {
                        a.scroll.scrollBy(0, 12)
                        if (++frames < 240) Choreographer.getInstance().postFrameCallback(this) else done.countDown()
                    }
                })
            }
            assertTrue(done.await(30, TimeUnit.SECONDS))
            SystemClock.sleep(300)
            s.onActivity { it.window.removeOnFrameMetricsAvailableListener(listener) }
            thread.quitSafely()
            val sorted = synchronized(durations) { durations.drop(10).sorted() } // drop start-up frames
            fun pct(p: Double) = sorted[((sorted.size - 1) * p).toInt()]
            return Stats(sorted.size, pct(0.5), pct(0.9), pct(0.99), sorted.count { it > 16.67 } * 100.0 / sorted.size)
        }
    }

    @Test fun scrollWithAndWithoutBlur() {
        val runs = listOf(
            "no blur" to measure(blur = false),
            "2 BlurViews AUTO" to measure(blur = true),
            "2 BlurViews DUAL_KAWASE (GLES)" to measure(blur = true, algorithm = BlurAlgorithm.DUAL_KAWASE),
        )
        for ((name, st) in runs) {
            Log.i(TAG, "api=${Build.VERSION.SDK_INT} scroll [$name] frames=${st.frames} p50=%.1f p90=%.1f p99=%.1f ms over16.7=%.1f%%"
                .format(st.p50, st.p90, st.p99, st.overBudget))
        }
        assertTrue(runs.all { it.second.frames > 100 }, "too few frames recorded")
    }
}
