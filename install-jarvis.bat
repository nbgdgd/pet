@echo off
chcp 65001 >nul
setlocal enabledelayedexpansion

set SCRIPT_DIR=%~dp0
set CONFIG_DIR=%USERPROFILE%\.opencode-jarvis
set PLUGIN_DIR=%USERPROFILE%\.config\opencode\plugin
set STARTUP_DIR=%APPDATA%\Microsoft\Windows\Start Menu\Programs\Startup

echo === J.A.R.V.I.S. for OpenCode - Installation ===
echo.

:menu
echo.
echo 1. Install / Update
echo 2. Uninstall
echo 3. Exit
echo.
set /p c=Choose (1-3): 
if "%c%"=="1" goto install
if "%c%"=="2" goto uninstall
if "%c%"=="3" exit /b
goto menu

:install
echo [1/4] Installing dependencies...
npm install -g node-edge-tts 2>nul
echo Done.

echo [2/4] Plugin already at %PLUGIN_DIR%
echo       (auto-discovered by OpenCode)

echo [3/4] AutoHotkey tray app...
where ahk.exe >nul 2>&1
if errorlevel 1 (
    echo AutoHotkey not found - install from https://www.autohotkey.com/
    echo or use Ctrl+Alt+J hotkey via another method.
) else (
    echo Copying tray script...
    copy /Y "%SCRIPT_DIR%jarvis-tray.ahk" "%CONFIG_DIR%\jarvis-tray.ahk" >nul
    echo Creating startup shortcut...
    set VBS="%TEMP%\jarvis-shortcut.vbs"
    >%VBS% echo Set WshShell = WScript.CreateObject("WScript.Shell")
    >>%VBS% echo Set lnk = WshShell.CreateShortcut("%STARTUP_DIR%\Jarvis for OpenCode.lnk")
    >>%VBS% echo lnk.TargetPath = "%CONFIG_DIR%\jarvis-tray.ahk"
    >>%VBS% echo lnk.WorkingDirectory = "%CONFIG_DIR%"
    >>%VBS% echo lnk.Description = "J.A.R.V.I.S. voice companion for OpenCode"
    >>%VBS% echo lnk.Save
    cscript //nologo %VBS%
    del %VBS% 2>nul
    echo Done.
)

echo [4/4] Starting tray app...
start "" "%CONFIG_DIR%\jarvis-tray.ahk"

echo.
echo === Installation complete ===
echo  - Ctrl+Alt+J to toggle voice on/off
echo  - Right-click tray icon for menu
echo  - Restart OpenCode to load the plugin
echo.
pause
goto menu

:uninstall
echo Removing startup link...
del "%STARTUP_DIR%\Jarvis for OpenCode.lnk" 2>nul
echo Removing tray script...
del "%CONFIG_DIR%\jarvis-tray.ahk" 2>nul
echo.
echo Plugin file kept at %PLUGIN_DIR%\jarvis-plugin.ts
echo Remove it manually if desired.
echo Config kept at %CONFIG_DIR%
echo Remove manually if desired.
echo.
pause
goto menu
