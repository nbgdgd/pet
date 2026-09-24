import sys, os, json, time, queue, winsound, traceback

LOG = []
def log(msg):
    LOG.append(msg)
    print(msg)

def section(title):
    log(f"\n{'='*60}")
    log(f"  {title}")
    log(f"{'='*60}")

section("SISTEM INFO")
log(f"Python: {sys.version}")
log(f"OS: {sys.platform}")
log(f"CWD: {os.getcwd()}")
log(f"Script dir: {os.path.dirname(__file__)}")

section("CONFIG")
sys.path.insert(0, r'D:\AndroidProjects\AniBlaze\jarvis-offline')
import config
log(f"MODEL_PATH: {config.MODEL_PATH}")
log(f"Model dir exists: {os.path.isdir(config.MODEL_PATH)}")
log(f"am/final.mdl exists: {os.path.isfile(os.path.join(config.MODEL_PATH, 'am', 'final.mdl'))}")
log(f"graph/HCLr.fst exists: {os.path.isfile(os.path.join(config.MODEL_PATH, 'graph', 'HCLr.fst'))}")
total_size = sum(os.path.getsize(os.path.join(dp, f)) for dp, dn, fn in os.walk(config.MODEL_PATH) for f in fn)
log(f"Model total size: {total_size / 1024 / 1024:.1f} MB")

section("VOICE FILES")
for cat in ["og", "howdy", "remaster"]:
    d = os.path.join(config.VOICES_PATH, cat)
    if os.path.isdir(d):
        files = [f for f in os.listdir(d) if f.endswith(('.wav','.mp3'))]
        log(f"  {cat}/: {len(files)} files")
    else:
        log(f"  {cat}/: MISSING")

section("SOUNDDEVICE")
try:
    import sounddevice as sd
    devices = sd.query_devices()
    log(f"Total devices: {len(devices)}")
    default_input = sd.default.device[0]
    default_output = sd.default.device[1]
    log(f"Default input:  [{default_input}] {devices[default_input]['name']}" if default_input else "Default input: NONE")
    log(f"Default output: [{default_output}] {devices[default_output]['name']}" if default_output else "Default output: NONE")
    for i, d in enumerate(devices):
        if d['max_input_channels'] > 0:
            log(f"  IN  [{i}] {d['name']} (ch={d['max_input_channels']}, rate={d['default_samplerate']})")
except Exception as e:
    log(f"ERROR: {e}")

section("VOSK MODEL LOAD")
try:
    from vosk import Model, KaldiRecognizer
    t0 = time.time()
    model = Model(config.MODEL_PATH)
    t1 = time.time()
    log(f"Model loaded in {t1-t0:.1f}s")
    rec = KaldiRecognizer(model, 16000)
    log("Recognizer created OK")
except Exception as e:
    log(f"ERROR: {e}\n{traceback.format_exc()}")

section("TTS (pyttsx3)")
try:
    import pyttsx3
    engine = pyttsx3.init()
    voices = engine.getProperty('voices')
    log(f"Voices: {len(voices)}")
    for v in voices:
        log(f"  {v.id}: {v.name} lang={v.languages}")
    engine.say("Тест")
    engine.runAndWait()
    log("pyttsx3 speak OK")
except Exception as e:
    log(f"ERROR: {e}")

section("VOICE PLAYBACK (winsound)")
try:
    test_file = os.path.join(config.VOICES_PATH, "og", "run.wav")
    winsound.PlaySound(test_file, winsound.SND_FILENAME | winsound.SND_NODEFAULT)
    log(f"winsound playback OK: {test_file}")
except Exception as e:
    log(f"ERROR: {e}")

section("MICROPHONE RECORD TEST")
try:
    import numpy as np
    import sounddevice as sd
    log("Recording 2 seconds of audio...")
    rec = sd.rec(int(2 * 16000), samplerate=16000, channels=1, dtype='int16')
    sd.wait()
    volume = np.linalg.norm(rec) * 10
    max_val = np.max(np.abs(rec))
    log(f"RMS volume: {volume:.2f}")
    log(f"Max sample: {max_val}")
    if max_val < 50:
        log("WARNING: Very low mic input! Check microphone.")
    elif max_val < 500:
        log("OK: Mic working, but quiet. You may need to speak louder.")
    else:
        log("GOOD: Mic input level is healthy.")
except Exception as e:
    log(f"ERROR: {e}")

section("VOSK RECOGNITION TEST")
try:
    if max_val > 50:
        from vosk import Model, KaldiRecognizer
        model = Model(config.MODEL_PATH)
        rec_engine = KaldiRecognizer(model, 16000)
        rec_engine.AcceptWaveform(rec.tobytes())
        result = json.loads(rec_engine.Result())
        text = result.get("text", "")
        log(f"Recognition result: '{text}'")
        if text:
            log("VOICE DETECTED: Mic + Vosk pipeline works!")
        else:
            log("No speech detected in recording (silence or noise)")
    else:
        log("Skipping Vosk test: mic too quiet")
except Exception as e:
    log(f"ERROR: {e}")

section("ROUTER TEST")
# класс называется IntentRouter; импорт `Router` падал ImportError и
# обрывал диагностику ровно на середине
from commands.router import IntentRouter
from commands.registry import COMMANDS
r = IntentRouter(COMMANDS)
tests = ["закрой проводник", "открой проводник", "который час", "какая дата",
         "выключи звук", "громче", "открой aniblaze"]
for t in tests:
    result = r.detect(t)
    if result:
        log(f"  '{t}' -> {result[0][0].__name__} ({result[0][1]:.0f}%)")
    else:
        log(f"  '{t}' -> NO MATCH (уйдёт в каталог программ)")

section("APP INDEX TEST")
try:
    from commands.app_index import INDEX
    INDEX.load_or_build(background=False)
    log(f"  программ в каталоге: {len(INDEX.entries)}")
    for q in ["aniblaze", "аниблейз", "телеграм", "стим"]:
        e, s = INDEX.resolve(q)
        log(f"  '{q}' -> {e['name'] if e else 'NO MATCH'} ({s}%)")
except Exception as e:
    log(f"ERROR: {e}")

section("ASR / TTS TEST")
try:
    from speech import asr as asr_mod
    a, errs = asr_mod.create()
    log(f"  ASR: {a.name if a else 'НЕТ'}  пропущено: {errs}")
    from tts import engine as tts_engine
    log(f"  TTS: {tts_engine.VOICE_NAME}, модель {os.path.basename(tts_engine.SILERO_PATH)}")
except Exception as e:
    log(f"ERROR: {e}")

section("SUMMARY")
log(f"\nLog saved to debug_report.txt")
log(f"Total log lines: {len(LOG)}")

log_path = os.path.join(os.path.dirname(__file__), "debug_report.txt")
with open(log_path, "w", encoding="utf-8") as f:
    f.write("\n".join(LOG))

log(f"\nOpen debug_report.txt to see full results.")
