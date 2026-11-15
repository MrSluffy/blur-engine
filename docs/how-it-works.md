# How it works

## Each frame

1. **Capture.** Before the frame is drawn, the part of the source (the window, or `sourceView`) under the surface is drawn into a bitmap at 1/`downsample` size, with a software canvas. The capture includes up to 1.5 × radius (3 sigma) of margin on each side, limited to the source's bounds, so content just outside the surface bleeds into its edges as it would in a real blur. A surface never captures itself or the surfaces drawn after it, so it never blurs its own output.
2. **Blur.**
    - API 31+ with `AUTO` or `GAUSSIAN`: a `RenderNode` with a platform `RenderEffect` blurs the capture on the GPU. There is no readback, and almost no main-thread cost.
    - Otherwise: the OpenGL ES 2.0 backend blurs it on its own GL thread. If GLES fails, or the algorithm is `BOX`, the CPU backend takes over.
3. **Effects.** Progressive blur masks the result with a gradient. Liquid glass chains a refraction shader after the blur on API 33+, then draws its lighting with Canvas.
4. **Draw.** The result is recorded into a `RenderNode` during the frame that's already being drawn. Updating the blur never schedules extra frames.

## Render paths

| Path | When | Main-thread cost |
| --- | --- | --- |
| `render-effect` | API 31+, `AUTO` or `GAUSSIAN` | Capture only, about 0.3 ms |
| `gles` | API 29 and 30, or `DUAL_KAWASE` | Capture plus a GPU readback, 4 to 7 ms on emulators |
| `software` | GLES unavailable or failed, or `BOX` | 9 ms or more |

`BlurView.activeRenderer` reports the path in use. Leave `algorithm` on `AUTO`: it picks the cheapest path, and every path targets the same blur strength (sigma = radius / 2). Dual Kawase's iterations and offsets come from a measured table, so it matches the Gaussian within 12% for radii 3 to 100.

## Bitmap backends

The backends also blur bitmaps directly, without a view:

| Backend | API | Algorithms | Notes |
| --- | --- | --- | --- |
| `render-effect` | 31+ | Gaussian, AUTO | `RenderEffect` drawn offscreen through `HardwareRenderer` and `ImageReader` |
| `gles` | 29+ | Dual Kawase, Gaussian | Own EGL context on its own thread; rebuilds itself once if the context is lost |
| `software` | 29+ | All | CPU implementation, also the reference the GPU outputs are tested against |

```kotlin
val backend = BlurBackendSelector().selectAndInitialize(config, DeviceCapabilitiesDetector.detect(context))
backend.render(BitmapImage(source), BitmapImage(output))   // off the main thread
backend.release()
```

`selectAndInitialize` falls back to the next backend when one fails to start.

## Custom surfaces: `BlurSurfaceRenderer`

`BlurView` and `blurSurface` are thin wrappers around `dev.blurengine.mrsluffy.view.BlurSurfaceRenderer`. To blur behind a custom view:

1. Implement `BlurTarget`: size, position in the window, visibility, and invalidate.
2. Create `BlurSurfaceRenderer(hostView, target)` and set its `config`.
3. Call `attach()` when attached to a window and `detach()` when detached.
4. Call `draw(canvas)` from your draw method.
5. Skip drawing while `CaptureGuard.shouldSkip(canvas)` returns true, so the surface doesn't capture itself.

## Modules

`blur-view` and `blur-compose` are the only artifacts most apps need. They bring in the rest:

| Module | Contents |
| --- | --- |
| `blur-core` (JVM) | `BlurConfig`, `BlurAlgorithm`, `BlurQuality`, `BlurGradient`, `GlassStyle`, backend contracts |
| `blur-algorithms` (JVM) | Gaussian, calibrated Dual Kawase, box and fast-Gaussian kernels; resampling; color adjustment |
| `blur-animation` (JVM) | `BlurAnimator`, tweens, springs, interpolators |
| `blur-platform` | RenderEffect, GLES and software backends; `BitmapImage`; refraction shader |
| `blur-render` | `BlurBackendSelector`, Choreographer frame clock |
| `blur-capture` | `ViewCapture`, `CaptureGuard` |
| `blur-view` | `BlurView`, `BlurSurfaceRenderer`, `BlurTarget` |
| `blur-compose` | `Modifier.blurSurface`, `BlurContainer` |
