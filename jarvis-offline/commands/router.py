"""
Intent-based router with multi-strategy matching.
Порядок matching'а:
  1. Exact match (полная нормализация + translit + error correction)
  2. Substring match (фраза пользователя содержится в известной команде)
  3. Keyword overlap (ключевые слова из интента перекрываются с речью)
  4. Short alias (1-2 слова, маппинг на действие)
  5. Voice profile (образцы речи пользователя)
  6. Fuzzy fallback (rapidfuzz, строгий порог, только если слова перекрываются)
"""
import re, os, json, inspect
from rapidfuzz import fuzz
from config import CONFIDENCE_THRESHOLD
from commands.normalizer import Normalizer, SYNONYMS


# Слова, совпадение по которым ничего не значит: они есть почти в каждой
# команде. Раньше "открой aniblaze" совпадал с "открой блютуз" на 71%
# ровно потому, что общим было единственное слово "открой".
GENERIC_TOKENS = {
    "открой", "открыть", "закрой", "закрыть", "запусти", "запустить",
    "включи", "включить", "выключи", "выключить", "покажи", "показать",
    "сделай", "скажи", "заверши", "сверни", "мой", "моя", "мои", "мне",
    "open", "close", "launch", "start", "run", "show", "tell", "make", "my",
}


class IntentRouter:
    def __init__(self, commands: dict, voice_profile=None):
        self.commands = commands
        self.normalizer = Normalizer()
        self.voice_profile = voice_profile
        # Строим intent map
        self._intents = {}  # action_key -> {keywords, variants, action}
        self._alias_map = {}  # normalized_alias -> (action, original)
        self._short_map = {}  # single_word -> action (для коротких алиасов)
        self._build_intents()
        self.log = []  # История последних N сопоставлений

    def _action_key(self, action):
        """Уникальный ключ для action-функции."""
        if hasattr(action, "__name__"):
            return f"{action.__module__}.{action.__name__}"
        return str(action)

    def _build_intents(self):
        for variants, action in self.commands.items():
            key = self._action_key(action)
            all_keywords = set()
            all_variants = []
            for v in variants:
                nv = self.normalizer.normalize(v)
                all_variants.append(nv)
                all_keywords.update(nv.split())
                # Точный match: нормализованный вариант → action
                self._alias_map[nv] = (action, v)
            # Короткие алиасы: если вариант из 1-2 слов, добавляем в short_map
            for v in variants:
                clean = self.normalizer.normalize(v)
                words = clean.split()
                if len(words) <= 2:
                    self._short_map[clean] = action
            # Расширяем ключевые слова синонимами
            expanded = set(all_keywords)
            for kw in list(all_keywords):
                if kw in SYNONYMS:
                    expanded.add(SYNONYMS[kw])
            self._intents[key] = {
                "action": action,
                "keywords": all_keywords,
                "expanded_keywords": expanded,
                "variants": all_variants,
                "name": action.__name__ if hasattr(action, "__name__") else key,
            }

    def detect(self, user_text: str, voice_profile_hint: bool = True):
        """
        Основной метод. Принимает сырой текст от STT.
        Возвращает [(action, confidence, matched_form)] или [].
        """
        raw = user_text.strip()
        if not raw:
            return []

        # Нормализация
        normalized = self.normalizer.normalize(raw)
        # Расширение коротких алиасов
        expanded = self.normalizer.expand_short_aliases(normalized)

        # Все стратегии matching'а
        candidates = []

        # Стратегия 1: точный exact match
        exact = self._exact_match(normalized)
        candidates.extend(exact)

        # Стратегия 2: substring match (фраза пользователя содержится в известной команде)
        substr = self._substring_match(normalized)
        candidates.extend(substr)

        # Стратегия 3: keyword overlap
        kw = self._keyword_match(normalized)
        candidates.extend(kw)

        # Стратегия 4: короткий алиас (после expand_short_aliases)
        short = self._short_alias_match(expanded)
        candidates.extend(s for s in short if s not in candidates)

        # Стратегия 5: voice profile
        if self.voice_profile and voice_profile_hint:
            vp = self._voice_profile_match(raw)
            candidates.extend(v for v in vp if v not in candidates)

        # Стратегия 6: fuzzy fallback (только если ничего не нашли выше)
        if not candidates:
            fuzzy = self._fuzzy_match(normalized, expanded)
            candidates.extend(fuzzy)

        # Дедупликация и сортировка
        seen_actions = set()
        unique = []
        for action, score, form in candidates:
            ak = self._action_key(action)
            if ak not in seen_actions:
                seen_actions.add(ak)
                unique.append((action, score, form))
        unique.sort(key=lambda x: -x[1])

        # Логирование
        self._log_match(raw, normalized, expanded, unique)

        # Порог обязателен. Раньше здесь стоял «если ничего не прошло порог —
        # всё равно верни лучшее», из-за чего CONFIDENCE_THRESHOLD ни на что
        # не влиял: любая незнакомая фраза ("открой aniblaze") запускала
        # случайную команду с 40–60%. Теперь ниже порога — это отказ,
        # и вызывающая сторона может передать фразу в каталог программ.
        if unique and unique[0][1] >= CONFIDENCE_THRESHOLD:
            return [unique[0]]
        return []

    def detect_all(self, user_text: str, top: int = 5):
        """Полный ранжированный список кандидатов — для отладки и меню [D]."""
        normalized = self.normalizer.normalize(user_text)
        expanded = self.normalizer.expand_short_aliases(normalized)
        cands = (self._exact_match(normalized) + self._substring_match(normalized)
                 + self._keyword_match(normalized) + self._short_alias_match(expanded)
                 + self._fuzzy_match(normalized, expanded))
        seen, out = set(), []
        for action, score, form in sorted(cands, key=lambda x: -x[1]):
            ak = self._action_key(action)
            if ak not in seen:
                seen.add(ak)
                out.append((action, score, form))
        return out[:top]

    def _exact_match(self, normalized: str) -> list:
        if normalized in self._alias_map:
            action, original = self._alias_map[normalized]
            return [(action, 100, f"exact: {original}")]
        return []

    def _substring_match(self, normalized: str) -> list:
        """
        Пользователь сказал часть известной команды.

        Совпадение проверяется по ГРАНИЦАМ СЛОВ. Раньше сравнивались голые
        подстроки, поэтому "дата" совпадала внутри "передатчик", а оценка
        len(user)/len(variant) давала 100% там, где пользователь произнёс
        одно слово из четырёх.
        """
        results = []
        u_words = normalized.split()
        if not u_words:
            return []
        u_set = set(u_words)
        for key, intent in self._intents.items():
            best = 0
            best_variant = ""
            for variant in intent["variants"]:
                v_words = variant.split()
                if not v_words:
                    continue
                v_set = set(v_words)
                if u_set <= v_set:
                    covered = len(u_set) / len(v_set)
                elif v_set <= u_set:
                    covered = len(v_set) / len(u_set)
                else:
                    continue
                if covered < 0.5:
                    continue
                if not ((u_set & v_set) - GENERIC_TOKENS):
                    continue
                score = int(55 + covered * 45)
                if score > best:
                    best, best_variant = score, variant
            if best >= CONFIDENCE_THRESHOLD:
                results.append((intent["action"], min(best, 100), f"substr: {best_variant}"))
        return results

    def _keyword_match(self, normalized: str) -> list:
        """Оценка по пересечению ключевых слов."""
        if not normalized:
            return []
        user_words = set(normalized.split())
        if not user_words:
            return []
        results = []
        for key, intent in self._intents.items():
            kw_set = intent["expanded_keywords"]
            if not kw_set:
                continue
            overlap = user_words & kw_set
            if not overlap:
                continue
            # Совпадение только по служебным глаголам — не совпадение
            if not (overlap - GENERIC_TOKENS):
                continue
            # Минимум 2 совпадающих слова ИЛИ одно слово с intent_coverage >= 0.35
            if len(overlap) < 2 and len(overlap) / len(kw_set) < 0.35:
                continue
            user_match_ratio = len(overlap) / len(user_words)
            intent_coverage = len(overlap) / len(kw_set)
            score = int((user_match_ratio * 60 + intent_coverage * 40))
            # Непокрытые слова пользователя — это, как правило, название
            # программы ("открой aniblaze"). Каждое такое слово снижает
            # уверенность, иначе один общий глагол вытягивает чужой интент.
            unknown = len(user_words) - len(overlap)
            score -= unknown * 12
            if score >= CONFIDENCE_THRESHOLD:
                results.append((intent["action"], min(score, 100), f"kw: {sorted(overlap)}"))
        return results

    def _short_alias_match(self, expanded: str) -> list:
        """
        Короткие 1–2 словные алиасы.

        Только для фразы целиком. Раньше проверялось КАЖДОЕ слово внутри
        фразы, поэтому "открой aniblaze" ловилось алиасом "открой" и
        уезжало в open_explorer.
        """
        if not expanded:
            return []
        if expanded in self._short_map:
            return [(self._short_map[expanded], 95, f"short: {expanded}")]
        words = expanded.split()
        if len(words) == 1 and words[0] in self._short_map:
            return [(self._short_map[words[0]], 90, f"word: {words[0]}")]
        return []

    def _voice_profile_match(self, raw: str) -> list:
        if not self.voice_profile:
            return []
        vp_results = self.voice_profile.match(raw)
        results = []
        for intent_name, score, sample in vp_results:
            # Ищем action по имени интента
            for key, intent in self._intents.items():
                if intent["name"] == intent_name or key == intent_name:
                    results.append((intent["action"], score, f"vp: {sample}"))
                    break
        return results

    def _fuzzy_match(self, normalized: str, expanded: str) -> list:
        """
        Fuzzy только как крайний случай.

        token_set_ratio здесь не годится: он игнорирует «лишние» слова,
        поэтому "открой aniblaze" получал ~90% на "открой проводник" —
        общее слово "открой" вытягивало любой интент. Берём token_sort_ratio
        (учитывает всю фразу) и требуем реального перекрытия по словам.
        """
        user_words = set(normalized.split())
        if not user_words:
            return []
        results = []
        for key, intent in self._intents.items():
            best_score = 0
            best_match = ""
            for variant in intent["variants"]:
                variant_words = set(variant.split())
                overlap = user_words & variant_words
                if not overlap or not (overlap - GENERIC_TOKENS):
                    continue
                if len(overlap) / max(len(user_words), 1) < 0.5:
                    continue
                score = fuzz.token_sort_ratio(normalized, variant)
                if score > best_score:
                    best_score = score
                    best_match = variant
            # Fuzzy — самый слабый сигнал, для него порог выше общего.
            # Иначе "сколько это стоит" уезжает в "сколько времени".
            if best_score >= CONFIDENCE_THRESHOLD + 8:
                results.append((intent["action"], int(min(best_score, 100)), f"fuzzy: {best_match}"))
        return results

    def _log_match(self, raw, normalized, expanded, results):
        entry = {
            "raw": raw,
            "normalized": normalized,
            "expanded": expanded,
            "results": [
                {"action": r[0].__name__ if hasattr(r[0], "__name__") else str(r[0]),
                 "score": r[1], "form": r[2]} for r in results
            ],
        }
        self.log.append(entry)
        if len(self.log) > 50:
            self.log.pop(0)

    def get_last_log(self, n: int = 5) -> list:
        return self.log[-n:] if self.log else []

    def save_log(self, filepath: str = None):
        if not filepath:
            filepath = os.path.join(os.path.dirname(__file__), "..", "logs", "router_log.json")
        os.makedirs(os.path.dirname(filepath), exist_ok=True)
        with open(filepath, "w", encoding="utf-8") as f:
            json.dump(self.log, f, ensure_ascii=False, indent=2)

    def teach_intent(self, intent_name: str, stt_text: str, action) -> bool:
        """
        Добавляет произнесённый пользователем вариант в voice profile.
        Вызывается когда пользователь сказал команду и она была правильно распознана.
        """
        if not self.voice_profile or not stt_text:
            return False
        action_path = self._action_key(action)
        self.voice_profile.learn(intent_name, stt_text, action_path)
        # После обучения перестраиваем intent map (добавляем ключевые слова)
        # ищем интент по action
        for key, intent in self._intents.items():
            if intent["action"] == action:
                # Добавляем новые ключевые слова
                new_words = set(self.normalizer.normalize(stt_text).split())
                intent["keywords"] |= new_words
                intent["expanded_keywords"] |= new_words
                # Расширяем синонимами
                for kw in new_words:
                    if kw in SYNONYMS:
                        intent["expanded_keywords"].add(SYNONYMS[kw])
                break
        return True
