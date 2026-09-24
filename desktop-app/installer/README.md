# Установщик AniBlaze

    powershell -ExecutionPolicy Bypass -File desktop-app\installeruild-installer.ps1

Собирает `dist\AniBlaze-Setup-1.0.2.exe` — один файл (~134 МБ) с образом приложения
внутри. Ставит в `%LOCALAPPDATA%\Programs\AniBlaze`, прав администратора не требует.

Состав:

* `build-installer.ps1` — сборка: gradle-задача `createReleaseDistributable`, упаковка
  образа в zip (7-Zip, если есть, иначе встроенный упаковщик) и компиляция `Setup.cs`
  компилятором C# из .NET Framework 4.8 — образ и деинсталлятор ложатся в exe ресурсами.
* `Setup.cs` — само окно установки: папка, ярлыки (рабочий стол, «Пуск»), запуск после
  установки, запись в «Установка и удаление программ». Тихий режим для проверок:
  `AniBlaze-Setup-1.0.2.exe /silent /dir <папка> [/noshortcuts] [/noregister] [/launch]`.
* `uninstall.ps1` — кладётся рядом с приложением, вызывается из «Установка и удаление
  программ». Удаляет ТОЛЬКО свою установку: ярлык сносится, если ведёт внутрь этой
  папки, ключ реестра — если его `InstallLocation` совпадает. Настройки
  (`%APPDATA%\AniBlaze`) остаются; `-Purge` удаляет и их.

Почему не `jpackage --type exe`: ему нужен WiX Toolset, которого на машине нет.
Почему не самораспаковка 7-Zip: модуль `7z.sfx` из обычной поставки запускать
установку не умеет (`RunProgram` игнорируется, проверено), а установочный `7zS.sfx`
идёт только в отдельном пакете LZMA SDK.
