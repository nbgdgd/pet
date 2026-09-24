"""
Открытие и закрытие приложений.

Было:
  * запуск через os.system("start X") — мигает окно cmd, ломается на путях
    с пробелами, невозможно узнать, запустилось ли;
  * закрытие через taskkill /IM X /F — грубо (потеря данных), не работает
    для UWP, а "закрой cmd" убивал собственную консоль JARVIS.

Стало:
  * запуск  -> commands/winlaunch.py (Popen DETACHED / ShellExecute / AppsFolder);
  * закрытие -> commands/winproc.py (WM_CLOSE, потом terminate, своё дерево
    процессов исключено);
  * если приложения нет в списке ниже — работает каталог Windows
    (commands/app_index.py) через команду "открой <что угодно>".
"""
import os

from commands import winproc
from commands.winlaunch import (
    powershell, start_console, start_exe, start_shell, start_uwp, which,
)

USER = os.environ.get("USERPROFILE", os.path.expanduser("~"))


def _log(msg):
    print(f"[APPS] {msg}", flush=True)


# ---------------------------------------------------------------- поиск exe

def _known_paths(name):
    local = os.path.expandvars("%LOCALAPPDATA%")
    prog = os.path.expandvars("%ProgramFiles%")
    prog86 = os.path.expandvars("%ProgramFiles(x86)%")
    appdata = os.path.expandvars("%APPDATA%")
    paths = {
        "telegram.exe": [
            f"{local}\\Telegram Desktop\\Telegram.exe",
            f"{prog}\\Telegram Desktop\\Telegram.exe",
        ],
        "discord.exe": [
            f"{local}\\Discord\\Update.exe",
            f"{local}\\Discord\\app-*\\Discord.exe",
        ],
        "spotify.exe": [
            f"{appdata}\\Spotify\\Spotify.exe",
            f"{prog}\\Spotify\\Spotify.exe",
        ],
        "code.exe": [
            f"{local}\\Programs\\Microsoft VS Code\\Code.exe",
            f"{prog}\\Microsoft VS Code\\Code.exe",
        ],
        "firefox.exe": [
            f"{prog}\\Mozilla Firefox\\firefox.exe",
            f"{prog86}\\Mozilla Firefox\\firefox.exe",
        ],
        "chrome.exe": [
            f"{prog}\\Google\\Chrome\\Application\\chrome.exe",
            f"{prog86}\\Google\\Chrome\\Application\\chrome.exe",
            f"{local}\\Google\\Chrome\\Application\\chrome.exe",
        ],
        "msedge.exe": [
            f"{prog86}\\Microsoft\\Edge\\Application\\msedge.exe",
            f"{prog}\\Microsoft\\Edge\\Application\\msedge.exe",
        ],
    }
    for p in paths.get(name.lower(), []):
        if "*" in p:
            import glob
            hits = sorted(glob.glob(p))
            if hits:
                return hits[-1]          # самая свежая версия app-*
        elif os.path.isfile(p):
            return p
    return None


def _registry_path(name):
    out = powershell(
        "$n='" + name.replace("'", "''") + "';"
        "foreach($r in 'HKLM:','HKCU:'){"
        "$p=\"$r\\SOFTWARE\\Microsoft\\Windows\\CurrentVersion\\App Paths\\$n\";"
        "$v=(Get-ItemProperty -Path $p -EA SilentlyContinue).'(default)';"
        "if($v){$v=$v.Trim('\"'); if(Test-Path $v){Write-Output $v; break}}}",
        timeout=10,
    )
    out = out.splitlines()[0].strip() if out else ""
    return out if out and os.path.isfile(out) else None


def _launch_app(exe_name, display_name=None, args=None):
    """
    Найти и запустить программу. Порядок: PATH -> известные пути ->
    реестр App Paths -> каталог Windows (Start menu / UWP).
    """
    dn = display_name or (exe_name[:-4] if exe_name.lower().endswith(".exe") else exe_name)

    for finder in (lambda: which(exe_name), lambda: _known_paths(exe_name), lambda: _registry_path(exe_name)):
        try:
            path = finder()
        except Exception as e:
            _log(f"поиск {exe_name}: {e}")
            path = None
        if path and start_exe(path, args):
            return f"Открываю {dn}"

    from commands.app_index import INDEX
    entry, score = INDEX.open(dn)
    if entry:
        return f"Открываю {entry['name']}"

    _log(f"не нашёл {exe_name}")
    return f"Не нашёл {dn}"


def _close(display, *procs):
    closed, total = winproc.close_app(*procs)
    if total == 0:
        return f"{display} и так не запущен"
    if closed:
        return f"Закрываю {display}"
    return f"Не смог закрыть {display}"


# ------------------------------------------------------------------ браузеры

def open_browser():
    return open_edge()


def open_firefox():
    return _launch_app("firefox.exe", "Firefox")


def open_chrome():
    return _launch_app("chrome.exe", "Chrome")


def open_edge():
    return _launch_app("msedge.exe", "Edge")


def close_firefox():
    return _close("Firefox", "firefox.exe")


def close_chrome():
    return _close("Chrome", "chrome.exe")


def close_edge():
    return _close("Edge", "msedge.exe")


# --------------------------------------------------------------- приложения

def open_telegram():
    return _launch_app("Telegram.exe", "Telegram")


def open_discord():
    return _launch_app("Discord.exe", "Discord")


def open_spotify():
    return _launch_app("Spotify.exe", "Spotify")


def open_vscode():
    return _launch_app("Code.exe", "VS Code")


def open_opencode():
    start_console("opencode")
    return "Запускаю opencode"


def close_telegram():
    return _close("Telegram", "Telegram.exe", "AyuGram.exe")


def close_discord():
    return _close("Discord", "Discord.exe")


def close_spotify():
    return _close("Spotify", "Spotify.exe")


def close_vscode():
    return _close("VS Code", "Code.exe")


def close_notepad():
    # Win11: классический notepad.exe, магазинная версия — Notepad.exe там же
    return _close("блокнот", "notepad.exe")


def close_calculator():
    # Калькулятор в Win11 — UWP, процесс называется CalculatorApp.exe,
    # а не calculator.exe, поэтому старый taskkill не срабатывал никогда.
    return _close("калькулятор", "CalculatorApp.exe", "Calculator.exe",
                  "Microsoft.WindowsCalculator.exe")


def close_cmd():
    # winproc исключает собственное дерево процессов, поэтому консоль,
    # из которой запущен JARVIS, не будет убита вместе с остальными.
    return _close("командную строку", "cmd.exe", "WindowsTerminal.exe")


def close_powershell():
    return _close("PowerShell", "powershell.exe", "pwsh.exe")


# ------------------------------------------------------------------ система

def open_notepad():
    return _launch_app("notepad.exe", "блокнот")


def open_calculator():
    if start_uwp("Microsoft.WindowsCalculator_8wekyb3d8bbwe!App"):
        return "Открываю калькулятор"
    return _launch_app("calc.exe", "калькулятор")


def open_cmd():
    start_console("", keep_open=True)
    return "Открываю командную строку"


def open_powershell():
    p = which("pwsh", "powershell")
    if p:
        start_exe(p)
        return "Открываю PowerShell"
    return "PowerShell не найден"


def open_task_manager():
    return _launch_app("taskmgr.exe", "диспетчер задач")


def open_run_dialog():
    powershell("(New-Object -ComObject Shell.Application).FileRun()", timeout=5)
    return "Окно «Выполнить»"


def show_desktop():
    powershell("(New-Object -ComObject Shell.Application).MinimizeAll()", timeout=5)
    return "Свернул все окна"


def screenshot():
    import datetime
    import PIL.ImageGrab
    pics = os.path.join(USER, "Pictures", "Screenshots")
    os.makedirs(pics, exist_ok=True)
    path = os.path.join(pics, f"screenshot_{datetime.datetime.now():%Y%m%d_%H%M%S}.png")
    PIL.ImageGrab.grab(all_screens=True).save(path)
    return "Скриншот сохранён"


def open_snipping_tool():
    if start_uwp("Microsoft.ScreenSketch_8wekyb3d8bbwe!App"):
        return "Открываю ножницы"
    return _launch_app("SnippingTool.exe", "ножницы")


def free_ram():
    """
    Сбросить рабочие наборы процессов (EmptyWorkingSet).

    Раньше открывался хендл с PROCESS_ALL_ACCESS (0x1F0FFF) — избыточные
    права, отказ на половине системных процессов. Нужны только
    QUERY_INFORMATION | SET_QUOTA.
    """
    import ctypes
    import gc
    import psutil

    PROCESS_QUERY_LIMITED_INFORMATION = 0x1000
    PROCESS_SET_QUOTA = 0x0100

    gc.collect()
    k32 = ctypes.windll.kernel32
    count = 0
    for p in psutil.process_iter(["pid"]):
        h = None
        try:
            h = k32.OpenProcess(
                PROCESS_QUERY_LIMITED_INFORMATION | PROCESS_SET_QUOTA, False, p.info["pid"]
            )
            if h and k32.SetProcessWorkingSetSize(h, -1, -1):
                count += 1
        except Exception:
            pass
        finally:
            if h:
                k32.CloseHandle(h)          # хендл закрывается всегда, а не только при успехе
    return f"Освободил память, обработано {count} процессов"


# ----------------------------------------------------------------- проводник

def open_explorer():
    """Открыть окно «Этот компьютер»."""
    start_shell("shell:MyComputerFolder")
    return "Открываю проводник"


def close_explorer():
    """
    Закрыть окна проводника, НЕ убивая процесс explorer.exe
    (иначе исчезает панель задач и рабочий стол).
    """
    hwnds = winproc.windows_by_class("CabinetWClass")
    hwnds += winproc.windows_by_class("ExploreWClass")
    n = winproc.close_windows(hwnds)
    return f"Закрыл окон проводника: {n}" if n else "Проводник не открыт"


def _open_folder(path, label):
    if not os.path.isdir(path):
        return f"Папка {label} не найдена"
    start_shell(path)
    return f"Открываю {label}"


def open_downloads():
    return _open_folder(os.path.join(USER, "Downloads"), "загрузки")


def open_documents():
    return _open_folder(os.path.join(USER, "Documents"), "документы")


def open_desktop_folder():
    return _open_folder(os.path.join(USER, "Desktop"), "рабочий стол")


def open_pictures():
    return _open_folder(os.path.join(USER, "Pictures"), "изображения")


def open_music():
    return _open_folder(os.path.join(USER, "Music"), "музыку")


def open_videos():
    return _open_folder(os.path.join(USER, "Videos"), "видео")


def open_recycle_bin():
    start_shell("shell:RecycleBinFolder")
    return "Открываю корзину"


def empty_recycle_bin():
    powershell("Clear-RecycleBin -Force -ErrorAction SilentlyContinue", timeout=30)
    return "Корзина очищена"


# ---------------------------------------------------- параметры и оснастки

def _uri(target, label):
    start_shell(target)
    return f"Открываю {label}"


def _mmc(tool, label):
    return _launch_app(tool, label) if tool.lower().endswith(".exe") else _uri(tool, label)


def open_settings():
    return _uri("ms-settings:", "настройки")


def open_control_panel():
    return _launch_app("control.exe", "панель управления")


def open_device_manager():
    start_exe(os.path.join(os.environ.get("SystemRoot", "C:\\Windows"), "System32", "mmc.exe"),
              ["devmgmt.msc"])
    return "Открываю диспетчер устройств"


def open_task_scheduler():
    start_exe(os.path.join(os.environ.get("SystemRoot", "C:\\Windows"), "System32", "mmc.exe"),
              ["taskschd.msc"])
    return "Открываю планировщик заданий"


def open_services():
    start_exe(os.path.join(os.environ.get("SystemRoot", "C:\\Windows"), "System32", "mmc.exe"),
              ["services.msc"])
    return "Открываю службы"


def open_registry_editor():
    return _launch_app("regedit.exe", "редактор реестра")


def open_environment_variables():
    return _launch_app("SystemPropertiesAdvanced.exe", "переменные среды")


def open_disk_cleanup():
    return _launch_app("cleanmgr.exe", "очистку диска")


def open_defragment():
    return _launch_app("dfrgui.exe", "дефрагментацию")


def open_mouse_settings():
    return _uri("ms-settings:mousetouchpad", "настройки мыши")


def open_network_settings():
    return _uri("ms-settings:network", "настройки сети")


def open_wifi_settings():
    return _uri("ms-settings:network-wifi", "настройки Wi-Fi")


def open_bluetooth():
    return _uri("ms-settings:bluetooth", "Bluetooth")


def open_sound_settings():
    return _uri("ms-settings:sound", "настройки звука")


def open_about_pc():
    return _uri("ms-settings:about", "сведения о системе")


def open_region_language():
    return _uri("ms-settings:regionlanguage", "язык и регион")


def open_keyboard_settings():
    return _uri("ms-settings:typing", "настройки клавиатуры")


def open_date_time_settings():
    return _uri("ms-settings:dateandtime", "дату и время")


def open_power_settings():
    return _uri("ms-settings:powersleep", "настройки питания")


def open_storage_settings():
    return _uri("ms-settings:storagesense", "хранилище")


def open_apps_settings():
    return _uri("ms-settings:appsfeatures", "приложения и возможности")


def open_gaming_settings():
    return _uri("ms-settings:gaming-gamebar", "игровые настройки")


def open_privacy_settings():
    return _uri("ms-settings:privacy", "конфиденциальность")


def open_update_settings():
    return _uri("ms-settings:windowsupdate", "центр обновления")


def open_activation_settings():
    return _uri("ms-settings:activation", "активацию")


def open_troubleshoot():
    return _uri("ms-settings:troubleshoot", "устранение неполадок")


def open_multitasking():
    return _uri("ms-settings:multitasking", "многозадачность")


def open_taskbar_settings():
    return _uri("ms-settings:taskbar", "настройки панели задач")


def open_notification_settings():
    return _uri("ms-settings:notifications", "настройки уведомлений")
