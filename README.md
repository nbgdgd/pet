# J.A.R.V.I.S. — голосовой компаньон для OpenCode

Озвучивает действия в реальном времени и итоги задач, используя **бесплатные** нейросетевые голоса Microsoft Edge. Без API-ключей, без платежей.

## Состав

| Файл | Назначение |
|------|-----------|
| `%USERPROFILE%\.config\opencode\plugin\jarvis-plugin.ts` | Плагин OpenCode (Bun/TypeScript) |
| `%USERPROFILE%\.config\opencode\node_modules\node-edge-tts` | Движок TTS (Microsoft Edge voices) |
| `%USERPROFILE%\.opencode-jarvis\config.json` | Конфиг: включение + голос |
| `%USERPROFILE%\.opencode-jarvis\jarvis-tray.ahk` | Трей + горячая клавиша |
| `install-jarvis.bat` | Скрипт установки |

## Установка

### 1. Установите зависимости

```powershell
# AutoHotkey (для трея)
winget install AutoHotkey.AutoHotkey

# Node.js (если нет) — https://nodejs.org/
```

### 2. Поставьте плагин

```powershell
# 2a. Папка плагина
mkdir $env:USERPROFILE\.config\opencode\plugin -Force

# 2b. Скопируйте jarvis-plugin.ts туда
copy jarvis-plugin.ts $env:USERPROFILE\.config\opencode\plugin\

# 2c. Установите TTS-движок
cd $env:USERPROFILE\.config\opencode
npm install node-edge-tts@1.2.10
```

### 3. Добавьте в opencode.jsonc

```json
"plugin": ["./plugin/jarvis-plugin.ts"]
```

### 4. Запустите трей (опционально)

Запустите `jarvis-tray.ahk` или `install-jarvis.bat`.

### 5. Перезапустите OpenCode

## Использование

| Действие | Результат |
|----------|-----------|
| `Ctrl+Alt+J` | Вкл/Выкл озвучку |
| Трей → Toggle | Вкл/Выкл |
| Трей → Change Voice | Сменить голос |
| `%USERPROFILE%\.opencode-jarvis\config.json` | Ручная правка |

## Голоса

| Код | Описание |
|-----|----------|
| `en-GB-RyanNeural` | Британский муж. (умолчание, похож на JARVIS) |
| `en-GB-SoniaNeural` | Британский жен. |
| `en-US-GuyNeural` | Американский муж. |
| `ru-RU-DmitryNeural` | Русский муж. |
| `ru-RU-SvetlanaNeural` | Русский жен. |

## Как это работает

```
tool.execute.before → "Starting Read file"
     ↓
tool.execute.after  → "Read file complete"
     ↓
session.idle (event) → "Task complete. Changed 3 files..."
     ↓
TTSQueue (не даёт фразам накладываться)
     ↓
node-edge-tts → MP3 → PowerShell MediaPlayer
  (если упал) → Windows SAPI (offline fallback)
```

## Удаление

```powershell
# Убрать из opencode.jsonc строку "plugin"
# Удалить файлы:
rm $env:USERPROFILE\.config\opencode\plugin\jarvis-plugin.ts
rm $env:USERPROFILE\.config\opencode\node_modules\node-edge-tts -Recurse
rm $env:USERPROFILE\.opencode-jarvis -Recurse
```
