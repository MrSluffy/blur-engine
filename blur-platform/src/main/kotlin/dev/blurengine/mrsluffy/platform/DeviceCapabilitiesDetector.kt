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

package dev.blurengine.mrsluffy.platform

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import dev.blurengine.mrsluffy.core.DeviceCapabilities

/** Reads [DeviceCapabilities] from the running device. */
public object DeviceCapabilitiesDetector {
    /** OpenGL ES 2.0, which is all [GlesBlurBackend] needs. */
    private const val GLES_2 = 0x00020000

    public fun detect(context: Context): DeviceCapabilities {
        val am = context.applicationContext.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        val glEsVersion = am?.deviceConfigurationInfo?.reqGlEsVersion ?: 0
        return DeviceCapabilities(
            sdkInt = Build.VERSION.SDK_INT,
            supportsRenderEffect = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S,
            supportsGles = glEsVersion >= GLES_2,
        )
    }
}
