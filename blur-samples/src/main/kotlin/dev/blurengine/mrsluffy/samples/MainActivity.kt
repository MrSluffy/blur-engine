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
import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.LinearLayout

/** Sample launcher. */
class MainActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pad = (24 * resources.displayMetrics.density).toInt()
        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad * 2, pad, pad)
            addView(button("BlurView over scrolling content", BlurViewSampleActivity::class.java))
            addView(button("Animation and gesture-driven blur", AnimationSampleActivity::class.java))
            addView(button("Jetpack Compose: Modifier.blurSurface", ComposeSampleActivity::class.java))
            addView(button("Liquid glass, progressive and stacked blur", EffectsSampleActivity::class.java))
            addView(button("Backends: blur a bitmap", BasicSampleActivity::class.java))
        })
    }

    private fun button(label: String, target: Class<out Activity>) = Button(this).apply {
        text = label
        setOnClickListener { startActivity(Intent(this@MainActivity, target)) }
    }
}
