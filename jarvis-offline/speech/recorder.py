"""
Выделение отдельной фразы из потока (endpointing).

Отличия от прежней логики в speech/stt.py:
  * нет busy-wait: раньше цикл `while True: if not q.empty()` крутился
    без пауз и жёг ядро процессора целиком, пока никто не говорил;
    здесь блокирующий q.get(timeout=...);
  * гистерезис: начало речи фиксируется по высокому порогу, конец —
    по низкому, поэтому тихие окончания слов не режутся;
  * пре-ролл: 300 мс звука ДО момента срабатывания VAD попадают в
    запись, иначе у каждой фразы отгрызалось начало ("...кажи время");
  * ограничение сверху: фраза не может длиться вечно.
"""
import queue
import time

import numpy as np

from speech.audio import SOURCE

PRE_ROLL_FRAMES = 5          # 500 мс до начала речи — начало слова не срезается
MIN_SPEECH_FRAMES = 2        # короче 200 мс — это щелчок, не речь
VAD_CHUNK = 512              # silero-vad на 16 кГц принимает ровно 512 сэмплов
STALE_AFTER = 0.7            # кадры старше — мусор, см. пояснение в record()


class SileroGate:
    """
    Нейросетевой детектор речи. Отличает голос от стука клавиш, музыки
    и шума кулера — чего энергетический порог не умеет в принципе.
    Если пакет не установлен, класс не создаётся и работает запасной
    энергетический детектор.
    """

    def __init__(self, threshold=0.5):
        from silero_vad import load_silero_vad
        self._model = load_silero_vad(onnx=True)
        self._torch = __import__("torch")
        self.threshold = threshold
        self._tail = np.zeros(0, dtype=np.float32)

    def reset(self):
        try:
            self._model.reset_states()
        except Exception:
            pass
        self._tail = np.zeros(0, dtype=np.float32)

    def is_speech(self, pcm: bytes) -> bool:
        audio = np.frombuffer(pcm, dtype=np.int16).astype(np.float32) / 32768.0
        audio = np.concatenate([self._tail, audio])
        voiced = False
        i = 0
        while i + VAD_CHUNK <= audio.size:
            chunk = self._torch.from_numpy(audio[i:i + VAD_CHUNK])
            if float(self._model(chunk, 16000).item()) >= self.threshold:
                voiced = True
            i += VAD_CHUNK
        self._tail = audio[i:]
        return voiced


def make_gate(threshold=0.5):
    try:
        return SileroGate(threshold)
    except Exception as e:
        print(f"[VAD] silero-vad недоступен ({e}), работаю по громкости", flush=True)
        return None


class Recorder:
    def __init__(self, sensitivity=3.0, silence_timeout=1.2,
                 max_phrase=12.0, floor_min=120.0, gate=None):
        self.sensitivity = sensitivity
        self.silence_timeout = silence_timeout
        self.max_phrase = max_phrase
        self.floor_min = floor_min
        self.gate = gate

    def _voiced(self, frame, hi, lo, speaking):
        if self.gate is not None:
            return self.gate.is_speech(frame.pcm)
        return frame.rms > (lo if speaking else hi)

    def record(self, q, wait_timeout=None, cancel=None):
        """
        Ждать речь в очереди кадров и вернуть PCM фразы (bytes) или None.

        wait_timeout — сколько секунд ждать НАЧАЛА речи (None = бесконечно).
        """
        pre = []
        buf = []
        speaking = False
        started = time.time()
        last_voice = time.time()
        voiced_frames = 0
        if self.gate is not None:
            self.gate.reset()

        while True:
            if cancel is not None and cancel():
                return None
            if not speaking and wait_timeout and time.time() - started > wait_timeout:
                return None
            if speaking and time.time() - last_voice > self.silence_timeout:
                break
            if speaking and time.time() - started > self.max_phrase:
                break

            try:
                frame = q.get(timeout=0.3)
            except queue.Empty:
                continue

            # Пока шло распознавание и выполнение прошлой команды, микрофон
            # продолжал писать — в очереди лежит хвост УЖЕ обработанной фразы.
            # Без этой отбраковки запись начиналась с огрызка ("откой" вместо
            # "открой аниблейз"): детектор срабатывал на старом кадре,
            # набирал обрывок и по таймеру тишины возвращал его как команду.
            # Отбрасываем всё, что старше STALE_AFTER, но только ДО начала
            # речи — внутри уже идущей фразы кадры не выбрасываем никогда.
            if not speaking and time.time() - frame.ts > STALE_AFTER:
                continue

            hi = SOURCE.threshold(self.sensitivity, self.floor_min)
            lo = hi * 0.55                      # гистерезис на спад
            voiced = self._voiced(frame, hi, lo, speaking)

            if not speaking:
                pre.append(frame.pcm)
                if len(pre) > PRE_ROLL_FRAMES:
                    pre.pop(0)
                if voiced:
                    speaking = True
                    started = time.time()
                    last_voice = time.time()
                    buf = list(pre)
                    voiced_frames = 1
            else:
                buf.append(frame.pcm)
                if voiced:
                    last_voice = time.time()
                    voiced_frames += 1

        if voiced_frames < MIN_SPEECH_FRAMES or not buf:
            return None
        return b"".join(buf)
