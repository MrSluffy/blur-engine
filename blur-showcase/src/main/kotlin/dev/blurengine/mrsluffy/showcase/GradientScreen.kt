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

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.blurengine.mrsluffy.compose.blurSurface
import dev.blurengine.mrsluffy.core.BlurGradient
import kotlinx.coroutines.delay

private class Place(val name: String, val detail: String, val from: Color, val to: Color)

private val places = listOf(
    Place("Lofoten", "Norway  ·  4 days", Color(0xFF1E3C72), Color(0xFF7FD3E8)),
    Place("Wadi Rum", "Jordan  ·  3 days", Color(0xFFB2452E), Color(0xFFF7C66B)),
    Place("Kyoto", "Japan  ·  6 days", Color(0xFF8E2DE2), Color(0xFFFF8FB1)),
    Place("Patagonia", "Chile  ·  9 days", Color(0xFF134E5E), Color(0xFF71B280)),
    Place("Santorini", "Greece  ·  5 days", Color(0xFF2B5876), Color(0xFF4FC3F7)),
    Place("Banff", "Canada  ·  7 days", Color(0xFF0F2027), Color(0xFF2C9E8F)),
    Place("Sahara", "Morocco  ·  4 days", Color(0xFFC94B4B), Color(0xFFFFB347)),
    Place("Iceland", "Ring road  ·  10 days", Color(0xFF3A1C71), Color(0xFFFFAF7B)),
)

/**
 * Progressive blur: a travel feed scrolls under a header that is fully blurred at the top and
 * clear at its bottom edge, and a footer that blurs toward the bottom of the screen.
 */
@Composable
fun GradientScreen(autoplay: Boolean) {
    val list = rememberLazyListState()
    if (autoplay) {
        LaunchedEffect(Unit) {
            delay(600)
            while (true) {
                list.animateScrollBy(1800f, tween(4_000, easing = FastOutSlowInEasing))
                delay(500)
                list.animateScrollBy(-1800f, tween(4_000, easing = FastOutSlowInEasing))
                delay(500)
            }
        }
    }
    Box(Modifier.fillMaxSize()) {
        LazyColumn(
            state = list,
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 150.dp, bottom = 140.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            items(places.size * 3) { i -> PlaceCard(places[i % places.size], i) }
        }

        // Header: blur 1 at the top fading to 0 at its bottom edge, so the feed slides out of focus.
        Box(
            Modifier.fillMaxWidth().height(190.dp).blurSurface {
                radius = 40.dp
                tint(Color(0xFF0B1026).copy(alpha = 0.35f))
                gradient = BlurGradient.BottomToTop
            },
        ) {
            ScreenTitle("Gradient Blur", "Directional, variable intensity")
        }

        // Footer: clear at its top edge, fully blurred at the bottom, under the tab bar.
        Box(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(170.dp).blurSurface {
                radius = 40.dp
                tint(Color(0xFF0B1026).copy(alpha = 0.3f))
                gradient = BlurGradient.TopToBottom
            },
        )
    }
}

/** A procedural "photo": a gradient sky, a sun and two ridges, with a caption. */
@Composable
private fun PlaceCard(place: Place, index: Int) {
    Box(
        Modifier.fillMaxWidth().height(230.dp).clip(RoundedCornerShape(28.dp)).drawWithCache {
            val w = size.width
            val h = size.height
            val sky = Brush.linearGradient(listOf(place.from, place.to), Offset.Zero, Offset(w * 0.3f, h))
            val shift = (index % 3) * 0.08f
            val far = Path().apply {
                moveTo(0f, h * (0.62f + shift)); lineTo(w * 0.3f, h * 0.45f); lineTo(w * 0.55f, h * (0.6f - shift))
                lineTo(w * 0.8f, h * 0.42f); lineTo(w, h * 0.58f); lineTo(w, h); lineTo(0f, h); close()
            }
            val near = Path().apply {
                moveTo(0f, h * 0.8f); lineTo(w * 0.4f, h * (0.66f + shift)); lineTo(w * 0.7f, h * 0.78f)
                lineTo(w, h * 0.7f); lineTo(w, h); lineTo(0f, h); close()
            }
            val sun = Offset(w * (0.2f + 0.25f * (index % 3)), h * 0.32f)
            onDrawBehind {
                drawRect(sky)
                drawCircle(Color.White.copy(alpha = 0.85f), h * 0.12f, sun)
                drawPath(far, Color.Black.copy(alpha = 0.28f))
                drawPath(near, Color.Black.copy(alpha = 0.5f))
            }
        },
        contentAlignment = Alignment.BottomStart,
    ) {
        Column(Modifier.padding(20.dp)) {
            BasicText(place.name, style = text(26, weight = FontWeight.Bold))
            BasicText(place.detail, style = text(14, alpha = 0.85f))
        }
    }
}
