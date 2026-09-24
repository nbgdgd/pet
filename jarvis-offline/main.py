import sys, time, random, traceback, os, threading
import numpy as np
import sounddevice as sd
from speech.stt import STT, _play_voice, reset_audio
from tts import engine as tts
from commands.router import IntentRouter
from commands.registry import COMMANDS
from commands.voice_profile import VoiceProfile
from commands.command_log import CommandLog
from commands.command_history import CommandHistory, review_mode
from config import (
    WAIT_WORDS, MICROPHONE_INDEX as CFG_MIC, MODEL_PATH,
    WAKE_WORD_REQUIRED, CONFIRM_DANGEROUS, TEXT_FALLBACK_HOTKEY,
    VOICE_LEARNING_ENABLED, DANGEROUS_INTENTS, CONFIDENCE_THRESHOLD,
    PROFILES_DIR, LOGS_DIR
)

DEBUG = "--debug" in sys.argv or "-d" in sys.argv
TEXT_FALLBACK = "--text" in sys.argv or "-tx" in sys.argv
TRAIN_MODE = "--train" in sys.argv  # режим обучения голосу
REVIEW_MODE = "--review" in sys.argv  # просмотр и правка лога


def log(*a):
    if DEBUG: print("[DEBUG]", *a, flush=True)


def list_mics():
    for i, d in enumerate(sd.query_devices()):
        if d['max_input_channels'] > 0:
            print(f"  [{i}] {d['name']}")


def test_mic(sec=3):
    kw = dict(samplerate=16000, channels=1, dtype='int16')
    if CFG_MIC is not None: kw["device"] = CFG_MIC
    print(f"Zapis {sec}s (govorite)...")
    r = sd.rec(int(sec * 16000), **kw)
    sd.wait()
    m = int(np.max(np.abs(r)))
    print(f"Max: {m}" + (" OK" if m > 500 else " TIHO!"))
    return m


# Глобальные переменные для текстового fallback
_text_input_queue = []
_text_input_lock = threading.Lock()


def text_fallback_listener():
    """Фоновый поток: слушает горячую клавишу для текстового ввода."""
    try:
        import keyboard
        log(f"Text fallback hotkey: {TEXT_FALLBACK_HOTKEY}")
        keyboard.add_hotkey(TEXT_FALLBACK_HOTKEY, lambda: _request_text_input())
        keyboard.wait()
    except ImportError:
        log("keyboard module not installed. Text fallback disabled.")
        print("[WARN] Установите 'pip install keyboard' для текстового fallback.")
    except Exception as e:
        log(f"Text fallback error: {e}")


def _request_text_input():
    """Вызывается по горячей клавише — запрашивает текстовый ввод."""
    print("\n[TEXT INPUT] Введите команду (Enter для отправки, 'cancel' для отмены):")
    # Сигнал для основного потока
    global _text_input_queue
    try:
        user_input = input("> ").strip()
        with _text_input_lock:
            _text_input_queue.append(user_input)
        print(f"[TEXT INPUT] Получено: '{user_input}'")
    except:
        pass


def get_text_fallback() -> str:
    """Проверяет, поступил ли текстовый ввод."""
    global _text_input_queue
    with _text_input_lock:
        if _text_input_queue:
            return _text_input_queue.pop(0)
    return ""


def confirm_dangerous_action(action_name: str) -> bool:
    """Запросить подтверждение для опасного действия."""
    msg = f"Вы уверены? Выполнить {action_name}?"
    print(f"[CONFIRM] {msg}")
    tts.say("Подтвердите действие.")
    reset_audio()
    # Слушаем подтверждение
    try:
        reply = input(f"  {msg} (y/n): ").strip().lower()
        if reply in ("y", "yes", "да", "д"):
            print(f"[CONFIRM] Подтверждено: {action_name}")
            tts.say("Подтверждено.")
            reset_audio()
            return True
        else:
            print(f"[CONFIRM] Отменено: {action_name}")
            tts.say("Отменено.")
            reset_audio()
            return False
    except:
        return False


class Assistant:
    def __init__(self):
        log("Loading Vosk...")
        self.stt = STT()
        log("Loading commands...")
        self.voice_profile = VoiceProfile(profile_dir=PROFILES_DIR) if VOICE_LEARNING_ENABLED else None
        self.router = IntentRouter(COMMANDS, voice_profile=self.voice_profile)
        self.logger = CommandLog(log_dir=LOGS_DIR)
        self.history = CommandHistory()
        self.fail_n = 0
        self.listen_on = True
        self.last_txt = ""
        self.train_intent = None  # Для режима обучения

        # Загружаем исправления из лога
        corrections = self.history.load_corrections()
        if corrections:
            n = self.history.apply_corrections(
                self.router.normalizer, self.router, self.voice_profile
            )
            log(f"Loaded {n} corrections from command history log")
            if n > 0:
                print(f"[JARVIS] Загружено {n} исправлений из лога команд.", flush=True)

    def _is_dangerous(self, action) -> bool:
        """Проверяет, является ли действие опасным."""
        if hasattr(action, "__name__"):
            module = getattr(action, "__module__", "")
            name = action.__name__
            full = f"{module}.{name}" if module else name
            return full in DANGEROUS_INTENTS or name in DANGEROUS_INTENTS
        return False

    def _execute_action(self, action, matched_form: str, score: int):
        """Выполнить действие с подтверждением для опасных команд."""
        action_name = action.__name__ if hasattr(action, "__name__") else str(action)
        log(f"Execute: {action_name} (score={score}) matched='{matched_form}'")

        # Подтверждение для опасных команд
        if CONFIRM_DANGEROUS and self._is_dangerous(action):
            if not confirm_dangerous_action(action_name):
                self.logger.log_confirm(action_name, confirmed=False)
                return None

        try:
            res = action()
            self.logger.log_execute(action_name, success=True, result=res)
            if res:
                tts.say(res)
                reset_audio()
            else:
                _play_voice("ok")

            # Обучение: добавляем распознанный текст в voice profile
            if self.voice_profile and VOICE_LEARNING_ENABLED and self.last_txt:
                self.router.teach_intent(action_name, self.last_txt, action)
                self.logger.log_voice_learn(action_name, self.last_txt)

            return res
        except Exception as e:
            print(f"[ERROR] {e}", flush=True)
            if DEBUG:
                traceback.print_exc()
            self.logger.log_execute(action_name, success=False, error=str(e))
            tts.say("Ошибка выполнения команды.")
            reset_audio()
            return None

    def _handle_command(self, cmd: str):
        """Обработать текстовую команду (из речи или текстового fallback)."""
        self.last_txt = cmd
        cl = cmd.lower()
        log(f"Command: [{cl}]")

        # Внутренние команды
        # Загрузить исправления из лога
        if any(p in cl for p in ["обучись по логу", "выучи лог", "загрузи лог", "обучись из лога"]):
            n = self.history.apply_corrections(
                self.router.normalizer, self.router, self.voice_profile
            )
            tts.say(f"Загружено {n} исправлений из лога.")
            reset_audio()
            return

        if any(p in cl for p in ["покажи лог", "открой лог"]):
            import subprocess
            subprocess.run(["notepad", self.history.log_path])
            tts.say("Лог открыт в блокноте.")
            reset_audio()
            return

        if any(p in cl for p in ["выключи режим прослушивания", "отключи прослушивание", "выключи прослушивание"]):
            self.listen_on = False
            tts.say("Режим прослушивания выключен.")
            reset_audio()
            return

        if any(p in cl for p in ["включи режим прослушивания", "активируй прослушивание", "включи прослушивание"]):
            self.listen_on = True
            tts.say("Режим прослушивания включён.")
            reset_audio()
            return

        if any(p in cl for p in ["повтори последнюю фразу", "повтори"]):
            if self.last_txt:
                tts.say(f"Вы сказали: {self.last_txt}")
            else:
                tts.say("Нет предыдущей фразы.")
            reset_audio()
            return

        if any(p in cl for p in ["скажи тест", "тест"]):
            tts.say("Тест. Микрофон работает. Система готова.")
            reset_audio()
            return

        if any(p in cl for p in ["выключись", "стоп", "выход", "заверши работу"]):
            _play_voice("goodbye")
            self.logger.save()
            sys.exit(0)

        # Режим обучения
        if TRAIN_MODE and self.train_intent:
            print(f"[TRAIN] Команда для '{self.train_intent}': '{cmd}'")
            if self.voice_profile:
                self.router.teach_intent(self.train_intent, cmd,
                                          self.router._intents.get(self.train_intent, {}).get("action"))
                tts.say("Образец сохранён.")
                reset_audio()
            return

        # Маршрутизация
        found = self.router.detect(cmd)
        self.logger.log_route(cmd, found)

        # Пишем в лог истории (для ручных правок)
        action_name = found[0][0].__name__ if found else None
        score = found[0][1] if found else 0
        self.history.append(cmd, self.router.normalizer.normalize(cmd), action_name, score)

        if found:
            self.fail_n = 0
            action, score, matched_form = found[0]
            print(f"[VYPOLNYAYU] {action.__name__} ({score:.0f}%) via '{matched_form}'", flush=True)
            self._execute_action(action, matched_form, score)
        else:
            _play_voice("not_found")
            self.fail_n += 1
            # Пытаемся сопоставить по keymatch (но у нас уже все стратегии в detect)
            msgs = ["Не понял.", "Повторите.", "Не расслышал."]
            tts.say(random.choice(msgs))
            reset_audio()
            if self.fail_n >= 5:
                tts.say("Скажите команду ещё раз или нажмите Ctrl+Shift+T для текстового ввода.")
                reset_audio()
                self.fail_n = 0

    def run(self):
        print("[JARVIS] Gotov.", flush=True)
        if TEXT_FALLBACK:
            print("[JARVIS] Режим: голос + текстовый ввод (Ctrl+Shift+T)", flush=True)
        if TRAIN_MODE:
            print("[JARVIS] Режим обучения голосу.", flush=True)
            self._run_train()
            return
        if DEBUG:
            print(f"[DEBUG] Mikrofon: {CFG_MIC or 'standart'}", flush=True)

        _play_voice("greet")

        # Запуск поток для текстового fallback
        if TEXT_FALLBACK:
            fb_thread = threading.Thread(target=text_fallback_listener, daemon=True)
            fb_thread.start()

        while True:
            try:
                if not self.listen_on:
                    time.sleep(0.5)
                    continue

                # Проверка текстового fallback
                if TEXT_FALLBACK:
                    fb_text = get_text_fallback()
                    if fb_text:
                        if fb_text.lower() == "cancel":
                            continue
                        log(f"Text input: {fb_text}")
                        self.logger.log_text_fallback(fb_text)
                        self._handle_command(fb_text)
                        continue

                if WAKE_WORD_REQUIRED:
                    log("Waiting 'dzhazvis'...")
                    txt = self.stt.wait_for_wake_word()
                    log(f"STT: {txt}")
                    if not txt:
                        continue
                    self.logger.log_stt(txt, wake_word_detected=True)
                    self.fail_n = 0
                    _play_voice("reply")
                    cmd = txt.lower().strip()
                    for w in WAIT_WORDS:
                        cmd = cmd.replace(w, "")
                    cmd = cmd.strip()
                    if not cmd:
                        _play_voice("not_found")
                        tts.say("Я не расслышал команду.")
                        reset_audio()
                        continue
                else:
                    # Режим без wake word — просто слушаем фразу
                    log("Listening...")
                    txt = self.stt.listen_phrase()
                    log(f"STT: {txt}")
                    if not txt:
                        continue
                    self.logger.log_stt(txt, wake_word_detected=False)
                    cmd = txt.lower().strip()

                self._handle_command(cmd)

            except KeyboardInterrupt:
                _play_voice("goodbye")
                self.logger.save()
                sys.exit(0)
            except Exception as e:
                print(f"[CRITICAL] {e}", flush=True)
                if DEBUG:
                    traceback.print_exc()
                time.sleep(1)

    def _run_train(self):
        """Режим обучения голосу."""
        print("\n=== РЕЖИМ ОБУЧЕНИЯ ГОЛОСУ ===")
        print("Доступные команды для обучения:")
        for i, (key, intent) in enumerate(self.router._intents.items()):
            print(f"  [{i}] {intent['name']}")
        print()

        while True:
            try:
                choice = input("Введите номер команды для обучения (или 'q' для выхода): ").strip()
                if choice.lower() == 'q':
                    break
                try:
                    idx = int(choice)
                    keys = list(self.router._intents.keys())
                    if 0 <= idx < len(keys):
                        self.train_intent = keys[idx]
                        intent = self.router._intents[self.train_intent]
                        print(f"\nОбучение: {intent['name']}")
                        print(f"Известные варианты: {intent['variants']}")
                        print("Произнесите команду 3-5 раз как вам удобно.")
                        print("Говорите 'ready' когда готовы, или 'next' для выбора другой.")
                        self._train_samples(intent)
                    else:
                        print("Неверный номер.")
                except ValueError:
                    print("Введите число.")
            except KeyboardInterrupt:
                break

        self.logger.save()
        print("\nОбучение завершено.")

    def _train_samples(self, intent):
        """Собрать образцы произношения для одного интента."""
        action = intent["action"]
        action_name = action.__name__ if hasattr(action, "__name__") else str(action)
        count = 0
        target = 3

        while count < target:
            txt = self.stt.wait_for_wake_word()
            if not txt:
                continue
            cmd = txt.lower().strip()
            for w in WAIT_WORDS:
                cmd = cmd.replace(w, "")
            cmd = cmd.strip()
            if not cmd:
                continue

            print(f"  [{count + 1}/{target}] Распознано: '{cmd}'")
            if self.voice_profile:
                self.router.teach_intent(action_name, cmd, action)
                self.logger.log_voice_learn(action_name, cmd)
            count += 1

            if count < target:
                tts.say(f"Образец {count} сохранён. Произнесите ещё раз.")
            else:
                tts.say(f"Обучение завершено. Сохранено {target} образцов.")
            reset_audio()

        # Показываем все ключевые слова
        patterns = self.voice_profile.get_patterns_for_intent(action_name) if self.voice_profile else []
        if patterns:
            print(f"  Ключевые слова: {patterns}")


if __name__ == "__main__":
    for i, a in enumerate(sys.argv):
        if a == "--device" and i + 1 < len(sys.argv):
            import config
            config.MICROPHONE_INDEX = int(sys.argv[i + 1])
    if "-l" in sys.argv:
        list_mics()
    elif "-t" in sys.argv:
        test_mic()
    elif "--test-tts" in sys.argv:
        from tts import engine as tts
        tts.say("Привет. Я Джарвис. Тест работает.")
        print("[TTS TEST DONE]")
    elif REVIEW_MODE:
        # Режим просмотра лога
        from commands.normalizer import Normalizer
        from commands.voice_profile import VoiceProfile
        vp = VoiceProfile(profile_dir=PROFILES_DIR) if VOICE_LEARNING_ENABLED else None
        router = IntentRouter(COMMANDS, voice_profile=vp)
        review_mode(router.normalizer, router, vp)
    else:
        print("[JARVIS] DEBUG=" + str(DEBUG), flush=True)
        print("[JARVIS] WAKE_WORD_REQUIRED=" + str(WAKE_WORD_REQUIRED), flush=True)
        print("[JARVIS] Text fallback=" + str(TEXT_FALLBACK), flush=True)
        print("[JARVIS] Dangerous confirm=" + str(CONFIRM_DANGEROUS), flush=True)
        Assistant().run()
