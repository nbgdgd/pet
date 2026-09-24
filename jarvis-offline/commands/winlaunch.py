"""
Запуск чего угодно в Windows без мигающего окна cmd.

Правила:
  * .exe и прочие бинарники   -> subprocess.Popen(..., DETACHED_PROCESS)
  * папки, документы, URI     -> os.startfile (ShellExecute)
  * UWP / Microsoft Store     -> explorer.exe shell:AppsFolder\\<AUMID>
  * консольные утилиты        -> отдельное окно cmd (это осознанно)

os.system("start ...") больше не используется нигде: он поднимает
промежуточный cmd.exe (мигает окно), ломается на путях с пробелами
и не даёт узнать, запустилось ли вообще.
"""
import os
import shlex
import subprocess
import sys

CREATE_NO_WINDOW = 0x08000000
DETACHED_PROCESS = 0x00000008
CREATE_NEW_CONSOLE = 0x00000010
CREATE_NEW_PROCESS_GROUP = 0x00000200

_IS_FROZEN = getattr(sys, "frozen", False)


def _log(msg):
    print(f"[LAUNCH] {msg}", flush=True)


def run_hidden(args, timeout=15):
    """Служебный вызов (powershell/reg/...) без появления окна."""
    try:
        return subprocess.run(
            args,
            capture_output=True,
            text=True,
            timeout=timeout,
            creationflags=CREATE_NO_WINDOW,
            encoding="utf-8",
            errors="replace",
        )
    except Exception as e:
        _log(f"run_hidden failed {args[:2]}: {e}")
        return None


_PS_EXE = None


def ps_exe():
    """pwsh 7, если стоит, иначе встроенный powershell 5.1."""
    global _PS_EXE
    if _PS_EXE is None:
        import shutil
        _PS_EXE = shutil.which("pwsh") or "powershell"
    return _PS_EXE


def powershell(script, timeout=20):
    """Выполнить PowerShell-скрипт скрыто. Возвращает stdout (str) или ''."""
    r = run_hidden(
        [ps_exe(), "-NoProfile", "-NonInteractive",
         "-ExecutionPolicy", "Bypass", "-Command", script],
        timeout=timeout,
    )
    if r is None:
        return ""
    if r.returncode != 0 and r.stderr:
        _log(f"powershell rc={r.returncode}: {r.stderr.strip()[:200]}")
    return (r.stdout or "").strip()


def powershell_capture(script, timeout=60):
    """
    PowerShell, результат которого пишется во временный UTF-8 файл.

    Через stdout нельзя: Windows PowerShell 5.1 отдаёт вывод в OEM-кодировке
    (cp866), кириллица приходит битой и JSON перестаёт разбираться.
    Файл с явным -Encoding utf8 такой проблемы не имеет.
    """
    import tempfile
    fd, out_path = tempfile.mkstemp(suffix=".json", prefix="jarvis_ps_")
    os.close(fd)
    fd, ps_path = tempfile.mkstemp(suffix=".ps1", prefix="jarvis_ps_")
    os.close(fd)
    body = (
        "$JarvisOut = " + _ps_quote(out_path) + "\n"
        "$ErrorActionPreference = 'SilentlyContinue'\n"
        + script + "\n"
    )
    try:
        with open(ps_path, "w", encoding="utf-8-sig") as f:
            f.write(body)
        run_hidden(
            [ps_exe(), "-NoProfile", "-NonInteractive",
             "-ExecutionPolicy", "Bypass", "-File", ps_path],
            timeout=timeout,
        )
        with open(out_path, "r", encoding="utf-8-sig") as f:
            return f.read().strip()
    except Exception as e:
        _log(f"powershell_capture: {e}")
        return ""
    finally:
        for p in (ps_path, out_path):
            try:
                os.unlink(p)
            except OSError:
                pass


def _ps_quote(s):
    return "'" + s.replace("'", "''") + "'"


def start_exe(path, args=None, cwd=None):
    """Запустить бинарник отсоединённо от нашего процесса."""
    cmd = [path] + list(args or [])
    try:
        subprocess.Popen(
            cmd,
            cwd=cwd or os.path.dirname(path) or None,
            creationflags=DETACHED_PROCESS | CREATE_NEW_PROCESS_GROUP,
            close_fds=True,
            stdin=subprocess.DEVNULL,
            stdout=subprocess.DEVNULL,
            stderr=subprocess.DEVNULL,
        )
        _log(f"exe: {path}")
        return True
    except Exception as e:
        _log(f"exe failed {path}: {e}")
        return False


def start_shell(target):
    """
    Папка, документ, .lnk или URI (ms-settings:, http://).
    ShellExecute — то же, что двойной клик.
    """
    try:
        os.startfile(target)
        _log(f"shell: {target}")
        return True
    except Exception as e:
        _log(f"shell failed {target}: {e}")
        return False


def start_uwp(aumid):
    """Запустить Store-приложение по AppUserModelID."""
    try:
        subprocess.Popen(
            ["explorer.exe", f"shell:AppsFolder\\{aumid}"],
            creationflags=DETACHED_PROCESS,
            close_fds=True,
            stdin=subprocess.DEVNULL,
            stdout=subprocess.DEVNULL,
            stderr=subprocess.DEVNULL,
        )
        _log(f"uwp: {aumid}")
        return True
    except Exception as e:
        _log(f"uwp failed {aumid}: {e}")
        return False


def start_console(command, keep_open=True):
    """Открыть новое окно консоли и выполнить в нём команду."""
    flag = "/k" if keep_open else "/c"
    try:
        subprocess.Popen(
            ["cmd", flag, command],
            creationflags=CREATE_NEW_CONSOLE,
            close_fds=True,
        )
        _log(f"console: {command}")
        return True
    except Exception as e:
        _log(f"console failed {command}: {e}")
        return False


def launch(entry):
    """
    Универсальный запуск записи каталога приложений.
    entry = {"kind": "exe"|"uwp"|"shell"|"console"|"uri", "target": ..., "args": [...]}
    """
    kind = entry.get("kind", "shell")
    target = entry.get("target", "")
    args = entry.get("args") or []
    if not target:
        return False
    if kind == "exe":
        if os.path.isfile(target):
            return start_exe(target, args)
        return start_shell(target)
    if kind == "uwp":
        return start_uwp(target)
    if kind == "console":
        return start_console(target)
    return start_shell(target)


def which(*names):
    """shutil.which по нескольким именам + .exe."""
    import shutil
    for n in names:
        if not n:
            continue
        p = shutil.which(n)
        if p:
            return p
        if not n.lower().endswith(".exe"):
            p = shutil.which(n + ".exe")
            if p:
                return p
    return None
