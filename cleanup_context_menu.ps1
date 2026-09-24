#Requires -RunAsAdministrator
# Очистка контекстного меню Windows - оставляем только WinRAR

Write-Host "=== Очистка контекстного меню ===" -ForegroundColor Cyan
Write-Host "Будут удалены все лишние элементы кроме WinRAR" -ForegroundColor Yellow

# Бэкап реестра
$backupPath = "$env:USERPROFILE\Desktop\context_menu_backup_$(Get-Date -Format 'yyyyMMdd_HHmmss').reg"
Write-Host "`nСоздание бэкапа: $backupPath" -ForegroundColor Gray
reg export "HKCU\Software\Classes\*\shell" $backupPath /y 2>$null
reg export "HKCU\Software\Classes\*\shellex\ContextMenuHandlers" "$env:USERPROFILE\Desktop\context_menu_handlers_backup_$(Get-Date -Format 'yyyyMMdd_HHmmss').reg" /y 2>$null

# Удаление ненужных элементов из HKCU\Software\Classes\*\shell
$shellItems = @(
    "removeproperties",
    "rename",
    "pintohome",
    "turntopsmart",
    "rotateleft",
    "rotateright",
    "setdesktopwallpaper",
    "copyaspath",
    "opennewwindow",
    "openwith",
    "share",
    "runas",
    "edit",
    "print"
)

Write-Host "`nУдаление элементов из HKCU\Software\Classes\*\shell..." -ForegroundColor Yellow
foreach ($item in $shellItems) {
    $path = "HKCU:\Software\Classes\*\shell\$item"
    if (Test-Path $path) {
        Remove-Item -Path $path -Recurse -Force -ErrorAction SilentlyContinue
        Write-Host "  Удален: $item" -ForegroundColor Red
    }
}

# Удаление из HKCR\*\shell (могут потребоваться права админа)
$hkcrShellItems = @(
    "Windows.RotateLeft",
    "Windows.RotateRight",
    "Windows.SetDesktopBackground",
    "copyaspath",
    "pintohome"
)

Write-Host "`nУдаление системных элементов (требуются права админа)..." -ForegroundColor Yellow
foreach ($item in $hkcrShellItems) {
    $path = "Registry::HKEY_CLASSES_ROOT\*\shell\$item"
    if (Test-Path $path) {
        Remove-Item -Path $path -Recurse -Force -ErrorAction SilentlyContinue
        Write-Host "  Удален: $item" -ForegroundColor Red
    }
}

# Удаление нестандартных ContextMenuHandlers
$handlersPath = "HKCU:\Software\Classes\*\shellex\ContextMenuHandlers"
if (Test-Path $handlersPath) {
    Write-Host "`nОчистка ContextMenuHandlers..." -ForegroundColor Yellow
    Get-ChildItem -Path $handlersPath -ErrorAction SilentlyContinue | ForEach-Object {
        $name = $_.PSChildName
        # Оставляем только WinRAR и стандартные
        if ($name -notmatch "WinRAR" -and $name -ne "ModernSharing") {
            Remove-Item -Path $_.PSPath -Recurse -Force -ErrorAction SilentlyContinue
            Write-Host "  Удален обработчик: $name" -ForegroundColor Red
        }
    }
}

# Удаление из HKLM для системных записей (ESET, Adobe, PowerToys и т.д.)
$hklmShellEx = @(
    "HKLM:\SOFTWARE\Classes\*\shellex\ContextMenuHandlers\ESET Smart Security Premium Shell Extension",
    "HKLM:\SOFTWARE\Classes\*\shellex\ContextMenuHandlers\PowerRenameExt",
    "HKLM:\SOFTWARE\Classes\*\shellex\ContextMenuHandlers\FileLocksmithExt"
)

Write-Host "`nУдаление расширений из HKLM..." -ForegroundColor Yellow
foreach ($path in $hklmShellEx) {
    if (Test-Path $path) {
        Remove-Item -Path $path -Recurse -Force -ErrorAction SilentlyContinue
        Write-Host "  Удален: $($path.Split('\')[-1])" -ForegroundColor Red
    }
}

# Удаление Copilot из контекстного меню
$copilotPath = "HKCU:\Software\Classes\*\shell\copilot"
if (Test-Path $copilotPath) {
    Remove-Item -Path $copilotPath -Recurse -Force -ErrorAction SilentlyContinue
    Write-Host "  Удален: Copilot" -ForegroundColor Red
}

# Блокировка Adobe PDF через ApproveSHellext
$adobePath = "HKLM:\SOFTWARE\Classes\*\shellex\ContextMenuHandlers\Adobe.Acrobat.ContextMenu"
if (Test-Path $adobePath) {
    # Переименовываем чтобы отключить, но не удаляем
    Rename-Item -Path $adobePath -NewName "Adobe.Acrobat.ContextMenu.DISABLED" -Force -ErrorAction SilentlyContinue
    Write-Host "  Отключен: Adobe PDF" -ForegroundColor Red
}

# Удаление Malwarebytes
$mbamPath = "HKLM:\SOFTWARE\Classes\*\shell\MBAMShContext"
if (Test-Path $mbamPath) {
    Remove-Item -Path $mbamPath -Recurse -Force -ErrorAction SilentlyContinue
    Write-Host "  Удален: Malwarebytes" -ForegroundColor Red
}

Write-Host "`n=== Готово! ===" -ForegroundColor Green
Write-Host "Бэкап сохранен на рабочий стол" -ForegroundColor Gray
Write-Host "Для применения изменений перезагрузите ПК или выполните:" -ForegroundColor Yellow
Write-Host "  Stop-Process -Name explorer -Force; Start-Process explorer" -ForegroundColor Cyan
