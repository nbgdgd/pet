@echo off
chcp 65001 >nul 2>&1
title Anonymizer

:: ─── Check admin ──────────────────────────────────────────────
net session >nul 2>&1
if %errorlevel% neq 0 (
    echo [*] Требуются права администратора. Запускаю от имени администратора...
    powershell -Command "Start-Process '%~f0' -Verb RunAs"
    exit /b
)

:: ─── Vars ─────────────────────────────────────────────────────
set "SCRIPT_DIR=%~dp0"
set "HOSTS=%SystemRoot%\System32\drivers\etc\hosts"
set "MARKER=# ANONYMIZER_ADDED"
set "DOMAIN=vkvideo.ru"
set "PORT=80"

:: ─── Check if port 80 is free ────────────────────────────────
netstat -ano | findstr ":80 " | findstr "LISTENING" >nul 2>&1
if %errorlevel% equ 0 (
    echo.
    echo [!] Порт 80 уже занят. Проверь Apache/IIS/другой сервис.
    echo.
    set /p "USE8080=[?] Запустить на порту 8080 вместо этого? (y/n): "
    if /i "%USE8080%"=="y" (
        set "PORT=8080"
    ) else (
        echo Выход.
        pause
        exit /b 1
    )
)

:: ─── Install dependencies ─────────────────────────────────────
echo.
echo [*] Устанавливаю зависимости...
pip install -r "%SCRIPT_DIR%requirements.txt" --quiet 2>nul
if %errorlevel% neq 0 (
    echo [!] Ошибка установки зависимостей.
    echo     Убедись, что Python и pip установлены и добавлены в PATH.
    pause
    exit /b 1
)
echo [+] Зависимости установлены.

:: ─── Add hosts entry (only for port 80) ─────────────────────
if "%PORT%"=="80" (
    findstr /c:"%MARKER%" "%HOSTS%" >nul 2>&1
    if %errorlevel% neq 0 (
        echo.
        echo 127.0.0.1    %DOMAIN% %MARKER%>> "%HOSTS%"
        echo [+] Добавлено в hosts: 127.0.0.1 %DOMAIN%
    ) else (
        echo [+] Запись уже есть в hosts.
    )
)

:: ─── Run ──────────────────────────────────────────────────────
echo.
echo ============================================
echo   Открой в браузере: http://%DOMAIN%:%PORT%
echo   Для выхода нажми Ctrl+C
echo ============================================
echo.
python "%SCRIPT_DIR%app.py" %PORT%

:: ─── Cleanup hosts on exit ───────────────────────────────────
if "%PORT%"=="80" (
    echo.
    echo [*] Очищаю hosts...
    powershell -Command "(Get-Content '%HOSTS%') | Where-Object { $_ -notmatch '%MARKER%' } | Set-Content '%HOSTS%'"
    echo [+] hosts очищен.
)

pause
