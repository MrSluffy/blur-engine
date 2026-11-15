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

package dev.blurengine.mrsluffy.compose

import androidx.test.platform.app.InstrumentationRegistry
import org.junit.rules.ExternalResource

/**
 * Turns off window and transition animations while tests run, so screenshots never catch an
 * activity still animating in, and sets animator_duration_scale to 1. The animations under test
 * follow that scale, and CI emulators often set it to 0 (for example the emulator runner's
 * `disable-animations`), which would make every animation jump to its end.
 * All three are restored afterwards.
 */
class NoWindowAnimations : ExternalResource() {
    private val scales = mapOf(
        "window_animation_scale" to "0",
        "transition_animation_scale" to "0",
        "animator_duration_scale" to "1",
    )
    private val saved = HashMap<String, String>()

    private fun shell(cmd: String): String {
        val pfd = InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(cmd)
        return android.os.ParcelFileDescriptor.AutoCloseInputStream(pfd).bufferedReader().use { it.readText().trim() }
    }

    override fun before() {
        // A notification shade left open covers the app and breaks every screenshot.
        shell("cmd statusbar collapse")
        for ((k, value) in scales) {
            saved[k] = shell("settings get global $k").takeIf { it.isNotEmpty() && it != "null" } ?: "1"
            shell("settings put global $k $value")
        }
    }

    override fun after() {
        for (k in scales.keys) shell("settings put global $k ${saved[k] ?: "1"}")
    }
}
