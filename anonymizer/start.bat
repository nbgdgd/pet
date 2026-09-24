@echo off
chcp 65001 >nul 2>&1
title Anonymizer (Full)

net session >nul 2>&1
if %errorlevel% neq 0 (
    echo [*] Требуются права администратора. Запускаю от имени администратора...
    powershell -Command "Start-Process '%~f0' -Verb RunAs"
    exit /b
)

set "SCRIPT_DIR=%~dp0"
set "HOSTS=%SystemRoot%\System32\drivers\etc\hosts"
set "MARKER=# ANONYMIZER_ADDED"
set "DOMAIN=vkvideo.ru"
set "PORT=80"

netstat -ano | findstr ":80 " | findstr "LISTENING" >nul 2>&1
if %errorlevel% equ 0 (
    echo.
    echo [!] Порт 80 уже занят.
    echo.
    set /p "USE8080=[?] Запустить на порту 8080? (y/n): "
    if /i "%USE8080%"=="y" (
        set "PORT=8080"
    ) else (
        pause
        exit /b 1
    )
)

echo.
echo [*] Установка зависимостей...
pip install -r "%SCRIPT_DIR%requirements.txt" --quiet 2>nul
if %errorlevel% neq 0 (
    echo [!] Ошибка. Убедись, что Python в PATH.
    pause
    exit /b 1
)
echo [+] Зависимости установлены.

if "%PORT%"=="80" (
    findstr /c:"%MARKER%" "%HOSTS%" >nul 2>&1
    if %errorlevel% neq 0 (
        echo 127.0.0.1    %DOMAIN% %MARKER%>> "%HOSTS%"
        echo [+] Добавлено в hosts: 127.0.0.1 %DOMAIN%
    )
)

echo.
echo ============================================
echo   Открой: http://%DOMAIN%:%PORT%
echo   Для выхода нажми Ctrl+C
echo ============================================
echo.

python "%SCRIPT_DIR%app.py" %PORT%

if "%PORT%"=="80" (
    echo [*] Очистка hosts...
    powershell -Command "(Get-Content '%HOSTS%') | Where-Object { $_ -notmatch '%MARKER%' } | Set-Content '%HOSTS%'"
    echo [+] hosts очищен.
)
pause
