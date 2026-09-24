"""
Движки распознавания речи. Все принимают PCM int16 16 кГц и возвращают текст.

Почему именно так:

  GigaAM v3 (по умолчанию, офлайн)
      8.4% WER на русском против 22–31% у vosk-model-small-ru, ~90 мс на
      команду на голом CPU, чистый onnxruntime без CUDA и PyTorch.
      Латиницу транслитерирует в кириллицу ("aniblaze" -> "аниблейз") —
      это НЕ проблема: commands/app_index.py сопоставляет по фонетическому
      скелету, и "аниблейз" находит AniBlaze.

  Whisper (faster-whisper, офлайн)
      Единственный офлайн-движок, который умеет ВЫВОДИТЬ латиницу.
      Через hotwords= в него подсовываются названия установленных
      программ. Медленнее GigaAM на CPU.

  Vosk (офлайн, запасной)
      Остаётся только как аварийный вариант: маленькая модель, закрытый
      кириллический словарь, латиницу выдать физически не может.

  Deepgram (онлайн, опционально)
      Прежний код НИ РАЗУ не отправил в Deepgram ни одного байта:
      listen.v2.connect — контекстный менеджер, а send_media вызывался
      у самого генератора, а не у объекта из __enter__. Исключение
      глоталось голым `except: pass`, поэтому распознавание просто молчало.
      Здесь это исправлено, плюс добавлены language_hint=[ru,en] и
      keyterm со списком программ — именно они сохраняют латиницу.
"""
import os
import threading

import numpy as np

BASE = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
os.environ.setdefault("HF_HOME", os.path.join(BASE, "models", "hf"))
os.environ.setdefault("HF_HUB_DISABLE_SYMLINKS_WARNING", "1")


def pcm_to_float(pcm: bytes) -> np.ndarray:
    return np.frombuffer(pcm, dtype=np.int16).astype(np.float32) / 32768.0


class BaseASR:
    name = "base"
    ready = False

    def transcribe(self, pcm: bytes) -> str:
        raise NotImplementedError

    def set_hotwords(self, words):
        pass

    def close(self):
        pass


# ------------------------------------------------------------------ GigaAM

class GigaAmASR(BaseASR):
    name = "gigaam"

    def __init__(self, model_name="gigaam-v3-ctc"):
        import onnx_asr
        self._model = onnx_asr.load_model(model_name)
        self._lock = threading.Lock()
        self.ready = True

    def transcribe(self, pcm: bytes) -> str:
        audio = pcm_to_float(pcm)
        if audio.size < 1600:
            return ""
        with self._lock:
            return (self._model.recognize(audio, sample_rate=16000) or "").strip()


# ----------------------------------------------------------------- Whisper

class WhisperASR(BaseASR):
    name = "whisper"

    def __init__(self, model_size="small", compute_type="int8", device="cpu"):
        from faster_whisper import WhisperModel
        self._model = WhisperModel(
            model_size,
            device=device,
            compute_type=compute_type,
            cpu_threads=max(4, (os.cpu_count() or 8) // 2),
            download_root=os.path.join(BASE, "models", "whisper"),
        )
        self._hotwords = ""
        self._lock = threading.Lock()
        self.ready = True

    def set_hotwords(self, words):
        # бюджет подсказки ~223 токена; самые важные слова — в конец,
        # внимание декодера смещено к хвосту промпта
        if not words:
            self._hotwords = ""
            return
        self._hotwords = "Команды: открой, закрой, запусти. Программы: " + ", ".join(words[:60]) + "."

    def transcribe(self, pcm: bytes) -> str:
        audio = pcm_to_float(pcm)
        if audio.size < 1600:
            return ""
        with self._lock:
            segments, _info = self._model.transcribe(
                audio,
                language="ru",
                beam_size=5,
                hotwords=self._hotwords or None,
                condition_on_previous_text=False,   # иначе команды «залипают» друг за друга
                temperature=[0.0],
                no_speech_threshold=0.6,
                without_timestamps=True,
            )
            return " ".join(s.text for s in segments).strip()


# -------------------------------------------------------------------- Vosk

class VoskASR(BaseASR):
    name = "vosk"

    def __init__(self, model_path=None, grammar=None):
        import json
        from vosk import KaldiRecognizer, Model
        from config import MODEL_PATH, SAMPLE_RATE
        self._json = json
        self._model = Model(model_path or MODEL_PATH)
        if grammar:
            self._rec = KaldiRecognizer(
                self._model, SAMPLE_RATE, json.dumps(list(grammar) + ["[unk]"], ensure_ascii=False)
            )
        else:
            self._rec = KaldiRecognizer(self._model, SAMPLE_RATE)
        self._lock = threading.Lock()
        self.ready = True

    def transcribe(self, pcm: bytes) -> str:
        with self._lock:
            self._rec.AcceptWaveform(pcm)
            text = self._json.loads(self._rec.FinalResult()).get("text", "").strip()
            self._rec.Reset()
        return "" if text == "[unk]" else text


# ---------------------------------------------------------------- Deepgram

class DeepgramASR(BaseASR):
    """
    Онлайн-распознавание. Держит один websocket и отдаёт текст по
    завершении реплики (EndOfTurn). Используется только если задан
    DEEPGRAM_API_KEY.
    """
    name = "deepgram"

    def __init__(self, api_key=None, model="flux-general-multi", keyterms=None):
        import queue as qmod
        from deepgram import DeepgramClient
        from config import SAMPLE_RATE

        key = api_key or os.environ.get("DEEPGRAM_API_KEY", "")
        if not key:
            raise RuntimeError("нет DEEPGRAM_API_KEY")

        self._sr = SAMPLE_RATE
        self._model = model
        self._keyterms = list(keyterms or [])[:100]
        self._client = DeepgramClient(api_key=key)
        self._results = qmod.Queue()
        self._cm = None
        self._conn = None
        self._thread = None
        self._lock = threading.Lock()
        self._connect()
        self.ready = self._conn is not None

    def _connect(self):
        try:
            kwargs = dict(
                model=self._model,
                encoding="linear16",
                sample_rate=str(self._sr),
                # Flux Multilingual: подсказка обоих языков — то, ради чего
                # он и сделан. Именно это сохраняет "aniblaze" латиницей.
                language_hint=["ru", "en"],
                eot_threshold="0.7",
                eot_timeout_ms="1500",
            )
            if self._keyterms:
                kwargs["keyterm"] = self._keyterms
            self._cm = self._client.listen.v2.connect(**kwargs)
            # ВАЖНО: работать нужно с объектом из __enter__, а не с самим
            # контекстным менеджером. На менеджере нет send_media.
            self._conn = self._cm.__enter__()
            self._conn.on("message", self._on_message)
            self._thread = threading.Thread(target=self._conn.start_listening, daemon=True)
            self._thread.start()
        except Exception as e:
            print(f"[ASR] Deepgram connect failed: {e}", flush=True)
            self._cm = self._conn = None

    def _on_message(self, msg):
        try:
            typ = getattr(msg, "type", "") or ""
            text = getattr(msg, "transcript", None)
            if text is None:
                ch = getattr(msg, "channel", None)
                if ch and getattr(ch, "alternatives", None):
                    text = ch.alternatives[0].transcript
            text = (text or "").strip()
            if text:
                self._results.put((typ, text))
        except Exception as e:
            print(f"[ASR] Deepgram message error: {e}", flush=True)

    def transcribe(self, pcm: bytes) -> str:
        import queue as qmod
        if self._conn is None:
            self._connect()
            if self._conn is None:
                return ""
        with self._lock:
            while True:                       # выбросить хвост прошлой фразы
                try:
                    self._results.get_nowait()
                except qmod.Empty:
                    break
            step = self._sr * 2 // 10         # куски по 100 мс
            try:
                for i in range(0, len(pcm), step):
                    self._conn.send_media(pcm[i:i + step])
            except Exception as e:
                print(f"[ASR] Deepgram send failed: {e}", flush=True)
                self.close()
                return ""
            best = ""
            deadline = 3.0
            while deadline > 0:
                try:
                    typ, text = self._results.get(timeout=0.25)
                except qmod.Empty:
                    deadline -= 0.25
                    continue
                best = text
                if "EndOfTurn" in typ or "Final" in typ:
                    break
            return best

    def close(self):
        try:
            if self._cm is not None:
                self._cm.__exit__(None, None, None)
        except Exception:
            pass
        self._cm = self._conn = None


# ------------------------------------------------------------------ выбор

ORDER = ("gigaam", "whisper", "vosk", "deepgram")


def create(preferred=None, hotwords=None):
    """
    Поднять лучший доступный движок. Возвращает (asr, [ошибки]).
    """
    errors = []
    names = list(ORDER)
    if preferred:
        names = [preferred] + [n for n in names if n != preferred]

    for name in names:
        try:
            if name == "gigaam":
                asr = GigaAmASR()
            elif name == "whisper":
                asr = WhisperASR()
            elif name == "vosk":
                asr = VoskASR()
            elif name == "deepgram":
                asr = DeepgramASR(keyterms=hotwords)
            else:
                continue
            asr.set_hotwords(hotwords or [])
            return asr, errors
        except Exception as e:
            errors.append(f"{name}: {e}")
    return None, errors
