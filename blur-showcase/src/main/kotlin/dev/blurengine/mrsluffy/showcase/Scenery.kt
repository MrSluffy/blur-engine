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

package dev.blurengine.mrsluffy.showcase

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

/**
 * An animated dusk landscape: drifting color blobs over a sky gradient, stars, a sun and two
 * mountain ridges. The ridges and stars give refraction sharp edges to bend. The animation is read
 * only while drawing, so it redraws every frame without recomposing.
 */
@Composable
fun Scenery(modifier: Modifier = Modifier) {
    val phase = rememberInfiniteTransition(label = "scenery").animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(14_000, easing = LinearEasing)),
        label = "phase",
    )
    Spacer(
        modifier.fillMaxSize().drawWithCache {
            val w = size.width
            val h = size.height
            val sky = Brush.verticalGradient(
                0f to Color(0xFF0B1026), 0.45f to Color(0xFF3B1E6E), 0.72f to Color(0xFFB0476E), 1f to Color(0xFFF59E5B),
            )
            val far = ridge(w, h, floatArrayOf(0.70f, 0.61f, 0.68f, 0.56f, 0.66f, 0.59f))
            val near = ridge(w, h, floatArrayOf(0.82f, 0.74f, 0.80f, 0.71f, 0.79f, 0.76f))
            val random = Random(7)
            val stars = List(70) { Offset(random.nextFloat() * w, random.nextFloat() * h * 0.5f) to 1f + random.nextFloat() * 2.5f }
            onDrawBehind {
                drawRect(sky)
                for ((at, r) in stars) drawCircle(Color.White.copy(alpha = 0.75f), r, at)
                val p = phase.value * 2f * PI.toFloat()
                blob(Color(0xFFFF4D8D), Offset(w * (0.25f + 0.14f * sin(p)), h * (0.30f + 0.06f * cos(p))), w * 0.50f)
                blob(Color(0xFF3DD6F5), Offset(w * (0.78f + 0.10f * cos(p)), h * (0.20f + 0.08f * sin(2 * p))), w * 0.45f)
                blob(Color(0xFFFFC84D), Offset(w * (0.55f + 0.16f * sin(p + 2f)), h * (0.50f + 0.04f * cos(p))), w * 0.38f)
                drawCircle(Color(0xFFFFE3A3), w * 0.11f, Offset(w * 0.68f, h * 0.58f))
                drawPath(far, Color(0xFF2A1745))
                drawPath(near, Color(0xFF140B26))
            }
        },
    )
}

/** A filled mountain ridge through [heights] (fractions of [h]) spread evenly across [w]. */
private fun ridge(w: Float, h: Float, heights: FloatArray) = Path().apply {
    moveTo(0f, h * heights[0])
    for (i in 1 until heights.size) lineTo(w * i / (heights.size - 1), h * heights[i])
    lineTo(w, h)
    lineTo(0f, h)
    close()
}

private fun DrawScope.blob(color: Color, center: Offset, radius: Float) {
    drawCircle(Brush.radialGradient(listOf(color.copy(alpha = 0.75f), color.copy(alpha = 0f)), center, radius), radius, center)
}

/** Screen title and subtitle, below the status bar. */
@Composable
fun ScreenTitle(title: String, subtitle: String, modifier: Modifier = Modifier) {
    Column(modifier.statusBarsPadding().padding(start = 24.dp, end = 24.dp, top = 20.dp)) {
        BasicText(title, style = TextStyle(Color.White, 34.sp, FontWeight.Bold))
        BasicText(subtitle, Modifier.padding(top = 4.dp), style = TextStyle(Color.White.copy(alpha = 0.72f), 16.sp))
    }
}

fun text(size: Int, alpha: Float = 1f, weight: FontWeight = FontWeight.Normal) =
    TextStyle(Color.White.copy(alpha = alpha), size.sp, weight)
