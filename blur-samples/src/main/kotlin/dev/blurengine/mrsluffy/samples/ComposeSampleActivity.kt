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

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.blurengine.mrsluffy.compose.BlurContainer
import dev.blurengine.mrsluffy.compose.blurSurface

/** Compose: a frosted top bar and a bottom card over a LazyColumn; the card's radius animates with a spring. */
class ComposeSampleActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { Screen() }
    }
}

private val palette = listOf(
    Color(0xFFE53935), Color(0xFFFB8C00), Color(0xFFFDD835), Color(0xFF43A047), Color(0xFF1E88E5), Color(0xFF8E24AA),
)

@Composable
private fun Screen() {
    var strong by remember { mutableStateOf(false) }
    // Read only inside the blurSurface block, so each animation frame skips recomposition.
    val cardRadius = animateDpAsState(
        if (strong) 48.dp else 12.dp,
        spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessLow),
        label = "radius",
    )

    Box(Modifier.fillMaxSize().background(Color.White)) {
        LazyColumn(contentPadding = PaddingValues(top = 88.dp, bottom = 220.dp)) {
            items(30) { i ->
                Box(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp).height(96.dp)
                        .clip(RoundedCornerShape(16.dp))
                        .background(Brush.horizontalGradient(listOf(palette[i % 6], palette[(i + 2) % 6]))),
                    contentAlignment = Alignment.CenterStart,
                ) {
                    BasicText("Item ${i + 1}", Modifier.padding(start = 24.dp), style = TextStyle(Color.White, 22.sp))
                }
            }
        }

        // Top bar: BlurContainer with a white tint.
        BlurContainer(
            blur = { radius = 32.dp; tint(Color.White.copy(alpha = 0.4f)) },
            modifier = Modifier.fillMaxWidth().height(88.dp),
        ) {
            BasicText("Compose blurSurface", Modifier.align(Alignment.BottomStart).padding(20.dp),
                style = TextStyle(Color.Black, 20.sp))
        }

        // Bottom card: Modifier.blurSurface with an animated radius.
        Column(
            Modifier.align(Alignment.BottomCenter).padding(16.dp).fillMaxWidth()
                .blurSurface {
                    radius = cardRadius.value
                    tint(Color.White.copy(alpha = 0.2f))
                    cornerRadius(28.dp)
                }
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            BasicText("Frosted card", style = TextStyle(Color.Black, 22.sp))
            BasicText(if (strong) "Springing to 48 dp" else "Springing to 12 dp", style = TextStyle(Color.DarkGray, 14.sp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Chip(if (strong) "Less blur" else "More blur") { strong = !strong }
            }
        }
    }
}

@Composable
private fun Chip(label: String, onClick: () -> Unit) {
    Box(
        Modifier.clip(RoundedCornerShape(20.dp)).background(Color.Black.copy(alpha = 0.75f))
            .clickable(onClick = onClick).padding(horizontal = 18.dp, vertical = 10.dp),
    ) {
        BasicText(label, style = TextStyle(Color.White, 16.sp))
    }
}
