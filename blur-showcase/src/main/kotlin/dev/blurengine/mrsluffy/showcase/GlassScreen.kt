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
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import dev.blurengine.mrsluffy.compose.blurSurface
import kotlin.math.PI
import kotlin.math.roundToInt
import kotlin.math.sin

/** Liquid glass widgets over the animated landscape, plus a clear glass lens you can drag. */
@Composable
fun GlassScreen(autoplay: Boolean) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        Scenery()
        Column {
            ScreenTitle("Liquid Glass", "Refraction, highlights and depth")
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                WeatherCard()
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    MusicCard(Modifier.weight(1f))
                    BatteryCard(Modifier.weight(1f))
                }
            }
        }
        val density = LocalDensity.current
        val lensW = 200.dp
        val lensH = 120.dp
        val home = with(density) {
            Offset((maxWidth - lensW).toPx() / 2f, maxHeight.toPx() * 0.70f)
        }
        val spread = with(density) { Offset(maxWidth.toPx() * 0.26f, maxHeight.toPx() * 0.05f) }
        Lens(home, spread, autoplay, Modifier.size(lensW, lensH))
    }
}

/**
 * A nearly clear glass pill with strong refraction. With [autoplay] it drifts in a figure eight
 * around [home]; dragging it takes over.
 */
@Composable
private fun Lens(home: Offset, spread: Offset, autoplay: Boolean, modifier: Modifier) {
    val drift = rememberInfiniteTransition(label = "lens").animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(7_000, easing = LinearEasing)),
        label = "drift",
    )
    var dragged by remember { mutableStateOf<Offset?>(null) }
    val position = remember { floatArrayOf(0f, 0f) }
    Box(
        modifier
            .offset {
                // Read in the layout phase: the lens moves every frame without recomposing.
                val p = dragged ?: if (autoplay) {
                    val a = drift.value * 2f * PI.toFloat()
                    Offset(home.x + spread.x * sin(a), home.y + spread.y * sin(2f * a))
                } else {
                    home
                }
                position[0] = p.x; position[1] = p.y
                IntOffset(p.x.roundToInt(), p.y.roundToInt())
            }
            .blurSurface {
                radius = 2.dp
                downsample = 2
                tint(Color.White.copy(alpha = 0.04f))
                cornerRadius(60.dp)
                glass(refraction = 26.dp, edgeWidth = 40.dp, dispersion = 0.6f, highlight = 0.9f, gloss = 0.18f)
            }
            .pointerInput(Unit) {
                detectDragGestures(
                    onDragStart = { dragged = Offset(position[0], position[1]) },
                    onDrag = { change, amount ->
                        change.consume()
                        dragged = (dragged ?: Offset(position[0], position[1])) + amount
                    },
                )
            },
        contentAlignment = Alignment.Center,
    ) {
        BasicText("drag me", style = text(13, alpha = 0.85f, weight = FontWeight.Medium))
    }
}

/** The shared material for the widgets. */
private fun Modifier.glassCard(corner: Int = 30) = blurSurface {
    radius = 28.dp
    tint(Color.White.copy(alpha = 0.14f))
    saturation = 1.3f
    cornerRadius(corner.dp)
    glass(refraction = 14.dp, edgeWidth = 20.dp, dispersion = 0.35f, highlight = 0.8f, gloss = 0.16f)
}

@Composable
private fun WeatherCard() {
    Column(Modifier.fillMaxWidth().glassCard(34).padding(24.dp)) {
        BasicText("Lisbon", style = text(18, weight = FontWeight.Medium))
        BasicText("24°", style = text(76, weight = FontWeight.Light))
        BasicText("Sunny  ·  H 27°  L 18°", style = text(15, alpha = 0.8f))
        Spacer(Modifier.height(18.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            val hours = listOf("Now" to 24, "15" to 25, "16" to 26, "17" to 25, "18" to 23, "19" to 21)
            for ((hour, temp) in hours) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    BasicText(hour, style = text(13, alpha = 0.75f))
                    Box(Modifier.size(18.dp).clip(CircleShape).background(Color(0xFFFFD36E)))
                    BasicText("$temp°", style = text(15, weight = FontWeight.Medium))
                }
            }
        }
    }
}

@Composable
private fun MusicCard(modifier: Modifier) {
    Column(modifier.aspectRatio(1f).glassCard().padding(18.dp), verticalArrangement = Arrangement.SpaceBetween) {
        Box(Modifier.size(44.dp).clip(RoundedCornerShape(12.dp)).background(Color(0xFFFF4D8D)))
        Column {
            BasicText("Midnight City", style = text(16, weight = FontWeight.SemiBold))
            BasicText("M83", style = text(13, alpha = 0.7f))
            Spacer(Modifier.height(10.dp))
            Box(Modifier.fillMaxWidth().height(4.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.25f))) {
                Box(Modifier.fillMaxWidth(0.4f).height(4.dp).clip(CircleShape).background(Color.White))
            }
        }
    }
}

@Composable
private fun BatteryCard(modifier: Modifier) {
    Column(modifier.aspectRatio(1f).glassCard().padding(18.dp), verticalArrangement = Arrangement.SpaceBetween) {
        BasicText("Battery", style = text(14, alpha = 0.75f))
        BasicText("82%", style = text(40, weight = FontWeight.Light))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            repeat(5) { i ->
                Box(
                    Modifier.width(18.dp).height(30.dp).clip(RoundedCornerShape(5.dp))
                        .background(if (i < 4) Color(0xFF7CF5A8) else Color.White.copy(alpha = 0.25f)),
                )
            }
        }
    }
}
