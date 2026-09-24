"""
Синтез речи.

Порядок движков:
  1. Silero v5 ru (models/silero/v5_ru.pt) — 48 кГц, живая интонация,
     автоматические ударения (put_accent) и «ё» (put_yo). Заметно
     естественнее, чем Piper medium на 22 кГц, и уже лежит в репозитории.
  2. Piper ONNX (models/tts/ru_RU-*-medium) — запасной, лёгкий.
  3. SAPI5 — если не поднялось вообще ничего.

Важное отличие от прежней версии: пока JARVIS говорит, поднят флаг
is_speaking(). Распознавание речи по нему выбрасывает свой же голос из
входного потока — раньше ассистент слышал сам себя, из-за чего
шумовой порог уползал вверх и следующая команда терялась.
"""
import os
import threading
import time

import numpy as np
import sounddevice as sd

from tts import rutext

BASE = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
PIPER_DIR = os.path.join(BASE, "models", "tts")

# Ветка русского Silero V5: v5_ru -> v5_1 -> ... -> v5_5_ru.
# v5_5_ru новее лежавшего в репозитории v5_ru и, помимо ударений и «ё»,
# умеет вопросительную интонацию. Берём самую свежую из имеющихся.
_SILERO_CANDIDATES = ["v5_5_ru.pt", "v5_4_ru.pt", "v5_3_ru.pt", "v5_ru.pt"]


def _silero_path():
    d = os.path.join(BASE, "models", "silero")
    for name in _SILERO_CANDIDATES:
        p = os.path.join(d, name)
        if os.path.isfile(p):
            return p
    return os.path.join(d, "v5_ru.pt")


SILERO_PATH = _silero_path()

AVAILABLE_VOICES = {
    "eugene": {"engine": "silero", "model": "eugene",
               "desc": "Евгений — мужской, Silero v5 48кГц (по умолчанию)"},
    "aidar":  {"engine": "silero", "model": "aidar",
               "desc": "Айдар — мужской, Silero v5 48кГц"},
    "xenia":  {"engine": "silero", "model": "xenia",
               "desc": "Ксения — женский, Silero v5 48кГц"},
    "baya":   {"engine": "silero", "model": "baya",
               "desc": "Бая — женский, Silero v5 48кГц"},
    "kseniya": {"engine": "silero", "model": "kseniya",
                "desc": "Ксения (старый) — женский, Silero v5"},
    "irina":  {"engine": "piper", "model": "ru_RU-irina-medium",
               "desc": "Ирина — женский, Piper 22кГц"},
    "denis":  {"engine": "piper", "model": "ru_RU-denis-medium",
               "desc": "Денис — мужской, Piper 22кГц"},
    "dmitri": {"engine": "piper", "model": "ru_RU-dmitri-medium",
               "desc": "Дмитрий — мужской, Piper 22кГц"},
}

VOICE_NAME = "eugene"
SILERO_SAMPLE_RATE = 48000

_silero = None
_piper_voices = {}
_speaker = None
_lock = threading.Lock()
_load_lock = threading.Lock()
_speaking = threading.Event()
_last_error = ""


def is_speaking() -> bool:
    return _speaking.is_set()


def _log(msg):
    print(f"[TTS] {msg}", flush=True)


# ---------------------------------------------------------------- Silero

def _get_silero():
    """
    Загрузка модели строго один раз.

    Без этого замка preload() из фонового потока и первый say() грузили
    модель одновременно: две копии по ~300 МБ в памяти и лишние 6 секунд.
    """
    global _silero, _last_error
    if _silero is not None:
        return _silero
    with _load_lock:
        if _silero is not None:
            return _silero
        if not os.path.isfile(SILERO_PATH):
            _last_error = f"нет модели {SILERO_PATH}"
            return None
        try:
            import warnings
            import torch
            torch.set_num_threads(max(2, (os.cpu_count() or 4) // 2))
            # Внутри пакета silero есть regex-строка без префикса r"" и
            # устаревший TypedStorage. Оба предупреждения приходят из
            # чужого кода, чинить его нечем, а в консоли они забивают лог.
            with warnings.catch_warnings():
                warnings.simplefilter("ignore")
                model = torch.package.PackageImporter(SILERO_PATH).load_pickle(
                    "tts_models", "model")
            model.to(torch.device("cpu"))
            _silero = model
            _log(f"Silero загружен ({os.path.basename(SILERO_PATH)}), "
                 f"голоса: {getattr(model, 'speakers', [])}")
            return _silero
        except Exception as e:
            _last_error = f"Silero: {e}"
            _log(_last_error)
            return None


def _say_silero(text, speaker):
    model = _get_silero()
    if model is None:
        return None
    try:
        audio = model.apply_tts(
            text=text,
            speaker=speaker,
            sample_rate=SILERO_SAMPLE_RATE,
            put_accent=True,
            put_yo=True,
        )
        return np.asarray(audio, dtype=np.float32), SILERO_SAMPLE_RATE
    except Exception as e:
        _log(f"Silero synth error: {e}")
        return None


# ----------------------------------------------------------------- Piper

def _get_piper(model_name):
    if model_name in _piper_voices:
        return _piper_voices[model_name]
    onnx = os.path.join(PIPER_DIR, model_name, f"{model_name}.onnx")
    if not os.path.isfile(onnx) or not os.path.isfile(onnx + ".json"):
        # без .json Piper не поднимется — раньше это молча роняло весь TTS
        _log(f"Piper: нет {onnx} (.onnx + .onnx.json)")
        return None
    try:
        from piper import PiperVoice
        v = PiperVoice.load(onnx, use_cuda=False)
        _piper_voices[model_name] = v
        return v
    except Exception as e:
        _log(f"Piper load error ({model_name}): {e}")
        return None


def _say_piper(text, model_name):
    voice = _get_piper(model_name)
    if voice is None:
        return None
    try:
        chunks = list(voice.synthesize(text))
        if not chunks:
            return None
        audio = np.concatenate([c.audio_float_array for c in chunks])
        return audio.astype(np.float32), chunks[0].sample_rate
    except Exception as e:
        _log(f"Piper synth error: {e}")
        return None


# ------------------------------------------------------------------ SAPI

def _say_sapi(text):
    global _speaker
    try:
        if _speaker is None:
            import win32com.client
            _speaker = win32com.client.Dispatch("SAPI.SpVoice")
        _speaker.Speak(text)
        return True
    except Exception as e:
        _log(f"SAPI error: {e}")
        return False


# ------------------------------------------------------------------- API

def _play(audio, rate):
    """
    Воспроизведение на устройстве вывода ПО УМОЛЧАНИЮ.

    Прежняя версия делала `sd.default.device = None, None`, то есть
    сбрасывала и устройство ВВОДА — микрофон, явно выбранный в config.py,
    переставал использоваться после первой же реплики.
    """
    out_dev = sd.default.device[1] if isinstance(sd.default.device, (list, tuple)) else None
    sd.play(audio, samplerate=rate, device=out_dev)
    sd.wait()


def say(text, blocking=True):
    """Произнести текст. Возвращает True, если что-то прозвучало."""
    if not text:
        return False
    spoken = rutext.prepare(text)
    print(f"[JARVIS] {text}", flush=True)
    if not spoken:
        return False

    cfg = AVAILABLE_VOICES.get(VOICE_NAME) or AVAILABLE_VOICES["eugene"]

    def _run():
        _speaking.set()
        try:
            with _lock:
                result = None
                if cfg["engine"] == "silero":
                    result = _say_silero(spoken, cfg["model"])
                    if result is None:
                        result = _say_piper(spoken, "ru_RU-irina-medium")
                else:
                    result = _say_piper(spoken, cfg["model"])
                    if result is None:
                        result = _say_silero(spoken, "eugene")
                if result is None:
                    _say_sapi(spoken)
                else:
                    _play(*result)
        except Exception as e:
            _log(f"playback error: {e}")
        finally:
            # небольшой хвост: динамики ещё звучат, микрофон пока не слушаем
            time.sleep(0.25)
            _speaking.clear()

    if blocking:
        _run()
        return True
    threading.Thread(target=_run, daemon=True).start()
    return True


def set_voice(name):
    global VOICE_NAME
    if name not in AVAILABLE_VOICES:
        _log(f"неизвестный голос: {name}. Есть: {list(AVAILABLE_VOICES)}")
        return False
    VOICE_NAME = name
    _log(f"голос: {name} — {AVAILABLE_VOICES[name]['desc']}")
    return True


def preload():
    """Прогреть модель заранее, чтобы первая фраза не тормозила."""
    threading.Thread(target=_get_silero, daemon=True).start()


def stop():
    try:
        sd.stop()
    finally:
        _speaking.clear()


def last_error():
    return _last_error
