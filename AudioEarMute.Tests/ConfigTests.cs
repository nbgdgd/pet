using AudioEarMute;
using Xunit;

namespace AudioEarMute.Tests;

public class ConfigTests : IDisposable
{
    private readonly string _directory =
        Path.Combine(Path.GetTempPath(), "AudioEarMuteTests", Guid.NewGuid().ToString("N"));

    private string PathFor(string name) => Path.Combine(_directory, name);

    public void Dispose()
    {
        try
        {
            if (Directory.Exists(_directory))
            {
                Directory.Delete(_directory, recursive: true);
            }
        }
        catch (IOException)
        {
        }
    }

    [Fact]
    public void Missing_file_yields_defaults()
    {
        var config = Config.LoadFrom(PathFor("absent.json"));

        Assert.Equal(EarMode.Stereo, config.Mode);
        Assert.Equal("Ctrl+Alt+Left", config.Hotkeys.LeftOnly);
    }

    [Fact]
    public void Round_trips_mode_and_hotkeys()
    {
        var path = PathFor("round-trip.json");
        var saved = new Config { Mode = EarMode.RightOnly };
        saved.Hotkeys.Stereo = "Ctrl+Shift+F8";
        saved.SaveTo(path);

        var loaded = Config.LoadFrom(path);

        Assert.Equal(EarMode.RightOnly, loaded.Mode);
        Assert.Equal("Ctrl+Shift+F8", loaded.Hotkeys.Stereo);
        Assert.Equal("Ctrl+Alt+Left", loaded.Hotkeys.LeftOnly);
    }

    [Fact]
    public void Mode_is_stored_by_name_not_by_number()
    {
        // A number would break if a new mode were inserted into the middle of the enum.
        var path = PathFor("named.json");
        new Config { Mode = EarMode.LeftOnly }.SaveTo(path);

        Assert.Contains("LeftOnly", File.ReadAllText(path));
    }

    [Fact]
    public void Corrupt_file_yields_defaults_instead_of_throwing()
    {
        var path = PathFor("corrupt.json");
        Directory.CreateDirectory(_directory);
        File.WriteAllText(path, "{ this is not json");

        var config = Config.LoadFrom(path);

        Assert.Equal(EarMode.Stereo, config.Mode);
    }

    [Fact]
    public void File_with_only_a_mode_still_gets_default_hotkeys()
    {
        var path = PathFor("partial.json");
        Directory.CreateDirectory(_directory);
        File.WriteAllText(path, """{ "mode": "LeftOnly" }""");

        var config = Config.LoadFrom(path);

        Assert.Equal(EarMode.LeftOnly, config.Mode);
        Assert.Equal("Ctrl+Alt+Up", config.Hotkeys.Stereo);
    }

    [Fact]
    public void Saving_creates_the_directory()
    {
        var path = Path.Combine(_directory, "nested", "deeper", "config.json");

        new Config().SaveTo(path);

        Assert.True(File.Exists(path));
    }
}
