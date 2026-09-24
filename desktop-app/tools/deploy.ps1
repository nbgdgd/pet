# Сборка jar и подмена его в установленном приложении (jpackage-образ).
#
#   powershell -ExecutionPolicy Bypass -File desktop-app\tools\deploy.ps1
#
# Имя jar внутри образа меняется от сборки к сборке (jpackage добавляет хэш), поэтому
# оно читается из AniBlaze.cfg, а не зашито здесь. Приложение останавливается,
# jar заменяется, приложение запускается снова.
param(
    [string]$AppDir = "$env:LOCALAPPDATA\Packages\OpenAI.Codex_2p2nqsd0c76g0\LocalCache\Local\AniBlaze\app"
)
$ErrorActionPreference = 'Continue'
$repo = Split-Path -Parent (Split-Path -Parent (Split-Path -Parent $MyInvocation.MyCommand.Path))
Set-Location $repo
& .\gradlew.bat :desktop-app:jar --console=plain -q 2>&1 | Select-Object -Last 3
$cfg = Join-Path $AppDir "app\AniBlaze.cfg"
$line = (Get-Content $cfg -Encoding UTF8 | Where-Object { $_ -like 'app.classpath=$APPDIR\desktop-app-*' } | Select-Object -First 1)
$jar = $line -replace '^app.classpath=\$APPDIR\\', ''
if (-not $jar) { throw "desktop-app jar not found in $cfg" }
Stop-Process -Name AniBlaze -Force -ErrorAction SilentlyContinue
Start-Sleep -Milliseconds 900
Copy-Item (Join-Path $repo "desktop-app\build\libs\desktop-app-1.0.2.jar") (Join-Path $AppDir "app\$jar") -Force
"deployed -> $jar"
Start-Process (Join-Path $AppDir "AniBlaze.exe")
