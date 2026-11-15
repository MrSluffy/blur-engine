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

package dev.blurengine.mrsluffy.samples

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.TextView
import dev.blurengine.mrsluffy.core.BlurAlgorithm
import dev.blurengine.mrsluffy.core.BlurBackend
import dev.blurengine.mrsluffy.core.BlurBackendFactory
import dev.blurengine.mrsluffy.core.BlurConfig
import dev.blurengine.mrsluffy.core.DeviceCapabilities
import dev.blurengine.mrsluffy.platform.BitmapImage
import dev.blurengine.mrsluffy.platform.DeviceCapabilitiesDetector
import dev.blurengine.mrsluffy.platform.GlesBlurBackend
import dev.blurengine.mrsluffy.platform.RenderEffectBackendFactory
import dev.blurengine.mrsluffy.platform.SoftwareBlurBackend
import dev.blurengine.mrsluffy.render.BlurBackendSelector

/**
 * Blurs a generated image on a worker thread. Pick a backend and algorithm, drag the slider to
 * change the radius. The label shows what actually rendered and how long one frame took.
 */
class BasicSampleActivity : Activity() {
    private enum class BackendChoice(val label: String, val factories: List<BlurBackendFactory>) {
        AUTO("Backend: automatic", BlurBackendSelector.DEFAULT_FACTORIES),
        RENDER_EFFECT("Backend: RenderEffect (API 31+)", listOf(RenderEffectBackendFactory)),
        GLES("Backend: GLES", listOf(GlesBlurBackend.factory)),
        SOFTWARE("Backend: software", listOf(SoftwareBlurBackend.factory)),
    }

    private val worker = HandlerThread("blur-worker").apply { start() }
    private val workerHandler = Handler(worker.looper)
    private val mainHandler = Handler(Looper.getMainLooper())

    private lateinit var imageView: ImageView
    private lateinit var label: TextView
    private lateinit var capabilities: DeviceCapabilities

    // Owned by the worker thread.
    private var backend: BlurBackend? = null
    private var backendFor: Pair<BackendChoice, BlurAlgorithm>? = null
    private lateinit var input: Bitmap
    private val outputs = arrayOfNulls<Bitmap>(2)
    private var nextOutput = 0

    @Volatile private var config = BlurConfig(radius = 24f, tintColor = Color.WHITE, tintAlpha = 0.15f)
    @Volatile private var choice = BackendChoice.AUTO
    @Volatile private var renderQueued = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        capabilities = DeviceCapabilitiesDetector.detect(this)
        val pad = (16 * resources.displayMetrics.density).toInt()

        imageView = ImageView(this).apply { scaleType = ImageView.ScaleType.FIT_CENTER }
        label = TextView(this)
        val backendSpinner = spinner(BackendChoice.values().map { it.label }) { choice = BackendChoice.values()[it]; requestRender() }
        val algorithms = BlurAlgorithm.values()
        val algorithmSpinner = spinner(algorithms.map { "Algorithm: ${it.name}" }) { update(config.copy(algorithm = algorithms[it])) }
        val seek = SeekBar(this).apply {
            max = 100
            progress = config.radius.toInt()
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar?, value: Int, fromUser: Boolean) =
                    update(config.copy(radius = value.toFloat()))
                override fun onStartTrackingTouch(s: SeekBar?) = Unit
                override fun onStopTrackingTouch(s: SeekBar?) = Unit
            })
        }

        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad * 2, pad, pad)
            addView(imageView, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))
            addView(label, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
            addView(backendSpinner, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
            addView(algorithmSpinner, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
            addView(seek, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        })

        workerHandler.post { input = createSourceImage(720, 960) }
        requestRender()
    }

    private fun spinner(items: List<String>, onSelected: (Int) -> Unit) = Spinner(this).apply {
        adapter = ArrayAdapter(context, android.R.layout.simple_spinner_dropdown_item, items)
        onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) = onSelected(pos)
            override fun onNothingSelected(p: AdapterView<*>?) = Unit
        }
    }

    private fun update(newConfig: BlurConfig) {
        config = newConfig
        requestRender()
    }

    /** Coalesces events: at most one render queued at a time, always using the latest settings. */
    private fun requestRender() {
        if (renderQueued) return
        renderQueued = true
        workerHandler.post {
            renderQueued = false
            val c = config
            val b = backendFor(choice, c) ?: return@post
            val out = outputs[nextOutput]?.takeIf { it.width == input.width && it.height == input.height }
                ?: Bitmap.createBitmap(input.width, input.height, Bitmap.Config.ARGB_8888).also { outputs[nextOutput] = it }
            // Double-buffer so the bitmap on screen is never written to.
            nextOutput = 1 - nextOutput

            val start = SystemClock.elapsedRealtimeNanos()
            val error = runCatching {
                b.updateConfig(c)
                b.render(BitmapImage(input), BitmapImage(out))
            }.exceptionOrNull()
            val ms = (SystemClock.elapsedRealtimeNanos() - start) / 1_000_000.0

            mainHandler.post {
                if (isDestroyed) return@post
                if (error != null) {
                    label.text = "${b.id} failed: ${error.message}"
                } else {
                    imageView.setImageBitmap(out)
                    val algorithm = if (b.id == "render-effect") "GAUSSIAN" else c.resolvedAlgorithm().name
                    label.text = "%s via %s  radius=%.0f  %.1f ms".format(algorithm, b.id, c.radius, ms)
                }
            }
        }
    }

    /** Re-selects the backend when the backend choice or algorithm changes. Runs on the worker. */
    private fun backendFor(choice: BackendChoice, c: BlurConfig): BlurBackend? {
        val key = choice to c.algorithm
        if (key == backendFor) return backend
        backend?.release()
        backend = null
        backendFor = key
        backend = runCatching { BlurBackendSelector(choice.factories).selectAndInitialize(c, capabilities) }
            .onFailure { e ->
                mainHandler.post { if (!isDestroyed) label.text = "${choice.label.removePrefix("Backend: ")} can't render ${c.algorithm}: ${e.message}" }
            }
            .getOrNull()
        return backend
    }

    override fun onDestroy() {
        super.onDestroy()
        workerHandler.post { backend?.release() }
        worker.quitSafely()
    }

    private fun createSourceImage(w: Int, h: Int): Bitmap {
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        paint.shader = LinearGradient(0f, 0f, w.toFloat(), h.toFloat(),
            intArrayOf(0xFF1E88E5.toInt(), 0xFF8E24AA.toInt(), 0xFFFB8C00.toInt()), null, Shader.TileMode.CLAMP)
        canvas.drawRect(0f, 0f, w.toFloat(), h.toFloat(), paint)
        paint.shader = null
        val cell = 60f
        var y = 0f
        var row = 0
        while (y < h) {
            var x = if (row % 2 == 0) 0f else cell
            while (x < w) {
                paint.color = 0x55FFFFFF
                canvas.drawRect(x, y, x + cell, y + cell, paint)
                x += cell * 2
            }
            y += cell; row++
        }
        paint.color = Color.WHITE
        paint.textSize = 96f
        paint.isFakeBoldText = true
        canvas.drawText("Blur Engine", 60f, h / 2f, paint)
        return bmp
    }
}
