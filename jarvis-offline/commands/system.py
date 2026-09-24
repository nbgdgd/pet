import os
import sys
import ctypes
import platform
import psutil
import subprocess
import time
import re

_last_cmd = ""

# Распознаватель отдаёт числа СЛОВАМИ ("до тридцать первого"), а не цифрами,
# поэтому одного regex \d+ мало — команда просто не срабатывала.
_DAY_WORDS = {
    "первое": 1, "первого": 1, "второе": 2, "второго": 2, "третье": 3, "третьего": 3,
    "четвертое": 4, "четвёртое": 4, "четвертого": 4, "четвёртого": 4,
    "пятое": 5, "пятого": 5, "шестое": 6, "шестого": 6, "седьмое": 7, "седьмого": 7,
    "восьмое": 8, "восьмого": 8, "девятое": 9, "девятого": 9, "десятое": 10, "десятого": 10,
    "одиннадцатое": 11, "одиннадцатого": 11, "двенадцатое": 12, "двенадцатого": 12,
    "тринадцатое": 13, "тринадцатого": 13, "четырнадцатое": 14, "четырнадцатого": 14,
    "пятнадцатое": 15, "пятнадцатого": 15, "шестнадцатое": 16, "шестнадцатого": 16,
    "семнадцатое": 17, "семнадцатого": 17, "восемнадцатое": 18, "восемнадцатого": 18,
    "девятнадцатое": 19, "девятнадцатого": 19, "двадцатое": 20, "двадцатого": 20,
    "тридцатое": 30, "тридцатого": 30,
    "один": 1, "два": 2, "три": 3, "четыре": 4, "пять": 5, "шесть": 6, "семь": 7,
    "восемь": 8, "девять": 9, "десять": 10, "одиннадцать": 11, "двенадцать": 12,
    "тринадцать": 13, "четырнадцать": 14, "пятнадцать": 15, "шестнадцать": 16,
    "семнадцать": 17, "восемнадцать": 18, "девятнадцать": 19,
}
_TENS_WORDS = {"двадцать": 20, "тридцать": 30}


def _extract_day(text: str):
    """Число из фразы: и цифрами, и словами ('тридцать первого' -> 31)."""
    m = re.search(r"\b(\d{1,2})\b", text or "")
    if m:
        return int(m.group(1))
    words = re.findall(r"[а-яё]+", (text or "").lower())
    for i, w in enumerate(words):
        if w in _TENS_WORDS:
            base = _TENS_WORDS[w]
            nxt = words[i + 1] if i + 1 < len(words) else ""
            add = _DAY_WORDS.get(nxt, 0)
            return base + add if 1 <= add <= 9 else base
        if w in _DAY_WORDS:
            return _DAY_WORDS[w]
    return None


def days_until():
    """
    Сколько дней до указанного числа.

    Прежняя версия падала: `target.replace(month=target.month + 1)` в
    декабре даёт month=13 и выбрасывает ValueError ДО проверки `month > 12`
    строкой ниже — то есть проверка была мёртвым кодом. Плюс replace(day=31)
    в феврале ронял функцию ещё раньше. Теперь месяц перебирается вперёд,
    пока в нём не найдётся такое число.
    """
    import calendar
    import datetime

    day = _extract_day(_last_cmd)
    if day is None:
        return "Не понял, до какого числа считать"
    if not 1 <= day <= 31:
        return f"Числа {day} в месяце не бывает"

    now = datetime.datetime.now()
    today = now.replace(hour=0, minute=0, second=0, microsecond=0)
    year, month = today.year, today.month

    for _ in range(14):                       # максимум год вперёд с запасом
        if day <= calendar.monthrange(year, month)[1]:
            target = datetime.datetime(year, month, day)
            if target > today:
                delta = (target - today).days
                w = "день" if delta % 10 == 1 and delta % 100 != 11 else \
                    "дня" if delta % 10 in (2, 3, 4) and delta % 100 not in (12, 13, 14) else "дней"
                return f"{delta} {w}"
        month += 1
        if month > 12:
            month, year = 1, year + 1
    return f"Не нашёл ближайшее {day}-е число"


def get_system_info():
    ram = round(psutil.virtual_memory().total / (1024**3), 1)
    cpu = platform.processor() or "неизвестный"
    used = round(psutil.virtual_memory().used / (1024**3), 1)
    cpu_percent = psutil.cpu_percent(interval=0.5)
    return f"Процессор {cpu}. Оперативная память {ram} гигабайт, занято {used}. Загрузка процессора {cpu_percent} процентов."


def get_cpu_usage():
    percent = psutil.cpu_percent(interval=1)
    return f"Загрузка процессора {percent} процентов."


def get_ram_usage():
    total = round(psutil.virtual_memory().total / (1024**3), 1)
    used = round(psutil.virtual_memory().used / (1024**3), 1)
    percent = psutil.virtual_memory().percent
    return f"Оперативная память: занято {used} из {total} гигабайт, {percent} процентов."


def get_disk_usage():
    d = psutil.disk_usage("/")
    free = round(d.free / (1024**3), 1)
    total = round(d.total / (1024**3), 1)
    return f"На диске свободно {free} из {total} гигабайт."


def lock_pc():
    ctypes.windll.user32.LockWorkStation()


def exit_assistant():
    sys.exit(0)


def _run(args):
    """Без os.system: тот поднимает лишний cmd.exe и мигает окном."""
    from commands.winlaunch import run_hidden
    return run_hidden(args, timeout=10)


def shutdown_pc():
    _run(["shutdown", "/s", "/t", "20"])
    return "Выключение через двадцать секунд. Скажите «отмени выключение», чтобы прервать"


def cancel_shutdown():
    _run(["shutdown", "/a"])
    return "Выключение отменено"


def restart_pc():
    _run(["shutdown", "/r", "/t", "20"])
    return "Перезагрузка через двадцать секунд. Скажите «отмени выключение», чтобы прервать"


def sleep_pc():
    _run(["rundll32.exe", "powrprof.dll,SetSuspendState", "0,1,0"])


def hibernate_pc():
    ctypes.windll.powrprof.SetSuspendState(0, 0, 0)


def log_off():
    _run(["shutdown", "/l"])


# Приложения, которые в игровом режиме закрываются всегда.
GAMING_TARGETS = {
    "spotify.exe", "discord.exe", "slack.exe", "teams.exe", "ms-teams.exe",
    "onedrive.exe", "dropbox.exe", "googledrivefs.exe", "yandexdisk.exe",
    "steamwebhelper.exe", "epicgameslauncher.exe", "eadesktop.exe",
    "battle.net.exe", "gog galaxy.exe", "ubisoftconnect.exe",
    "obs64.exe", "obs32.exe", "adobe desktop service.exe", "creative cloud.exe",
    "acrotray.exe", "skype.exe", "zoom.exe", "notion.exe", "obsidian.exe",
    "figma.exe", "postman.exe", "docker desktop.exe", "com.docker.backend.exe",
    "javaw.exe", "msedgewebview2.exe", "widgets.exe", "gamebar.exe",
    "phoneexperiencehost.exe", "yourphone.exe", "searchapp.exe",
}

# Никогда не трогать: ядро сессии, звук, оболочка, свои процессы.
GAMING_PROTECT = {
    "explorer.exe", "dwm.exe", "csrss.exe", "wininit.exe", "winlogon.exe",
    "services.exe", "lsass.exe", "svchost.exe", "smss.exe", "audiodg.exe",
    "ctfmon.exe", "conhost.exe", "fontdrvhost.exe", "sihost.exe",
    "runtimebroker.exe", "shellexperiencehost.exe", "startmenuexperiencehost.exe",
    "textinputhost.exe", "searchhost.exe", "taskmgr.exe", "python.exe",
    "pythonw.exe", "jarvis.exe", "windowsterminal.exe", "openconsole.exe",
    "telegram.exe", "chrome.exe", "firefox.exe", "msedge.exe", "fxsound.exe",
    "code.exe", "cmd.exe", "powershell.exe", "pwsh.exe",
}

HEAVY_RSS_MB = 300          # «прочее тяжёлое» — от 300 МБ


def gaming_mode(include_heavy=True):
    """
    Освободить ресурсы под игру.

    Прежняя версия завершала ВСЁ, чего не было в коротком белом списке —
    на этой машине это около двух третей всех процессов, включая сам
    JARVIS, службы звука и антивирус. Теперь наоборот: закрывается только
    явный список фоновых приложений плюс, по желанию, пользовательские
    процессы тяжелее 300 МБ. Системные каталоги, чужие сессии и
    собственное дерево процессов не трогаются никогда.
    """
    import gc
    import os as _os

    from commands import winproc

    own = winproc.own_pid_tree()
    sysroot = _os.environ.get("SystemRoot", "C:\\Windows").lower()
    try:
        me = psutil.Process().username()
    except Exception:
        me = None

    victims = []
    for p in psutil.process_iter(["pid", "name", "exe", "username", "memory_info"]):
        try:
            info = p.info
            name = (info["name"] or "").lower()
            if not name or p.pid in own or p.pid < 100:
                continue
            if name in GAMING_PROTECT:
                continue
            if me and info.get("username") and info["username"] != me:
                continue
            exe = (info.get("exe") or "").lower()
            if exe.startswith(sysroot):
                continue
            if name in GAMING_TARGETS:
                victims.append(p)
                continue
            if include_heavy and info.get("memory_info"):
                if info["memory_info"].rss > HEAVY_RSS_MB * 1024 * 1024:
                    victims.append(p)
        except (psutil.NoSuchProcess, psutil.AccessDenied):
            continue

    names = sorted({(p.info["name"] or "?") for p in victims})
    for p in victims:
        try:
            p.terminate()
        except Exception:
            pass
    gone, alive = psutil.wait_procs(victims, timeout=3.0)
    for p in alive:
        try:
            p.kill()
        except Exception:
            pass
    gc.collect()
    print(f"[GAMING] закрыто: {', '.join(names) or '—'}", flush=True)
    return f"Игровой режим: закрыл {len(gone) + len(alive)} приложений"
