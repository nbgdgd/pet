"""
Корректное закрытие приложений в Windows.

Почему не `taskkill /IM name.exe /F`:
  * /F убивает без сохранения — Telegram/VS Code теряют состояние;
  * не работает для UWP (Калькулятор, Параметры) — там процесс
    называется иначе, чем думает пользователь;
  * у Chrome/Discord десятки процессов с одним именем, /F по имени
    валит и фоновые хелперы;
  * `taskkill /IM cmd.exe /F` убивает СОБСТВЕННУЮ консоль JARVIS,
    если он запущен из cmd — ассистент вылетает вместе с целью.

Стратегия close_app():
  1. найти PID-ы по имени процесса (psutil), исключив своё дерево;
  2. послать WM_CLOSE всем видимым верхнеуровневым окнам этих PID —
     приложение закрывается штатно, с сохранением;
  3. подождать grace-период;
  4. кто не ушёл — psutil.terminate(), затем kill().
"""
import ctypes
import ctypes.wintypes as wt
import os
import time

import psutil

user32 = ctypes.windll.user32
kernel32 = ctypes.windll.kernel32

WM_CLOSE = 0x0010
WM_QUIT = 0x0012

_EnumWindowsProc = ctypes.WINFUNCTYPE(wt.BOOL, wt.HWND, wt.LPARAM)

_OWN_PIDS = None


def _log(msg):
    print(f"[PROC] {msg}", flush=True)


def own_pid_tree():
    """PID самого JARVIS и всех его родителей — их трогать нельзя."""
    global _OWN_PIDS
    if _OWN_PIDS is not None:
        return _OWN_PIDS
    pids = set()
    try:
        p = psutil.Process(os.getpid())
        pids.add(p.pid)
        for parent in p.parents():
            pids.add(parent.pid)
        for child in p.children(recursive=True):
            pids.add(child.pid)
    except Exception:
        pids.add(os.getpid())
    _OWN_PIDS = pids
    return pids


def find_pids(*names):
    """PID-ы процессов с указанными именами (без учёта регистра)."""
    wanted = {n.lower() for n in names if n}
    own = own_pid_tree()
    out = []
    for p in psutil.process_iter(["pid", "name"]):
        try:
            n = (p.info["name"] or "").lower()
            if n in wanted and p.info["pid"] not in own:
                out.append(p.info["pid"])
        except (psutil.NoSuchProcess, psutil.AccessDenied):
            continue
    return out


def top_windows(pids):
    """Видимые верхнеуровневые окна, принадлежащие данным PID."""
    pidset = set(pids)
    found = []

    def cb(hwnd, _lparam):
        if not user32.IsWindowVisible(hwnd):
            return True
        pid = wt.DWORD()
        user32.GetWindowThreadProcessId(hwnd, ctypes.byref(pid))
        if pid.value in pidset:
            found.append(hwnd)
        return True

    user32.EnumWindows(_EnumWindowsProc(cb), 0)
    return found


def windows_by_class(class_name):
    """Окна с заданным классом (для окон Проводника — CabinetWClass)."""
    found = []
    buf = ctypes.create_unicode_buffer(256)

    def cb(hwnd, _lparam):
        if user32.IsWindowVisible(hwnd):
            user32.GetClassNameW(hwnd, buf, 256)
            if buf.value == class_name:
                found.append(hwnd)
        return True

    user32.EnumWindows(_EnumWindowsProc(cb), 0)
    return found


def close_windows(hwnds):
    n = 0
    for h in hwnds:
        try:
            if user32.PostMessageW(h, WM_CLOSE, 0, 0):
                n += 1
        except Exception:
            pass
    return n


def close_app(*names, grace=2.5, force_after_grace=True):
    """
    Мягко закрыть приложение по имени(ам) процесса.
    Возвращает (закрыто_процессов, было_найдено).
    """
    pids = find_pids(*names)
    if not pids:
        return 0, 0
    total = len(pids)

    sent = close_windows(top_windows(pids))
    _log(f"{names[0]}: pids={total}, WM_CLOSE -> {sent} окон")

    if sent:
        deadline = time.time() + grace
        while time.time() < deadline:
            alive = [p for p in pids if psutil.pid_exists(p)]
            if not alive:
                return total, total
            time.sleep(0.15)

    if not force_after_grace:
        alive = [p for p in pids if psutil.pid_exists(p)]
        return total - len(alive), total

    alive = []
    for pid in pids:
        try:
            p = psutil.Process(pid)
            p.terminate()
            alive.append(p)
        except (psutil.NoSuchProcess, psutil.AccessDenied):
            pass
    gone, still = psutil.wait_procs(alive, timeout=2.0)
    for p in still:
        try:
            p.kill()
        except Exception:
            pass
    closed = total - len([p for p in still if p.is_running()])
    _log(f"{names[0]}: закрыто {closed}/{total}")
    return closed, total


def is_running(*names):
    return bool(find_pids(*names))
