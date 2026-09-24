using System.Text.Json;
using System.Text.Json.Serialization;

namespace AudioEarMute;

/// <summary>Hotkeys in the textual form used by config.json.</summary>
public sealed class HotkeyConfig
{
    [JsonPropertyName("leftOnly")]
    public string LeftOnly { get; set; } = "Ctrl+Alt+Left";

    [JsonPropertyName("rightOnly")]
    public string RightOnly { get; set; } = "Ctrl+Alt+Right";

    [JsonPropertyName("stereo")]
    public string Stereo { get; set; } = "Ctrl+Alt+Up";
}

/// <summary>
/// Settings stored in <c>%APPDATA%\AudioEarMute\config.json</c>.
/// A missing or corrupt file is not an error: defaults are used and the file is
/// rewritten on the next save.
/// </summary>
public sealed class Config
{
    [JsonPropertyName("mode")]
    public EarMode Mode { get; set; } = EarMode.Stereo;

    [JsonPropertyName("hotkeys")]
    public HotkeyConfig Hotkeys { get; set; } = new();

    private static readonly JsonSerializerOptions SerializerOptions = new()
    {
        WriteIndented = true,
        Converters = { new JsonStringEnumConverter() },
    };

    public static string Directory => Path.Combine(
        Environment.GetFolderPath(Environment.SpecialFolder.ApplicationData), "AudioEarMute");

    public static string FilePath => Path.Combine(Directory, "config.json");

    public static Config Load() => LoadFrom(FilePath);

    public void Save() => SaveTo(FilePath);

    /// <summary>Reads settings from a given file. The path is a parameter so this is testable.</summary>
    public static Config LoadFrom(string path)
    {
        try
        {
            if (File.Exists(path))
            {
                var json = File.ReadAllText(path);
                var loaded = JsonSerializer.Deserialize<Config>(json, SerializerOptions);
                if (loaded is not null)
                {
                    loaded.Hotkeys ??= new HotkeyConfig();
                    return loaded;
                }
            }
        }
        catch (Exception ex) when (ex is IOException or JsonException or UnauthorizedAccessException)
        {
            // Settings are not critical data. Fall back to defaults silently.
        }

        return new Config();
    }

    public void SaveTo(string path)
    {
        try
        {
            var directory = Path.GetDirectoryName(path);
            if (!string.IsNullOrEmpty(directory))
            {
                System.IO.Directory.CreateDirectory(directory);
            }

            File.WriteAllText(path, JsonSerializer.Serialize(this, SerializerOptions));
        }
        catch (Exception ex) when (ex is IOException or UnauthorizedAccessException)
        {
            // Nothing saved, but the mode still applies to the current session.
        }
    }
}
