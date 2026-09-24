"""
Единственный источник звука с микрофона.

Раньше каждый объект STT открывал СВОЙ RawInputStream, а очередь кадров
была общей на уровне модуля. Когда jarvis_cmd параллельно создавал второй
STT (пункт меню «сказать команду сейчас»), на одном устройстве висело два
потока записи, а два потребителя растаскивали кадры из одной очереди —
половина речи уходила не туда. Отсюда «то слышит, то не слышит».

Здесь поток записи ровно один. Слушатели подписываются и получают
собственные очереди кадров.

Ещё две вещи, которые чинятся именно тут:
  * шумовой порог не обновляется, пока говорит сам JARVIS
    (иначе ассистент считает свой голос фоновым шумом и глохнет);
  * кадры, записанные во время его собственной речи, вообще
    не попадают подписчикам — самоперехват исключён.
"""
import math
import queue
import threading
import time

import numpy as np
import sounddevice as sd

from config import MICROPHONE_INDEX, SAMPLE_RATE

FRAME_MS = 100
FRAME_SAMPLES = SAMPLE_RATE * FRAME_MS // 1000


def _is_tts_speaking():
    try:
        from tts import engine as tts_engine
        return tts_engine.is_speaking()
    except Exception:
        return False


class Frame:
    __slots__ = ("pcm", "rms", "ts")

    def __init__(self, pcm, rms, ts):
        self.pcm = pcm
        self.rms = rms
        self.ts = ts


class AudioSource:
    def __init__(self):
        self._stream = None
        self._subs = []
        self._lock = threading.Lock()
        self.noise_floor = 0.0
        self._alpha = 0.05
        self._device = MICROPHONE_INDEX
        self._muted = False
        self._started_at = 0.0

    # ------------------------------------------------------------- поток

    def start(self):
        with self._lock:
            if self._stream is not None:
                return True
            opts = dict(
                samplerate=SAMPLE_RATE,
                blocksize=FRAME_SAMPLES,
                dtype="int16",
                channels=1,
                callback=self._callback,
            )
            for device in (self._device, None):
                try:
                    if device is not None:
                        opts["device"] = device
                    else:
                        opts.pop("device", None)
                    self._stream = sd.RawInputStream(**opts)
                    self._stream.start()
                    self._device = device
                    self._started_at = time.time()
                    print(f"[AUDIO] микрофон открыт (device={device})", flush=True)
                    return True
                except Exception as e:
                    print(f"[AUDIO] device={device} не открылся: {e}", flush=True)
                    self._stream = None
            return False

    def stop(self):
        with self._lock:
            if self._stream is None:
                return
            try:
                self._stream.stop()
                self._stream.close()
            except Exception:
                pass
            self._stream = None
            print("[AUDIO] микрофон закрыт", flush=True)

    @property
    def running(self):
        return self._stream is not None

    def _callback(self, indata, frames, time_info, status):
        pcm = bytes(indata)
        a = np.frombuffer(pcm, dtype=np.int16)
        if a.size == 0:
            return
        rms = float(math.sqrt(float(np.mean(a.astype(np.float32) ** 2)) + 1e-9))

        speaking = self._muted or _is_tts_speaking()
        if not speaking:
            self.noise_floor = (1 - self._alpha) * self.noise_floor + self._alpha * rms
            frame = Frame(pcm, rms, time.time())
            for q in list(self._subs):
                try:
                    q.put_nowait(frame)
                except queue.Full:
                    try:
                        q.get_nowait()          # выбрасываем самый старый кадр
                        q.put_nowait(frame)
                    except queue.Empty:
                        pass

    # -------------------------------------------------------- подписчики

    def subscribe(self, maxsize=200):
        q = queue.Queue(maxsize=maxsize)
        self._subs.append(q)
        return q

    def unsubscribe(self, q):
        try:
            self._subs.remove(q)
        except ValueError:
            pass

    @staticmethod
    def drain(q):
        while True:
            try:
                q.get_nowait()
            except queue.Empty:
                return

    def mute(self, on=True):
        self._muted = on

    def is_muted(self):
        return self._muted or _is_tts_speaking()

    def wait_unmuted(self, timeout=8.0):
        """
        Дождаться, пока JARVIS договорит.

        Без этого слушающий цикл начинал запись сразу после команды, когда
        клип подтверждения ещё играл и микрофон был заглушён: первые секунды
        ответа пользователя просто не попадали в запись, и фраза обрывалась.
        """
        deadline = time.time() + timeout
        while self.is_muted() and time.time() < deadline:
            time.sleep(0.05)
        return not self.is_muted()

    def threshold(self, sensitivity, floor_min=120.0):
        """Порог VAD: либо базовый минимум, либо шумовой фон с запасом."""
        return max(floor_min, self.noise_floor * sensitivity + 8.0)


SOURCE = AudioSource()
