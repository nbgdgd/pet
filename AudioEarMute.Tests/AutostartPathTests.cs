using AudioEarMute;
using Xunit;

namespace AudioEarMute.Tests;

public class AutostartPathTests
{
    [Theory]
    [InlineData("\"C:\\Tools\\AudioEarMute.exe\"", "C:\\Tools\\AudioEarMute.exe")]
    [InlineData("C:\\Tools\\AudioEarMute.exe", "C:\\Tools\\AudioEarMute.exe")]
    [InlineData("  \"C:\\Tools\\a b\\AudioEarMute.exe\"  ", "C:\\Tools\\a b\\AudioEarMute.exe")]
    public void Unquote_strips_quotes_and_padding(string stored, string expected)
    {
        Assert.Equal(expected, Autostart.Unquote(stored));
    }

    [Fact]
    public void Quoted_registry_value_matches_the_bare_path()
    {
        Assert.True(Autostart.PathsMatch("\"C:\\Tools\\AudioEarMute.exe\"", "C:\\Tools\\AudioEarMute.exe"));
    }

    [Fact]
    public void Comparison_ignores_case()
    {
        Assert.True(Autostart.PathsMatch("\"c:\\tools\\audioearmute.exe\"", "C:\\Tools\\AudioEarMute.exe"));
    }

    [Fact]
    public void Comparison_normalises_relative_segments()
    {
        Assert.True(Autostart.PathsMatch("\"C:\\Tools\\sub\\..\\AudioEarMute.exe\"", "C:\\Tools\\AudioEarMute.exe"));
    }

    [Fact]
    public void Different_locations_do_not_match()
    {
        Assert.False(Autostart.PathsMatch("\"C:\\Old\\AudioEarMute.exe\"", "C:\\New\\AudioEarMute.exe"));
    }

    [Fact]
    public void Garbage_in_the_registry_is_not_a_match_and_does_not_throw()
    {
        Assert.False(Autostart.PathsMatch("\"C:\\bad|path\\x.exe\"", "C:\\Tools\\AudioEarMute.exe"));
        Assert.False(Autostart.PathsMatch("   ", "C:\\Tools\\AudioEarMute.exe"));
    }
}
