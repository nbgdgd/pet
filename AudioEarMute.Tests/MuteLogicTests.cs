using AudioEarMute;
using Xunit;

namespace AudioEarMute.Tests;

public class ChannelVolumesTests
{
    [Fact]
    public void Stereo_keeps_both_channels()
    {
        Assert.Equal((1f, 1f), MuteLogic.ChannelVolumes(EarMode.Stereo));
    }

    [Fact]
    public void LeftOnly_silences_the_right_channel()
    {
        var (left, right) = MuteLogic.ChannelVolumes(EarMode.LeftOnly);
        Assert.Equal(1f, left);
        Assert.Equal(0f, right);
    }

    [Fact]
    public void RightOnly_silences_the_left_channel()
    {
        var (left, right) = MuteLogic.ChannelVolumes(EarMode.RightOnly);
        Assert.Equal(0f, left);
        Assert.Equal(1f, right);
    }

    [Fact]
    public void Unknown_mode_falls_back_to_stereo()
    {
        Assert.Equal((1f, 1f), MuteLogic.ChannelVolumes((EarMode)42));
    }
}

public class SessionEvaluationTests
{
    [Fact]
    public void Stereo_session_is_applied()
    {
        Assert.Equal(SessionSkipReason.None, MuteLogic.Evaluate(isExpired: false, channelCount: 2));
    }

    [Theory]
    [InlineData(0u)]
    [InlineData(1u)]
    [InlineData(6u)]
    [InlineData(8u)]
    public void Non_stereo_session_is_skipped(uint channels)
    {
        Assert.Equal(SessionSkipReason.UnsupportedChannelCount,
            MuteLogic.Evaluate(isExpired: false, channelCount: channels));
    }

    [Fact]
    public void Expired_session_is_skipped()
    {
        Assert.Equal(SessionSkipReason.Expired, MuteLogic.Evaluate(isExpired: true, channelCount: 2));
    }

    [Fact]
    public void Expiry_is_checked_before_channel_count()
    {
        // Otherwise an expired mono session would land in the "mono unsupported"
        // counter and the user would see a problem that does not exist.
        Assert.Equal(SessionSkipReason.Expired, MuteLogic.Evaluate(isExpired: true, channelCount: 1));
    }
}

public class ModeCyclingTests
{
    [Fact]
    public void Click_cycles_stereo_left_right_stereo()
    {
        var mode = EarMode.Stereo;

        mode = MuteLogic.Next(mode);
        Assert.Equal(EarMode.LeftOnly, mode);

        mode = MuteLogic.Next(mode);
        Assert.Equal(EarMode.RightOnly, mode);

        mode = MuteLogic.Next(mode);
        Assert.Equal(EarMode.Stereo, mode);
    }
}

public class ModeLabelTests
{
    [Theory]
    [InlineData(EarMode.Stereo)]
    [InlineData(EarMode.LeftOnly)]
    [InlineData(EarMode.RightOnly)]
    public void Every_mode_has_a_description_and_a_short_label(EarMode mode)
    {
        Assert.False(string.IsNullOrWhiteSpace(MuteLogic.Describe(mode)));
        Assert.False(string.IsNullOrWhiteSpace(MuteLogic.ShortLabel(mode)));
    }
}

public class HotkeyParsingTests
{
    [Fact]
    public void Parses_control_alt_left()
    {
        Assert.True(MuteLogic.TryParseHotkey("Ctrl+Alt+Left", out var modifiers, out var key));
        Assert.Equal(HotkeyModifiers.Control | HotkeyModifiers.Alt, modifiers);
        Assert.Equal(Keys.Left, key);
    }

    [Fact]
    public void Is_case_insensitive_and_ignores_spaces()
    {
        Assert.True(MuteLogic.TryParseHotkey(" ctrl + SHIFT + f9 ", out var modifiers, out var key));
        Assert.Equal(HotkeyModifiers.Control | HotkeyModifiers.Shift, modifiers);
        Assert.Equal(Keys.F9, key);
    }

    [Fact]
    public void Accepts_control_and_windows_aliases()
    {
        Assert.True(MuteLogic.TryParseHotkey("Control+Windows+P", out var modifiers, out var key));
        Assert.Equal(HotkeyModifiers.Control | HotkeyModifiers.Win, modifiers);
        Assert.Equal(Keys.P, key);
    }

    [Fact]
    public void Accepts_a_bare_key_without_modifiers()
    {
        Assert.True(MuteLogic.TryParseHotkey("F12", out var modifiers, out var key));
        Assert.Equal(HotkeyModifiers.None, modifiers);
        Assert.Equal(Keys.F12, key);
    }

    [Theory]
    [InlineData(null)]
    [InlineData("")]
    [InlineData("   ")]
    [InlineData("Ctrl+Alt")]
    [InlineData("Ctrl+Alt+")]
    [InlineData("Ctrl+Nonsense")]
    [InlineData("Ctrl+Left+Right")]
    [InlineData("None")]
    public void Rejects_malformed_specs(string? spec)
    {
        Assert.False(MuteLogic.TryParseHotkey(spec, out var modifiers, out var key));
        Assert.Equal(HotkeyModifiers.None, modifiers);
        Assert.Equal(Keys.None, key);
    }

    [Fact]
    public void Default_hotkeys_from_config_all_parse()
    {
        var defaults = new HotkeyConfig();

        Assert.True(MuteLogic.TryParseHotkey(defaults.Stereo, out _, out _));
        Assert.True(MuteLogic.TryParseHotkey(defaults.LeftOnly, out _, out _));
        Assert.True(MuteLogic.TryParseHotkey(defaults.RightOnly, out _, out _));
    }
}
