@echo off
chcp 65001 >nul 2>&1
title Cleanup

net session >nul 2>&1
if %errorlevel% neq 0 (
    powershell -Command "Start-Process '%~f0' -Verb RunAs"
    exit /b
)

set "HOSTS=%SystemRoot%\System32\drivers\etc\hosts"
set "MARKER=# ANONYMIZER_ADDED"

echo [*] Удаляю запись из hosts...
powershell -Command "(Get-Content '%HOSTS%') | Where-Object { $_ -notmatch '%MARKER%' } | Set-Content '%HOSTS%'"
echo [+] Готово.
pause
