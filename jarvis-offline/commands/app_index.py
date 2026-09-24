"""
Динамический каталог установленных программ.

Проблема, которую решает: раньше JARVIS умел открывать только те ~15 программ,
которые кто-то вручную вписал в registry.py. "Открой AniBlaze" не работало
никогда — такой команды просто не существовало.

Теперь каталог строится из самой Windows:
  1. Get-StartApps      — ровно то, что видит поиск в меню Пуск (в т.ч. UWP);
  2. ярлыки .lnk        — меню Пуск (пользователя и общее) + рабочий стол;
  3. App Paths          — реестр, ключи запуска по имени exe;
  4. Uninstall          — реестр, всё установленное с DisplayName.

Сопоставление произнесённого имени идёт по фонетическому "скелету"
(commands/translit.py), поэтому "аниблейз", "а ни блэйз" и "aniblaze"
попадают в одну и ту же запись.
"""
import json
import os
import re
import threading
import time

from rapidfuzz import fuzz, process as rf_process

from commands import translit
from commands.winlaunch import powershell_capture, start_exe, start_shell, start_uwp

CACHE_PATH = os.path.join(
    os.path.dirname(os.path.dirname(os.path.abspath(__file__))), "logs", "app_index.json"
)
CACHE_TTL = 6 * 3600  # пересобирать каталог раз в 6 часов

# Мусорные ярлыки, которые пользователь никогда не просит открыть
_JUNK = re.compile(
    r"(uninstall|удалить|удаление|readme|help|справка|документац|documentation|"
    r"website|веб.?сайт|release notes|changelog|licen[cs]e|лиценз|"
    r"repair|восстановл|troubleshoot|report a (bug|problem)|"
    r"\.url$|deutsch|fran[cç]ais|espa[nñ]ol)",
    re.IGNORECASE,
)

_PS_COLLECT = r"""
$ErrorActionPreference = 'SilentlyContinue'
$out = New-Object System.Collections.ArrayList

Get-StartApps | ForEach-Object {
  [void]$out.Add([pscustomobject]@{ n = $_.Name; id = $_.AppID; t = ''; s = 'startapps' })
}

$sh = New-Object -ComObject WScript.Shell
$dirs = @(
  "$env:APPDATA\Microsoft\Windows\Start Menu\Programs",
  "$env:ProgramData\Microsoft\Windows\Start Menu\Programs",
  "$env:USERPROFILE\Desktop",
  "$env:PUBLIC\Desktop"
) | Where-Object { Test-Path $_ }
Get-ChildItem -Path $dirs -Filter *.lnk -Recurse | ForEach-Object {
  $t = ''
  try { $t = $sh.CreateShortcut($_.FullName).TargetPath } catch {}
  [void]$out.Add([pscustomobject]@{ n = $_.BaseName; id = $_.FullName; t = $t; s = 'lnk' })
}

foreach ($root in @('HKLM:', 'HKCU:')) {
  Get-ChildItem "$root\SOFTWARE\Microsoft\Windows\CurrentVersion\App Paths" | ForEach-Object {
    $d = (Get-ItemProperty $_.PSPath).'(default)'
    if ($d) {
      $d = $d.Trim('"')
      [void]$out.Add([pscustomobject]@{
        n = ($_.PSChildName -replace '\.exe$', ''); id = $d; t = $d; s = 'apppath' })
    }
  }
}

$unin = @(
  'HKLM:\SOFTWARE\Microsoft\Windows\CurrentVersion\Uninstall',
  'HKLM:\SOFTWARE\WOW6432Node\Microsoft\Windows\CurrentVersion\Uninstall',
  'HKCU:\SOFTWARE\Microsoft\Windows\CurrentVersion\Uninstall'
)
foreach ($p in $unin) {
  Get-ChildItem $p | ForEach-Object {
    $i = Get-ItemProperty $_.PSPath
    if ($i.DisplayName -and -not $i.SystemComponent) {
      $exe = ''
      if ($i.DisplayIcon) { $exe = (($i.DisplayIcon -split ',')[0]).Trim('"') }
      [void]$out.Add([pscustomobject]@{ n = $i.DisplayName; id = $exe; t = $exe; s = 'uninst' })
    }
  }
}

$out | ConvertTo-Json -Compress -Depth 3 | Out-File -FilePath $JarvisOut -Encoding utf8
"""


def _log(msg):
    print(f"[APPS] {msg}", flush=True)


def _clean_name(name: str) -> str:
    n = re.sub(r"\s*\((x64|x86|64-bit|32-bit|64 бит|32 бит)\)\s*", " ", name, flags=re.I)
    n = re.sub(r"\s+", " ", n).strip(" -–—")
    return n


class AppEntry(dict):
    """{name, kind, target, exe, proc, keys, source}"""

    @property
    def name(self):
        return self.get("name", "")

    @property
    def proc(self):
        return self.get("proc") or ""


class AppIndex:
    def __init__(self, cache_path=CACHE_PATH):
        self.cache_path = cache_path
        self.entries = []
        self._by_key = {}
        self._names = []
        self._skeletons = []
        self._lock = threading.Lock()
        self._built_at = 0.0

    # ------------------------------------------------------------------ build

    def _ensure_loaded(self):
        """
        Каталог нужен любому входу в программу, а не только jarvis_cmd:
        подтягиваем кэш (или собираем заново) при первом обращении.
        """
        if self.entries:
            return
        if not self._load_cache():
            self.rebuild()

    def load_or_build(self, background=True):
        """Мгновенно поднять кэш; если он устарел — обновить в фоне."""
        fresh = self._load_cache()
        if not fresh:
            if background:
                threading.Thread(target=self.rebuild, daemon=True).start()
            else:
                self.rebuild()
        elif time.time() - self._built_at > CACHE_TTL:
            threading.Thread(target=self.rebuild, daemon=True).start()
        return len(self.entries)

    def _load_cache(self) -> bool:
        try:
            with open(self.cache_path, "r", encoding="utf-8") as f:
                blob = json.load(f)
            entries = [AppEntry(e) for e in blob.get("entries", [])]
            if not entries:
                return False
            with self._lock:
                self.entries = entries
                self._built_at = blob.get("built_at", 0.0)
                self._reindex()
            _log(f"кэш: {len(entries)} программ")
            return True
        except Exception:
            return False

    def _save_cache(self):
        try:
            os.makedirs(os.path.dirname(self.cache_path), exist_ok=True)
            with open(self.cache_path, "w", encoding="utf-8") as f:
                json.dump(
                    {"built_at": self._built_at, "entries": self.entries},
                    f, ensure_ascii=False,
                )
        except Exception as e:
            _log(f"кэш не сохранён: {e}")

    def rebuild(self):
        t0 = time.time()
        raw = powershell_capture(_PS_COLLECT, timeout=180)
        if not raw:
            _log("PowerShell не вернул список программ")
            return 0
        try:
            data = json.loads(raw)
        except Exception as e:
            _log(f"не разобрал JSON каталога: {e}")
            return 0
        if isinstance(data, dict):
            data = [data]

        merged = {}
        for row in data:
            entry = self._row_to_entry(row)
            if not entry:
                continue
            key = entry["name"].lower()
            old = merged.get(key)
            if old is None or self._better(entry, old):
                if old:
                    entry["proc"] = entry.get("proc") or old.get("proc")
                    entry["exe"] = entry.get("exe") or old.get("exe")
                merged[key] = entry

        entries = sorted(merged.values(), key=lambda e: e["name"].lower())
        with self._lock:
            self.entries = entries
            self._built_at = time.time()
            self._reindex()
        self._save_cache()
        _log(f"каталог собран: {len(entries)} программ за {time.time()-t0:.1f}с")
        return len(entries)

    _SOURCE_RANK = {"startapps": 3, "lnk": 2, "apppath": 1, "uninst": 0}

    def _better(self, new, old):
        return self._SOURCE_RANK.get(new["source"], 0) >= self._SOURCE_RANK.get(old["source"], 0)

    def _row_to_entry(self, row):
        name = _clean_name(str(row.get("n") or ""))
        ident = str(row.get("id") or "")
        target = str(row.get("t") or "")
        source = row.get("s") or ""
        if not name or len(name) < 2 or _JUNK.search(name):
            return None

        exe = target if target.lower().endswith(".exe") and os.path.isfile(target) else ""
        proc = os.path.basename(exe) if exe else ""

        if source == "startapps":
            if "!" in ident:                      # UWP AppUserModelID
                kind, tgt = "uwp", ident
            elif ident:
                kind, tgt = "uwp", ident          # shell:AppsFolder работает и для Win32
            else:
                return None
        elif source == "lnk":
            kind, tgt = "shell", ident            # запускаем сам .lnk — сохраняются аргументы
        elif source == "apppath":
            if not (ident and os.path.isfile(ident)):
                return None
            kind, tgt = "exe", ident
            exe = exe or ident
            proc = proc or os.path.basename(ident)
        else:                                     # uninst
            if not (exe and os.path.isfile(exe)):
                return None
            kind, tgt = "exe", exe

        return AppEntry(
            name=name, kind=kind, target=tgt, exe=exe, proc=proc, source=source
        )

    def _reindex(self):
        self._names = [e["name"] for e in self.entries]
        self._skeletons = []
        self._by_key = {}
        for i, e in enumerate(self.entries):
            skel = translit.skeleton(e["name"])
            e["skel"] = skel
            self._skeletons.append(skel)
            self._by_key.setdefault(skel, i)
            if e.get("proc"):
                self._by_key.setdefault(translit.skeleton(e["proc"][:-4]), i)

    # --------------------------------------------------------------- matching

    # Длины скелетов не должны расходиться сильнее, чем в ~1.8 раза.
    # Без этого короткий мусорный пункт каталога ("Un") набирает 90 по
    # WRatio на любом запросе — partial-ratio не штрафует разницу длин.
    _LEN_GUARD = 0.55

    def resolve(self, spoken: str, min_score: int = 74):
        """
        Произнесённое имя -> (entry, score) или (None, 0).
        Сравнение по фонетическому скелету плюс по обычному тексту.
        """
        spoken = (spoken or "").strip().lower()
        if not spoken:
            return None, 0
        self._ensure_loaded()
        if not self.entries:
            return None, 0

        with self._lock:
            names = list(self._names)
            skels = list(self._skeletons)
            entries = list(self.entries)
            exact = self._by_key.get(translit.skeleton(spoken))

        if exact is not None:
            return entries[exact], 100

        skel = translit.skeleton(spoken)
        if len(skel) < 2:
            return None, 0

        best_i, best_score = -1, 0
        for i, (nm, sk) in enumerate(zip(names, skels)):
            if not sk:
                continue
            lo, hi = min(len(skel), len(sk)), max(len(skel), len(sk))
            if lo / hi < self._LEN_GUARD:
                continue
            score = fuzz.ratio(skel, sk)
            s2 = fuzz.ratio(spoken, nm.lower())
            if s2 > score:
                score = s2
            # префикс скелета: "аниблейз" против "AniBlaze Studio"
            if len(skel) >= 5 and sk.startswith(skel):
                score = max(score, 92)
            # разный первый звук — почти наверняка другое слово.
            # без этого "абракадабра" набирает 75 на "Steps Recorder".
            if skel[0] != sk[0]:
                score -= 18
            if score > best_score:
                best_score, best_i = int(score), i

        if best_i >= 0 and best_score >= min_score:
            return entries[best_i], best_score
        return None, 0

    # ---------------------------------------------------------------- actions

    def open(self, spoken: str):
        entry, score = self.resolve(spoken)
        if not entry:
            return None, 0
        ok = False
        if entry["kind"] == "uwp":
            ok = start_uwp(entry["target"])
        elif entry["kind"] == "exe":
            ok = start_exe(entry["target"])
        else:
            ok = start_shell(entry["target"])
        if not ok and entry.get("exe"):
            ok = start_exe(entry["exe"])
        return (entry if ok else None), score

    def close(self, spoken: str):
        from commands import winproc

        entry, score = self.resolve(spoken)
        if not entry:
            return None, 0, (0, 0)
        procs = [p for p in (entry.get("proc"), self._guess_proc(entry)) if p]
        if not procs:
            return entry, score, (0, 0)
        return entry, score, winproc.close_app(*procs)

    @staticmethod
    def _guess_proc(entry):
        """Если exe неизвестен — пробуем угадать имя процесса из названия."""
        if not entry or not entry.get("name"):
            return ""
        n = re.sub(r"[^A-Za-z0-9]+", "", entry["name"])
        return f"{n}.exe" if n else ""

    # ------------------------------------------------------------- для STT

    # Слова, которые есть в каждом втором названии и подсказкой быть не могут
    _GENERIC = {
        "the", "and", "for", "app", "application", "tool", "tools", "manager",
        "file", "files", "edition", "version", "studio", "desktop", "client",
        "launcher", "browser", "player", "viewer", "editor", "settings", "setup",
        "install", "installer", "update", "updater", "server", "service", "run",
        "new", "old", "beta", "alpha", "free", "pro", "plus", "lite", "home",
        "about", "info", "start", "open", "windows", "microsoft", "google",
        "language", "changer", "com", "exe", "bit", "x64", "x86",
    }

    def hotwords(self, limit=250):
        """
        Названия программ для подсказки распознавателю речи
        (Whisper initial_prompt / Deepgram keyterms). Именно это чинит
        "открой aniblaze" -> распознаватель заранее знает такое слово.
        """
        with self._lock:
            names = [e["name"] for e in self.entries]
        seen, out = set(), []
        for n in names:
            for w in re.split(r"[^A-Za-z0-9]+", n):
                w = w.strip()
                if len(w) < 4 or len(w) > 20 or not translit.has_latin(w):
                    continue
                if w.isdigit():
                    continue
                k = w.lower()
                if k in self._GENERIC or k in seen:
                    continue
                seen.add(k)
                out.append(w)
                if len(out) >= limit:
                    return out
        return out

    def full_names(self, limit=120):
        """Полные названия — для initial_prompt Whisper."""
        with self._lock:
            names = [e["name"] for e in self.entries if translit.has_latin(e["name"])]
        return names[:limit]


INDEX = AppIndex()
