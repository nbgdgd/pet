"""
Транслитерация RU <-> LAT для сопоставления произнесённых названий программ.

Задача: пользователь говорит "открой аниблейз" / "открой а ни блэйз",
STT выдаёт кириллицу, а программа называется "AniBlaze".
Нужен общий фонетический ключ, по которому обе формы совпадут.

Метод: обе строки приводятся к "фонетическому скелету" — грубой
последовательности звуков, где схлопнуты все различия, которые
русское ухо и STT не различают (a/e/o -> гласная-класс, s/z, k/c/q, i/y/j).
"""
import re
import unicodedata

# --- Кириллица -> латиница (основной вариант) ---
_RU2LAT = {
    "а": "a", "б": "b", "в": "v", "г": "g", "д": "d", "е": "e", "ё": "e",
    "ж": "zh", "з": "z", "и": "i", "й": "y", "к": "k", "л": "l", "м": "m",
    "н": "n", "о": "o", "п": "p", "р": "r", "с": "s", "т": "t", "у": "u",
    "ф": "f", "х": "h", "ц": "ts", "ч": "ch", "ш": "sh", "щ": "sch",
    "ъ": "", "ы": "y", "ь": "", "э": "e", "ю": "yu", "я": "ya",
}

# Многобуквенные сочетания обрабатываются до посимвольных
_RU2LAT_DIGRAPHS = [
    ("кс", "x"), ("дж", "j"), ("ей", "ey"), ("ай", "ay"), ("ой", "oy"),
    ("уй", "uy"), ("ья", "ya"), ("ье", "ye"),
]

# --- Латиница -> кириллица (для генерации русских написаний имени программы) ---
_LAT2RU_DIGRAPHS = [
    ("sch", "щ"), ("tch", "ч"), ("sh", "ш"), ("ch", "ч"), ("zh", "ж"),
    ("th", "т"), ("ph", "ф"), ("ck", "к"), ("kh", "х"), ("ts", "ц"),
    ("qu", "кв"), ("ee", "и"), ("oo", "у"), ("ea", "и"), ("ai", "эй"),
    ("ay", "эй"), ("ey", "эй"), ("ie", "и"), ("ou", "ау"), ("ow", "оу"),
    ("yu", "ю"), ("ya", "я"), ("ja", "джа"), ("ju", "джу"), ("jo", "джо"),
]
_LAT2RU = {
    "a": "а", "b": "б", "c": "к", "d": "д", "e": "е", "f": "ф", "g": "г",
    "h": "х", "i": "и", "j": "дж", "k": "к", "l": "л", "m": "м", "n": "н",
    "o": "о", "p": "п", "q": "к", "r": "р", "s": "с", "t": "т", "u": "у",
    "v": "в", "w": "в", "x": "кс", "y": "й", "z": "з",
}

# --- Классы звуков для "скелета" ---
# Всё, что STT и русское ухо путают, схлопывается в один символ.
_SKELETON = {
    # гласные -> одна гласная-заглушка (порядок гласных сохраняем, тембр — нет)
    "a": "A", "e": "A", "o": "A", "u": "A", "i": "I", "y": "I",
    # шумные пары
    "b": "B", "p": "B",
    "v": "V", "f": "V", "w": "V",
    "g": "G", "k": "G", "q": "G", "c": "G", "x": "GS",
    "d": "D", "t": "D",
    "z": "S", "s": "S",
    "zh": "J", "j": "J", "sh": "J", "ch": "J", "sch": "J",
    "h": "H",
    "l": "L", "r": "R", "m": "M", "n": "N",
    "ts": "S",
}
_SKEL_KEYS = sorted(_SKELETON, key=len, reverse=True)

_CYR_RE = re.compile(r"[а-яё]")
_LAT_RE = re.compile(r"[a-z]")


def has_cyrillic(s: str) -> bool:
    return bool(_CYR_RE.search(s.lower()))


def has_latin(s: str) -> bool:
    return bool(_LAT_RE.search(s.lower()))


def ru_to_lat(text: str) -> str:
    """Кириллица -> латиница."""
    t = text.lower()
    for a, b in _RU2LAT_DIGRAPHS:
        t = t.replace(a, b)
    return "".join(_RU2LAT.get(ch, ch) for ch in t)


def lat_to_ru(text: str) -> str:
    """Латиница -> кириллица (одно наиболее вероятное чтение)."""
    t = text.lower()
    for a, b in _LAT2RU_DIGRAPHS:
        t = t.replace(a, b)
    return "".join(_LAT2RU.get(ch, ch) for ch in t)


def skeleton(text: str) -> str:
    """
    Фонетический скелет: строка приводится к латинице, затем
    к последовательности классов звуков. Повторы схлопываются.

    'AniBlaze'  -> 'ANIBLAS'  (примерно)
    'аниблейз'  -> тот же скелет
    'а ни блэйз'-> тот же скелет
    """
    if not text:
        return ""
    t = unicodedata.normalize("NFKD", text.lower())
    if has_cyrillic(t):
        t = ru_to_lat(t)
    t = re.sub(r"[^a-z0-9]+", "", t)
    out = []
    i = 0
    while i < len(t):
        for k in _SKEL_KEYS:
            if t.startswith(k, i):
                out.append(_SKELETON[k])
                i += len(k)
                break
        else:
            out.append(t[i].upper())
            i += 1
    s = "".join(out)
    # схлопываем удвоения (anna -> ana, ss -> s)
    s = re.sub(r"(.)\1+", r"\1", s)
    return s


def ru_spellings(latin_name: str) -> list:
    """
    Возможные русские написания латинского имени — чтобы засеять
    нормализатор и подсказки для STT.
    'AniBlaze' -> ['аниблазе', 'аниблэйз', ...]
    """
    base = lat_to_ru(latin_name)
    out = {base}
    # частые варианты чтения
    variants = [
        (base.replace("е", "э"), None),
        (base.replace("к", "с"), None),
        (base.replace("в", "у"), None),
    ]
    for v, _ in variants:
        if v:
            out.add(v)
    return [v for v in out if v]


def split_words(text: str) -> list:
    return [w for w in re.split(r"[^\w]+", text.lower()) if w]
