"""
Структурированное логирование пайплайна команды.
"""
import os, json, datetime


class CommandLog:
    def __init__(self, log_dir: str = None):
        self.log_dir = log_dir or os.path.join(os.path.dirname(__file__), "..", "logs")
        os.makedirs(self.log_dir, exist_ok=True)
        self.session_id = datetime.datetime.now().strftime("%Y%m%d_%H%M%S")
        self.entries = []
        self._seq = 0

    def log(self, stage: str, **kwargs):
        entry = {
            "seq": self._seq,
            "ts": datetime.datetime.now().isoformat(),
            "stage": stage,
        }
        entry.update(kwargs)
        self.entries.append(entry)
        self._seq += 1

    def log_stt(self, raw_text: str, wake_word_detected: bool = False):
        self.log("stt", raw=raw_text, wake_word=wake_word_detected)

    def log_normalize(self, raw: str, normalized: str, expanded: str):
        self.log("normalize", raw=raw, normalized=normalized, expanded=expanded)

    def log_route(self, text: str, results: list):
        self.log("route", text=text, results=[
            {"action": r[0].__name__ if hasattr(r[0], "__name__") else str(r[0]),
             "score": r[1], "matched": r[2]} for r in results
        ])

    def log_execute(self, action_name: str, success: bool, result=None, error: str = None):
        self.log("execute", action=action_name, success=success,
                 result=str(result) if result else None,
                 error=str(error) if error else None)

    def log_confirm(self, action_name: str, confirmed: bool):
        self.log("confirm", action=action_name, confirmed=confirmed)

    def log_text_fallback(self, text: str):
        self.log("text_fallback", text=text)

    def log_voice_learn(self, intent: str, stt_text: str):
        self.log("voice_learn", intent=intent, stt_text=stt_text)

    def save(self, filename: str = None):
        if not filename:
            filename = f"session_{self.session_id}.json"
        path = os.path.join(self.log_dir, filename)
        with open(path, "w", encoding="utf-8") as f:
            json.dump({
                "session": self.session_id,
                "count": len(self.entries),
                "entries": self.entries,
            }, f, ensure_ascii=False, indent=2)
        return path

    def print_last(self, n: int = 1):
        for e in self.entries[-n:]:
            stage = e.get("stage", "?")
            if stage == "stt":
                print(f"[STT] '{e.get('raw','')}' wake={e.get('wake_word',False)}")
            elif stage == "normalize":
                print(f"[NORM] '{e.get('raw','')}' -> '{e.get('normalized','')}'")
            elif stage == "route":
                res = e.get("results", [])
                if res:
                    r = res[0]
                    print(f"[ROUTE] {r['action']} ({r['score']}%) matched='{r['matched']}'")
                else:
                    print(f"[ROUTE] no match for '{e.get('text','')}'")
            elif stage == "execute":
                ok = "OK" if e.get("success") else "FAIL"
                err = f" error={e.get('error','')}" if e.get("error") else ""
                print(f"[EXEC] {e.get('action','?')} {ok}{err}")
            elif stage == "confirm":
                print(f"[CONF] {e.get('action','?')} confirmed={e.get('confirmed',False)}")
