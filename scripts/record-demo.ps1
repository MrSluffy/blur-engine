<#
  Copyright 2026 MrSluffy

  Licensed under the Apache License, Version 2.0 (the "License");
  you may not use this file except in compliance with the License.
  You may obtain a copy of the License at

      http://www.apache.org/licenses/LICENSE-2.0

  Unless required by applicable law or agreed to in writing, software
  distributed under the License is distributed on an "AS IS" BASIS,
  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
  See the License for the specific language governing permissions and
  limitations under the License.
#>
<#
.SYNOPSIS
  Records the demo videos from the blur-showcase app on a running emulator or device.

.DESCRIPTION
  Builds and installs the blur-showcase release build, halves the display size (see -Scale),
  sets the animator scale to 1, and puts the status bar into demo mode (fixed clock, full
  battery, no notifications). Then for each screen (glass, gradient, layers, motion):
    - launches it with scripted motion (autoplay),
    - saves a screenshot to docs/media/<screen>.png (the video's poster and the README image),
    - records the screen (from the host on an emulator) and encodes docs/media/<screen>.mp4.

  The MP4 keeps the recording's resolution and frame timing: no frame-rate conversion and no
  scaling. It is H.264 (plays in every browser), trimmed to exactly one cycle of the screen's
  motion so it loops seamlessly, and re-encoded with stronger compression only if it would be
  larger than -MaxMB. The script reports each video's size and distinct frames per second.

  The display, animator scale and status bar are restored at the end, even if a step fails.

  Needs adb (from ANDROID_HOME, ANDROID_SDK_ROOT or %LOCALAPPDATA%\Android\Sdk) and ffmpeg (on
  PATH, or pass -Ffmpeg). Use an API 33+ device with a hardware GPU to get refraction and a
  smooth recording.

.EXAMPLE
  .\scripts\record-demo.ps1
  .\scripts\record-demo.ps1 -Serial emulator-5554 -Screens glass -SkipBuild
#>
param(
    [string]$Serial,
    # Clip length in seconds for every screen. By default each video is exactly one cycle of that
    # screen's autoplay motion (see $Loops), so it loops seamlessly.
    [double]$Seconds = 0,
    # Recording frame rate. The video keeps it as is.
    [ValidateRange(15, 60)][int]$Fps = 30,
    # H.264 quality to start from (lower is better; 18 is close to visually lossless).
    [ValidateRange(14, 30)][int]$Crf = 20,
    # Upper bound per video. Over it, the video is encoded again with a higher CRF.
    [double]$MaxMB = 9.5,
    [int]$ShotWidth = 540,
    [string[]]$Screens = @("glass", "gradient", "layers", "motion"),
    [string]$Ffmpeg,
    [switch]$SkipBuild,
    # Display size divisor while recording. 2 halves width, height and density.
    [ValidateRange(1, 4)][int]$Scale = 2
)

# Not "Stop": Windows PowerShell treats any stderr line from a native tool (Gradle, adb) as an
# error. Failures are detected through exit codes and explicit throws instead.
$ErrorActionPreference = "Continue"
$root = Split-Path -Parent $PSScriptRoot
$out = Join-Path $root "docs\media"
$work = Join-Path ([IO.Path]::GetTempPath()) "blur-demo"
$package = "dev.blurengine.mrsluffy.showcase"
New-Item -ItemType Directory -Force -Path $out, $work | Out-Null

# One cycle of each screen's autoplay motion, in seconds (from the showcase app's autoplay code):
# glass: the lens's figure eight; gradient: scroll down, pause, up, pause; layers: fan out and
# back; motion: show, two springs, hide and the sheet gesture.
$Loops = @{ glass = 7.0; gradient = 9.0; layers = 4.8; motion = 8.3 }
function Clip-Length([string]$screen) {
    if ($Seconds -gt 0) { return $Seconds }
    if ($Loops.ContainsKey($screen)) { return $Loops[$screen] }
    return 9.0
}

# --- Tools ------------------------------------------------------------------------------------
$sdk = @($env:ANDROID_HOME, $env:ANDROID_SDK_ROOT, (Join-Path $env:LOCALAPPDATA "Android\Sdk")) |
    Where-Object { $_ -and (Test-Path (Join-Path $_ "platform-tools\adb.exe")) } | Select-Object -First 1
if (-not $sdk) { throw "adb not found. Set ANDROID_HOME to your Android SDK." }
$adbExe = Join-Path $sdk "platform-tools\adb.exe"

if (-not $Ffmpeg) { $Ffmpeg = (Get-Command ffmpeg -ErrorAction SilentlyContinue).Source }
if (-not $Ffmpeg -or -not (Test-Path $Ffmpeg)) { throw "ffmpeg not found. Put it on PATH or pass -Ffmpeg <path to ffmpeg.exe>." }

if (-not $Serial) {
    $Serial = (& $adbExe devices | Select-String "\tdevice$" | Select-Object -First 1) -replace "\tdevice$", ""
    if (-not $Serial) { throw "No device connected. Start an emulator first." }
}
function Adb { & $adbExe -s $Serial @args }
function Shell([string]$command) { & $adbExe -s $Serial shell $command | Out-Null }

$api = [int](Adb shell getprop ro.build.version.sdk).Trim()
Write-Host "Device $Serial, API $api"
# While recording, the display runs at 1/$Scale of its size and density, so the layout is the
# same but the app renders far fewer pixels. At full size (1440x3120 on a Pixel 7 Pro profile)
# the app dropped frames on the emulator. Sizes are multiples of 16, which video encoders need.
$physical = ((Adb shell wm size) | Select-String "Physical size: (\d+)x(\d+)").Matches[0].Groups
$density = [int](((Adb shell wm density) | Select-String "Physical density: (\d+)").Matches[0].Groups[1].Value)
$recordW = [int][math]::Floor([int]$physical[1].Value / $Scale / 16) * 16
$recordH = [int][math]::Floor([int]$physical[2].Value / $Scale / 16) * 16
$recordSize = "${recordW}x$recordH"
$recordDensity = [int][math]::Round($density * $recordW / [int]$physical[1].Value)
if ($api -lt 33) { Write-Warning "API $api has no refraction; the glass will show lighting only." }

# --- Build ------------------------------------------------------------------------------------
if (-not $SkipBuild) {
    # Release build: a debuggable build runs Compose much slower and drops frames on camera.
    Write-Host "Building and installing blur-showcase (release)..."
    $env:ANDROID_SERIAL = $Serial
    & (Join-Path $root "gradlew.bat") -p $root :blur-showcase:installRelease --console=plain -q
    if ($LASTEXITCODE -ne 0) { throw "Build failed." }
}

# --- Status bar demo mode ---------------------------------------------------------------------
function Demo([string]$extras) { Shell "am broadcast -a com.android.systemui.demo $extras" }
function Enter-DemoMode {
    Shell "settings put global sysui_demo_allowed 1"
    Demo "-e command enter"
    Demo "-e command clock -e hhmm 0941"
    Demo "-e command battery -e level 100 -e plugged false"
    Demo "-e command network -e wifi show -e level 4 -e fully true"
    Demo "-e command network -e mobile show -e datatype none -e level 4 -e fully true"
    Demo "-e command notifications -e visible false"
    Shell "cmd statusbar collapse"
}
function Exit-DemoMode { Demo "-e command exit" }

# --- Capture ----------------------------------------------------------------------------------
# On an emulator, record from the host (`adb emu screenrecord`): the emulator encodes on the PC.
# screenrecord inside the emulator uses a software encoder that kept up with only 6 to 10 of the
# app's ~50 frames per second. On a physical device, screenrecord has a hardware encoder.
$onEmulator = $Serial -like "emulator-*"
function Record-Clip([string]$screen) {
    # Record a little longer than needed; Convert-Mp4 trims to the exact length.
    $limit = [int][math]::Ceiling((Clip-Length $screen) + 1)
    if ($onEmulator) {
        $clip = Join-Path $work "$screen.webm"
        Remove-Item $clip -ErrorAction SilentlyContinue
        Adb emu screenrecord start --fps $Fps --time-limit $limit $clip | Out-Null
        Start-Sleep -Seconds ($limit + 1)
        Adb emu screenrecord stop | Out-Null # finishes the file; harmless if it already stopped
        $deadline = (Get-Date).AddSeconds(15)
        while ((Get-Date) -lt $deadline -and -not (Test-Path $clip)) { Start-Sleep -Milliseconds 300 }
        if (-not (Test-Path $clip)) { throw "The emulator did not write $clip" }
        return $clip
    }
    # screenrecord blocks until the time limit.
    Shell "screenrecord --time-limit $limit --size $recordSize --bit-rate 20000000 /sdcard/blur-demo.mp4"
    $clip = Join-Path $work "$screen-raw.mp4"
    Adb pull /sdcard/blur-demo.mp4 $clip 2>&1 | Out-Null
    return $clip
}

# Distinct frames per second in a clip. Repeated frames (the recorder filling in for a frame the
# app didn't draw in time) don't count, so this shows how smooth the clip really is.
function Get-DistinctFps([string]$clip) {
    $info = & $Ffmpeg -hide_banner -i $clip -vf "mpdecimate=hi=256:lo=128:frac=0.1,showinfo" -f null - 2>&1
    $unique = ($info | Select-String "n:\s*\d+").Count
    $duration = ($info | Select-String "Duration: (\d+):(\d+):([\d.]+)").Matches | Select-Object -First 1
    $seconds = [int]$duration.Groups[1].Value * 3600 + [int]$duration.Groups[2].Value * 60 + [double]$duration.Groups[3].Value
    return [math]::Round($unique / [math]::Max($seconds, 0.1), 1)
}

# H.264 at the recording's own size and frame timing (-fps_mode passthrough: no frames added or
# dropped). yuv420p and +faststart so browsers can play it while it downloads. No audio.
function Convert-Mp4([string]$clip, [string]$mp4, [double]$length) {
    for ($crf = $Crf; $crf -le 32; $crf += 2) {
        & $Ffmpeg -y -loglevel error -ss 0.4 -t $length -i $clip -an -fps_mode passthrough `
            -c:v libx264 -preset slow -crf $crf -pix_fmt yuv420p -movflags +faststart $mp4
        if ($LASTEXITCODE -ne 0) { throw "ffmpeg failed on $clip" }
        $mb = (Get-Item $mp4).Length / 1MB
        if ($mb -le $MaxMB) { return $crf }
    }
    throw "$mp4 is still over $MaxMB MB at CRF 32. Use a shorter -Seconds or a higher -Scale."
}

try {
    Shell "input keyevent KEYCODE_WAKEUP"
    Shell "wm dismiss-keyguard"
    Shell "wm size $recordSize"
    Shell "wm density $recordDensity"
    Write-Host "Display set to $recordSize at $recordDensity dpi for recording."
    # The showcase animates with the system animator scale, which test setups often leave at 0
    # (everything would jump to its end). Use 1 while recording, then put back what was there.
    $savedScale = ((Adb shell settings get global animator_duration_scale) | Out-String).Trim()
    Shell "settings put global animator_duration_scale 1"
    Enter-DemoMode
    foreach ($screen in $Screens) {
        Write-Host "Recording $screen..."
        # -W waits until the launch completes; then wait for the window to have focus, so the
        # capture never catches the splash screen or the launch animation.
        Shell "am start -W -S -n $package/.ShowcaseActivity --es screen $screen --ez autoplay true"
        $deadline = (Get-Date).AddSeconds(15)
        while ((Get-Date) -lt $deadline -and -not ((Adb shell dumpsys window) -match "mCurrentFocus=.*$package")) {
            Start-Sleep -Milliseconds 300
        }
        Start-Sleep -Seconds 3 # splash exit, backend warm-up, autoplay start

        Shell "screencap -p /sdcard/blur-demo.png"
        Adb pull /sdcard/blur-demo.png (Join-Path $work "$screen.png") 2>&1 | Out-Null # progress goes to stderr
        & $Ffmpeg -y -loglevel error -i (Join-Path $work "$screen.png") -vf "scale=${ShotWidth}:-1:flags=lanczos" (Join-Path $out "$screen.png")

        $clip = Record-Clip $screen
        $mp4 = Join-Path $out "$screen.mp4"
        $usedCrf = Convert-Mp4 $clip $mp4 (Clip-Length $screen)
        $size = [math]::Round((Get-Item $mp4).Length / 1MB, 1)
        $distinct = Get-DistinctFps $mp4
        Write-Host "  docs/media/$screen.png, docs/media/$screen.mp4 ($size MB, CRF $usedCrf, $distinct distinct fps)"
        # Screens that hold still on purpose (layers, motion) count fewer distinct frames, so only
        # warn well below the recording frame rate.
        if ($distinct -lt $Fps * 0.5) {
            Write-Warning "$screen got only $distinct distinct frames per second. Close other apps, or use an emulator with a hardware GPU."
        }
    }
} finally {
    Shell "wm size reset"
    Shell "wm density reset"
    if ($savedScale -and $savedScale -ne "null") { Shell "settings put global animator_duration_scale $savedScale" }
    Exit-DemoMode
    Shell "rm -f /sdcard/blur-demo.png /sdcard/blur-demo.mp4"
}
Write-Host "Done. Raw recordings are in $work"
