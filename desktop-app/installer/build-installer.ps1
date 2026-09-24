<#
    Сборка установщика: jpackage-образ приложения + один exe со встроенным образом.

        powershell -ExecutionPolicy Bypass -File desktop-app\installer\build-installer.ps1

    Собирается БЕЗ сторонних инструментов: `jpackage --type exe` требует WiX Toolset,
    которого на машине нет, а самораспаковка 7-Zip отпадает — модуль 7z.sfx из обычной
    поставки запускать установку не умеет (проверено: RunProgram игнорируется). Зато в
    самой Windows есть компилятор C# (.NET Framework 4.8), им и собирается Setup.cs с
    образом приложения внутри ресурсом.

    Результат: dist\AniBlaze-Setup-<версия>.exe
#>
param(
    [switch]$SkipBuild,
    [string]$SevenZip = 'C:\Games\7-Zip\7z.exe'
)
$ErrorActionPreference = 'Stop'
$installerDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$repo = Split-Path -Parent (Split-Path -Parent $installerDir)
$version = '1.0.2'

$csc = Join-Path $env:WINDIR 'Microsoft.NET\Framework64\v4.0.30319\csc.exe'
if (-not (Test-Path $csc)) { throw "Нет компилятора C#: $csc" }

if (-not $SkipBuild) {
    Push-Location $repo
    & .\gradlew.bat :desktop-app:createReleaseDistributable --console=plain -q
    Pop-Location
}

$image = Join-Path $repo 'desktop-app\build\compose\binaries\main-release\app\AniBlaze'
if (-not (Test-Path (Join-Path $image 'AniBlaze.exe'))) { throw "Нет образа приложения: $image" }

$work = Join-Path $env:TEMP 'aniblaze-installer-build'
if (Test-Path -LiteralPath $work) { Remove-Item -LiteralPath $work -Recurse -Force }
New-Item -ItemType Directory -Force -Path $work | Out-Null
$zip = Join-Path $work 'payload.zip'

Write-Host 'Упаковываю образ приложения…'
if (Test-Path $SevenZip) {
    # 7-Zip быстрее встроенного упаковщика в несколько раз (многопоточный deflate).
    & $SevenZip a -tzip -mx=5 -mmt=on $zip (Join-Path $image '*') | Select-Object -Last 2
    if ($LASTEXITCODE -ne 0) { throw "7z вернул $LASTEXITCODE" }
} else {
    Add-Type -AssemblyName System.IO.Compression.FileSystem
    [System.IO.Compression.ZipFile]::CreateFromDirectory($image, $zip, 'Optimal', $false)
}

Copy-Item (Join-Path $installerDir 'uninstall.ps1') (Join-Path $work 'uninstall.ps1') -Force

$dist = Join-Path $repo 'dist'
New-Item -ItemType Directory -Force -Path $dist | Out-Null
$setup = Join-Path $dist "AniBlaze-Setup-$version.exe"
if (Test-Path -LiteralPath $setup) { Remove-Item -LiteralPath $setup -Force }

Write-Host 'Собираю установщик…'
$icon = Join-Path $repo 'desktop-app\icons\aniblaze.ico'
$args = @(
    '/nologo', '/target:winexe', '/optimize+', '/platform:anycpu',
    "/out:$setup", "/win32icon:$icon",
    '/reference:System.dll', '/reference:System.Drawing.dll', '/reference:System.Windows.Forms.dll',
    '/reference:System.IO.Compression.dll', '/reference:System.IO.Compression.FileSystem.dll',
    "/resource:$zip,payload.zip",
    "/resource:$(Join-Path $work 'uninstall.ps1'),uninstall.ps1",
    (Join-Path $installerDir 'Setup.cs')
)
& $csc $args
if ($LASTEXITCODE -ne 0) { throw "csc вернул $LASTEXITCODE" }

Remove-Item -LiteralPath $work -Recurse -Force -ErrorAction SilentlyContinue
"{0} — {1:N0} МБ" -f $setup, ((Get-Item $setup).Length / 1MB)
