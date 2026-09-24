namespace AudioEarMute;

/// <summary>Which earpiece is allowed to play.</summary>
public enum EarMode
{
    /// <summary>Normal stereo in both earpieces.</summary>
    Stereo,

    /// <summary>Only the left earpiece plays — the right ear rests.</summary>
    LeftOnly,

    /// <summary>Only the right earpiece plays — the left ear rests.</summary>
    RightOnly,
}

/// <summary>Why a session was skipped while applying a mode.</summary>
public enum SessionSkipReason
{
    /// <summary>Not skipped — the mode is applied.</summary>
    None,

    /// <summary>The session expired: the app no longer renders audio.</summary>
    Expired,

    /// <summary>Not two channels, so there is nothing to split into left and right.</summary>
    UnsupportedChannelCount,
}

/// <summary>
/// Pure logic with no dependency on COM or on audio hardware. Everything here is covered
/// by unit tests; everything that talks to WASAPI lives in <see cref="AudioEngine"/>.
/// </summary>
public static class MuteLogic
{
    /// <summary>Left and right channel volumes for a mode.</summary>
    public static (float Left, float Right) ChannelVolumes(EarMode mode) => mode switch
    {
        EarMode.Stereo => (1f, 1f),
        EarMode.LeftOnly => (1f, 0f),
        EarMode.RightOnly => (0f, 1f),
        _ => (1f, 1f),
    };

    /// <summary>
    /// Decides what to do with a session before writing volumes. Expired sessions are
    /// skipped because writing to them is pointless and COM may throw. Non-stereo
    /// sessions are skipped as well: a mono session has nothing to split, and muting it
    /// outright would silence audio the user still wants to hear.
    /// </summary>
    public static SessionSkipReason Evaluate(bool isExpired, uint channelCount)
    {
        if (isExpired)
        {
            return SessionSkipReason.Expired;
        }

        return channelCount == 2 ? SessionSkipReason.None : SessionSkipReason.UnsupportedChannelCount;
    }

    /// <summary>Next mode in the cycle — used by a left click on the tray icon.</summary>
    public static EarMode Next(EarMode mode) => mode switch
    {
        EarMode.Stereo => EarMode.LeftOnly,
        EarMode.LeftOnly => EarMode.RightOnly,
        _ => EarMode.Stereo,
    };

    /// <summary>Mode caption for the menu and the tooltip.</summary>
    public static string Describe(EarMode mode) => mode switch
    {
        EarMode.Stereo => "Stereo",
        EarMode.LeftOnly => "Left only (right ear rests)",
        EarMode.RightOnly => "Right only (left ear rests)",
        _ => "Stereo",
    };

    /// <summary>Short label drawn on the tray icon.</summary>
    public static string ShortLabel(EarMode mode) => mode switch
    {
        EarMode.Stereo => "LR",
        EarMode.LeftOnly => "L",
        EarMode.RightOnly => "R",
        _ => "LR",
    };

    /// <summary>
    /// Parses a hotkey string such as <c>Ctrl+Alt+Left</c>. Modifiers and the key name
    /// are case insensitive, and surrounding whitespace is ignored.
    /// </summary>
    public static bool TryParseHotkey(string? text, out HotkeyModifiers modifiers, out Keys key)
    {
        modifiers = HotkeyModifiers.None;
        key = Keys.None;

        if (string.IsNullOrWhiteSpace(text))
        {
            return false;
        }

        // Accumulate into locals: on failure the out parameters must stay empty,
        // otherwise the caller risks acting on a half-parsed combination.
        var parsedModifiers = HotkeyModifiers.None;
        var parsedKey = Keys.None;

        foreach (var rawPart in text.Split('+', StringSplitOptions.RemoveEmptyEntries))
        {
            var part = rawPart.Trim();
            if (part.Length == 0)
            {
                return false;
            }

            switch (part.ToLowerInvariant())
            {
                case "ctrl":
                case "control":
                    parsedModifiers |= HotkeyModifiers.Control;
                    continue;
                case "alt":
                    parsedModifiers |= HotkeyModifiers.Alt;
                    continue;
                case "shift":
                    parsedModifiers |= HotkeyModifiers.Shift;
                    continue;
                case "win":
                case "windows":
                    parsedModifiers |= HotkeyModifiers.Win;
                    continue;
            }

            // Not a modifier, so this is the key itself — and a combination has only one.
            if (parsedKey != Keys.None || !Enum.TryParse(part, ignoreCase: true, out Keys candidate) ||
                candidate == Keys.None)
            {
                return false;
            }

            parsedKey = candidate;
        }

        if (parsedKey == Keys.None)
        {
            return false;
        }

        modifiers = parsedModifiers;
        key = parsedKey;
        return true;
    }
}

/// <summary>Modifier flags for <c>RegisterHotKey</c> (values from winuser.h).</summary>
[Flags]
public enum HotkeyModifiers
{
    None = 0,
    Alt = 0x0001,
    Control = 0x0002,
    Shift = 0x0004,
    Win = 0x0008,

    /// <summary>Do not repeat while the key is held down.</summary>
    NoRepeat = 0x4000,
}
