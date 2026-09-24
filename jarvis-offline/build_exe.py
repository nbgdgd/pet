"""
Build JARVIS as standalone EXE using PyInstaller.
Запуск: py build_exe.py
"""
import os, sys, shutil

PROJECT_DIR = os.path.dirname(os.path.abspath(__file__))
DIST_DIR = os.path.join(PROJECT_DIR, "dist")
BUILD_DIR = os.path.join(PROJECT_DIR, "build")

for d in [DIST_DIR, BUILD_DIR]:
    if os.path.exists(d):
        shutil.rmtree(d)
        print(f"  удалён: {d}")

print("Сборка J.A.R.V.I.S. EXE...")

cmd = [
    sys.executable, "-m", "PyInstaller",
    "--name", "JARVIS",
    "--onefile",
    "--noconfirm",
    "--clean",
    "--add-data", f"commands{os.pathsep}commands",
    "--add-data", f"speech{os.pathsep}speech",
    "--add-data", f"tts{os.pathsep}tts",
    "--add-data", f"logs{os.pathsep}logs",
    "--add-data", f"profiles{os.pathsep}profiles",
    "--add-data", f"voices{os.pathsep}voices",
    "--add-data", f"models{os.pathsep}models",
    "--hidden-import", "rapidfuzz",
    "--collect-all", "sounddevice",
    "--collect-all", "soundfile",
    "--hidden-import", "numpy",
    "--hidden-import", "psutil",
    "--collect-all", "vosk",
    "--collect-all", "piper",
    "--collect-all", "onnxruntime",
    "--collect-all", "win32com",
    "--hidden-import", "deepgram",
    "--hidden-import", "httpx",
    "--hidden-import", "websockets",
    "--hidden-import", "colorama",
    os.path.join(PROJECT_DIR, "jarvis_cmd.py"),
]

os.chdir(PROJECT_DIR)
result = os.system(" ".join(cmd))
if result == 0:
    print(f"\nOK! EXE собран: {os.path.join(DIST_DIR, 'JARVIS.exe')}")
else:
    print(f"\nОшибка сборки (код {result})")
