param(
    [Parameter(Mandatory = $true, Position = 0)]
    [string]$InputFile,

    [Parameter(Mandatory = $false)]
    [switch]$Test,

    [Parameter(Mandatory = $false)]
    [int]$GPU = -1,

    [Parameter(Mandatory = $false)]
    [switch]$KeepFrames,

    [Parameter(Mandatory = $false)]
    [ValidateSet("1440p", "4K")]
    [string]$Target = "4K",

    [Parameter(Mandatory = $false)]
    [ValidateSet("lossless", "fast")]
    [string]$Mode = "fast"
)

$ErrorActionPreference = "Stop"

$ToolsDir = "C:\tools"
$FfmpegDir = "$env:LOCALAPPDATA\Microsoft\WinGet\Packages\Gyan.FFmpeg_Microsoft.Winget.Source_8wekyb3d8bbwe\ffmpeg-8.1.1-full_build\bin"
$ffmpeg = "$FfmpegDir\ffmpeg.exe"
$ffprobe = "$FfmpegDir\ffprobe.exe"
$RifeExe = "$ToolsDir\rife\rife-ncnn-vulkan.exe"
$RealesrganExe = "$ToolsDir\realesrgan\realesrgan-ncnn-vulkan.exe"

$RifeModel = "rife-v4.6"
$TestDuration = 15

function Write-Log { param([string]$Msg); Write-Host "[$(Get-Date -Format 'HH:mm:ss')] $Msg" }

$InputFile = (Resolve-Path $InputFile -ErrorAction Stop).Path

if (-not (Test-Path $ffmpeg)) { throw "ffmpeg not found: $ffmpeg" }
if (-not (Test-Path $ffprobe)) { throw "ffprobe not found: $ffprobe" }
if (-not (Test-Path $RifeExe)) { throw "RIFE not found: $RifeExe" }
if (-not (Test-Path $RealesrganExe)) { throw "Real-ESRGAN not found: $RealesrganExe" }

$OutputName = [System.IO.Path]::GetFileNameWithoutExtension($InputFile)
$OutputDir = [System.IO.Path]::GetDirectoryName($InputFile)
$OutputVideo = Join-Path $OutputDir "${OutputName}_${Target}_${Mode}.mp4"

$safeName = ($OutputName -replace '[^\w\s-]', '').Trim()
$BaseTemp = Join-Path $env:TEMP "upscale_$safeName"
$DirOriginal = Join-Path $BaseTemp "frames_original"
$DirInterpolated = Join-Path $BaseTemp "frames_interpolated"
$DirFinal = Join-Path $BaseTemp "frames_final"

Write-Log "=== VIDEO ANALYSIS ==="
Write-Log "File: $InputFile"
Write-Log "Mode: $Mode"

$probeJson = & $ffprobe -v quiet -print_format json -show_format -show_streams $InputFile 2>&1 | ConvertFrom-Json
$videoStream = $probeJson.streams | Where-Object { $_.codec_type -eq "video" } | Select-Object -First 1
if (-not $videoStream) { throw "Video stream not found" }

$srcWidth = [int]$videoStream.width
$srcHeight = [int]$videoStream.height
$srcFpsRaw = $videoStream.r_frame_rate
$fpsParts = $srcFpsRaw -split '/'
$srcFps = if ($fpsParts[1] -ne '0') { [double]$fpsParts[0] / [double]$fpsParts[1] } else { 0 }
$srcDuration = [double]$probeJson.format.duration
$totalFrames = [int]$videoStream.nb_frames

Write-Log "Resolution: ${srcWidth}x${srcHeight}, FPS: $([Math]::Round($srcFps, 3))"
Write-Log "Duration: $([Math]::Round($srcDuration / 60, 1)) min, frames: $totalFrames"

if ($Target -eq "4K") {
    if ($srcHeight -le 480) { $Scale = 4; $Model = "realesr-animevideov3-x4" }
    elseif ($srcHeight -le 720) { $Scale = 3; $Model = "realesr-animevideov3-x3" }
    else { $Scale = 2; $Model = "realesr-animevideov3-x2" }
} else {
    if ($srcHeight -le 360) { $Scale = 4; $Model = "realesr-animevideov3-x4" }
    elseif ($srcHeight -le 540) { $Scale = 3; $Model = "realesr-animevideov3-x3" }
    else { $Scale = 2; $Model = "realesr-animevideov3-x2" }
}

if ($Mode -eq "fast") {
    $FrameExt = "jpg"
    $FrameFmt = "jpg"
    $X264Preset = "fast"
    $multiplier = 2
    $RifeThreads = "2:4:2"
} else {
    $FrameExt = "png"
    $FrameFmt = "png"
    $X264Preset = "slow"
    $multiplier = 3
    $RifeThreads = $null
}

if ($Test) {
    $testFrames = [Math]::Ceiling($srcFps * $TestDuration)
    Write-Log "=== TEST MODE: first $TestDuration sec (~$testFrames frames) ==="
}

$targetFps = $srcFps * $multiplier
$outWidth = $srcWidth * $Scale
$outHeight = $srcHeight * $Scale

Write-Log "Interpolation: $([Math]::Round($srcFps, 2)) -> $([Math]::Round($targetFps, 2)) fps (x$multiplier)"
Write-Log "Upscale: ${srcWidth}x${srcHeight} -> ${outWidth}x${outHeight} (x$Scale, $Model)"
Write-Log "Format: $FrameExt, x264 preset: $X264Preset"

$procFrames = if ($Test) { $testFrames } else { $totalFrames }
$procFramesInterp = $procFrames * $multiplier
$sizeFactor = if ($FrameExt -eq "jpg") { 0.02 } else { 0.5 }
$kbPerFrame = $srcWidth * $srcHeight * 3 / 1024 * $sizeFactor
$estOrigGB = [Math]::Round($procFrames * $kbPerFrame / 1024 / 1024, 1)
$estInterpGB = [Math]::Round($procFramesInterp * $kbPerFrame / 1024 / 1024, 1)
$sizeFactorFinal = if ($FrameExt -eq "jpg") { 0.08 } else { 0.3 }
$kbPerFrameFinal = $outWidth * $outHeight * 3 / 1024 * $sizeFactorFinal
$estFinalGB = [Math]::Round($procFramesInterp * $kbPerFrameFinal / 1024 / 1024, 1)
$estTotalGB = $estOrigGB + $estInterpGB + $estFinalGB
$freeGB = [Math]::Round((Get-PSDrive -Name (Get-Item $env:TEMP).PSDrive).Free / 1GB, 1)

Write-Log "Disk: orig=${estOrigGB}GB + interp=${estInterpGB}GB + final=${estFinalGB}GB = ${estTotalGB}GB needed, ${freeGB}GB free"

if (-not $Test -and ($freeGB -lt ($estTotalGB * 1.3))) {
    Write-Warning "Low disk space! Need ~$([Math]::Round($estTotalGB * 1.3)) GB, have ${freeGB}GB"
    $answer = Read-Host "Continue? (y/N)"
    if ($answer -ne 'y') { throw "Cancelled" }
}

Write-Log "Creating temp directories..."
@($BaseTemp, $DirOriginal, $DirInterpolated, $DirFinal) | ForEach-Object {
    if (-not (Test-Path $_)) { New-Item -ItemType Directory -Path $_ -Force | Out-Null }
}

Write-Log "=== STAGE 1: EXTRACT FRAMES ==="
$extractArgs = @("-loglevel", "error")
if ($Test) { $extractArgs += "-ss", "0", "-t", "$TestDuration" }
$extractArgs += "-i", $InputFile, "-q:v", "2", "-fps_mode", "vfr"
$extractArgs += (Join-Path $DirOriginal "%08d.$FrameExt")
& $ffmpeg $extractArgs
if ($LASTEXITCODE -ne 0) { throw "Failed to extract frames (code: $LASTEXITCODE)" }

$actualFrames = @(Get-ChildItem "$DirOriginal\*.$FrameExt").Count
if ($actualFrames -eq 0) { throw "No frames extracted" }
Write-Log "Extracted frames: $actualFrames"

Write-Log "=== STAGE 2: RIFE INTERPOLATION ==="
$rifeTargetFrames = $actualFrames * $multiplier
$rifeArgs = @("-i", $DirOriginal, "-o", $DirInterpolated, "-n", $rifeTargetFrames, "-m", $RifeModel)
if ($GPU -ge 0) { $rifeArgs += "-g", $GPU }
if ($RifeThreads) { $rifeArgs += "-j", $RifeThreads }

Write-Log "RIFE: ${actualFrames} -> ${rifeTargetFrames} frames (model: $RifeModel)"
& $RifeExe $rifeArgs
if ($LASTEXITCODE -ne 0) { throw "RIFE failed (code: $LASTEXITCODE)" }

$interpFrames = @(Get-ChildItem "$DirInterpolated\*.$FrameExt").Count
Write-Log "After RIFE: $interpFrames frames"

Write-Log "=== STAGE 3: Real-ESRGAN UPSCALE ==="
$esrganArgs = @("-i", $DirInterpolated, "-o", $DirFinal, "-s", $Scale, "-n", $Model, "-f", "$FrameFmt")
if ($GPU -ge 0) { $esrganArgs += "-g", $GPU }
Write-Log "Upscale: x${Scale} (model: $Model, format: $FrameFmt)"
& $RealesrganExe $esrganArgs
if ($LASTEXITCODE -ne 0) { throw "Real-ESRGAN failed (code: $LASTEXITCODE)" }

$finalFrames = @(Get-ChildItem "$DirFinal\*.$FrameExt").Count
Write-Log "After upscale: $finalFrames frames"

Write-Log "=== STAGE 4: ASSEMBLE VIDEO ==="
Write-Log "Output: $OutputVideo"

$encodeArgs = @("-loglevel", "error",
    "-r", "$([Math]::Round($targetFps, 3))",
    "-i", (Join-Path $DirFinal "%08d.$FrameExt"),
    "-i", $InputFile,
    "-c:v", "libx264", "-crf", "18", "-preset", $X264Preset,
    "-pix_fmt", "yuv420p",
    "-c:a", "copy",
    "-map", "0:v:0", "-map", "1:a:0",
    "-shortest",
    "-y", $OutputVideo)
& $ffmpeg $encodeArgs
if ($LASTEXITCODE -ne 0) { throw "Failed to assemble video (code: $LASTEXITCODE)" }

Write-Log "DONE: $OutputVideo"
Write-Log "Size: $([Math]::Round((Get-Item $OutputVideo).Length / 1GB, 2)) GB"

if (-not $KeepFrames) {
    Write-Log "Cleaning up..."
    Remove-Item $BaseTemp -Recurse -Force -ErrorAction SilentlyContinue
} else {
    Write-Log "Temp frames kept: $BaseTemp"
}
Write-Log "=== PIPELINE COMPLETE ==="
