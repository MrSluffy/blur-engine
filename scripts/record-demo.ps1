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
  Records the README demo from the blur-showcase app on a running emulator or device.

.DESCRIPTION
  Builds and installs blur-showcase, puts the status bar into demo mode (fixed clock, full
  battery, no notifications), then for each screen (glass, gradient, layers, motion):
    - launches it with scripted motion (autoplay),
    - saves a screenshot to docs/media/<screen>.png,
    - records the screen and converts it to docs/media/<screen>.gif with ffmpeg.
  Demo mode is turned off again at the end, even if a step fails.

  Needs adb (from ANDROID_HOME, ANDROID_SDK_ROOT or %LOCALAPPDATA%\Android\Sdk) and ffmpeg (on
  PATH, or pass -Ffmpeg). Use an API 33+ device to get refraction in the recording.

.EXAMPLE
  .\scripts\record-demo.ps1
  .\scripts\record-demo.ps1 -Serial emulator-5554 -Seconds 10 -SkipBuild
#>
param(
    [string]$Serial,
    [int]$Seconds = 9,
    [int]$GifWidth = 360,
    [int]$GifFps = 24,
    [int]$ShotWidth = 540,
    [string[]]$Screens = @("glass", "gradient", "layers", "motion"),
    [string]$Ffmpeg,
    [switch]$SkipBuild
)

# Not "Stop": Windows PowerShell treats any stderr line from a native tool (Gradle, adb) as an
# error. Failures are detected through exit codes and explicit throws instead.
$ErrorActionPreference = "Continue"
$root = Split-Path -Parent $PSScriptRoot
$out = Join-Path $root "docs\media"
$work = Join-Path ([IO.Path]::GetTempPath()) "blur-demo"
$package = "dev.blurengine.mrsluffy.showcase"
New-Item -ItemType Directory -Force -Path $out, $work | Out-Null

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
# Emulator encoders often reject full resolution, and screenrecord's own fallback (720x1280)
# stretches tall screens. Record at half size, rounded to multiples of 16, keeping the aspect.
$physical = ((Adb shell wm size) | Select-String "Physical size: (\d+)x(\d+)").Matches[0].Groups
$recordW = [int][math]::Floor([int]$physical[1].Value / 2 / 16) * 16
$recordH = [int][math]::Floor([int]$physical[2].Value / 2 / 16) * 16
$recordSize = "${recordW}x$recordH"
if ($api -lt 33) { Write-Warning "API $api has no refraction; the glass will show lighting only." }

# --- Build ------------------------------------------------------------------------------------
if (-not $SkipBuild) {
    Write-Host "Building and installing blur-showcase..."
    $env:ANDROID_SERIAL = $Serial
    & (Join-Path $root "gradlew.bat") -p $root :blur-showcase:installDebug --console=plain -q
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
function Convert-Gif([string]$mp4, [string]$gif) {
    # Two-pass palette: much cleaner gradients than ffmpeg's default GIF palette.
    $filter = "fps=$GifFps,scale=${GifWidth}:-1:flags=lanczos,split[a][b];" +
        "[a]palettegen=max_colors=256:stats_mode=diff[p];[b][p]paletteuse=dither=bayer:bayer_scale=4:diff_mode=rectangle"
    & $Ffmpeg -y -loglevel error -ss 0.4 -i $mp4 -vf $filter -loop 0 $gif
    if ($LASTEXITCODE -ne 0) { throw "ffmpeg failed on $mp4" }
}

try {
    Shell "input keyevent KEYCODE_WAKEUP"
    Shell "wm dismiss-keyguard"
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
        Adb pull /sdcard/blur-demo.png (Join-Path $work "$screen.png") | Out-Null
        & $Ffmpeg -y -loglevel error -i (Join-Path $work "$screen.png") -vf "scale=${ShotWidth}:-1:flags=lanczos" (Join-Path $out "$screen.png")

        # screenrecord blocks until the time limit, so the clip is exactly $Seconds long.
        Shell "screenrecord --time-limit $Seconds --size $recordSize --bit-rate 12000000 /sdcard/blur-demo.mp4"
        $mp4 = Join-Path $work "$screen.mp4"
        Adb pull /sdcard/blur-demo.mp4 $mp4 | Out-Null
        Convert-Gif $mp4 (Join-Path $out "$screen.gif")
        $size = [math]::Round((Get-Item (Join-Path $out "$screen.gif")).Length / 1MB, 1)
        Write-Host "  docs/media/$screen.png, docs/media/$screen.gif ($size MB)"
    }
} finally {
    Exit-DemoMode
    Shell "rm -f /sdcard/blur-demo.png /sdcard/blur-demo.mp4"
}
Write-Host "Done. Raw recordings are in $work"
