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

/**
 * One long-lived thread that runs every GL call for one backend.
 *
 * EGL lets a context move between threads, but drivers handle that badly. On the Android
 * emulator, for example:
 * - rendering on a thread other than the one that created the context failed with
 *   GL_INVALID_VALUE;
 * - the context's objects broke after the creating thread exited;
 * - each new thread paid about 300 ms of one-time GL setup.
 *
 * Keeping all GL work on one thread that lives as long as the backend avoids all three.
 *
 * [run] blocks the caller until the task is done, so callers keep a synchronous API. The handoff
 * is a plain monitor wait and allocates nothing, so per-frame rendering stays allocation-free as
 * long as the caller reuses its [Runnable].
 */
internal class GlThread(name: String) {
    private val lock = Object()
    private var task: Runnable? = null
    private var failure: Throwable? = null
    private var done = false
    private var quitting = false
    private val thread = Thread({ loop() }, name).apply {
        isDaemon = true
        start()
    }

    private fun loop() {
        while (true) {
            val current: Runnable
            synchronized(lock) {
                while (task == null && !quitting) lock.wait()
                current = task ?: return // quitting with nothing queued
            }
            var error: Throwable? = null
            try {
                current.run()
            } catch (e: Throwable) {
                error = e
            }
            synchronized(lock) {
                failure = error
                task = null
                done = true
                lock.notifyAll()
            }
        }
    }

    /** Runs [task] on the GL thread and waits for it. Exceptions from [task] are rethrown as is. */
    fun run(task: Runnable) {
        if (Thread.currentThread() === thread) {
            task.run()
            return
        }
        try {
            synchronized(lock) {
                check(!quitting) { "GL thread has quit" }
                while (this.task != null) lock.wait() // another caller's task is still running
                this.task = task
                done = false
                lock.notifyAll()
                while (!done) lock.wait()
                done = false
                val error = failure
                failure = null
                if (error != null) throw error
            }
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            throw IllegalStateException("interrupted while waiting for the GL thread", e)
        }
    }

    /** Runs [block] on the GL thread and returns its result. Allocates; use for rare calls only. */
    fun <T> call(block: () -> T): T {
        var result: Any? = null
        // Explicit Runnable, so this can't resolve to the stdlib `run {}`, which would execute on the caller.
        this.run(Runnable { result = block() })
        @Suppress("UNCHECKED_CAST")
        return result as T
    }

    /** Lets the thread finish its current task and exit. [run] must not be called afterwards. */
    fun quit() {
        synchronized(lock) {
            quitting = true
            lock.notifyAll()
        }
    }
}
