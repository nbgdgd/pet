import os
import json

BASE = os.path.dirname(__file__)
MODEL_PATH = os.path.join(BASE, "models", "vosk-ru")
VOICES_PATH = os.path.join(BASE, "voices")
SAMPLE_RATE = 16000

WAIT_WORDS = ["джарвис", "jarvis", "джэрвис", "джарвиз", "джарвес"]
CONFIDENCE_THRESHOLD = 72

# Укажите номер микрофона (см. py main.py --list-mics)
# или оставьте None для устройства по умолчанию
MICROPHONE_INDEX = 0  # Микрофон (Realtek Audio)

SILENCE_TIMEOUT = 1.4      # пауза, после которой фраза считается законченной
LISTEN_TIMEOUT = 12        # максимальная длина одной фразы
SENSITIVITY = 3.0          # множитель шумового порога (запасной VAD)

# === РАСПОЗНАВАНИЕ РЕЧИ ===
# gigaam   — офлайн, ~8% WER на русском, ~90 мс на команду (по умолчанию)
# whisper  — офлайн, единственный, кто выводит латиницу как латиницу
# vosk     — офлайн, запасной, старая маленькая модель
# deepgram — онлайн, нужен DEEPGRAM_API_KEY в переменных окружения
STT_BACKEND = os.environ.get("JARVIS_STT", "gigaam")

# Ключ Deepgram берётся ТОЛЬКО из переменной окружения.
# Раньше он лежал прямо в коде speech/deepgram_stt.py и попадал
# в сборку JARVIS.exe и в любую копию репозитория.
DEEPGRAM_API_KEY = os.environ.get("DEEPGRAM_API_KEY", "")

# === СИНТЕЗ РЕЧИ ===
# eugene/aidar/xenia/baya/kseniya — Silero v5 (48 кГц)
# irina/denis/dmitri              — Piper (22 кГц)
TTS_VOICE = os.environ.get("JARVIS_VOICE", "eugene")

# Озвучивать ли текстовые ответы голосом TTS (иначе — только клипы JARVIS)
SPEAK_RESULTS = True

SEPARATORS = ["и", "потом", "затем", "после этого", "а потом", "далее"]
FILTER_WORDS = ["пожалуйста", "ну", "давай", "короче", "типа"]

# === НОВЫЕ НАСТРОЙКИ ===

# Режим wake word: True — ждать "джарвис" перед каждой командой
# False — слушать команды без wake word (требуется push-to-talk)
WAKE_WORD_REQUIRED = False

# Подтверждение опасных команд (shutdown, restart, lock)
CONFIRM_DANGEROUS = True

# Текстовый fallback: горячая клавиша для ввода команды вручную
TEXT_FALLBACK_HOTKEY = "ctrl+shift+t"

# Обучение голосу: добавлять успешные распознавания в voice profile
VOICE_LEARNING_ENABLED = True

# Путь к директории профилей голоса
PROFILES_DIR = os.path.join(BASE, "profiles")

# Путь к логам
LOGS_DIR = os.path.join(BASE, "logs")

# === DANGEROUS COMMANDS (нуждаются в подтверждении) ===
DANGEROUS_INTENTS = [
    "system.shutdown_pc",
    "system.restart_pc",
    "system.lock_pc",
    "system.log_off",
    "system.hibernate_pc",
    "system.sleep_pc",
]

VOICE = {
    "greet": ["og/run.wav"],
    "reply": ["og/reply1.wav", "og/reply2.wav", "og/reply3.wav"],
    "ok": ["og/ok1.wav", "og/ok2.wav", "og/ok3.wav", "og/ok4.wav"],
    "not_found": ["og/not_found.wav"],
    "thanks": ["og/thanks.wav"],
    "goodbye": ["og/off.wav"],
    "stupid": ["og/stupid.wav"],
    "confirm": ["og/confirm.wav"],
    # JARVIS voice clips (ElevenLabs clone, Paul Bettany)
    "jarvis_acknowledge": ["jarvis/acknowledge_1.mp3", "jarvis/acknowledge_2.mp3", "jarvis/acknowledge_3.mp3", "jarvis/acknowledge_4.mp3", "jarvis/acknowledge_5.mp3", "jarvis/acknowledge_6.mp3", "jarvis/acknowledge_7.mp3", "jarvis/acknowledge_8.mp3"],
    "jarvis_complete": ["jarvis/complete_1.mp3", "jarvis/complete_2.mp3", "jarvis/complete_3.mp3", "jarvis/complete_4.mp3", "jarvis/complete_5.mp3", "jarvis/complete_6.mp3", "jarvis/complete_7.mp3"],
    "jarvis_session_start": ["jarvis/session_start_1.mp3", "jarvis/session_start_2.mp3", "jarvis/session_start_3.mp3", "jarvis/session_start_4.mp3", "jarvis/session_start_5.mp3"],
}
