Set WshShell = CreateObject("WScript.Shell")
WshShell.Run "cmd /k title JARVIS && cd /d D:\AndroidProjects\AniBlaze\jarvis-offline && py main.py -d", 1, False
