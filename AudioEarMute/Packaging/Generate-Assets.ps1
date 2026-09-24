<#
.SYNOPSIS
    Generates every PNG asset the MSIX manifest and the Store listing need.

.DESCRIPTION
    The mark is a pair of earcups joined by a headband: the left cup is filled and lit,
    the right cup is a dim outline. That reads as "one side on, one side off" even at
    16 pixels, which is the whole point of the app.

    Everything is drawn in code, so there are no binary assets in the repository and the
    whole set can be regenerated after any design change.

.EXAMPLE
    powershell -ExecutionPolicy Bypass -File Generate-Assets.ps1
#>

[CmdletBinding()]
param(
    [string] $OutputDirectory
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

# $PSScriptRoot is not always populated in a param default, so resolve it in the body.
if (-not $OutputDirectory) {
    $scriptRoot = $PSScriptRoot
    if (-not $scriptRoot) { $scriptRoot = Split-Path -Parent $MyInvocation.MyCommand.Path }
    $OutputDirectory = Join-Path $scriptRoot 'Assets'
}

Add-Type -AssemblyName System.Drawing

$backgroundColor = [System.Drawing.Color]::FromArgb(255, 18, 22, 31)
$litColor        = [System.Drawing.Color]::FromArgb(255, 123, 200, 255)
$dimColor        = [System.Drawing.Color]::FromArgb(255, 88, 100, 120)

function New-Mark {
    param(
        [int] $Width,
        [int] $Height,
        [bool] $Plated = $true
    )

    $bitmap = New-Object System.Drawing.Bitmap($Width, $Height)
    $g = [System.Drawing.Graphics]::FromImage($bitmap)
    try {
        $g.SmoothingMode = [System.Drawing.Drawing2D.SmoothingMode]::AntiAlias
        $g.Clear([System.Drawing.Color]::Transparent)

        # The square logos sit on a plate; the unplated variants Windows uses on the
        # taskbar must stay transparent or they get a double background.
        if ($Plated) {
            $plate = New-Object System.Drawing.SolidBrush($backgroundColor)
            try {
                $radius = [Math]::Min($Width, $Height) * 0.18
                $path = New-Object System.Drawing.Drawing2D.GraphicsPath
                try {
                    $d = $radius * 2
                    $path.AddArc(0, 0, $d, $d, 180, 90)
                    $path.AddArc($Width - $d, 0, $d, $d, 270, 90)
                    $path.AddArc($Width - $d, $Height - $d, $d, $d, 0, 90)
                    $path.AddArc(0, $Height - $d, $d, $d, 90, 90)
                    $path.CloseFigure()
                    $g.FillPath($plate, $path)
                }
                finally { $path.Dispose() }
            }
            finally { $plate.Dispose() }
        }

        # Layout is driven by the shorter side so wide tiles stay centred.
        $unit = [Math]::Min($Width, $Height)
        $cx = $Width / 2.0
        $cy = $Height / 2.0

        $cupRadius = $unit * 0.155
        $span      = $unit * 0.235
        $bandPen   = [Math]::Max(1.0, $unit * 0.062)

        $leftX  = $cx - $span
        $rightX = $cx + $span
        $cupY   = $cy + $unit * 0.085

        # Headband: an arc across the top joining the two cups.
        $pen = New-Object System.Drawing.Pen($litColor, $bandPen)
        try {
            $pen.StartCap = [System.Drawing.Drawing2D.LineCap]::Round
            $pen.EndCap = [System.Drawing.Drawing2D.LineCap]::Round
            $bandW = $span * 2
            $bandH = $unit * 0.46
            $g.DrawArc($pen, [float]($cx - $span), [float]($cupY - $bandH), [float]$bandW, [float]$bandH, 180, 180)
        }
        finally { $pen.Dispose() }

        # Left cup: filled - this ear is playing.
        $lit = New-Object System.Drawing.SolidBrush($litColor)
        try {
            $g.FillEllipse($lit, [float]($leftX - $cupRadius), [float]($cupY - $cupRadius),
                [float]($cupRadius * 2), [float]($cupRadius * 2))
        }
        finally { $lit.Dispose() }

        # Right cup: hollow outline - this ear is resting.
        $dimPen = New-Object System.Drawing.Pen($dimColor, [Math]::Max(1.0, $unit * 0.05))
        try {
            $g.DrawEllipse($dimPen, [float]($rightX - $cupRadius), [float]($cupY - $cupRadius),
                [float]($cupRadius * 2), [float]($cupRadius * 2))
        }
        finally { $dimPen.Dispose() }

        return $bitmap
    }
    finally {
        $g.Dispose()
    }
}

function Save-Mark {
    param(
        [string] $Name,
        [int] $Width,
        [int] $Height,
        [bool] $Plated = $true
    )

    $bitmap = New-Mark -Width $Width -Height $Height -Plated $Plated
    try {
        $path = Join-Path $OutputDirectory $Name
        $bitmap.Save($path, [System.Drawing.Imaging.ImageFormat]::Png)
        [pscustomobject]@{ Asset = $Name; Size = "${Width}x${Height}" }
    }
    finally {
        $bitmap.Dispose()
    }
}

New-Item -ItemType Directory -Path $OutputDirectory -Force | Out-Null

$generated = @()

# Referenced directly by AppxManifest.xml.
$generated += Save-Mark -Name 'StoreLogo.png'          -Width 50   -Height 50
$generated += Save-Mark -Name 'Square44x44Logo.png'    -Width 44   -Height 44
$generated += Save-Mark -Name 'Square150x150Logo.png'  -Width 150  -Height 150
$generated += Save-Mark -Name 'SmallTile.png'          -Width 71   -Height 71
$generated += Save-Mark -Name 'LargeTile.png'          -Width 310  -Height 310
$generated += Save-Mark -Name 'Wide310x150Logo.png'    -Width 310  -Height 150
$generated += Save-Mark -Name 'SplashScreen.png'       -Width 620  -Height 300

# Scale variants. Windows picks these on high-DPI displays; without them it upscales
# the 100% asset and the result looks soft in the Start menu.
foreach ($scale in 125, 150, 200, 400) {
    $factor = $scale / 100.0
    $generated += Save-Mark -Name "Square44x44Logo.scale-$scale.png" `
        -Width ([int](44 * $factor)) -Height ([int](44 * $factor))
    $generated += Save-Mark -Name "Square150x150Logo.scale-$scale.png" `
        -Width ([int](150 * $factor)) -Height ([int](150 * $factor))
    $generated += Save-Mark -Name "StoreLogo.scale-$scale.png" `
        -Width ([int](50 * $factor)) -Height ([int](50 * $factor))
}

# Unplated target-size variants: the taskbar and the Alt+Tab switcher use these and
# draw their own background behind them, so these must have no plate of their own.
foreach ($size in 16, 24, 32, 48, 256) {
    $generated += Save-Mark -Name "Square44x44Logo.targetsize-$size.png" `
        -Width $size -Height $size
    $generated += Save-Mark -Name "Square44x44Logo.targetsize-${size}_altform-unplated.png" `
        -Width $size -Height $size -Plated $false
}

foreach ($item in $generated) {
    Write-Output ("  {0,-52} {1}" -f $item.Asset, $item.Size)
}

Write-Output "$($generated.Count) assets written to $OutputDirectory"
