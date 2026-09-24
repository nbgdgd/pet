"""
Профиль голоса пользователя.
Обучается на образцах речи: пользователь произносит команду несколько раз,
система запоминает варианты распознавания и позже использует их для matching'а.

Формат хранения (JSON):
{
  "intent_name": {
    "samples": ["текст1", "текст2", ...],
    "patterns": ["ключевое_слово1", ...],  // извлечено из samples
    "action": "module.function_name"
  }
}
"""
import json, os, glob
from collections import defaultdict


class VoiceProfile:
    def __init__(self, profile_dir: str = None):
        self.data = {}  # intent_name -> {samples, patterns, action}
        self.profile_dir = profile_dir or os.path.join(
            os.path.dirname(__file__), "..", "profiles"
        )
        os.makedirs(self.profile_dir, exist_ok=True)
        self._load()

    def _profile_path(self, name: str) -> str:
        safe = name.replace(" ", "_").replace(".", "_")
        return os.path.join(self.profile_dir, f"{safe}.json")

    def _load(self):
        for fpath in glob.glob(os.path.join(self.profile_dir, "*.json")):
            try:
                with open(fpath, "r", encoding="utf-8") as f:
                    intent_name = os.path.splitext(os.path.basename(fpath))[0]
                    self.data[intent_name] = json.load(f)
            except Exception:
                pass

    def save(self, intent_name: str):
        path = self._profile_path(intent_name)
        with open(path, "w", encoding="utf-8") as f:
            json.dump(self.data.get(intent_name, {}), f, ensure_ascii=False, indent=2)

    def learn(self, intent_name: str, stt_text: str, action_path: str):
        """Добавить образец произношения для intent'а."""
        if intent_name not in self.data:
            self.data[intent_name] = {
                "samples": [],
                "patterns": [],
                "action": action_path,
            }
        entry = self.data[intent_name]
        if stt_text not in entry["samples"]:
            entry["samples"].append(stt_text)
            # Извлекаем ключевые слова
            words = set(stt_text.lower().split())
            # Убираем короткие слова
            words = {w for w in words if len(w) > 2}
            entry["patterns"] = list(set(entry["patterns"]) | words)
        self.save(intent_name)

    def match(self, stt_text: str) -> list:
        """
        Ищет совпадения по сохранённым образцам.
        Возвращает список (intent_name, score, matched_sample).
        """
        if not stt_text:
            return []
        t_lower = stt_text.lower()
        t_words = set(t_lower.split())
        results = []
        for intent_name, entry in self.data.items():
            # Точное совпадение образца
            for sample in entry.get("samples", []):
                if t_lower == sample.lower():
                    results.append((intent_name, 100, sample))
                    break
            else:
                # Совпадение по ключевым словам.
                #
                # Раньше здесь было `p in t_lower` — подстрока, а не слово.
                # Профиль с patterns=["час"] давал 100% на фразе "часы",
                # "сейчас" и вообще на любой, где встречались эти буквы,
                # после чего роутер выполнял чужую команду с максимальным
                # приоритетом. Сравниваем по целым словам и требуем,
                # чтобы совпала бо́льшая часть шаблона.
                patterns = [p for p in entry.get("patterns", []) if len(p) > 2]
                if not patterns:
                    continue
                match_count = sum(1 for p in patterns if p.lower() in t_words)
                if not match_count:
                    continue
                score = int((match_count / len(patterns)) * 100)
                # штраф за слова пользователя, которых нет в шаблоне
                extra = len(t_words) - match_count
                score -= extra * 10
                if score >= 60:
                    results.append((intent_name, min(score, 95),
                                    f"pattern {match_count}/{len(patterns)}"))
        return sorted(results, key=lambda x: -x[1])

    def get_all_actions(self) -> dict:
        """Возвращает {intent_name: action_path} для всех записей."""
        return {k: v.get("action", "") for k, v in self.data.items()}

    def get_patterns_for_intent(self, intent_name: str) -> list:
        entry = self.data.get(intent_name, {})
        return entry.get("patterns", [])
