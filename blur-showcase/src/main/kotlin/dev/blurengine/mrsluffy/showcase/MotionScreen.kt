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

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import dev.blurengine.mrsluffy.animation.BlurAnimationBuilder
import dev.blurengine.mrsluffy.compose.blurSurface
import dev.blurengine.mrsluffy.view.BlurView
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

private enum class Step(val label: String, val code: String) {
    Show("show", "blurView.show(duration = 450L)"),
    Spring("spring", "blurView.animateBlur { radius = …; spring { dampingRatio = 0.35f } }"),
    Hide("hide", "blurView.hide(duration = 400L)"),
    Gesture("gesture", "blurView.setBlurProgress(sheetFraction)"),
}

private val apps = listOf(
    "Photos" to listOf(Color(0xFFFF8A65), Color(0xFFFFD54F)),
    "Maps" to listOf(Color(0xFF4DD0E1), Color(0xFF81C784)),
    "Music" to listOf(Color(0xFFFF4D8D), Color(0xFFB388FF)),
    "Mail" to listOf(Color(0xFF42A5F5), Color(0xFF7E57C2)),
    "Notes" to listOf(Color(0xFFFFF176), Color(0xFFFFB74D)),
    "Camera" to listOf(Color(0xFF90A4AE), Color(0xFF37474F)),
    "Weather" to listOf(Color(0xFF29B6F6), Color(0xFFE1F5FE)),
    "Clock" to listOf(Color(0xFF263238), Color(0xFF607D8B)),
    "Health" to listOf(Color(0xFFFF5252), Color(0xFFFF80AB)),
    "Wallet" to listOf(Color(0xFF212121), Color(0xFF5D4037)),
    "Books" to listOf(Color(0xFFFFA726), Color(0xFFEF6C00)),
    "Fitness" to listOf(Color(0xFF76FF03), Color(0xFF00C853)),
)

/**
 * blur-animation on a real BlurView (through AndroidView): show() and hide(), a bouncy spring
 * with animateBlur, and setBlurProgress() following a sheet as it is dragged. Animations are
 * interruptible, so tapping a chip mid-animation continues from what is on screen.
 */
@Composable
fun MotionScreen(autoplay: Boolean) {
    val context = LocalContext.current
    val density = LocalDensity.current
    val strong = with(density) { 40.dp.toPx() }
    val soft = with(density) { 8.dp.toPx() }
    val blur = remember {
        BlurView(context).apply {
            blurRadius = strong
            setTint(0xFF0B1026.toInt(), 0.3f)
            setBlurProgress(0f) // start clear; show() animates from here
        }
    }
    val sheet = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    var step by remember { mutableStateOf<Step?>(null) }

    fun spring(radius: Float) = blur.animateBlur {
        this.radius = radius
        spring { dampingRatio = 0.35f; stiffness = BlurAnimationBuilder.STIFFNESS_LOW }
    }
    suspend fun moveSheet(to: Float, millis: Int) =
        sheet.animateTo(to, tween(millis, easing = FastOutSlowInEasing)) { blur.setBlurProgress(value) }

    fun run(s: Step) {
        step = s
        when (s) {
            Step.Show -> blur.show(duration = 450L, radius = strong)
            Step.Spring -> spring(if (blur.displayedConfig.radius > (strong + soft) / 2) soft else strong)
            Step.Hide -> blur.hide(duration = 400L)
            Step.Gesture -> scope.launch { moveSheet(if (sheet.value > 0.5f) 0f else 1f, 700) }
        }
    }

    if (autoplay) {
        LaunchedEffect(Unit) {
            delay(500)
            while (true) {
                step = Step.Show; blur.show(duration = 450L, radius = strong); delay(1_300)
                step = Step.Spring; spring(soft); delay(1_300); spring(strong); delay(1_300)
                step = Step.Hide; blur.hide(duration = 400L); delay(1_000)
                step = Step.Gesture; moveSheet(1f, 1_100); delay(700); moveSheet(0f, 900); delay(700)
            }
        }
    }

    Box(Modifier.fillMaxSize()) {
        Scenery()
        HomeGrid()
        AndroidView({ blur }, Modifier.fillMaxSize())

        Column(Modifier.statusBarsPadding().padding(horizontal = 20.dp, vertical = 20.dp)) {
            BasicText("Blur Animation", style = text(34, weight = FontWeight.Bold))
            BasicText("Springs, show / hide and gestures", Modifier.padding(top = 4.dp), style = text(16, alpha = 0.72f))
            Spacer(Modifier.height(16.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for (s in Step.entries) StepChip(s.label, s == step) { run(s) }
            }
            Spacer(Modifier.height(10.dp))
            BasicText(
                step?.code ?: "Tap a chip, or drag the sheet",
                Modifier.clip(RoundedCornerShape(10.dp)).background(Color.Black.copy(alpha = 0.45f))
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                style = TextStyle(Color(0xFF9EF0C3), 12.sp, fontFamily = FontFamily.Monospace),
            )
        }

        ControlSheet(
            fraction = { sheet.value },
            onDrag = { delta ->
                scope.launch {
                    step = Step.Gesture
                    sheet.snapTo((sheet.value + delta).coerceIn(0f, 1f))
                    blur.setBlurProgress(sheet.value)
                }
            },
            onRelease = { scope.launch { moveSheet(if (sheet.value > 0.5f) 1f else 0f, 350) } },
            modifier = Modifier.align(Alignment.BottomCenter),
        )
    }
}

/** Sharp icons and labels, so the change in blur is easy to see. */
@Composable
private fun HomeGrid() {
    Column(
        Modifier.statusBarsPadding().padding(start = 24.dp, end = 24.dp, top = 210.dp),
        verticalArrangement = Arrangement.spacedBy(22.dp),
    ) {
        for (row in apps.chunked(4)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                for ((name, colors) in row) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Box(
                            Modifier.size(64.dp).clip(RoundedCornerShape(18.dp)).background(Brush.linearGradient(colors)),
                            contentAlignment = Alignment.Center,
                        ) {
                            BasicText(name.take(1), style = text(26, weight = FontWeight.Bold))
                        }
                        BasicText(name, Modifier.padding(top = 6.dp), style = text(12, alpha = 0.9f))
                    }
                }
            }
        }
    }
}

/** A glass sheet that slides up from behind the tab bar; its open fraction drives the blur. */
@Composable
private fun ControlSheet(fraction: () -> Float, onDrag: (Float) -> Unit, onRelease: () -> Unit, modifier: Modifier) {
    val height = 300.dp
    // Far enough to clear the bottom padding and the navigation bar, so no edge peeks out when closed.
    val travel = with(LocalDensity.current) { (height + 200.dp).toPx() }
    Column(
        modifier
            .navigationBarsPadding()
            .padding(start = 16.dp, end = 16.dp, bottom = 100.dp)
            .fillMaxWidth()
            .height(height)
            .offset { IntOffset(0, ((1f - fraction()) * travel).roundToInt()) }
            .blurSurface {
                radius = 30.dp
                tint(Color.White.copy(alpha = 0.14f))
                saturation = 1.3f
                cornerRadius(34.dp)
                glass(refraction = 12.dp, edgeWidth = 18.dp, highlight = 0.8f, gloss = 0.15f)
            }
            .pointerInput(Unit) {
                detectVerticalDragGestures(onDragEnd = onRelease, onDragCancel = onRelease) { change, dy ->
                    change.consume()
                    onDrag(-dy / travel)
                }
            }
            .padding(22.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        Box(Modifier.align(Alignment.CenterHorizontally).width(40.dp).height(5.dp).clip(CircleShape)
            .background(Color.White.copy(alpha = 0.5f)))
        BasicText("Control Center", style = text(22, weight = FontWeight.SemiBold))
        Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            for ((label, on) in listOf("Wi-Fi" to true, "Focus" to true, "Bluetooth" to false)) {
                Column(
                    Modifier.weight(1f).aspectRatio(1f).clip(RoundedCornerShape(22.dp))
                        .background(if (on) Color.White.copy(alpha = 0.85f) else Color.White.copy(alpha = 0.18f))
                        .padding(14.dp),
                    verticalArrangement = Arrangement.Bottom,
                ) {
                    BasicText(label, style = TextStyle(if (on) Color(0xFF0B1026) else Color.White, 14.sp, FontWeight.Medium))
                }
            }
        }
        Box(Modifier.fillMaxWidth().height(12.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.2f))) {
            Box(Modifier.fillMaxWidth(0.65f).height(12.dp).clip(CircleShape).background(Color.White))
        }
    }
}

@Composable
private fun StepChip(label: String, active: Boolean, onClick: () -> Unit) {
    BasicText(
        label,
        Modifier.clip(CircleShape)
            .background(if (active) Color.White else Color.White.copy(alpha = 0.16f))
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp),
        style = TextStyle(if (active) Color(0xFF0B1026) else Color.White, 14.sp, FontWeight.Medium),
    )
}
