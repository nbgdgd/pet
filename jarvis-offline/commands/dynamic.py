"""
Открыть/закрыть ЛЮБУЮ установленную программу, а не только вписанные в registry.py.

Работает так: если фраза начинается с глагола запуска/закрытия, всё
остальное считается названием программы и ищется в каталоге Windows
(commands/app_index.py) по фонетическому скелету.

    "открой аниблейз"  -> AniBlaze
    "закрой стим"      -> Steam (WM_CLOSE, не taskkill /F)

Вызывается ПОСЛЕ обычного роутера: явные команды из registry.py имеют приоритет.
"""
import os
import re

from commands.app_index import INDEX

OPEN_VERBS = (
    "открой", "открыть", "открывай", "откройте", "запусти", "запустить",
    "запускай", "включи", "включить", "врубани", "врубай", "стартани",
    "open", "launch", "run", "start", "execute",
)
CLOSE_VERBS = (
    "закрой", "закрыть", "закрывай", "закройте", "выключи", "выключить",
    "заверши", "завершить", "убей", "прибей", "вырубани", "вырубай",
    "close", "kill", "quit", "exit", "terminate", "stop",
)

# Хвосты, которые НЕ являются названием программы — это системные команды.
# Без этого "выключи компьютер" превратилось бы в поиск программы "компьютер".
NOT_AN_APP = {
    "компьютер", "комп", "пк", "пекарню", "машину", "систему", "ноутбук", "ноут",
    "звук", "аудио", "громкость", "музыку", "свет", "экран", "монитор",
    "интернет", "вайфай", "wifi", "wi-fi", "блютуз", "bluetooth",
    "микрофон", "камеру", "джарвиса", "себя", "всё", "все", "это",
    "pc", "computer", "sound", "volume", "music", "screen", "wifi", "mic",
}

_VERB_RE = re.compile(
    r"^\s*(?:" + "|".join(sorted(map(re.escape, OPEN_VERBS + CLOSE_VERBS), key=len, reverse=True)) + r")\b",
    re.IGNORECASE,
)


def split_verb(text: str):
    """'открой аниблейз' -> ('open', 'аниблейз'). Иначе (None, '')."""
    t = (text or "").strip().lower()
    if not t:
        return None, ""
    words = t.split()
    verb = words[0]
    if verb in OPEN_VERBS:
        kind = "open"
    elif verb in CLOSE_VERBS:
        kind = "close"
    else:
        return None, ""
    tail = " ".join(words[1:]).strip()
    # "открой программу телеграм", "запусти приложение стим"
    tail = re.sub(r"^(программу|приложение|прогу|прилу|app|program|the)\s+", "", tail)
    if not tail or tail in NOT_AN_APP:
        return None, ""
    return kind, tail


def handle(text: str, min_score: int = 78):
    """
    Попытаться выполнить фразу как «открой/закрой <любая программа>».
    Возвращает строку-отчёт при успехе или None, если не наш случай.
    """
    # «открой файл ...» / «открой папку ...» — это поиск по диску,
    # а не по каталогу программ
    file_reply = handle_file(text)
    if file_reply:
        return file_reply

    kind, tail = split_verb(text)
    if not kind:
        return None

    entry, score = INDEX.resolve(tail, min_score=min_score)
    if not entry:
        return None

    if kind == "open":
        opened, _ = INDEX.open(tail)
        if opened:
            return f"Открываю {opened['name']}"
        return f"Не смог запустить {entry['name']}"

    entry, _score, (closed, total) = INDEX.close(tail)
    if not entry:
        return None
    if total == 0:
        return f"{entry['name']} и так не запущен"
    if closed:
        return f"Закрываю {entry['name']}"
    return f"Не смог закрыть {entry['name']}"


# --------------------------------------------------------------- файлы

FILE_VERBS = ("открой файл", "открой документ", "найди файл", "найди документ",
              "открой папку", "найди папку", "open file", "find file")

_FILE_PS = r"""
$name = $JarvisQuery -replace "'", "''"
$folder = $JarvisFolders
$out = @()
try {
  $c = New-Object -ComObject ADODB.Connection
  $rs = New-Object -ComObject ADODB.Recordset
  $c.Open("Provider=Search.CollatorDSO;Extended Properties='Application=Windows';")
  # ItemUrl, а не ItemPathDisplay: второй отдаёт ЛОКАЛИЗОВАННЫЙ путь
  # ("C:\Пользователи\..."), которого не существует на диске и который
  # ShellExecute открыть не может.
  $kind = if ($folder -eq '1') { "AND System.ItemType = 'Directory'" }
          else { "AND System.ItemType <> 'Directory'" }
  $q = "SELECT TOP 8 System.ItemUrl FROM SYSTEMINDEX " +
       "WHERE System.FileName LIKE '%$name%' $kind ORDER BY System.DateModified DESC"
  $rs.Open($q, $c)
  while (-not $rs.EOF) { $out += $rs.Fields.Item(0).Value; $rs.MoveNext() }
  $rs.Close(); $c.Close()
} catch {}
$out | ConvertTo-Json -Compress | Out-File -FilePath $JarvisOut -Encoding utf8
"""


def find_files(query, folders_only=False, limit=8):
    """
    Поиск по индексу Windows Search — тому же, что и поиск в меню Пуск.
    Работает мгновенно и видит все файлы пользователя, а не только
    заранее прописанные пути.
    """
    import json

    from commands.winlaunch import powershell_capture

    q = re.sub(r"[^\w\s.\-]", "", query or "").strip()
    if len(q) < 2:
        return []
    script = (
        "$JarvisQuery = " + repr(q).replace('"', "'") + "\n"
        "$JarvisFolders = '" + ("1" if folders_only else "0") + "'\n"
        + _FILE_PS
    )
    raw = powershell_capture(script, timeout=30)
    if not raw:
        return []
    try:
        data = json.loads(raw)
    except Exception:
        return []
    if isinstance(data, str):
        data = [data]
    out = []
    for p in data or []:
        if not p:
            continue
        p = re.sub(r"^file:/*", "", str(p)).replace("/", "\\")
        if os.path.exists(p):
            out.append(p)
        if len(out) >= limit:
            break
    return out


def split_file_verb(text: str):
    t = (text or "").strip().lower()
    for v in FILE_VERBS:
        if t.startswith(v):
            tail = t[len(v):].strip()
            return ("folder" if "папк" in v else "file"), tail
    return None, ""


def handle_file(text: str):
    from commands.winlaunch import start_shell

    kind, tail = split_file_verb(text)
    if not kind or not tail:
        return None
    hits = find_files(tail, folders_only=(kind == "folder"))
    if not hits:
        return f"Не нашёл ничего похожего на «{tail}»"
    if start_shell(hits[0]):
        return f"Открываю {os.path.basename(hits[0]) or hits[0]}"
    return f"Не смог открыть {os.path.basename(hits[0])}"


def refresh_index():
    n = INDEX.rebuild()
    return f"Список программ обновлён: {n} приложений"


def list_apps(query: str = "", limit: int = 40):
    if query:
        entry, score = INDEX.resolve(query)
        return [f"{entry['name']} ({score}%)"] if entry else []
    return [e["name"] for e in INDEX.entries[:limit]]
