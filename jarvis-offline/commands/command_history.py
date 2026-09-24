"""
История команд: человекочитаемый лог, который пользователь правит вручную.

Формат файла (logs/command_history.txt):
  STT: "фаер фокс" | NORM: "фаер фокс" | ROUTE: NOT_FOUND | CORRECT:
Пользователь дописывает:
  STT: "фаер фокс" | NORM: "фаер фокс" | ROUTE: NOT_FOUND | CORRECT: open_firefox

Система:
  - Пишет каждую попытку в лог
  - При старте читает лог, находит строки с CORRECT
  - Добавляет исправления в normalizer.STT_ERRORS + voice_profile
  - При "обучись" команде перечитывает лог заново
"""
import os, datetime, re, glob
import commands.normalizer as _norm_mod


class CommandHistory:
    def __init__(self, log_path: str = None):
        self.log_path = log_path or os.path.join(
            os.path.dirname(__file__), "..", "logs", "command_history.txt"
        )
        os.makedirs(os.path.dirname(self.log_path), exist_ok=True)
        self._ensure_has_header()

    def _ensure_has_header(self):
        if not os.path.exists(self.log_path):
            self._write_header()
        else:
            with open(self.log_path, "r", encoding="utf-8") as f:
                first = f.read(256)
            if "JARVIS Command History" not in first:
                old = self._read_existing_body()
                self._write_header()
                self._append_raw_lines(old)

    def _write_header(self):
        with open(self.log_path, "w", encoding="utf-8") as f:
            f.write(
                "# JARVIS Command History\n"
                "# Формат: STT: \"сырой текст\" | NORM: \"нормализованный\" | "
                "ROUTE: action(None/имя) | CORRECT: \n"
                "# Если команда не распознана — напишите правильное имя после CORRECT:\n"
                "#   CORRECT: open_firefox\n"
                "# Если команда распознана верно — оставьте CORRECT пустым.\n"
                "# Можно удалить всю строку, если она не нужна.\n"
                "# Система загрузит правки при следующем запуске или по команде 'обучись по логу'.\n"
                "=" * 80 + "\n"
            )

    def _read_existing_body(self) -> str:
        """Read all non-header lines from existing file."""
        lines = []
        with open(self.log_path, "r", encoding="utf-8") as f:
            for line in f:
                if not line.startswith(("#", "=")):
                    lines.append(line.rstrip("\n\r"))
        return "\n".join(lines)

    def _append_raw_lines(self, text: str):
        if not text:
            return
        with open(self.log_path, "a", encoding="utf-8") as f:
            f.write(text + "\n")

    def append(self, stt_raw: str, normalized: str, action_name: str, score: int):
        ts = datetime.datetime.now().strftime("%Y-%m-%d %H:%M")
        route = action_name if action_name else "NOT_FOUND"
        line = (
            f'STT: "{stt_raw}" | NORM: "{normalized}" | '
            f"ROUTE: {route}({score}) | CORRECT: \n"
        )
        with open(self.log_path, "a", encoding="utf-8") as f:
            f.write(line)

    def append_correction(self, raw_text: str, correct_action: str):
        """Записать прямое исправление в лог."""
        with open(self.log_path, "a", encoding="utf-8") as f:
            f.write(
                f'STT: "{raw_text}" | NORM: "" | '
                f"ROUTE: MANUAL | CORRECT: {correct_action}\n"
            )

    def append_raw(self, line: str):
        """Записать строку в лог минуя форматирование (для внешних редакторов)."""
        with open(self.log_path, "a", encoding="utf-8") as f:
            f.write(line.rstrip() + "\n")

    def load_corrections(self) -> list:
        """
        Читает лог, возвращает список исправлений:
        [(stt_text, correct_action_name), ...]
        Пропускает строки с пустым CORRECT.
        """
        if not os.path.exists(self.log_path):
            return []
        corrections = []
        with open(self.log_path, "r", encoding="utf-8") as f:
            for line in f:
                line = line.strip()
                if not line or line.startswith("#") or line.startswith("="):
                    continue
                m = re.search(r'STT:\s*"(.+?)"\s*\|\s*NORM:.+?CORRECT:\s*(\S+)', line)
                if m:
                    stt_text = m.group(1).strip()
                    correct_action = m.group(2).strip()
                    if correct_action and correct_action.lower() not in ("", "none", "not_found"):
                        corrections.append((stt_text, correct_action))
        return corrections

    def apply_corrections(self, normalizer, router, voice_profile):
        """Применить все исправления из лога к normalizer и voice_profile."""
        corrections = self.load_corrections()
        if not corrections:
            return 0

        applied = 0
        for stt_text, correct_action in corrections:
            # Находим каноническую форму для этого действия
            canonical = self._find_canonical_form(router, correct_action)
            if not canonical:
                canonical = correct_action  # fallback

            # Добавляем в STT_ERRORS: stt_text → canonical
            if stt_text not in _norm_mod.STT_ERRORS:
                _norm_mod.STT_ERRORS[stt_text] = canonical
                normalizer._build_error_patterns()

            # Добавляем в voice profile
            if voice_profile:
                voice_profile.learn(correct_action, stt_text, correct_action)

            # Добавляем в PHONETIC карту
            if stt_text not in _norm_mod.PHONETIC:
                _norm_mod.PHONETIC[stt_text] = canonical
                normalizer._build_phonetic_patterns()

            applied += 1

        # Перестраиваем router с новыми данными из voice profile
        if voice_profile and applied > 0:
            for intent_name, entry in voice_profile.data.items():
                for key, intent in router._intents.items():
                    if intent["name"] == intent_name:
                        new_words = set(normalizer.normalize(" ".join(entry.get("patterns", []))).split())
                        intent["keywords"] |= new_words
                        intent["expanded_keywords"] |= new_words

        return applied

    @staticmethod
    def _find_canonical_form(router, action_name: str) -> str:
        """Найти каноническую форму (самый короткий нормализованный вариант) для интента."""
        best = None
        for key, intent in router._intents.items():
            if intent["name"] == action_name:
                for v in intent["variants"]:
                    if best is None or len(v) < len(best):
                        best = v
        return best


def review_mode(normalizer, router, voice_profile, log_path=None):
    """Интерактивный режим просмотра и правки лога."""
    ch = CommandHistory(log_path)
    corrections = ch.load_corrections()
    already = {c[0] for c in corrections}

    print("\n=== REVIEW MODE ===")
    print("Просмотр неправильно распознанных команд.")
    print("Для каждой строки нажмите Enter чтобы оставить как есть,")
    print("или напишите правильное имя команды.")
    print()

    if not os.path.exists(ch.log_path):
        print("Лог пуст.")
        return

    new_corrections = []
    with open(ch.log_path, "r", encoding="utf-8") as f:
        lines = f.readlines()

    for i, line in enumerate(lines):
        stripped = line.strip()
        if not stripped or stripped.startswith("#") or stripped.startswith("="):
            continue

        # Пропускаем уже исправленные
        if "CORRECT:" in stripped and not stripped.rstrip().endswith("CORRECT:") and "CORRECT: " in stripped:
            after = stripped.split("CORRECT:")[-1].strip()
            if after and after.lower() not in ("", "none", "not_found"):
                continue

        m = re.search(r'STT:\s*"(.+?)"', stripped)
        if not m:
            continue

        stt_text = m.group(1)
        if stt_text in already:
            continue

        print(f"[{i+1}] {stt_text}")
        user_input = input(f"    CORRECT: ").strip()
        if user_input:
            new_corrections.append((stt_text, user_input))
            lines[i] = stripped.rstrip() + f" {user_input}\n"
            if not lines[i].endswith("\n"):
                lines[i] += "\n"

    if new_corrections:
        with open(ch.log_path, "w", encoding="utf-8") as f:
            f.writelines(lines)
        applied = ch.apply_corrections(normalizer, router, voice_profile)
        print(f"Применено исправлений: {applied}")
    else:
        print("Новых исправлений нет.")

    return new_corrections
