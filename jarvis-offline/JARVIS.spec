# -*- mode: python ; coding: utf-8 -*-
from PyInstaller.utils.hooks import collect_all

datas = [('commands', 'commands'), ('speech', 'speech'), ('tts', 'tts'), ('logs', 'logs'), ('profiles', 'profiles'), ('voices', 'voices'), ('models', 'models')]
binaries = []
hiddenimports = ['rapidfuzz', 'numpy', 'psutil', 'deepgram', 'httpx', 'websockets',
                 'colorama', 'onnx_asr', 'silero_vad', 'faster_whisper', 'ctranslate2',
                 'tokenizers', 'av', 'huggingface_hub']

# torch тянет за собой ~2 ГБ — это цена Silero TTS (48 кГц) и silero-vad.
# Нужна лёгкая сборка: закомментируйте блок torch, поставьте
# TTS_VOICE=irina (Piper) в config.py — тогда VAD автоматически
# откатится на энергетический порог.
for pkg in ('sounddevice', 'vosk', 'piper', 'onnxruntime', 'onnx_asr',
            'silero_vad', 'faster_whisper', 'win32com', 'torch'):
    try:
        tmp_ret = collect_all(pkg)
        datas += tmp_ret[0]; binaries += tmp_ret[1]; hiddenimports += tmp_ret[2]
    except Exception as e:
        print(f'[spec] {pkg} пропущен: {e}')


a = Analysis(
    ['D:\\AndroidProjects\\AniBlaze\\jarvis-offline\\jarvis_cmd.py'],
    pathex=[],
    binaries=binaries,
    datas=datas,
    hiddenimports=hiddenimports,
    hookspath=[],
    hooksconfig={},
    runtime_hooks=[],
    excludes=[],
    noarchive=False,
    optimize=0,
)
pyz = PYZ(a.pure)

exe = EXE(
    pyz,
    a.scripts,
    a.binaries,
    a.datas,
    [],
    name='JARVIS',
    debug=False,
    bootloader_ignore_signals=False,
    strip=False,
    upx=True,
    upx_exclude=[],
    runtime_tmpdir=None,
    console=True,
    disable_windowed_traceback=False,
    argv_emulation=False,
    target_arch=None,
    codesign_identity=None,
    entitlements_file=None,
)
