@echo off
chcp 65001 >nul
cd /d "%~dp0"
echo ========================================
echo   JARVIS Offline - Assistant
echo ========================================
echo.
echo  py main.py          - Запуск ассистента
echo  py main.py -l       - Список микрофонов
echo  py main.py -t       - Тест микрофона
echo  py debug.py         - Полная диагностика
echo.
echo ========================================
py main.py %*
pause
