"""
Совместимость со старым импортом `from speech.deepgram_stt import DeepgramSTT`.

Вся логика переехала:
  * микрофон        -> speech/audio.py
  * выделение фразы -> speech/recorder.py
  * движки          -> speech/asr.py  (класс DeepgramASR)
  * фасад           -> speech/stt.py

Прежний DeepgramSTT не работал вовсе: send_media вызывался у контекстного
менеджера, а не у соединения, и падал на каждом кадре внутри `except: pass`.
"""
from speech.audio import SOURCE
from speech.sounds import play_voice as _play_voice  # noqa: F401
from speech.stt import STT


def reset_audio():
    SOURCE.noise_floor = 0.0


class DeepgramSTT(STT):
    def __init__(self, hotwords=None):
        super().__init__(backend="deepgram", hotwords=hotwords)
