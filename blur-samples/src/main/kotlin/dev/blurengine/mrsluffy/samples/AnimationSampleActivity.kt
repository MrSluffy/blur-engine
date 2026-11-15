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

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.app.Activity
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.MotionEvent
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.animation.DecelerateInterpolator
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import dev.blurengine.mrsluffy.view.BlurView

/**
 * Animation: a frosted card with show / hide / spring buttons (tap quickly to see
 * interruptions), and a bottom sheet whose drag position drives the background blur.
 */
class AnimationSampleActivity : Activity() {
    private val dp get() = resources.displayMetrics.density
    private lateinit var card: BlurView
    private lateinit var backdrop: BlurView
    private lateinit var sheet: LinearLayout
    private var sheetTravel = 0f

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = FrameLayout(this)

        val content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val palette = intArrayOf(0xFFE53935.toInt(), 0xFFFB8C00.toInt(), 0xFF43A047.toInt(), 0xFF1E88E5.toInt(), 0xFF8E24AA.toInt())
        for (i in 0 until 8) {
            content.addView(TextView(this).apply {
                text = "Background ${i + 1}"
                textSize = 26f
                setTextColor(Color.WHITE)
                gravity = Gravity.CENTER
                background = GradientDrawable(GradientDrawable.Orientation.TL_BR,
                    intArrayOf(palette[i % palette.size], palette[(i + 2) % palette.size]))
            }, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))
        }
        root.addView(content, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))

        // Full-screen backdrop: blurred by the sheet's position, from 0 (collapsed) to 1 (expanded).
        backdrop = BlurView(this).apply {
            sourceView = content
            blurRadius = 40 * dp
            setTint(Color.BLACK, 0.25f)
            setBlurProgress(0f)
        }
        root.addView(backdrop, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))

        card = BlurView(this) {
            radius = 24 * dp
            overlay { color = Color.WHITE; alpha = 0.25f }
            shape { cornerRadius = 24 * dp }
        }.apply {
            sourceView = content
            addView(TextView(context).apply {
                text = "Frosted card"
                textSize = 22f
                setTextColor(Color.BLACK)
                gravity = Gravity.CENTER
            }, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        }
        root.addView(card, FrameLayout.LayoutParams(MATCH_PARENT, (140 * dp).toInt(), Gravity.TOP).apply {
            setMargins((24 * dp).toInt(), (64 * dp).toInt(), (24 * dp).toInt(), 0)
        })

        val buttons = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(button("Show") { card.show(duration = 350L) })
            addView(button("Hide") { card.hide(duration = 250L) })
            addView(button("Spring") {
                val target = if (card.config.radius > 30 * dp) 12 * dp else 48 * dp
                card.animateBlur { radius = target; spring { dampingRatio = 0.4f; stiffness = 300f } }
            })
            addView(button("Tint") {
                val warm = card.config.tintColor == Color.WHITE
                card.animateBlur { tintColor = if (warm) 0xFFFFB74D.toInt() else Color.WHITE; duration = 400L }
            })
        }
        root.addView(buttons, FrameLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT, Gravity.TOP).apply {
            topMargin = (216 * dp).toInt()
            leftMargin = (16 * dp).toInt()
        })

        sheet = buildSheet()
        root.addView(sheet, FrameLayout.LayoutParams(MATCH_PARENT, (420 * dp).toInt(), Gravity.BOTTOM))
        setContentView(root)

        sheet.post {
            sheetTravel = sheet.height - 96 * dp // keep 96 dp peeking when collapsed
            sheet.translationY = sheetTravel
        }
    }

    private fun button(label: String, onClick: () -> Unit) = Button(this).apply {
        text = label
        setOnClickListener { onClick() }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun buildSheet() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = GradientDrawable().apply {
            setColor(Color.WHITE)
            cornerRadii = floatArrayOf(28 * dp, 28 * dp, 28 * dp, 28 * dp, 0f, 0f, 0f, 0f)
        }
        elevation = 8 * dp
        val pad = (24 * dp).toInt()
        setPadding(pad, pad, pad, pad)
        addView(TextView(context).apply {
            text = "Drag me up"
            textSize = 20f
            setTextColor(Color.BLACK)
        })
        addView(TextView(context).apply {
            text = "The background blur follows the sheet with setBlurProgress()."
            setTextColor(Color.DKGRAY)
        })

        var downY = 0f
        var startTranslation = 0f
        setOnTouchListener { v, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> { downY = e.rawY; startTranslation = v.translationY; true }
                MotionEvent.ACTION_MOVE -> {
                    moveSheet((startTranslation + e.rawY - downY).coerceIn(0f, sheetTravel))
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    val expand = v.translationY < sheetTravel / 2
                    settle(if (expand) 0f else sheetTravel)
                    true
                }
                else -> false
            }
        }
    }

    private fun moveSheet(translation: Float) {
        sheet.translationY = translation
        backdrop.setBlurProgress(if (sheetTravel > 0f) 1f - translation / sheetTravel else 0f)
    }

    private fun settle(target: Float) {
        ValueAnimator.ofFloat(sheet.translationY, target).apply {
            duration = 250L
            interpolator = DecelerateInterpolator()
            addUpdateListener { moveSheet(it.animatedValue as Float) }
            start()
        }
    }
}
