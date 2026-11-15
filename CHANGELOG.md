# Changelog

All notable changes are listed here. Versions follow [Semantic Versioning](https://semver.org): from 1.0.0 on, breaking changes to the public API only happen in a new major version. The public API of each module is recorded in `<module>/api/*.api` and checked on every build.

## Unreleased

### Changed
- **Build:** publishing plugin `com.vanniktech.maven.publish` 0.29.0 → 0.34.0. Central Portal deployments are now named `io.github.mrsluffy-<version>` instead of a random ID. The publishing properties use the plugin's current names: `mavenCentralPublishing`, `mavenCentralAutomaticPublishing` and `signAllPublications`. The published artifacts are unchanged.
- **Releases:** only the repository owner can start the publish workflow.

## 1.0.0

The first stable release. The public API is now frozen under semantic versioning.

### Added
- **Liquid glass:** `GlassStyle` (`BlurView.glass`, Compose `glass()`). On every API level it adds a rim highlight, gloss and edge shading. On API 33+ it also refracts and disperses color at the rim, using an AGSL `RuntimeShader` chained after the blur, so it costs no main-thread time.
- **Progressive blur:** `BlurGradient` (`BlurView.gradient`, Compose `gradient`), with presets `TopToBottom`, `BottomToTop`, `LeftToRight` and `RightToLeft`.
- **Multi-layer blur:** blur surfaces drawn earlier now appear in the blur of the ones drawn after them, so stacked glass panels show each other. `ViewCapture.capture` gained an `owner` parameter for this.
- **`blur-showcase`:** a Compose app that demonstrates the three effects and the `BlurView` animation API. Its `autoplay` mode makes recordings repeatable.
- **`scripts/record-demo.ps1`:** records the README screenshots and GIFs from `blur-showcase` on an emulator or device.
- **License:** released under the Apache License 2.0. Every Kotlin, Gradle and XML source file carries the license header. `./gradlew spotlessApply` adds it to new files, and `spotlessCheck` (run in CI) fails if one is missing.
- **Publishing:** Maven Central publishing for the 8 library modules under `io.github.mrsluffy`, each with a sources jar, a Dokka javadoc jar and signed artifacts.
- **API docs:** generated with Dokka (`./gradlew dokkaHtmlMultiModule`).
- **API check:** public API snapshots (`api/*.api`), checked by `./gradlew check`.
- **CI:** GitHub Actions runs lint, unit tests, the API check and device tests on API 29 and 34. Pushing a `v*` tag runs the publish workflow.
- **Timing switch for device tests:** the instrumentation argument `timingAssertions=false` turns off frame-rate checks for emulators without GPU acceleration. CI sets it. Correctness checks always run.

### Fixed
- **GLES threading:** the GLES backend now runs every GL call on one thread that it owns. `BlurView` creates the backend on a background thread and renders on the main thread, and moving the EGL context between those threads was unreliable. On the Android emulator, a test reproduced three faults:
  - the first render on the second thread failed with GL_INVALID_VALUE;
  - the GL objects broke once the creating thread exited;
  - the main thread paid about 300 ms of one-time GL setup on its first frame, which dropped frames at the start of a GLES animation.

  `initialize`, `render` and `release` still block until their work is done, and the hand-off allocates nothing per frame. `GlesThreadingTest` covers all three faults.
- **API 29 lint error:** `RenderEffectBackendFactory`, which must be safe to load on API 29 and 30, referenced the API 31 backend class. It now owns the `ID` constant itself and uses an explicit `SDK_INT` branch.

### Changed
- `RenderEffectBlurBackend.ID` is now defined as `RenderEffectBackendFactory.ID`. The value, `"render-effect"`, is unchanged.

## 0.8.0-beta

### Added
- **HARDWARE bitmaps:** the capture now handles HARDWARE bitmaps (the default in Coil and Glide) drawn with `drawBitmap`, which covers ImageView and Compose Image. Each one is copied once at capture resolution and cached weakly, up to 16 MB.
- **Fast fallback:** `FastGaussianBlur`, three box passes matched to the Gaussian's spread (within 8%), and `SoftwareBlurBackend(fastApproximation = true)`, used as `BlurView`'s CPU fallback. It's 4 to 9 times faster than the exact kernels.
- **`KawasePlanCache`.**
- **`benchmarks` module:** backend, capture and per-frame update microbenchmarks with allocation counts, plus a scrolling test that records frame times.
- **Device tests:** activity recreation (leak check), background and foreground, several `BlurView`s on one screen, and detaching during backend warm-up.
- **`docs/PERFORMANCE.md`.**

### Changed
- **Allocations:** steady-state `BlurView` frames allocate nothing (measured on API 35).
- **Warm-up:** backend warm-up renders one frame on the background thread, so the driver's shader compilation no longer stalls the first animated frame.

## 0.5.0

### Added
- **Compose:** `blur-compose` with `Modifier.blurSurface { }` and `BlurContainer`. Animated Compose state read inside the block updates the blur without recomposing.
- **Shared renderer:** `BlurSurfaceRenderer` and `BlurTarget`, the engine shared by `BlurView` and Compose.
- **`CaptureGuard.shouldSkip(canvas)`.**

### Fixed
- **Missing blur in Compose:** Compose could cache a recording made during a capture, and the blur never appeared. The renderer now re-records its own `RenderNode` every frame.

## 0.4.0

### Added
- **`blur-animation`:** `BlurAnimator`, with tweens, springs and per-property animation. Animations can be interrupted with value and velocity carried over, follow the system animation scale (reduced motion), and don't allocate per frame.
- **`BlurView` animation API:** `animateBlur { }`, `show()`, `hide()`, `setBlurProgress()`, `displayedConfig`.
- **Background warm-up:** GLES and software backends are created on a background thread.

### Changed
- **`FrameClock`:** now passes frame timestamps through a `FrameCallback`.
- **No-op blur:** capture is skipped when the blur has no effect (radius 0).

## 0.3.0

### Added
- **`blur-capture`:** captures the content behind a view, with an edge margin so content outside the view blurs into its edges.
- **`blur-view`:** `BlurView`, with XML attributes and a builder DSL.

## 0.2.0

### Added
- **`blur-platform`:** RenderEffect (API 31+), OpenGL ES 2.0 and software backends, plus `BitmapImage` and `BlurRenderEffects`.
- **Backend selection:** `BlurBackendSelector.selectAndInitialize` falls back to the next backend when one fails to start.
- **GLES recovery:** the GLES backend recovers from EGL context loss.

### Changed
- **Backend contract:** `BlurBackend.render` takes `BlurInput` / `BlurOutput`.
- **Dual Kawase:** calibrated to the Gaussian (sigma = radius / 2).

## 0.1.0

### Added
- **Core:** `BlurConfig` with its DSL, the backend contracts, `PixelBuffer` and its pool, and `FrameDrivenScheduler`.
- **Algorithms:** Gaussian, Dual Kawase and box blur, resampling and color adjustment.
- **CPU backend:** the software backend and the sample app.
