# AudioEarMute

Mutes one earpiece so that ear can rest while you keep listening with the other.
Switch between left, right and stereo on the fly, from the tray or with a hotkey.

Works on Bluetooth headphones, where per-channel endpoint volume is useless because of
A2DP Absolute Volume.

## How it works

The app sets per-channel volume through `IChannelAudioVolume` — the interface of an audio
**session** (an individual application), not of the device. The Windows software mixer
applies it before the stream reaches the device, so the properties of the Bluetooth
endpoint never come into it.

No third-party driver, no registry surgery, no reboot, no added latency.

The mode is the source of truth inside the app, not in the system. It is reapplied when:

- a new application starts playing (`IAudioSessionNotification`);
- the headphones reconnect (`IMMNotificationClient`);
- a 2-second safety timer fires — that covers the case where an app reactivates its own
  expired session and no notification arrives.

## Building

```bash
dotnet build -c Release
```

Output: `bin\Release\net8.0-windows\AudioEarMute.exe`.

A self-contained single file in `dist\` — start at logon points at this one, and no .NET
installation is required to run it:

```bash
dotnet publish -c Release -r win-x64 --self-contained true -p:PublishSingleFile=true -p:IncludeNativeLibrariesForSelfExtract=true -p:EnableCompressionInSingleFile=true -o dist
```

## Using it

Run `AudioEarMute.exe` and a tray icon appears.

| Action | Result |
|---|---|
| Left click on the icon | Next mode in the cycle: stereo → left → right |
| Right click | Menu with every mode, the test tone and diagnostics |
| `Ctrl+Alt+Left` | Left only — the right ear rests |
| `Ctrl+Alt+Right` | Right only — the left ear rests |
| `Ctrl+Alt+Up` | Stereo |

The icon shows `LR`, `L` or `R`. The tooltip shows the device, the number of sessions
handled, and any hotkeys taken by other programs.

## Start at logon

The menu item `Start at logon` reflects the real state read back from the system, not the
intent.

**Unpackaged build** — creates a Task Scheduler logon task named `AudioEarMute` with
`LogonType=InteractiveToken` and `RunLevel=LeastPrivilege`, so no administrator rights are
needed.

**Microsoft Store build** — Windows owns start-up through the `windows.startupTask`
manifest extension. The menu item points at Settings → Apps → Startup instead of offering
a checkbox the app cannot honour. See `Packaging\PUBLISHING.md`.

From the console:

```bash
AudioEarMute.exe --autostart-status
```

Also `--enable-autostart` and `--disable-autostart`. Exit code 0 on success, and the path
that will be launched is printed so you can verify it.

### Why a task and not the Run key

Explorer runs `HKCU\...\Run` entries **sequentially**, so a slow neighbour delays
everything behind it. On the development machine Proton VPN and Proton Drive took 30
seconds each, which made the tray icon appear **1 minute 48 seconds** after logon — it
looked exactly like start-up had failed.

A scheduled task starts in parallel and waits for nobody.

An old `Run` value is removed when start-up is enabled, and one left over from an earlier
version is migrated to a task on the next launch. There will not be two copies.

### Location

Start at logon must point at a **stable** location. `bin\Release\` will not do — any
rebuild wipes it. The single-file build lives in `dist\`, and start-up points there.

If the program is moved, the path repairs itself on the next launch — but only when the
old path no longer exists. Two working copies do not steal start-up from each other.

## Verifying

`Test tone` in the menu plays 440 Hz in the left channel only and 880 Hz in the right
only. Run through the three modes and you can hear it immediately.

`Diagnostics…` shows, per session, the channel count, what was written to the volume and
**what read back**. A mismatch between the two is objective evidence that the mixer
ignores per-channel volume on this hardware.

The same thing from the console, with exit code 0 on success:

```bash
AudioEarMute.exe --diagnose
```

## Settings

`%APPDATA%\AudioEarMute\config.json`

```json
{
  "mode": "Stereo",
  "hotkeys": {
    "leftOnly": "Ctrl+Alt+Left",
    "rightOnly": "Ctrl+Alt+Right",
    "stereo": "Ctrl+Alt+Up"
  }
}
```

A combination is written as `Ctrl+Alt+Left`, `Ctrl+Shift+F9` or `Win+P`. Case does not
matter. A corrupt file falls back to defaults rather than failing.

## Limitations

- **Mono sessions are skipped.** A single-channel session has nothing to split, and muting
  it outright would lose the audio. The count is shown in the tray tooltip.
- **WASAPI exclusive mode** (ASIO, some players) bypasses the software mixer and cannot be
  affected. Rare, and the diagnostics window reveals it.
- **Virtual audio devices** (FxSound, VoiceMeeter, Nahimic) sit *after* this app in the
  chain. If such a device has an effect that mixes channels — surround, ambience, 3D,
  mono downmix — the muted channel can be refilled downstream. Turn the effect off, or
  select the headphones themselves as the default output device.

## Tests

```bash
cd ..\AudioEarMute.Tests
dotnet test
```

Covers the pure logic in `MuteLogic.cs`, `Config.cs` and the task-XML construction in
`Autostart.cs`: mode-to-volume mapping, the session skip decision, hotkey parsing, settings
round-trips and XML escaping. COM work is verified on live hardware through `--diagnose`.

## Publishing to the Microsoft Store

See [`Packaging/PUBLISHING.md`](Packaging/PUBLISHING.md).
