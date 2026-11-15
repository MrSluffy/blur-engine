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
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.os.Build
import android.os.SystemClock
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.blurengine.mrsluffy.core.BlurAlgorithm
import dev.blurengine.mrsluffy.core.BlurBackend
import dev.blurengine.mrsluffy.core.BlurConfig
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.abs
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Difference between two images, per 8-bit channel (RGB only; test images are opaque). */
data class Diff(val mean: Double, val max: Int)

object Images {
    /** Opaque gradient + checkerboard + text, like the sample app. */
    fun source(w: Int, h: Int): Bitmap {
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        paint.shader = LinearGradient(0f, 0f, w.toFloat(), h.toFloat(),
            intArrayOf(0xFF1E88E5.toInt(), 0xFF8E24AA.toInt(), 0xFFFB8C00.toInt()), null, Shader.TileMode.CLAMP)
        canvas.drawRect(0f, 0f, w.toFloat(), h.toFloat(), paint)
        paint.shader = null
        paint.color = 0x55FFFFFF
        val cell = 24f
        var y = 0f; var row = 0
        while (y < h) {
            var x = if (row % 2 == 0) 0f else cell
            while (x < w) { canvas.drawRect(x, y, x + cell, y + cell, paint); x += cell * 2 }
            y += cell; row++
        }
        paint.color = Color.WHITE
        paint.textSize = h / 6f
        canvas.drawText("Blur", w / 8f, h / 2f, paint)
        return bmp
    }

    fun blank(w: Int, h: Int): Bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)

    fun diff(a: Bitmap, b: Bitmap): Diff {
        val n = a.width * a.height
        val pa = IntArray(n).also { a.getPixels(it, 0, a.width, 0, 0, a.width, a.height) }
        val pb = IntArray(n).also { b.getPixels(it, 0, b.width, 0, 0, b.width, b.height) }
        var sum = 0L; var max = 0
        for (i in 0 until n) for (shift in intArrayOf(16, 8, 0)) {
            val d = abs((pa[i] shr shift and 0xFF) - (pb[i] shr shift and 0xFF))
            sum += d; if (d > max) max = d
        }
        return Diff(sum.toDouble() / (n * 3), max)
    }
}

@RunWith(AndroidJUnit4::class)
class BackendComparisonTest {
    private val w = 360
    private val h = 480
    private val source = Images.source(w, h)
    private val tag = "BlurEngineTest"

    private fun render(backend: BlurBackend, config: BlurConfig): Bitmap {
        backend.initialize(config)
        val out = Images.blank(w, h)
        backend.render(BitmapImage(source), BitmapImage(out))
        return out
    }

    private fun reference(config: BlurConfig): Bitmap = SoftwareBlurBackend().let { b ->
        render(b, config).also { b.release() }
    }

    private fun compare(name: String, backend: BlurBackend, config: BlurConfig, maxMean: Double): Diff {
        val actual = render(backend, config)
        val d = Images.diff(reference(config), actual)
        Log.i(tag, "api=${Build.VERSION.SDK_INT} $name vs software: mean=%.3f max=%d".format(d.mean, d.max))
        assertTrue(d.mean <= maxMean, "$name mean diff ${d.mean} > $maxMean (max ${d.max})")
        return d
    }

    private val configs = listOf(
        BlurConfig(radius = 24f, algorithm = BlurAlgorithm.DUAL_KAWASE, downsample = 4),
        BlurConfig(radius = 8f, algorithm = BlurAlgorithm.DUAL_KAWASE, downsample = 2),
        BlurConfig(radius = 80f, algorithm = BlurAlgorithm.DUAL_KAWASE, downsample = 4),
        BlurConfig(radius = 24f, algorithm = BlurAlgorithm.GAUSSIAN, downsample = 4),
        BlurConfig(radius = 6f, algorithm = BlurAlgorithm.GAUSSIAN, downsample = 1),
        BlurConfig(radius = 20f, algorithm = BlurAlgorithm.DUAL_KAWASE, downsample = 4, saturation = 0.3f,
            brightness = 1.1f, tintColor = Color.WHITE, tintAlpha = 0.2f),
    )

    @Test fun glesMatchesSoftware() {
        val gles = GlesBlurBackend()
        try {
            for (c in configs) compare("gles ${c.algorithm} r=${c.radius} ds=${c.downsample}", gles, c, maxMean = 2.0)
        } finally {
            gles.release()
        }
    }

    @Test fun renderEffectMatchesSoftwareGaussian() {
        assumeTrue(Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
        val re = RenderEffectBlurBackend()
        try {
            for (c in configs.filter { it.algorithm == BlurAlgorithm.GAUSSIAN }) {
                compare("render-effect r=${c.radius} ds=${c.downsample}", re, c, maxMean = 3.0)
            }
            // Same blur via AUTO, plus color adjustment and tint.
            val tinted = BlurConfig(radius = 20f, algorithm = BlurAlgorithm.GAUSSIAN, saturation = 0.3f,
                brightness = 1.1f, tintColor = Color.WHITE, tintAlpha = 0.2f)
            compare("render-effect tinted", re, tinted, maxMean = 3.0)
        } finally {
            re.release()
        }
    }

    @Test fun fullTintGivesTintColorOnEveryBackend() {
        val config = BlurConfig(radius = 10f, algorithm = BlurAlgorithm.GAUSSIAN, tintColor = Color.RED, tintAlpha = 1f)
        val backends = buildList<BlurBackend> {
            add(GlesBlurBackend())
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) add(RenderEffectBlurBackend())
        }
        for (b in backends) {
            val out = render(b, config)
            assertEquals(Color.RED, out.getPixel(w / 2, h / 2), b.id)
            b.release()
        }
    }

    @Test fun glesRecoversFromContextLoss() {
        val gles = GlesBlurBackend()
        try {
            val config = configs[0]
            val before = render(gles, config)
            gles.simulateContextLoss()
            val after = Images.blank(w, h)
            gles.render(BitmapImage(source), BitmapImage(after))
            val d = Images.diff(before, after)
            assertEquals(0, d.max, "output changed after context recovery: $d")
        } finally {
            gles.release()
        }
    }

    @Test fun glesHandlesSizeChangesAndReinitialize() {
        val gles = GlesBlurBackend()
        try {
            gles.initialize(configs[0])
            for ((sw, sh) in listOf(64 to 64, 300 to 17, 1 to 1, 721 to 403)) {
                val src = Images.source(sw, sh)
                val out = Images.blank(sw, sh)
                gles.render(BitmapImage(src), BitmapImage(out))
            }
            gles.release()
            gles.initialize(configs[0]) // usable again after release
            gles.render(BitmapImage(source), BitmapImage(Images.blank(w, h)))
        } finally {
            gles.release()
        }
    }

    /** Not a pass/fail benchmark: logs warm per-frame times so backends can be compared. */
    @Test fun logTimings() {
        val bw = 1080; val bh = 2400
        val big = Images.source(bw, bh)
        val out = Images.blank(bw, bh)
        val config = BlurConfig(radius = 24f, downsample = 4)
        // GPU backends only: a full-screen CPU blur needs ~20 MB of Java arrays, more than some
        // emulator test processes get, and BlurView never hands the CPU path a frame this large.
        // CPU costs at the real (downsampled) size are in the benchmarks module.
        val backends = buildList<BlurBackend> {
            add(GlesBlurBackend())
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) add(RenderEffectBlurBackend())
        }
        for (b in backends) {
            val c = if (b.id == RenderEffectBlurBackend.ID) config.copy(algorithm = BlurAlgorithm.GAUSSIAN)
                else config.copy(algorithm = BlurAlgorithm.DUAL_KAWASE)
            b.initialize(c)
            b.render(BitmapImage(big), BitmapImage(out)) // warm-up
            val runs = if (b.id == SoftwareBlurBackend.ID) 3 else 20
            val start = SystemClock.elapsedRealtimeNanos()
            repeat(runs) { b.render(BitmapImage(big), BitmapImage(out)) }
            val ms = (SystemClock.elapsedRealtimeNanos() - start) / 1e6 / runs
            Log.i(tag, "api=${Build.VERSION.SDK_INT} timing ${b.id} ${bw}x$bh ds=4 r=24: %.1f ms/frame".format(ms))
            b.release()
        }
    }
}
