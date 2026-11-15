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

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import dev.blurengine.mrsluffy.compose.blurSurface
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

private class Ticket(val city: String, val code: String, val time: String, val seat: String, val tint: Color)

private val tickets = listOf(
    Ticket("Kyoto", "KIX", "08:40", "Gate B12  ·  Seat 14A", Color.White),
    Ticket("Reykjavík", "KEF", "13:15", "Gate D4  ·  Seat 3C", Color(0xFFFF7EB3)),
    Ticket("Santorini", "JTR", "19:05", "Gate A7  ·  Seat 22F", Color(0xFF7EE8FA)),
)

/**
 * Multi-layer blur: three independent glass tickets. Each one blurs the tickets drawn before it,
 * so overlapping cards stack like real glass. Tap to fan them out or stack them; drag any card.
 */
@Composable
fun LayersScreen(autoplay: Boolean) {
    var fanned by remember { mutableStateOf(false) }
    val fan = animateFloatAsState(
        if (fanned) 1f else 0f,
        spring(dampingRatio = 0.72f, stiffness = Spring.StiffnessLow),
        label = "fan",
    )
    val drags = remember { mutableStateListOf(Offset.Zero, Offset.Zero, Offset.Zero) }
    if (autoplay) {
        LaunchedEffect(Unit) {
            delay(900)
            while (true) {
                fanned = !fanned
                delay(2_400)
            }
        }
    }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        Scenery()
        ScreenTitle("Multi-layer Blur", "Independent glass surfaces, stacked")
        val cardWidth = maxWidth - 48.dp
        tickets.forEachIndexed { i, ticket ->
            TicketCard(
                ticket = ticket,
                index = i,
                fan = fan,
                drag = { drags[i] },
                onDrag = { drags[i] = drags[i] + it },
                onTap = { fanned = !fanned },
                modifier = Modifier.size(cardWidth, 170.dp),
            )
        }
    }
}

@Composable
private fun TicketCard(
    ticket: Ticket,
    index: Int,
    fan: State<Float>,
    drag: () -> Offset,
    onDrag: (Offset) -> Unit,
    onTap: () -> Unit,
    modifier: Modifier,
) {
    Column(
        modifier
            .offset {
                // Stacked: a tight cascade with alternating sideways offsets. Fanned: one below another.
                val f = fan.value
                val side = if (index == 1) 1f else -1f
                val x = 24.dp.toPx() + side * 14.dp.toPx() * (1f - f)
                val y = lerp(250.dp, 200.dp, f).toPx() + index * lerp(78.dp, 192.dp, f).toPx()
                val d = drag()
                IntOffset((x + d.x).roundToInt(), (y + d.y).roundToInt())
            }
            .blurSurface {
                radius = 22.dp
                tint(ticket.tint.copy(alpha = 0.16f))
                saturation = 1.3f
                cornerRadius(28.dp)
                glass(refraction = 12.dp, edgeWidth = 18.dp, dispersion = 0.35f, highlight = 0.8f, gloss = 0.15f)
            }
            .pointerInput(Unit) {
                detectDragGestures { change, amount ->
                    change.consume()
                    onDrag(amount)
                }
            }
            .clickable(remember { MutableInteractionSource() }, indication = null, onClick = onTap)
            .padding(22.dp),
        verticalArrangement = Arrangement.SpaceBetween,
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.Top) {
            Column {
                BasicText("BOARDING PASS", style = text(11, alpha = 0.7f, weight = FontWeight.SemiBold))
                BasicText(ticket.city, style = text(28, weight = FontWeight.Bold))
            }
            Column(horizontalAlignment = Alignment.End) {
                BasicText(ticket.code, style = text(28, weight = FontWeight.Light))
                BasicText(ticket.time, style = text(14, alpha = 0.8f))
            }
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(Color.White.copy(alpha = 0.3f)))
        BasicText(ticket.seat, style = text(14, alpha = 0.85f))
    }
}

private fun lerp(a: Dp, b: Dp, f: Float): Dp = a + (b - a) * f
