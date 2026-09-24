"""
System monitor — фоновая проверка CPU/GPU/RAM.
Оповещает голосом ОДИН РАЗ при превышении порога.
"""
import threading, time, psutil, json, os

try:
    import GPUtil
    HAVE_GPU = True
except ImportError:
    HAVE_GPU = False


class SystemMonitor:
    CPU_THRESHOLD = 85
    RAM_THRESHOLD = 90
    GPU_THRESHOLD = 85
    CHECK_INTERVAL = 15  # секунд между проверками

    def __init__(self, tts_engine=None, on_alert=None):
        self._tts = tts_engine
        self._on_alert = on_alert  # callback(alert_type, text)
        self._running = False
        self._thread = None

        # Флаги однократного оповещения — сбрасываются когда нагрузка падает ниже порога
        self._cpu_alerted = False
        self._ram_alerted = False
        self._gpu_alerted = False

        self._cpu_went_down = True
        self._ram_went_down = True
        self._gpu_went_down = True

    def start(self):
        if self._running:
            return
        self._running = True
        self._thread = threading.Thread(target=self._loop, daemon=True)
        self._thread.start()

    def stop(self):
        self._running = False

    def _loop(self):
        while self._running:
            try:
                self._check()
            except Exception:
                pass
            time.sleep(self.CHECK_INTERVAL)

    def _check(self):
        cpu = psutil.cpu_percent(interval=0.5)
        ram = psutil.virtual_memory().percent

        gpu = None
        if HAVE_GPU:
            try:
                gpus = GPUtil.getGPUs()
                if gpus:
                    gpu = gpus[0].load * 100
            except Exception:
                pass

        now_cpu = cpu >= self.CPU_THRESHOLD
        now_ram = ram >= self.RAM_THRESHOLD
        now_gpu = gpu is not None and gpu >= self.GPU_THRESHOLD

        # CPU
        if now_cpu and self._cpu_went_down and not self._cpu_alerted:
            self._cpu_alerted = True
            self._cpu_went_down = False
            msg = f"Процессор под нагрузкой. Загрузка {cpu} процентов."
            self._alert("cpu", msg)

        if not now_cpu:
            self._cpu_went_down = True
            self._cpu_alerted = False

        # RAM
        if now_ram and self._ram_went_down and not self._ram_alerted:
            self._ram_alerted = True
            self._ram_went_down = False
            msg = f"Оперативная память под нагрузкой. Занято {ram} процентов."
            self._alert("ram", msg)

        if not now_ram:
            self._ram_went_down = True
            self._ram_alerted = False

        # GPU
        if now_gpu and self._gpu_went_down and not self._gpu_alerted:
            self._gpu_alerted = True
            self._gpu_went_down = False
            msg = f"Видеокарта под нагрузкой. Загрузка {gpu:.0f} процентов."
            self._alert("gpu", msg)

        if gpu is not None and not now_gpu:
            self._gpu_went_down = True
            self._gpu_alerted = False

    def _alert(self, alert_type, text):
        if self._on_alert:
            self._on_alert(alert_type, text)
        elif self._tts:
            self._tts.say(text)
