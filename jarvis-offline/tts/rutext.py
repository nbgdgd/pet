"""
Подготовка текста для русского синтеза речи.

Два обязательных шага, без которых любой русский TTS звучит плохо:
  1. цифры -> слова ("62 процента" -> "шестьдесят два процента");
  2. латиница -> кириллица ("Открываю Firefox" -> "Открываю Файрфокс"),
     иначе движок либо молча пропускает слово, либо читает по буквам.
"""
import re

from commands import translit

_ONES = ["", "один", "два", "три", "четыре", "пять", "шесть", "семь", "восемь", "девять"]
_ONES_F = ["", "одна", "две", "три", "четыре", "пять", "шесть", "семь", "восемь", "девять"]
_TEENS = ["десять", "одиннадцать", "двенадцать", "тринадцать", "четырнадцать",
          "пятнадцать", "шестнадцать", "семнадцать", "восемнадцать", "девятнадцать"]
_TENS = ["", "", "двадцать", "тридцать", "сорок", "пятьдесят", "шестьдесят",
         "семьдесят", "восемьдесят", "девяносто"]
_HUNDREDS = ["", "сто", "двести", "триста", "четыреста", "пятьсот", "шестьсот",
             "семьсот", "восемьсот", "девятьсот"]

# Известные бренды читаем правильно, а не по правилам транслитерации
_BRANDS = {
    "firefox": "файрфокс", "chrome": "хром", "edge": "эдж", "telegram": "телеграм",
    "discord": "дискорд", "spotify": "спотифай", "steam": "стим", "windows": "виндоус",
    "opencode": "опенкод", "vs code": "вэ эс код", "vscode": "вэ эс код",
    "cpu": "процессор", "ram": "оперативная память", "gpu": "видеокарта",
    "ssd": "эс эс ди", "hdd": "жёсткий диск", "wi-fi": "вайфай", "wifi": "вайфай",
    "bluetooth": "блютус", "youtube": "ютуб", "google": "гугл", "jarvis": "джарвис",
    "pc": "пэ цэ", "ok": "окей", "explorer": "проводник", "notepad": "блокнот",
}


def _under_thousand(n, feminine=False):
    ones = _ONES_F if feminine else _ONES
    out = []
    if n >= 100:
        out.append(_HUNDREDS[n // 100])
        n %= 100
    if 10 <= n < 20:
        out.append(_TEENS[n - 10])
        n = 0
    elif n >= 20:
        out.append(_TENS[n // 10])
        n %= 10
    if n:
        out.append(ones[n])
    return " ".join(x for x in out if x)


def number_to_words(n: int) -> str:
    """0..999 999 999 словами. Больше в голосовом ассистенте не встречается."""
    if n == 0:
        return "ноль"
    if n < 0:
        return "минус " + number_to_words(-n)
    parts = []
    if n >= 1_000_000:
        m = n // 1_000_000
        parts.append(_under_thousand(m))
        parts.append(_plural(m, "миллион", "миллиона", "миллионов"))
        n %= 1_000_000
    if n >= 1000:
        t = n // 1000
        parts.append(_under_thousand(t, feminine=True))
        parts.append(_plural(t, "тысяча", "тысячи", "тысяч"))
        n %= 1000
    if n:
        parts.append(_under_thousand(n))
    return " ".join(p for p in parts if p)


def _plural(n, one, few, many):
    n = abs(n) % 100
    if 11 <= n <= 19:
        return many
    n %= 10
    if n == 1:
        return one
    if 2 <= n <= 4:
        return few
    return many


def _num_sub(m):
    whole = m.group(1)
    frac = m.group(2)
    try:
        text = number_to_words(int(whole))
    except ValueError:
        return m.group(0)
    if frac:
        text += " и " + number_to_words(int(frac)) + (
            " десятых" if len(frac) == 1 else " сотых")
    return text


_NUM_RE = re.compile(r"\b(\d+)(?:[.,](\d+))?\b")
_LAT_WORD_RE = re.compile(r"[A-Za-z][A-Za-z0-9+\-]*")


def _lat_sub(m):
    w = m.group(0)
    low = w.lower()
    if low in _BRANDS:
        return _BRANDS[low]
    # аббревиатура из заглавных (RAM, CPU) — читаем побуквенно
    if len(w) <= 4 and w.isupper():
        letters = {"a": "эй", "b": "би", "c": "си", "d": "ди", "e": "и", "f": "эф",
                   "g": "джи", "h": "эйч", "i": "ай", "j": "джей", "k": "кей",
                   "l": "эл", "m": "эм", "n": "эн", "o": "оу", "p": "пи", "q": "кью",
                   "r": "ар", "s": "эс", "t": "ти", "u": "ю", "v": "ви", "w": "дабл ю",
                   "x": "экс", "y": "уай", "z": "зед"}
        return " ".join(letters.get(c, c) for c in low)
    # немая конечная "e": Blaze -> блэйз, а не "блазе"
    if len(low) > 3 and low.endswith("e") and low[-2] not in "aeiou":
        low = low[:-1]
    return translit.lat_to_ru(low)


def prepare(text: str) -> str:
    """Полная подготовка строки к синтезу."""
    if not text:
        return ""
    t = str(text).strip()
    for brand, ru in _BRANDS.items():
        if " " in brand:
            t = re.sub(rf"\b{re.escape(brand)}\b", ru, t, flags=re.IGNORECASE)
    t = t.replace("%", " процентов").replace("°", " градусов")
    t = _NUM_RE.sub(_num_sub, t)
    t = _LAT_WORD_RE.sub(_lat_sub, t)
    t = re.sub(r"\s+", " ", t).strip()
    return t
