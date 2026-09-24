"""
JARVIS CMD — мощный терминальный интерфейс.
Запуск: py jarvis_cmd.py [--debug]
"""
import sys, os, time, threading, traceback, datetime, subprocess

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

DEBUG = "--debug" in sys.argv or "-d" in sys.argv

try:
    import colorama
    colorama.init()
    C = colorama.Fore
    C.RESET = colorama.Style.RESET_ALL
    C.BRIGHT = colorama.Style.BRIGHT
    C.DIM = colorama.Style.DIM
except Exception:
    class _C: pass
    C = _C()
    for a in ["RED","GREEN","YELLOW","BLUE","MAGENTA","CYAN","WHITE","RESET","BRIGHT","DIM","LIGHTBLACK_EX","LIGHTWHITE_EX","LIGHTCYAN_EX","LIGHTGREEN_EX","LIGHTYELLOW_EX","LIGHTRED_EX","LIGHTBLUE_EX","LIGHTMAGENTA_EX"]:
        setattr(C, a, "")


def _p(*a, **kw):
    print(*a, **kw, flush=True)


class JarvisCMD:
    W = 78

    def __init__(self):
        self._router = None
        self._tts = None
        self._voice_profile = None
        self._history = None
        self._stt_cls = None
        self._stt = None
        self._stt_lock = threading.Lock()
        self._stt_backend = None
        self._apps = None
        self._audio = None
        self._play_voice = lambda _: None
        self._reset_audio = lambda: None
        self._listening = False
        self._asking = False
        self._retrying = False
        self._wake_required = True
        self._running = True
        self._monitor = None
        self._monitor_on = True
        self._log_entries = []
        self._load_modules()
        self._init_monitor()

    def _load_modules(self):
        from commands.registry import COMMANDS
        from commands.router import IntentRouter
        from commands.voice_profile import VoiceProfile
        from commands.command_history import CommandHistory
        from commands.app_index import INDEX
        from config import (PROFILES_DIR, STT_BACKEND, TTS_VOICE,
                            VOICE_LEARNING_ENABLED, WAKE_WORD_REQUIRED)

        self._wake_required = WAKE_WORD_REQUIRED
        self._voice_profile = VoiceProfile(profile_dir=PROFILES_DIR) if VOICE_LEARNING_ENABLED else None
        self._router = IntentRouter(COMMANDS, voice_profile=self._voice_profile)
        self._history = CommandHistory()
        self._cmd_list = list(COMMANDS.items())
        self._stt_backend = STT_BACKEND

        corrections = self._history.load_corrections()
        if corrections:
            n = self._history.apply_corrections(self._router.normalizer, self._router, self._voice_profile)
            self.log(f"Загружено {n} исправлений из лога")

        # Каталог установленных программ: поднимается из кэша мгновенно,
        # устаревший пересобирается в фоне и не задерживает запуск.
        self._apps = INDEX
        n_apps = INDEX.load_or_build(background=True)
        self.log(f"Каталог программ: {n_apps or '...'}")

        from speech.sounds import play_voice
        self._play_voice = lambda cat: play_voice(cat, blocking=False)

        from speech.stt import STT
        from speech.audio import SOURCE
        self._stt_cls = STT
        self._audio = SOURCE
        self._reset_audio = lambda: setattr(SOURCE, "noise_floor", 0.0)

        try:
            from tts import engine as tts_engine
            self._tts = tts_engine
            tts_engine.set_voice(TTS_VOICE)
            tts_engine.preload()
            self.log(f"TTS: {TTS_VOICE}")
        except Exception as e:
            self.log(f"TTS: {e}")

        self.log("J.A.R.V.I.S. загружен")

    def _init_monitor(self):
        try:
            from commands.system_monitor import SystemMonitor
            self._monitor = SystemMonitor(on_alert=self._on_alert)
        except Exception as e:
            self.log(f"Monitor: {e}")

    def _on_alert(self, alert_type, text):
        self.log(f"ALERT {alert_type}: {text}")
        if self._tts:
            self._tts.say(text)

    def log(self, msg):
        ts = datetime.datetime.now().strftime("%H:%M:%S")
        self._log_entries.append((ts, msg))
        if len(self._log_entries) > 500:
            self._log_entries = self._log_entries[-200:]

    def _hr(self, ch="─"):
        _p(C.DIM + ch * self.W + C.RESET)

    def _header(self, status="STANDBY", sub=""):
        self._clear()
        w = self.W
        # max(0, ...): при длинном статусе выражение уходило в минус,
        # а ' ' * (-n) в f-строке валило отрисовку меню целиком
        pad = max(0, w - 26 - len(status))
        _p(f"{C.BRIGHT}{C.CYAN}╔{'═'*(w-2)}╗{C.RESET}")
        _p(f"{C.CYAN}║{C.RESET}{C.BRIGHT}{C.WHITE}  J.A.R.V.I.S.  {C.RESET}{C.YELLOW}● {status}{' '*pad}{C.DIM}{sub}{' '*3}{C.CYAN}║{C.RESET}")
        _p(f"{C.CYAN}╚{'═'*(w-2)}╝{C.RESET}")

    def _clear(self):
        os.system("cls" if os.name == "nt" else "clear")

    def _status_line(self, text, color=C.CYAN):
        _p(f"  {color}{text}{C.RESET}")

    def run(self):
        if self._monitor:
            self._monitor.start()
            self.log("Монитор системы запущен")
        if self._stt_cls:
            self._listening = True
            self.log("Микрофон включён (авто)")
            threading.Thread(target=self._listen_loop, daemon=True).start()

        try:
            while self._running:
                try:
                    self._main_screen()
                except KeyboardInterrupt:
                    self._running = False
                    break
                except Exception as e:
                    _p(f"{C.RED}ERROR: {e}{C.RESET}")
                    if DEBUG:
                        traceback.print_exc()
                    time.sleep(1)
        finally:
            self._shutdown()

        _p(f"\n{C.YELLOW}J.A.R.V.I.S. завершён.{C.RESET}")

    def _shutdown(self):
        self._listening = False
        if self._monitor:
            try:
                self._monitor.stop()
            except Exception:
                pass
        if self._stt:
            try:
                self._stt.stop()
            except Exception:
                pass
        if self._audio:
            try:
                self._audio.stop()
            except Exception:
                pass

    def _main_screen(self):
        self._header("ГЛАВНОЕ МЕНЮ", "Ctrl+C выход")
        _p("")

        mic_status = f"{C.GREEN}●{C.RESET}" if self._listening else f"{C.RED}○{C.RESET}"
        backend = self._stt.backend if self._stt else (self._stt_backend or "—")
        stt_ok = f"{C.GREEN}{backend}{C.RESET}" if self._stt_cls else f"{C.RED}---{C.RESET}"
        wake_status = f"{C.BRIGHT}{C.CYAN}РЕЖИМ: {'ДЖАРВИС' if self._wake_required else 'БЕЗ ОЖИДАНИЯ'}{C.RESET}"
        mon_status = f"{C.GREEN}●{C.RESET}" if self._monitor else f"{C.RED}○{C.RESET}"
        n_apps = len(self._apps.entries) if self._apps else 0
        voice = self._tts.VOICE_NAME if self._tts else "—"
        _p(f"  {C.DIM}│{C.RESET} Микрофон: {mic_status}  STT: {stt_ok}  Голос: {C.LIGHTCYAN_EX}{voice}{C.RESET}  |  {wake_status}  |  Монитор: {mon_status}")
        _p(f"  {C.DIM}│{C.RESET} Команд: {len(self._cmd_list)}  |  Программ в каталоге: {C.LIGHTGREEN_EX}{n_apps}{C.RESET}")
        self._hr("─")
        _p("")

        items = [
            (f"{C.GREEN}[M]{C.RESET}", "Вкл/Выкл микрофон", self._toggle_mic),
            (f"{C.MAGENTA}[V]{C.RESET}", "Голосовая команда (сказать сейчас)", self._voice_once),
            (f"{C.YELLOW}[T]{C.RESET}", "Текстовый ввод команды", self._text_input),
            (f"{C.CYAN}[W]{C.RESET}", f"Wake word: {'ВЫКЛ' if self._wake_required else 'ВКЛ'}", self._toggle_wake),
            (f"{C.LIGHTBLUE_EX}[L]{C.RESET}", "Показать лог", self._show_log),
            (f"{C.LIGHTGREEN_EX}[R]{C.RESET}", "Обучиться по логу", self._relearn),
            (f"{C.LIGHTYELLOW_EX}[E]{C.RESET}", "Открыть лог в блокноте", self._open_log),
            (f"{C.LIGHTMAGENTA_EX}[S]{C.RESET}", "Монитор системы: вкл/выкл", self._toggle_monitor),
            (f"{C.LIGHTCYAN_EX}[G]{C.RESET}", "Сменить голос TTS", self._switch_voice),
            (f"{C.LIGHTWHITE_EX}[C]{C.RESET}", "Список всех команд", self._list_commands),
            (f"{C.GREEN}[A]{C.RESET}", "Каталог программ: показать / обновить", self._apps_menu),
            (f"{C.YELLOW}[D]{C.RESET}", "Разбор фразы (почему сработала команда)", self._explain),
        ]

        for key, desc, _ in items:
            _p(f"    {key}  {C.DIM}{desc}{C.RESET}")

        _p(f"\n    {C.LIGHTWHITE_EX}[1-{len(self._cmd_list)}]{C.RESET}  {C.DIM}Запустить команду по номеру{C.RESET}")
        self._hr("─")
        _p("")

        if self._log_entries:
            for ts, msg in self._log_entries[-5:]:
                clr = C.RED if "NOT_FOUND" in msg or "Ошибка" in msg else C.GREEN if "→ " in msg else C.DIM
                _p(f"  {clr}[{ts}]{C.RESET} {msg}")

        _p("")
        self._hr("·")
        try:
            cmd = input(f"  {C.CYAN}❯{C.RESET} ").strip()
        except (EOFError, KeyboardInterrupt):
            self._running = False
            return

        if not cmd:
            return

        self._handle_menu_input(cmd)

    def _handle_menu_input(self, cmd: str):
        c = cmd.upper()
        # раскладка ЙЦУКЕН: латинская клавиша -> русская буква на ней же.
        # Было "M"->"М" и "V"->"В" — это не те клавиши: на M стоит "Ь",
        # на V — "М", поэтому в русской раскладке пункты не срабатывали.
        if c in ("M", "Ь"):
            self._toggle_mic()
        elif c in ("V", "М"):
            self._voice_once()
        elif c in ("T", "Е"):
            self._text_input()
        elif c in ("W", "Ц"):
            self._toggle_wake()
        elif c in ("L", "Д"):
            self._show_log()
        elif c in ("R", "К"):
            self._relearn()
        elif c in ("E", "У"):
            self._open_log()
        elif c in ("S", "Ы"):
            self._toggle_monitor()
        elif c in ("G", "П"):
            self._switch_voice()
        elif c in ("C", "С"):
            self._list_commands()
        elif c in ("A", "Ф"):
            self._apps_menu()
        elif c in ("D", "В"):
            self._explain()
        elif c in ("Q", "Й"):
            self._running = False
        elif cmd.isdigit():
            n = int(cmd)
            if 1 <= n <= len(self._cmd_list):
                self._exec_cmd_by_index(n - 1)
            else:
                _p(f"  {C.RED}Номер вне диапазона (1-{len(self._cmd_list)}){C.RESET}")
                time.sleep(0.5)
        else:
            _p(f"  {C.RED}Неизвестная команда{C.RESET}")
            time.sleep(0.3)

    def _ensure_stt(self):
        """
        Один-единственный объект STT на всё приложение.

        Прежде пункт меню [V] создавал ВТОРОЙ объект STT, а с ним второй
        поток записи на том же микрофоне; оба потока писали в одну общую
        очередь, и два потребителя растаскивали кадры друг у друга —
        речь терялась примерно наполовину.
        """
        if self._stt is not None:
            return self._stt
        with self._stt_lock:
            if self._stt is None:
                hot = self._apps.hotwords() if self._apps else []
                self._stt = self._stt_cls(backend=self._stt_backend, hotwords=hot)
                self.log(f"STT: {self._stt.backend}")
                for err in self._stt.errors:
                    self.log(f"STT пропущен {err}")
        return self._stt

    def _toggle_mic(self):
        if self._listening:
            self._listening = False
            if self._stt:
                self._stt.cancel()
            self.log("Микрофон выключен")
        else:
            self._listening = True
            if self._stt:
                self._stt.resume()
            self.log("Микрофон включён")
            threading.Thread(target=self._listen_loop, daemon=True).start()

    def _voice_once(self):
        """Разовая команда без wake word — на том же потоке записи."""
        _p(f"\n  {C.YELLOW}Говорите...{C.RESET}")
        was_listening = self._listening
        self._listening = False
        try:
            stt = self._ensure_stt()
            stt.resume()
            txt = stt.listen_phrase(wait_timeout=6)
            if txt:
                self.log(f"STT: {txt}")
                self._handle_command(txt.lower().strip())
            else:
                _p(f"  {C.DIM}(не расслышал){C.RESET}")
                time.sleep(0.6)
        except Exception as e:
            self.log(f"STT error: {e}")
        finally:
            if was_listening:
                self._listening = True
                threading.Thread(target=self._listen_loop, daemon=True).start()

    def _toggle_wake(self):
        self._wake_required = not self._wake_required
        self.log(f"Wake word: {'вкл' if self._wake_required else 'выкл'}")

    def _toggle_monitor(self):
        if not self._monitor:
            self.log("Монитор не загружен")
            return
        self._monitor_on = not self._monitor_on
        if self._monitor_on:
            self._monitor.start()
            self.log("Монитор запущен")
        else:
            self._monitor.stop()
            self.log("Монитор остановлен")

    def _switch_voice(self):
        if not self._tts:
            return
        try:
            self._do_switch_voice()
        except Exception as e:
            self.log(f"Voice menu error: {e}")
            if DEBUG:
                traceback.print_exc()

    def _do_switch_voice(self):
        voices = list(self._tts.AVAILABLE_VOICES.keys())
        cur = self._tts.VOICE_NAME

        self._clear()
        w = self.W
        _p(f"{C.CYAN}╔{'═'*(w-2)}╗{C.RESET}")
        _p(f"{C.CYAN}║{C.RESET}{C.BRIGHT}{C.WHITE}  ВЫБОР ГОЛОСА{' '*(w-20)}{C.CYAN}║{C.RESET}")
        _p(f"{C.CYAN}╚{'═'*(w-2)}╝{C.RESET}")
        _p("")

        for i, v in enumerate(voices, 1):
            cfg = self._tts.AVAILABLE_VOICES[v]
            active = f"{C.BRIGHT} ← ТЕКУЩИЙ{C.RESET}" if v == cur else ""
            _p(f"  {C.GREEN}[{i}]{C.RESET} {C.LIGHTCYAN_EX}{v:10s}{C.RESET} "
               f"{C.DIM}{cfg['desc']}{C.RESET}{active}")

        _p("")
        choice = input(f"  {C.CYAN}Номер голоса (Enter — отмена){C.RESET} ").strip()
        if choice.isdigit() and 1 <= int(choice) <= len(voices):
            name = voices[int(choice) - 1]
            if self._tts.set_voice(name):
                self.log(f"Голос: {name}")
                self._tts.say("Голос переключён", blocking=False)

    def _list_commands(self):
        self._clear()
        w = self.W
        _p(f"{C.CYAN}╔{'═'*(w-2)}╗{C.RESET}")
        _p(f"{C.CYAN}║{C.RESET}{C.BRIGHT}{C.WHITE}  ВСЕ КОМАНДЫ ({len(self._cmd_list)}){' '*(w-20)}{C.CYAN}║{C.RESET}")
        _p(f"{C.CYAN}╚{'═'*(w-2)}╝{C.RESET}")
        _p("")

        for i, (variants, action) in enumerate(self._cmd_list, 1):
            name = action.__name__ if hasattr(action, "__name__") else "?"
            first = variants[0] if variants else "?"
            _p(f"  {C.GREEN}[{i:2d}]{C.RESET} {C.LIGHTCYAN_EX}{name:25s}{C.RESET} {C.DIM}{first}{C.RESET}")

        _p("")
        input(f"  {C.DIM}[Enter назад]{C.RESET} ")

    def _apps_menu(self):
        self._clear()
        _p(f"{C.CYAN}╔{'═'*(self.W-2)}╗{C.RESET}")
        _p(f"{C.CYAN}║{C.RESET}{C.BRIGHT}{C.WHITE}  КАТАЛОГ ПРОГРАММ{' '*(self.W-23)}{C.CYAN}║{C.RESET}")
        _p(f"{C.CYAN}╚{'═'*(self.W-2)}╝{C.RESET}\n")

        entries = self._apps.entries if self._apps else []
        _p(f"  Найдено программ: {C.LIGHTGREEN_EX}{len(entries)}{C.RESET}")
        _p(f"  {C.DIM}Любую можно открыть голосом: «открой <название>»{C.RESET}\n")
        for e in entries[:40]:
            _p(f"    {C.LIGHTCYAN_EX}{e['name'][:38]:38s}{C.RESET} {C.DIM}{e['kind']}{C.RESET}")
        if len(entries) > 40:
            _p(f"    {C.DIM}... и ещё {len(entries)-40}{C.RESET}")

        _p(f"\n  {C.YELLOW}[R]{C.RESET} пересобрать каталог   "
           f"{C.YELLOW}[текст]{C.RESET} проверить распознавание названия   "
           f"{C.DIM}[Enter] назад{C.RESET}")
        ans = input(f"  {C.CYAN}❯{C.RESET} ").strip()
        if not ans:
            return
        if ans.upper() in ("R", "К"):
            _p(f"  {C.DIM}Собираю каталог...{C.RESET}")
            n = self._apps.rebuild()
            _p(f"  {C.GREEN}Готово: {n} программ{C.RESET}")
            time.sleep(1.2)
            return
        entry, score = self._apps.resolve(ans)
        if entry:
            _p(f"  {C.GREEN}{ans!r} → {entry['name']} ({score}%), запуск: {entry['kind']}{C.RESET}")
        else:
            _p(f"  {C.RED}{ans!r} — ничего похожего не нашёл{C.RESET}")
        input(f"  {C.DIM}[Enter назад]{C.RESET} ")

    def _explain(self):
        """Показать, как разбирается фраза — видно, почему сработало не то."""
        _p(f"\n  {C.CYAN}Фраза для разбора:{C.RESET}")
        txt = input(f"  {C.YELLOW}❯{C.RESET} ").strip()
        if not txt:
            return
        norm = self._router.normalizer.normalize(txt)
        _p(f"\n  Нормализовано: {C.LIGHTCYAN_EX}{norm}{C.RESET}")
        _p(f"  Алиасы:        {C.DIM}{self._router.normalizer.expand_short_aliases(norm)}{C.RESET}\n")
        cands = self._router.detect_all(txt)
        if cands:
            from config import CONFIDENCE_THRESHOLD
            for action, score, form in cands:
                mark = C.GREEN if score >= CONFIDENCE_THRESHOLD else C.RED
                _p(f"    {mark}{score:3d}%{C.RESET} {getattr(action,'__name__','?'):24s} {C.DIM}{form}{C.RESET}")
            _p(f"\n  {C.DIM}Порог: {CONFIDENCE_THRESHOLD}%. Ниже порога команда отклоняется.{C.RESET}")
        else:
            _p(f"    {C.DIM}(кандидатов нет){C.RESET}")
        from commands import dynamic
        kind, tail = dynamic.split_verb(txt)
        if kind:
            entry, score = self._apps.resolve(tail)
            _p(f"  Каталог: {kind} «{tail}» → "
               f"{C.LIGHTGREEN_EX}{entry['name'] if entry else '—'}{C.RESET} ({score}%)")
        input(f"\n  {C.DIM}[Enter назад]{C.RESET} ")

    def _text_input(self):
        _p(f"\n  {C.CYAN}Введите команду (или Enter для отмены):{C.RESET}")
        txt = input(f"  {C.YELLOW}❯{C.RESET} ").strip()
        if txt:
            self._handle_command(txt)

    def _show_log(self):
        self._clear()
        w = self.W
        _p(f"{C.CYAN}╔{'═'*(w-2)}╗{C.RESET}")
        _p(f"{C.CYAN}║{C.RESET}{C.BRIGHT}{C.WHITE}  ЛОГ{' '*(w-8)}{C.CYAN}║{C.RESET}")
        _p(f"{C.CYAN}╚{'═'*(w-2)}╝{C.RESET}")
        _p("")

        if not self._log_entries:
            _p(f"  {C.DIM}(пусто){C.RESET}")
        else:
            for ts, msg in self._log_entries[-50:]:
                clr = C.RED if "NOT_FOUND" in msg or "Ошибка" in msg else C.GREEN if "→ " in msg else C.DIM
                _p(f"  {clr}[{ts}]{C.RESET} {msg}")

        _p("")
        input(f"  {C.DIM}[Enter назад]{C.RESET} ")

    def _relearn(self):
        n = self._history.apply_corrections(self._router.normalizer, self._router, self._voice_profile)
        self.log(f"Загружено {n} исправлений из лога")
        _p(f"\n  {C.GREEN}Загружено {n} исправлений{C.RESET}")
        time.sleep(0.5)

    def _open_log(self):
        try:
            subprocess.run(["notepad", self._history.log_path])
        except Exception:
            _p(f"  {C.RED}Не удалось открыть блокнот{C.RESET}")
            time.sleep(0.5)

    def _exec_cmd_by_index(self, idx: int):
        try:
            variants, action = self._cmd_list[idx]
            name = action.__name__ if hasattr(action, "__name__") else f"cmd{idx}"
            self._status_line(f"→ {name} ({variants[0]})", C.GREEN)
            res = action()
            self.log(f"→ {name}")
            self._speak_or_clip(res)
            time.sleep(0.3)
        except Exception as e:
            self.log(f"Ошибка: {e}")
            if DEBUG:
                traceback.print_exc()
            time.sleep(0.5)

    def _listen_loop(self):
        """
        Слушающий цикл.

        Убрана логика «пять пустых ответов подряд — навсегда переключаемся
        на запасной движок»: тишина в комнате давала ровно такие же пустые
        ответы, и ассистент деградировал до самой слабой модели просто
        потому, что пользователь молчал.
        """
        try:
            stt = self._ensure_stt()
        except Exception as e:
            self.log(f"STT init: {e}")
            self._listening = False
            return

        stt.resume()
        while self._listening:
            try:
                if self._wake_required:
                    self._status_line("● ЖДУ 'ДЖАРВИС'...", C.CYAN)
                    txt = stt.wait_for_wake_word(wait_timeout=30)
                    if not txt or not self._listening:
                        continue
                    self._status_line(f"● РАСПОЗНАНО: {txt}", C.LIGHTCYAN_EX)
                    cmd = stt.strip_wake_word(txt).lower().strip()
                    if cmd:
                        self._handle_command(cmd)
                    else:
                        self._play_voice("reply")
                        follow = stt.listen_phrase(wait_timeout=6)
                        if follow:
                            self._status_line(f"● РАСПОЗНАНО: {follow}", C.LIGHTCYAN_EX)
                            self._handle_command(follow.lower().strip())
                else:
                    self._status_line("● СЛУШАЮ... (без 'джарвис')", C.CYAN)
                    txt = stt.listen_phrase(wait_timeout=30)
                    if txt and self._listening:
                        self._status_line(f"● РАСПОЗНАНО: {txt}", C.LIGHTCYAN_EX)
                        self._handle_command(txt.lower().strip())
            except Exception as e:
                self.log(f"STT error: {e}")
                if DEBUG:
                    traceback.print_exc()
                time.sleep(1)

    def _handle_command(self, cmd: str):
        if not self._router:
            return

        cl = cmd.lower()
        if any(p in cl for p in ["обучись по логу", "выучи лог", "загрузи лог"]):
            n = self._history.apply_corrections(self._router.normalizer, self._router, self._voice_profile)
            self.log(f"Загружено {n} исправлений")
            self._play_voice("ok")
            return

        if any(p in cl for p in ["покажи лог", "открой лог"]):
            self._open_log()
            return

        if any(p in cl for p in ["обнови список программ", "пересобери каталог",
                                 "обнови программы", "найди программы"]):
            from commands import dynamic
            self._speak_or_clip(dynamic.refresh_index())
            return

        # Голый глагол без цели ("открой") — не ошибка, а недосказанная
        # команда. Раньше на это играло "не распознано", хотя человеку
        # достаточно переспросить.
        if self._ask_target_if_bare(cl):
            return

        action_name, score = None, 0
        result = self._router.detect(cmd)

        if result:
            action, score, matched = result[0]
            action_name = getattr(action, "__name__", str(action))
            self.log(f"→ {action_name} ({score}%) [{matched}]")
            self._status_line(f"● ВЫПОЛНЯЮ: {action_name} ({score}%)", C.LIGHTGREEN_EX)
            try:
                from commands import system as sys_mod
                sys_mod._last_cmd = cmd
                self._speak_or_clip(action())
            except Exception as e:
                self.log(f"Ошибка {action_name}: {e}")
                if DEBUG:
                    traceback.print_exc()
                self._play_voice("not_found")
        else:
            # Команды нет в registry.py — пробуем открыть/закрыть любую
            # установленную программу через каталог Windows.
            from commands import dynamic
            reply = None
            try:
                reply = dynamic.handle(cmd)
            except Exception as e:
                self.log(f"Ошибка каталога: {e}")
            if reply:
                action_name, score = "app_index", 100
                self.log(f"→ {reply}")
                self._status_line(f"● {reply}", C.LIGHTGREEN_EX)
                self._speak_or_clip(reply)
            elif self._retry_with_latin(cmd):
                return
            else:
                self._status_line(f"● НЕ РАСПОЗНАНО: {cmd}", C.RED)
                self.log(f"NOT_FOUND: {cmd}")
                self._play_voice("not_found")

        if self._history:
            self._history.append(cmd, self._router.normalizer.normalize(cmd),
                                 action_name, score)

    def _retry_with_latin(self, cmd: str) -> bool:
        """
        Фраза никуда не легла — переспрашиваем движок, который пишет латиницей.

        Нужно ровно для английских слов: GigaAM физически не может выдать
        "obsidian", он отдаёт "обсидиан". Обычно этого хватает, но если
        транслитерация ушла слишком далеко от оригинала, Whisper по той же
        записи выдаёт исходное написание.
        """
        if self._retrying or not self._stt:
            return False
        self._retrying = True
        try:
            alt = (self._stt.retry_latin() or "").lower().strip()
            if not alt or alt == cmd:
                return False
            self._status_line(f"● УТОЧНЕНО: {alt}", C.LIGHTCYAN_EX)
            self.log(f"whisper: {cmd!r} -> {alt!r}")
            if self._router.detect(alt):
                self._handle_command(alt)
                return True
            from commands import dynamic
            reply = dynamic.handle(alt)
            if reply:
                self.log(f"→ {reply}")
                self._status_line(f"● {reply}", C.LIGHTGREEN_EX)
                self._speak_or_clip(reply)
                return True
            return False
        except Exception as e:
            self.log(f"Ошибка второго прохода: {e}")
            return False
        finally:
            self._retrying = False

    def _ask_target_if_bare(self, cl: str) -> bool:
        """«открой» без цели -> «Что открыть?» и ждём продолжение."""
        from commands import dynamic

        words = cl.split()
        if len(words) != 1 or self._asking or not self._stt:
            return False
        verb = words[0]
        if verb in dynamic.OPEN_VERBS:
            question = "Что открыть?"
        elif verb in dynamic.CLOSE_VERBS:
            question = "Что закрыть?"
        else:
            return False

        self._asking = True
        try:
            self._status_line(f"● {question}", C.YELLOW)
            if self._tts:
                self._tts.say(question, blocking=True)
            tail = self._stt.listen_phrase(wait_timeout=6)
            if tail:
                self._status_line(f"● РАСПОЗНАНО: {tail}", C.LIGHTCYAN_EX)
                self._handle_command(f"{verb} {tail.lower().strip()}")
            else:
                self._play_voice("not_found")
        finally:
            self._asking = False
        return True

    def _speak_or_clip(self, text):
        """Есть текст — произносим его, нет — играем клип подтверждения."""
        from config import SPEAK_RESULTS
        if text and self._tts and SPEAK_RESULTS:
            self._tts.say(str(text), blocking=False)
        else:
            self._play_voice("ok")


if __name__ == "__main__":
    _p(f"{C.CYAN}{C.BRIGHT}")
    _p("╔══════════════════════════════════════════════════════════════╗")
    _p("║                     J.A.R.V.I.S. CMD                        ║")
    _p("║         Just A Rather Very Intelligent System               ║")
    _p("╚══════════════════════════════════════════════════════════════╝")
    _p(C.RESET)
    JarvisCMD().run()
