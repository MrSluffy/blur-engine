# Limitations and troubleshooting

## Known limitations

- **SurfaceView and TextureView** (video, camera, maps, some game views) can't be drawn by a software canvas, so they're missing from the blur.
- **HARDWARE bitmaps in shaders.** HARDWARE bitmaps (the default in Coil and Glide) drawn with `drawBitmap` are captured, which covers `ImageView` and Compose `Image`. Inside a `BitmapShader` they make the capture fail; the blur turns off and a warning is logged once.
- **Software canvases.** When a surface itself is drawn into a software canvas (for example `View.draw(bitmapCanvas)` for a screenshot), the RenderEffect path draws only the unblurred, tinted capture.
- **First frames without blur.** Off the RenderEffect path, the backend warms up on a background thread so the UI doesn't stutter. For the first 100 to 400 ms, the surface shows only its tint.
- **Refraction needs API 33.** Below it, liquid glass draws its lighting without refraction.
- **Main-thread cost on API 29 and 30.** The GLES path reads the blurred image back on the main thread, about 4 to 7 ms per surface per frame on emulators. Keep to one or two blur surfaces on screen on those versions. See [Performance](performance.md).

## Troubleshooting

??? question "The surface shows only the tint, never a blur"
    - Check `BlurView.activeRenderer`. If it's null, no frame has been blurred yet: the backend may still be warming up, or the capture failed.
    - Look in Logcat for the tag `BlurEngine`. A capture failure caused by a HARDWARE bitmap in a shader is logged once, with the exception.
    - Make sure the radius isn't 0 and `setBlurProgress` / `progress` isn't 0.

??? question "Video, camera preview or a map isn't blurred"
    These draw through `SurfaceView` or `TextureView`, which can't be captured. Put a static frame or placeholder behind the blur instead.

??? question "The content inside the blur surface is blurred too"
    Children of a `BlurView`, and the content of a `blurSurface` element, draw on top of the blur and are never captured. If content still looks blurred, it's probably a sibling drawn *before* the surface, behind it. Move it into the surface, or after it.

??? question "Stacked glass panels don't show each other"
    A surface only shows the surfaces drawn before it. Check the order: later siblings, or a higher `zIndex` in Compose, draw on top.

??? question "The blur jumps instead of animating"
    - Views: setting a property (`blurRadius = ...`) applies immediately. Use `animateBlur`, `show` or `hide`.
    - Compose: read the animated `State` inside the `blurSurface` block, not outside it.
    - If the device has animations turned off (developer options or accessibility), animations jump to the end by design. Set `respectsAnimationScale = false` to override.

??? question "Scrolling is slower on Android 10 and 11"
    That's the GLES readback. Fewer or smaller blur surfaces, a higher `downsample`, or `sourceView` set to just the content behind the blur all reduce the cost.
