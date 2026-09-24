# Store listing copy

Paste these into the **Store listings** tab in Partner Center. Character limits are the
ones Partner Center enforces.

---

## Name (reserved product name)

```
AudioEarMute
```

If taken, alternatives worth reserving: `Ear Rest Audio`, `One Ear Audio`, `SoloEar`.

---

## Short description (max 200 characters)

```
Mute one earpiece so that ear can rest, and keep listening with the other. Works on Bluetooth headphones, where the usual balance slider does nothing.
```

---

## Description (max 10,000 characters)

```
Wearing over-ear headphones all day is tiring. AudioEarMute lets one ear rest without
taking the headphones off and without losing the audio: pick the side that should go
silent, and everything keeps playing in the other earpiece.

Switch with a click on the tray icon or a global hotkey. The choice survives restarts,
app launches and headphone reconnections.

WHY THIS WORKS ON BLUETOOTH

Windows has a left/right balance slider, but on Bluetooth headphones it does nothing
useful. A2DP Absolute Volume sends a single volume value to the headphones themselves, so
the per-channel split never reaches them.

AudioEarMute works one level earlier, on the per-channel volume of each audio session, so
the change is applied by the Windows software mixer before the stream is sent to the
device. Bluetooth quirks never come into it.

There is no virtual audio driver, no reboot, and no added latency.

FEATURES

- Three modes: stereo, left only, right only
- Global hotkeys: Ctrl+Alt+Left, Ctrl+Alt+Right, Ctrl+Alt+Up
- One click on the tray icon cycles through the modes
- The mode is reapplied automatically when a new app starts playing or the headphones
  reconnect, so it never silently falls off
- Built-in test tone: 440 Hz in the left channel, 880 Hz in the right
- Built-in diagnostics that report, per session, what was written and what read back —
  so you can confirm it works rather than guess
- Start at logon, switchable from Windows Settings
- No network access, no accounts, no telemetry

GOOD TO KNOW

Apps that play a mono stream are left alone. There is nothing to split in a single
channel, and muting such a stream outright would silence it entirely. The tray tooltip
tells you how many sessions were skipped for this reason.

Apps that bypass the Windows mixer in WASAPI exclusive mode are not affected. This is
rare, and the diagnostics window will show it.

If you use a virtual audio device such as FxSound, VoiceMeeter or Nahimic, it sits after
AudioEarMute in the chain. If that device has an effect that mixes the channels together
— surround, ambience, 3D — the muted side can be refilled downstream. Turn that effect
off, or select your headphones directly as the default output device.

REQUIREMENTS

Windows 10 version 1809 or newer. Any output device: Bluetooth, USB or 3.5 mm.
```

---

## Search terms (max 7, 30 characters each)

```
mute one ear
one earpiece
audio balance
left right channel
headphone balance
ear rest
bluetooth balance
```

---

## Category

- **Primary:** Utilities & tools
- **Secondary:** Music (optional)

---

## Screenshots

Partner Center requires at least one screenshot, 1366x768 or larger, PNG.

Suggested set, four images:

1. **Tray menu open** — shows the three modes with the current one checked. This is the
   whole product in one image, so make it the first screenshot.
2. **Diagnostics window** — the per-session table with the verdict line. Sells the
   "confirm rather than guess" angle.
3. **Tooltip on the tray icon** — mode, device name and session count.
4. **Windows Settings, Startup apps** — the app listed there, showing start-up is under
   the user's control rather than forced.

Capture on a clean desktop with no personal data in window titles: certification looks at
screenshots and a visible personal detail is an easy avoidable rejection.

---

## Notes for certification

```
AudioEarMute is a tray utility that mutes one channel of the default audio output device.

It uses the Windows Core Audio API (IChannelAudioVolume) to set per-channel volume on the
current audio sessions of the default render endpoint. This requires the runFullTrust
capability, which is why the app is submitted as a packaged Win32 desktop application.

The app has no user interface beyond a notification-area icon and a diagnostics dialog.
It makes no network connections, collects no data and requires no account.

To exercise it: right-click the tray icon, choose "Test tone", then switch between
"Left only" and "Right only" and observe that the tone stops in the corresponding
earpiece. The "Diagnostics" menu item prints a per-session report confirming the volumes
that were applied.

Start at logon is declared through the windows.startupTask manifest extension with
UserConfigurable="true" and is not enabled by default; the user turns it on in Settings.
```
