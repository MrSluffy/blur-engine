# Performance

## Summary

- **API 31+ (RenderEffect path):** about 0.3 to 0.5 ms of main-thread time per blur surface per frame, with no allocations. The blur itself runs on the GPU.
- **API 29 and 30 (GLES path):** 4 to 7 ms per surface per frame, mostly reading the blurred image back from the GPU. One or two surfaces on screen is fine; more can cost frames at 60 Hz.
- **Liquid glass** adds about 0.2 ms for its lighting. Refraction (API 33+) is a GPU shader; its main-thread cost hasn't been measured separately.
- **Progressive blur** adds about 0.7 ms on the GLES path.
- **Hidden blur is free:** at radius 0 or progress 0 the capture is skipped.

## How these were measured

All numbers are from x86_64 emulators on one Windows PC, with debuggable builds. Treat them as **relative comparisons, not device numbers**: debuggable builds are slower, and the emulator's GPU is emulated on the host. Physical-device results are not available yet. Contributions are welcome; see [Contributing](contributing.md#benchmarks).

| | Android 10 | Android 15 |
| --- | --- | --- |
| Emulator | Pixel 3a profile, 1080x2220 | Pixel 7 Pro profile, 1440x3120 |
| Path `AUTO` picks | GLES (Dual Kawase) | RenderEffect |
| Refresh rate | 60 Hz | 60 Hz |

The microbenchmarks use AndroidX Benchmark and report medians. Timings drift between emulator sessions, by up to several times, so compare numbers within one table.

## Main-thread cost of one blur surface

A 1080x600 surface, downsample 4, 64 px capture margin:

| | Android 10 | Android 15 |
| --- | --- | --- |
| Capture only | 0.22 ms | 0.26 ms |
| Full update, RenderEffect | n/a | 0.29 to 0.47 ms |
| Full update, GLES | 4.1 to 5.3 ms | 6.4 to 6.6 ms |
| Allocations per update | 6, inside Android 10's own View drawing | 0 |

## Effects

Full update on the GLES path, Android 10, same session:

| | Time | Allocations |
| --- | --- | --- |
| No effects | 3.17 ms | 6 |
| Liquid glass (lighting; no refraction below API 33) | 3.37 ms | 9 |
| Progressive blur | 3.86 ms | 6 |

On API 33+ the refraction is a `RuntimeShader` chained after the blur, so it runs on the GPU. On the RenderEffect path, progressive blur draws two blur levels (half and full radius) for a smooth transition. Both are GPU work.

## Backends, bitmap to bitmap

A 270x600 buffer (a 1080x2400 screen at downsample 4), radius 24:

| Backend / algorithm | Android 10 | Android 15 | Allocations per frame |
| --- | --- | --- | --- |
| GLES Dual Kawase | 5.0 to 7.1 ms | 7.0 ms | 0 |
| GLES Gaussian | 6.9 ms | 7.3 ms | 0 |
| RenderEffect | n/a | 11.6 ms | 15 (platform `ImageReader`) |
| Software, fast Gaussian (the fallback) | 25.4 ms | 40.5 ms | 0 |
| Software, box (2 passes) | 11.2 ms | 26.8 ms | 0 |
| Software, exact Gaussian | 69.8 ms | 177.7 ms | 0 |

The exact CPU kernels are the reference the GPU outputs are tested against, not a frame path.

### Output accuracy

| Comparison | Mean difference per channel |
| --- | --- |
| GLES vs CPU | 0.01 to 0.73 / 255 |
| RenderEffect vs CPU Gaussian | 0.11 to 0.28 / 255 |
| Dual Kawase vs Gaussian blur strength, radii 3 to 100 | within 12% |

## Scrolling

80 gradient rows scrolled for 240 frames, reading `FrameMetrics.TOTAL_DURATION` for each frame:

| | Android 10 p50 / p90 | Android 15 p50 / p90 |
| --- | --- | --- |
| No blur | 28.1 / 31.7 ms | 34 to 36 / 52 to 66 ms |
| Frosted top bar + card, `AUTO` | 54.0 / 72.8 ms | 47 to 54 / 51 to 86 ms |
| Same, forced GLES | 44.8 / 56.4 ms | 53 to 68 / 69 to 82 ms |

The emulators miss 16.7 ms frames even with no blur, so this shows relative overhead (about 1.3 to 2 times the baseline frame time), not whether a real phone would drop frames.

## Animation

A 400 ms radius animation from 0 to 40:

| | Android 10 (GLES) | Android 15 (RenderEffect) |
| --- | --- | --- |
| Frames with a new value | 22 to 24 of 23 to 25 | 24 of 25 |
| Main-thread cost per frame | 5 to 10 ms | 0.3 to 1.3 ms |

The animator allocates under 1 KB over 1,000 frames. The first GLES frame after a surface appears takes about 28 ms, because it allocates the capture bitmap and GPU textures.

## Reliability

Device tests on both emulators cover:

| Scenario | Result |
| --- | --- |
| HARDWARE bitmaps (Coil/Glide default) behind the surface | Blurred |
| Activity recreation, including during an animation and with a GLES surface | No leaked Activity |
| Background, then foreground | Blurs again after resume |
| 4 surfaces in one window, RenderEffect and GLES mixed | All render |
| Detaching while the backend warms up, 5 times in a row | No crash |
| Detach and reattach | Works |
| GPU context loss (GLES) | Rebuilds, identical output |

## Known limits

1. **GLES readback on the main thread.** 4 to 7 ms per surface per frame on API 29 and 30. Blurring on a background thread (one frame of latency) or drawing a GPU buffer directly would remove it.
2. **First GLES frame allocates** buffers, about 28 ms, one dropped frame when a GLES blur first appears.
3. **Capture runs every window frame,** even when nothing behind the surface changed.
4. **The bitmap-to-bitmap RenderEffect backend allocates** 15 objects per frame inside `ImageReader`. `BlurView` and `blurSurface` don't use it.
