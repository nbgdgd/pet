@echo off
chcp 65001 >nul 2>&1
title Anonymizer
cd /d "%~dp0"

pip install -r requirements.txt --quiet 2>nul

echo.
echo ============================================
echo   Открой: http://localhost:8080
echo   Для выхода нажми Ctrl+C
echo   Или закрой это окно
echo ============================================
echo.

start "" http://localhost:8080
python app.py 8080
