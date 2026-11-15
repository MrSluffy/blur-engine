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

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.blurengine.mrsluffy.compose.blurSurface

/**
 * Showcase for Blur Engine: liquid glass, progressive (gradient) blur, stacked (multi-layer)
 * glass and blur animation, switched with a liquid glass tab bar.
 *
 * Intent extras: `screen` = glass | gradient | layers | motion opens that screen, and `autoplay` = true plays
 * scripted motion so recordings are smooth and repeatable (see scripts/record-demo.ps1).
 */
class ShowcaseActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        val transparent = android.graphics.Color.TRANSPARENT
        enableEdgeToEdge(SystemBarStyle.dark(transparent), SystemBarStyle.dark(transparent))
        super.onCreate(savedInstanceState)
        val start = Tab.entries.firstOrNull { it.key == intent.getStringExtra(EXTRA_SCREEN) } ?: Tab.Glass
        val autoplay = intent.getBooleanExtra(EXTRA_AUTOPLAY, false)
        setContent { Showcase(start, autoplay) }
    }

    companion object {
        const val EXTRA_SCREEN = "screen"
        const val EXTRA_AUTOPLAY = "autoplay"
    }
}

enum class Tab(val key: String, val label: String) {
    Glass("glass", "Glass"),
    Gradient("gradient", "Gradient"),
    Layers("layers", "Layers"),
    Motion("motion", "Motion"),
}

@Composable
private fun Showcase(start: Tab, autoplay: Boolean) {
    var tab by rememberSaveable { mutableStateOf(start) }
    Box(Modifier.fillMaxSize().background(Color(0xFF0B1026))) {
        when (tab) {
            Tab.Glass -> GlassScreen(autoplay)
            Tab.Gradient -> GradientScreen(autoplay)
            Tab.Layers -> LayersScreen(autoplay)
            Tab.Motion -> MotionScreen(autoplay)
        }
        TabBar(tab, { tab = it }, Modifier.align(Alignment.BottomCenter))
    }
}

/** A floating liquid glass bar. It is drawn last, so every other surface shows through its blur. */
@Composable
private fun TabBar(selected: Tab, onSelect: (Tab) -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier.navigationBarsPadding().padding(horizontal = 28.dp, vertical = 16.dp).fillMaxWidth().height(64.dp)
            .blurSurface {
                radius = 24.dp
                tint(Color.White.copy(alpha = 0.10f))
                saturation = 1.4f
                cornerRadius(32.dp)
                glass(refraction = 8.dp, edgeWidth = 14.dp, highlight = 0.75f, gloss = 0.14f)
            }
            .padding(6.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        for (t in Tab.entries) {
            val active = t == selected
            Box(
                Modifier.weight(1f).fillMaxHeight().clip(RoundedCornerShape(26.dp))
                    .background(if (active) Color.White.copy(alpha = 0.22f) else Color.Transparent)
                    .clickable(remember { MutableInteractionSource() }, indication = null) { onSelect(t) },
                contentAlignment = Alignment.Center,
            ) {
                BasicText(
                    t.label,
                    style = TextStyle(
                        color = Color.White.copy(alpha = if (active) 1f else 0.7f),
                        fontSize = 15.sp,
                        fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
                    ),
                )
            }
        }
    }
}
