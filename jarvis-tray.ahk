#SingleInstance
#Requires AutoHotkey >=2.0

AppName   := "J.A.R.V.I.S. for OpenCode"
ConfigDir := EnvGet("USERPROFILE") "\.opencode-jarvis"
ConfigPath := ConfigDir "\config.json"

Enabled := true
Voice   := "en-GB-RyanNeural"

LoadConfig() {
    global Enabled, Voice
    if FileExist(ConfigPath) {
        json := FileRead(ConfigPath)
        if RegExMatch(json, '"enabled":\s*(true|false)', &m)
            Enabled := m[1] = "true"
        if RegExMatch(json, '"voice":\s*"([^"]+)"', &m)
            Voice := m[1]
    }
}

SaveConfig() {
    state := Enabled ? "true" : "false"
    json := '{"enabled": ' state ', "voice": "' Voice '"}'
    FileOpen(ConfigPath, "w").Write(json)
}

Toggle() {
    global Enabled := !Enabled
    SaveConfig()
    UpdateTray()
}

UpdateTray() {
    A_IconTip := AppName "`nStatus: " (Enabled ? "ON" : "OFF") "`nVoice: " Voice
}

; Replace tray menu items (A_TrayMenu is a built-in Menu object)
A_TrayMenu.Delete()
A_TrayMenu.Add(AppName, ShowAbout)
A_TrayMenu.Add()
A_TrayMenu.Add("Toggle On/Off", (*) => Toggle())
A_TrayMenu.Add("Change Voice...", ChangeVoice)
A_TrayMenu.Add()
A_TrayMenu.Add("About", ShowAbout)
A_TrayMenu.Add("Exit", (*) => ExitApp())

LoadConfig()
UpdateTray()

^!J::Toggle()

ShowAbout(*) {
    MsgBox("J.A.R.V.I.S. voice companion for OpenCode`n`nCtrl+Alt+J to toggle`nVoice: " Voice "`nStatus: " (Enabled ? "ON" : "OFF"), AppName)
}

ChangeVoice(*) {
    global Voice
    ib := InputBox("Enter voice code (e.g. en-GB-RyanNeural):", "Change Voice", "w400 h180", Voice)
    if ib.Result = "OK" {
        Voice := ib.Value
        SaveConfig()
        UpdateTray()
    }
}
