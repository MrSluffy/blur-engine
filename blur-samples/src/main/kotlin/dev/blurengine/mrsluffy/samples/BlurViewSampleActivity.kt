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
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Choreographer
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.TextView
import dev.blurengine.mrsluffy.core.BlurAlgorithm
import dev.blurengine.mrsluffy.view.BlurView

/**
 * Background blur: a scrolling list behind a frosted top bar and a rounded bottom card.
 * Scroll to see the blur follow the content; the card's controls change radius and algorithm.
 */
class BlurViewSampleActivity : Activity() {
    private val dp get() = resources.displayMetrics.density
    private lateinit var status: TextView
    private lateinit var card: BlurView
    private lateinit var bar: BlurView

    private val statusUpdater = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            status.text = "%s, %.1f ms per frame on main thread".format(card.activeRenderer ?: "waiting", card.lastUpdateMillis)
            Choreographer.getInstance().postFrameCallbackDelayed(this, 250)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = FrameLayout(this)

        val list = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, (72 * dp).toInt(), 0, (260 * dp).toInt())
        }
        val palette = intArrayOf(0xFFE53935.toInt(), 0xFFFB8C00.toInt(), 0xFFFDD835.toInt(), 0xFF43A047.toInt(),
            0xFF1E88E5.toInt(), 0xFF8E24AA.toInt())
        for (i in 0 until 30) {
            val start = palette[i % palette.size]
            val end = palette[(i + 2) % palette.size]
            list.addView(TextView(this).apply {
                text = "Item ${i + 1}"
                textSize = 22f
                setTextColor(Color.WHITE)
                gravity = Gravity.CENTER_VERTICAL
                setPadding((24 * dp).toInt(), 0, 0, 0)
                background = GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, intArrayOf(start, end)).apply {
                    cornerRadius = 16 * dp
                }
            }, LinearLayout.LayoutParams(MATCH_PARENT, (96 * dp).toInt()).apply {
                setMargins((16 * dp).toInt(), (8 * dp).toInt(), (16 * dp).toInt(), (8 * dp).toInt())
            })
        }
        val scroll = ScrollView(this).apply { addView(list) }
        root.addView(scroll, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))

        // Level 1 API: a frosted top bar. Blurs only the list (sourceView), not the whole window.
        bar = BlurView(this).apply {
            sourceView = scroll
            blurRadius = 32 * dp
            setTint(Color.WHITE, 0.35f)
            addView(TextView(context).apply {
                text = "BlurView sample"
                textSize = 20f
                setTextColor(Color.BLACK)
                gravity = Gravity.CENTER_VERTICAL
                setPadding((20 * dp).toInt(), (24 * dp).toInt(), 0, 0)
            }, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        }
        root.addView(bar, FrameLayout.LayoutParams(MATCH_PARENT, (72 * dp).toInt(), Gravity.TOP))

        // Level 2 API: builder DSL.
        card = BlurView(this) {
            radius = 24 * dp
            overlay { color = Color.WHITE; alpha = 0.2f }
            shape { cornerRadius = 28 * dp }
            performance { downsample = 4 }
        }
        card.sourceView = scroll
        card.addView(controls(), FrameLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        root.addView(card, FrameLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT, Gravity.BOTTOM).apply {
            setMargins((16 * dp).toInt(), 0, (16 * dp).toInt(), (24 * dp).toInt())
        })

        setContentView(root)
    }

    private fun controls(): View = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        val pad = (20 * dp).toInt()
        setPadding(pad, pad, pad, pad)
        status = TextView(context).apply { setTextColor(Color.BLACK) }
        addView(TextView(context).apply { text = "Frosted card"; textSize = 20f; setTextColor(Color.BLACK) })
        addView(status)
        addView(SeekBar(context).apply {
            max = 100
            progress = (card.blurRadius / dp).toInt()
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar?, value: Int, fromUser: Boolean) {
                    card.blurRadius = value * dp
                    bar.blurRadius = value * dp
                }
                override fun onStartTrackingTouch(s: SeekBar?) = Unit
                override fun onStopTrackingTouch(s: SeekBar?) = Unit
            })
        })
        val algorithms = BlurAlgorithm.values()
        addView(Spinner(context).apply {
            adapter = ArrayAdapter(context, android.R.layout.simple_spinner_dropdown_item, algorithms.map { "Algorithm: ${it.name}" })
            onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                    card.algorithm = algorithms[pos]
                    bar.algorithm = algorithms[pos]
                }
                override fun onNothingSelected(p: AdapterView<*>?) = Unit
            }
        })
    }

    override fun onResume() {
        super.onResume()
        Choreographer.getInstance().postFrameCallback(statusUpdater)
    }

    override fun onPause() {
        Choreographer.getInstance().removeFrameCallback(statusUpdater)
        super.onPause()
    }
}
