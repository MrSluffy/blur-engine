# Contributing

Bug reports, device measurements and pull requests are welcome. For a larger change, open an issue first so we can agree on the approach. Report security problems privately, as described in the [security policy](https://github.com/MrSluffy/blur-engine/blob/HEAD/SECURITY.md), not in an issue.

Dependency updates arrive as Dependabot pull requests (configured in `.github/dependabot.yml`), and CI tests them like any other pull request. Kotlin and AGP updates can change the recorded public API: if `apiCheck` fails on one, review the diff before running `apiDump`.

## Building

Requirements: JDK 17 and the Android SDK (API 35). Then:

```shell
./gradlew build                         # compile, JVM tests, lint, API check
./gradlew spotlessApply                 # add the Apache license header to new files
./gradlew :blur-showcase:installDebug   # the demo app
./gradlew :blur-samples:installDebug    # one screen per feature
```

`spotlessCheck` runs in CI and fails if a source file lacks the license header.

### Public API

Each library module's public API is recorded in `<module>/api/*.api`, and `apiCheck` (part of `check`) fails when it changes. If the change is intended, run `./gradlew apiDump` and commit the updated file. From 1.0.0 on, breaking changes need a new major version.

## Tests

| Where | Command | What |
| --- | --- | --- |
| JVM | `./gradlew test` | Algorithms, calibration, config, animator timing and springs, selector, capture geometry |
| Device | `./gradlew connectedDebugAndroidTest` | Backends against the CPU reference, screenshot checks of `BlurView` and `blurSurface` (blur, alignment, stability, corners, effects, stacking), animations, lifecycle and leaks |

Device tests need one emulator or device. They take real screenshots, so:

- **Let the emulator settle.** Right after boot (load average above about 3) screenshot and frame-timing tests can fail. Wait, then rerun.
- **Run one emulator at a time.**
- **Collapse the notification shade** if an emulator rebooted: `adb shell cmd statusbar collapse`. Screenshots taken against the shade fail.
- **After an interrupted run,** force-stop the `*.test` packages or reboot the emulator. A leftover test process can hold UiAutomation and fail every screenshot test in the next run.
- **Without GPU acceleration** (CI emulators with software GL), skip frame-rate checks and keep the correctness checks: `-Pandroid.testInstrumentationRunnerArguments.timingAssertions=false`.

## Benchmarks

The `benchmarks` module has microbenchmarks (backends, capture, per-frame update, effects) and a scrolling frame-time test:

```shell
./gradlew :benchmarks:connectedDebugAndroidTest
```

Results from **physical devices** are the most useful contribution right now: the [performance page](https://github.com/MrSluffy/blur-engine/blob/HEAD/docs/performance.md) only has emulator numbers. Please include the device model, Android version and refresh rate.

## Re-recording the demo

[`scripts/record-demo.ps1`](https://github.com/MrSluffy/blur-engine/blob/HEAD/scripts/record-demo.ps1) regenerates the screenshots and GIFs in `docs/media/` from `blur-showcase`, on a running emulator or device (use API 33+ for refraction):

```powershell
.\scripts\record-demo.ps1                                  # build, install, record all four screens
.\scripts\record-demo.ps1 -SkipBuild -Seconds 12 -Screens glass
```

It needs `adb` (through `ANDROID_HOME` or the default SDK location) and `ffmpeg` on `PATH` (or `-Ffmpeg <path>`). It turns on System UI demo mode (9:41 clock, full battery, no notifications), launches each screen with `--ez autoplay true` so the app plays scripted motion, records at half resolution, converts each clip to a GIF with a two-pass palette, and turns demo mode off again.

To open a screen by hand: `adb shell am start -n dev.blurengine.mrsluffy.showcase/.ShowcaseActivity --es screen layers --ez autoplay true` (screens: `glass`, `gradient`, `layers`, `motion`).

## Documentation

The site under `docs/` is built with [MkDocs Material](https://squidfunk.github.io/mkdocs-material/):

```shell
pip install -r mkdocs-requirements.txt
mkdocs serve            # http://127.0.0.1:8000
mkdocs build --strict   # what CI runs
```

`.github/workflows/pages.yml` builds the site on every pull request that touches the docs, and publishes it to GitHub Pages on pushes to `development`. Pages must be set to deploy from GitHub Actions (Settings → Pages → Source).

API reference: `./gradlew dokkaHtmlMultiModule`.

## License

By contributing, you agree that your contributions are licensed under the [Apache License 2.0](https://github.com/MrSluffy/blur-engine/blob/HEAD/LICENSE).
