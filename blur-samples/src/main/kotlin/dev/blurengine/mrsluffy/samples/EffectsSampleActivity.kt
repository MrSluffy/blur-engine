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
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import dev.blurengine.mrsluffy.core.BlurGradient
import dev.blurengine.mrsluffy.core.GlassStyle
import dev.blurengine.mrsluffy.view.BlurView

/**
 * Liquid glass, progressive blur and stacked glass panels over a scrolling list. Scroll to see
 * the glass rim bend the rows under it (API 33+) and the header fade from blurred to clear.
 */
class EffectsSampleActivity : Activity() {
    private val dp get() = resources.displayMetrics.density

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = FrameLayout(this)

        val list = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, (40 * dp).toInt(), 0, (40 * dp).toInt())
        }
        val palette = intArrayOf(0xFFE53935.toInt(), 0xFFFB8C00.toInt(), 0xFFFDD835.toInt(), 0xFF43A047.toInt(),
            0xFF1E88E5.toInt(), 0xFF8E24AA.toInt())
        for (i in 0 until 30) {
            list.addView(TextView(this).apply {
                text = "Row ${i + 1}"
                textSize = 22f
                setTextColor(Color.WHITE)
                gravity = Gravity.CENTER_VERTICAL
                setPadding((24 * dp).toInt(), 0, 0, 0)
                background = GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT,
                    intArrayOf(palette[i % 6], palette[(i + 2) % 6])).apply { cornerRadius = 16 * dp }
            }, LinearLayout.LayoutParams(MATCH_PARENT, (88 * dp).toInt()).apply {
                setMargins((16 * dp).toInt(), (8 * dp).toInt(), (16 * dp).toInt(), (8 * dp).toInt())
            })
        }
        val scroll = ScrollView(this).apply { addView(list) }
        root.addView(scroll, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))

        // Progressive blur: fully blurred at the top, clear at the bottom of the header.
        val header = BlurView(this).apply {
            sourceView = scroll
            blurRadius = 40 * dp
            setTint(Color.WHITE, 0.3f)
            gradient = BlurGradient.BottomToTop
            addView(label("Progressive blur", 20f), FrameLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT, Gravity.START or Gravity.TOP).apply {
                setMargins((20 * dp).toInt(), (28 * dp).toInt(), 0, 0)
            })
        }
        root.addView(header, FrameLayout.LayoutParams(MATCH_PARENT, (140 * dp).toInt(), Gravity.TOP))

        // Two stacked liquid glass panels: the front one shows the back one through its blur.
        // With Gravity.CENTER a margin shifts the view by its full size, so these offset the panels
        // 60dp up-left and down-right of center: they overlap and stay on screen.
        val back = glassPanel(28f, "Back panel")
        root.addView(back, FrameLayout.LayoutParams((220 * dp).toInt(), (150 * dp).toInt(), Gravity.CENTER).apply {
            setMargins(0, 0, (60 * dp).toInt(), (60 * dp).toInt())
        })
        val front = glassPanel(28f, "Liquid glass")
        root.addView(front, FrameLayout.LayoutParams((240 * dp).toInt(), (160 * dp).toInt(), Gravity.CENTER).apply {
            setMargins((60 * dp).toInt(), (60 * dp).toInt(), 0, 0)
        })

        val note = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) "refraction on" else "refraction needs API 33; lighting only"
        root.addView(label(note, 14f).apply {
            setBackgroundColor(0x99000000.toInt())
            setTextColor(Color.WHITE)
            setPadding((12 * dp).toInt(), (6 * dp).toInt(), (12 * dp).toInt(), (6 * dp).toInt())
        }, FrameLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply {
            bottomMargin = (24 * dp).toInt()
        })

        setContentView(root)
    }

    private fun glassPanel(cornerDp: Float, title: String) = BlurView(this).apply {
        blurRadius = 18 * dp
        cornerRadius = cornerDp * dp
        setTint(Color.WHITE, 0.12f)
        glass = GlassStyle(refraction = 14 * dp, edgeWidth = 16 * dp, dispersion = 0.35f, highlight = 0.7f, gloss = 0.14f)
        addView(label(title, 18f), FrameLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT, Gravity.CENTER))
    }

    private fun label(text: String, size: Float) = TextView(this).apply {
        this.text = text
        textSize = size
        setTextColor(Color.BLACK)
    }
}
