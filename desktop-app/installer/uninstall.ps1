<#
    Удаление AniBlaze: файлы своей установки, ярлыки на неё и её запись в
    «Установка и удаление программ».

    ГЛАВНОЕ ПРАВИЛО: трогаем только то, что указывает ВНУТРЬ этой папки. Ярлык на
    другую копию AniBlaze (например, установленную MSI в C:\Games) и чужая запись в
    реестре обязаны остаться на месте — ярлык с тем же именем ещё не значит «мой».

    Настройки, история и избранное (%APPDATA%\AniBlaze) не удаляются: при повторной
    установке человек ждёт свою библиотеку на месте. Полная очистка — флагом -Purge.
#>
param([switch]$Silent, [switch]$Purge)

$ErrorActionPreference = 'Continue'
$dir = (Split-Path -Parent $MyInvocation.MyCommand.Path).TrimEnd('\')
$exe = Join-Path $dir 'AniBlaze.exe'

if (-not $Silent) {
    Add-Type -AssemblyName System.Windows.Forms
    $answer = [System.Windows.Forms.MessageBox]::Show(
        "Удалить AniBlaze из`n$dir ?`n`nНастройки и история останутся.",
        'Удаление AniBlaze', 'YesNo', 'Question')
    if ($answer -ne 'Yes') { return }
}

# Закрываем только процессы, запущенные ИЗ ЭТОЙ папки: вторая копия программы
# продолжает работать.
Get-Process -Name AniBlaze -ErrorAction SilentlyContinue | Where-Object {
    $p = $null; try { $p = $_.Path } catch {}
    $p -and $p.StartsWith($dir, [StringComparison]::OrdinalIgnoreCase)
} | Stop-Process -Force -ErrorAction SilentlyContinue
Start-Sleep -Milliseconds 800

# Ярлык удаляется, только если ведёт в эту папку.
function Remove-OwnShortcut([string]$Path) {
    if (-not (Test-Path -LiteralPath $Path)) { return }
    $target = ''
    try { $target = (New-Object -ComObject WScript.Shell).CreateShortcut($Path).TargetPath } catch { return }
    if ($target -and $target.StartsWith($dir, [StringComparison]::OrdinalIgnoreCase)) {
        Remove-Item -LiteralPath $Path -Force -ErrorAction SilentlyContinue
    }
}

Remove-OwnShortcut (Join-Path ([Environment]::GetFolderPath('Desktop')) 'AniBlaze.lnk')
$menu = Join-Path $env:APPDATA 'Microsoft\Windows\Start Menu\Programs\AniBlaze'
Remove-OwnShortcut (Join-Path $menu 'AniBlaze.lnk')
# Папку меню убираем, только если она опустела — в ней могли лежать чужие ярлыки.
if ((Test-Path -LiteralPath $menu) -and -not (Get-ChildItem -LiteralPath $menu -Force)) {
    Remove-Item -LiteralPath $menu -Force -ErrorAction SilentlyContinue
}

# Запись в реестре — только своя: сверяем InstallLocation.
$key = 'HKCU:\Software\Microsoft\Windows\CurrentVersion\Uninstall\AniBlaze'
$installed = (Get-ItemProperty -Path $key -Name InstallLocation -ErrorAction SilentlyContinue).InstallLocation
if ($installed -and $installed.TrimEnd('\').Equals($dir, [StringComparison]::OrdinalIgnoreCase)) {
    Remove-Item -LiteralPath $key -Recurse -Force -ErrorAction SilentlyContinue
}

if ($Purge) { Remove-Item -LiteralPath (Join-Path $env:APPDATA 'AniBlaze') -Recurse -Force -ErrorAction SilentlyContinue }

# Себя удалить нельзя, пока скрипт выполняется: папку дочищает отдельный процесс,
# и только если это действительно папка установки (внутри лежит AniBlaze.exe).
if (Test-Path -LiteralPath $exe) {
    $cleanup = @"
Start-Sleep -Seconds 2
if (Test-Path -LiteralPath '$exe') { Remove-Item -LiteralPath '$dir' -Recurse -Force -ErrorAction SilentlyContinue }
"@
    $temp = Join-Path $env:TEMP 'aniblaze-cleanup.ps1'
    Set-Content -LiteralPath $temp -Value $cleanup -Encoding utf8
    Start-Process powershell -ArgumentList '-NoProfile', '-WindowStyle', 'Hidden', '-ExecutionPolicy', 'Bypass', '-File', $temp
}

if (-not $Silent) {
    [System.Windows.Forms.MessageBox]::Show('AniBlaze удалён.', 'Готово', 'OK', 'Information') | Out-Null
}
