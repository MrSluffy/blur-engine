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

package dev.blurengine.mrsluffy.platform.gles

import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLSurface
import dev.blurengine.mrsluffy.core.BlurBackendException

/** Signals that the EGL context is gone and every GL object made in it is invalid. */
internal class ContextLostException : Exception()

/**
 * An offscreen OpenGL ES 2.0 context with a 1x1 pbuffer surface. All drawing goes to
 * framebuffer objects, so the surface size does not matter.
 *
 * The context is made current only for the duration of a render and released afterwards,
 * so the owning backend can be used from different threads (one at a time).
 */
internal class EglEnvironment {
    private var display: EGLDisplay = EGL14.EGL_NO_DISPLAY
    private var context: EGLContext = EGL14.EGL_NO_CONTEXT
    private var surface: EGLSurface = EGL14.EGL_NO_SURFACE

    /** @throws BlurBackendException if any EGL step fails. */
    fun create() {
        display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        if (display == EGL14.EGL_NO_DISPLAY) throw BlurBackendException("eglGetDisplay failed")
        val version = IntArray(2)
        if (!EGL14.eglInitialize(display, version, 0, version, 1)) throw eglFailure("eglInitialize")

        val attributes = intArrayOf(
            EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
            EGL14.EGL_SURFACE_TYPE, EGL14.EGL_PBUFFER_BIT,
            EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8, EGL14.EGL_ALPHA_SIZE, 8,
            EGL14.EGL_NONE,
        )
        val configs = arrayOfNulls<EGLConfig>(1)
        val count = IntArray(1)
        if (!EGL14.eglChooseConfig(display, attributes, 0, configs, 0, 1, count, 0) || count[0] == 0) {
            throw eglFailure("eglChooseConfig")
        }
        val config = configs[0]

        context = EGL14.eglCreateContext(
            display, config, EGL14.EGL_NO_CONTEXT,
            intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE), 0,
        )
        if (context == EGL14.EGL_NO_CONTEXT) throw eglFailure("eglCreateContext")

        surface = EGL14.eglCreatePbufferSurface(
            display, config, intArrayOf(EGL14.EGL_WIDTH, 1, EGL14.EGL_HEIGHT, 1, EGL14.EGL_NONE), 0,
        )
        if (surface == EGL14.EGL_NO_SURFACE) {
            val failure = eglFailure("eglCreatePbufferSurface")
            destroy()
            throw failure
        }
    }

    /**
     * @throws ContextLostException if the context was lost or destroyed.
     * @throws BlurBackendException for other EGL errors.
     */
    fun makeCurrent() {
        if (EGL14.eglMakeCurrent(display, surface, surface, context)) return
        val error = EGL14.eglGetError()
        if (error == EGL14.EGL_CONTEXT_LOST || error == EGL14.EGL_BAD_CONTEXT) throw ContextLostException()
        throw BlurBackendException("eglMakeCurrent failed: 0x${error.toString(16)}")
    }

    fun releaseCurrent() {
        if (display != EGL14.EGL_NO_DISPLAY) {
            EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
        }
    }

    /**
     * Destroys the surface and context. eglTerminate is deliberately not called: the default
     * display is shared with everything else in the process, including the UI renderer.
     */
    fun destroy() {
        if (display == EGL14.EGL_NO_DISPLAY) return
        releaseCurrent()
        if (surface != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(display, surface)
        if (context != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(display, context)
        surface = EGL14.EGL_NO_SURFACE
        context = EGL14.EGL_NO_CONTEXT
        display = EGL14.EGL_NO_DISPLAY
    }

    /** Test hook: destroys the context but keeps the stale handle, so the next [makeCurrent] fails like a real loss. */
    fun destroyContextForTest() {
        releaseCurrent()
        EGL14.eglDestroyContext(display, context)
    }

    private fun eglFailure(call: String) =
        BlurBackendException("$call failed: 0x${EGL14.eglGetError().toString(16)}")
}
