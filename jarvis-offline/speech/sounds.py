"""
Воспроизведение голосовых клипов JARVIS.

Раньше клипы игрались через winsound.PlaySound, который умеет ТОЛЬКО WAV.
В config.py при этом были прописаны наборы .mp3 (voices/jarvis/*, клоны
голоса Пола Беттани) — они не звучали никогда, вызов молча выходил.
Здесь mp3 играется через MCI (winmm.dll), без сторонних зависимостей.

На время проигрывания микрофон заглушается, иначе распознавание слышит
собственные реплики ассистента.
"""
import ctypes
import os
import random
import threading
import time

import winsound

from config import VOICE, VOICES_PATH

_winmm = ctypes.windll.winmm
_lock = threading.Lock()
_alias_n = 0


def _mute_mic(on):
    try:
        from speech.audio import SOURCE
        SOURCE.mute(on)
    except Exception:
        pass


def _mci(cmd):
    buf = ctypes.create_unicode_buffer(256)
    err = _winmm.mciSendStringW(cmd, buf, 254, 0)
    return err, buf.value


def _play_mp3(path):
    global _alias_n
    _alias_n += 1
    alias = f"jarvisclip{_alias_n}"
    err, _ = _mci(f'open "{path}" type mpegvideo alias {alias}')
    if err:
        return False
    try:
        _mci(f"play {alias} wait")
    finally:
        _mci(f"close {alias}")
    return True


def _play_wav(path):
    winsound.PlaySound(path, winsound.SND_FILENAME | winsound.SND_NODEFAULT)
    return True


def play_file(path, blocking=True):
    if not path or not os.path.isfile(path):
        return False

    def _run():
        _mute_mic(True)
        try:
            if path.lower().endswith(".mp3"):
                _play_mp3(path)
            else:
                _play_wav(path)
        except Exception as e:
            print(f"[SND] {os.path.basename(path)}: {e}", flush=True)
        finally:
            time.sleep(0.15)
            _mute_mic(False)

    if blocking:
        with _lock:
            _run()
        return True
    threading.Thread(target=_run, daemon=True).start()
    return True


def play_voice(category, blocking=True):
    """Проиграть случайный клип из категории config.VOICE."""
    files = VOICE.get(category)
    if not files:
        return False
    existing = [f for f in files if os.path.isfile(os.path.join(VOICES_PATH, f))]
    if not existing:
        return False
    return play_file(os.path.join(VOICES_PATH, random.choice(existing)), blocking)


# обратная совместимость со старым импортом из speech.stt
_play_voice = play_voice
