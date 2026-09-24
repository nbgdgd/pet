"""
Распознавание речи: фасад над общим микрофоном и выбранным ASR-движком.

Что здесь принципиально иначе, чем в прежней версии:
  * микрофон один на всё приложение (speech/audio.py), а не по одному
    RawInputStream на каждый созданный объект STT;
  * никакого busy-wait — очередь кадров читается с блокировкой;
  * wait_for_wake_word проверяет флаг отмены и умеет завершаться,
    раньше это был `while True` без единого выхода, и микрофон
    невозможно было выключить;
  * убран «кулдаун» в 1.5 секунды, из-за которого listen_phrase
    мгновенно возвращал None; пять таких None подряд заставляли
    jarvis_cmd навсегда переключиться на самый слабый движок;
  * wake word ищется нечётко: "джарвес", "джарис", "чарльз" тоже подходят.
"""
import threading

from rapidfuzz import fuzz

from config import (LISTEN_TIMEOUT, SAMPLE_RATE, SENSITIVITY,
                    SILENCE_TIMEOUT, WAIT_WORDS)
from speech import asr as asr_mod
from speech.audio import SOURCE
from speech.recorder import Recorder, make_gate
from speech.sounds import play_voice  # noqa: F401  (обратная совместимость)

_play_voice = play_voice

WAKE_FUZZY_THRESHOLD = 80


def reset_audio():
    """Совместимость со старым API."""
    SOURCE.noise_floor = 0.0


class STT:
    def __init__(self, backend=None, hotwords=None, gate=None):
        self.asr, self.errors = asr_mod.create(preferred=backend, hotwords=hotwords)
        if self.asr is None:
            raise RuntimeError("ни один движок распознавания не поднялся: "
                               + "; ".join(self.errors))
        self.backend = self.asr.name
        self._cancel = threading.Event()
        self._gate = gate if gate is not None else make_gate()
        self._rec = Recorder(
            sensitivity=SENSITIVITY,
            silence_timeout=min(SILENCE_TIMEOUT, 1.6),
            max_phrase=LISTEN_TIMEOUT,
            gate=self._gate,
        )
        self._q = None
        self._hotwords = list(hotwords or [])
        self._last_pcm = None
        self._latin_asr = False        # False = ещё не пробовали, None = не завёлся

    # ------------------------------------------------------------ приём

    def _ensure(self):
        if not SOURCE.running:
            SOURCE.start()
        if self._q is None:
            self._q = SOURCE.subscribe()

    def _cancelled(self):
        return self._cancel.is_set()

    def _capture(self, wait_timeout=None):
        self._ensure()
        # Не пишем, пока JARVIS ещё говорит — иначе начало ответа теряется
        SOURCE.wait_unmuted()
        SOURCE.drain(self._q)
        pcm = self._rec.record(self._q, wait_timeout=wait_timeout, cancel=self._cancelled)
        if not pcm:
            return ""
        self._last_pcm = pcm
        seconds = len(pcm) / 2 / SAMPLE_RATE
        try:
            text = self.asr.transcribe(pcm)
        except Exception as e:
            print(f"[STT] {self.backend}: {e}", flush=True)
            return ""
        print(f"[STT] записано {seconds:.1f}с -> {text!r}", flush=True)
        return text

    # ------------------------------------------------------------- API

    def listen_phrase(self, wait_timeout=None):
        text = self._capture(wait_timeout=wait_timeout)
        text = (text or "").strip()
        return text if self._is_valid(text) else None

    def wait_for_wake_word(self, wait_timeout=None):
        """Ждать «джарвис». Возвращает всю распознанную фразу."""
        while not self._cancelled():
            text = self._capture(wait_timeout=wait_timeout)
            if not text:
                if wait_timeout:
                    return None
                continue
            if self.contains_wake_word(text):
                return text.strip()
        return None

    @staticmethod
    def contains_wake_word(text: str) -> bool:
        low = (text or "").lower()
        if any(w in low for w in WAIT_WORDS):
            return True
        for word in low.split():
            if len(word) < 5:
                continue
            for wake in WAIT_WORDS:
                if fuzz.ratio(word, wake) >= WAKE_FUZZY_THRESHOLD:
                    return True
        return False

    @staticmethod
    def strip_wake_word(text: str) -> str:
        words = (text or "").split()
        out = []
        dropped = False
        for w in words:
            clean = w.strip(".,!?;:").lower()
            if not dropped and len(clean) >= 5 and any(
                fuzz.ratio(clean, wake) >= WAKE_FUZZY_THRESHOLD for wake in WAIT_WORDS
            ):
                dropped = True
                continue
            out.append(w)
        return " ".join(out).strip(" ,.!?")

    @staticmethod
    def _is_valid(text: str) -> bool:
        t = (text or "").strip()
        if len(t) < 3:
            return False
        words = t.split()
        return not (len(words) == 1 and len(words[0]) < 3)

    def set_hotwords(self, words):
        self._hotwords = list(words or [])
        for engine in (self.asr, self._latin_asr):
            if engine and engine is not True:
                try:
                    engine.set_hotwords(self._hotwords)
                except Exception:
                    pass

    def retry_latin(self):
        """
        Второй проход по ТОЙ ЖЕ записи движком, который умеет латиницу.

        GigaAM всегда отдаёт кириллицу: "открой obsidian" приходит как
        "открой обсидиан". Для названий из каталога это неважно —
        сопоставление идёт по звучанию. Но если фраза никуда не легла,
        имеет смысл переспросить Whisper: он держит латиницу в словаре
        и получает список установленных программ через hotwords.
        Вызывается только при неудаче, поэтому на скорость не влияет.
        """
        if not self._last_pcm:
            return ""
        if self._latin_asr is False:
            try:
                self._latin_asr = asr_mod.WhisperASR()
                self._latin_asr.set_hotwords(self._hotwords)
                print("[STT] второй движок: whisper (латиница)", flush=True)
            except Exception as e:
                print(f"[STT] whisper недоступен: {e}", flush=True)
                self._latin_asr = None
        if not self._latin_asr:
            return ""
        try:
            text = (self._latin_asr.transcribe(self._last_pcm) or "").strip()
        except Exception as e:
            print(f"[STT] whisper: {e}", flush=True)
            return ""
        if text:
            print(f"[STT] whisper -> {text!r}", flush=True)
        return text

    def cancel(self):
        self._cancel.set()

    def resume(self):
        self._cancel.clear()

    def stop(self):
        self._cancel.set()
        if self._q is not None:
            SOURCE.unsubscribe(self._q)
            self._q = None
        try:
            self.asr.close()
        except Exception:
            pass
