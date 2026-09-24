using System.Media;

namespace AudioEarMute;

/// <summary>
/// Test signal: 440 Hz in the left channel only, 880 Hz in the right channel only.
/// Played through SoundPlayer, so it creates its own audio session — which also
/// exercises applying the mode to a newly created session.
/// </summary>
public static class TestTone
{
    private const int SampleRate = 44100;
    private const double LeftFrequency = 440.0;
    private const double RightFrequency = 880.0;

    private static SoundPlayer? _player;

    public static void Play(int seconds = 5, double amplitude = 0.35)
    {
        _player?.Stop();
        _player?.Dispose();
        _player = new SoundPlayer(BuildWav(seconds, amplitude));
        _player.Play();
    }

    public static void Stop()
    {
        _player?.Stop();
        _player?.Dispose();
        _player = null;
    }

    private static MemoryStream BuildWav(int seconds, double amplitude)
    {
        const int channels = 2;
        const int bitsPerSample = 16;
        var frames = SampleRate * seconds;
        var dataBytes = frames * channels * (bitsPerSample / 8);

        var stream = new MemoryStream(44 + dataBytes);
        var writer = new BinaryWriter(stream);

        writer.Write("RIFF"u8.ToArray());
        writer.Write(36 + dataBytes);
        writer.Write("WAVE"u8.ToArray());

        writer.Write("fmt "u8.ToArray());
        writer.Write(16);                                             // fmt chunk size
        writer.Write((short)1);                                       // PCM
        writer.Write((short)channels);
        writer.Write(SampleRate);
        writer.Write(SampleRate * channels * (bitsPerSample / 8));    // bytes per second
        writer.Write((short)(channels * (bitsPerSample / 8)));        // block align
        writer.Write((short)bitsPerSample);

        writer.Write("data"u8.ToArray());
        writer.Write(dataBytes);

        for (var i = 0; i < frames; i++)
        {
            var t = (double)i / SampleRate;
            writer.Write((short)(Math.Sin(2 * Math.PI * LeftFrequency * t) * amplitude * short.MaxValue));
            writer.Write((short)(Math.Sin(2 * Math.PI * RightFrequency * t) * amplitude * short.MaxValue));
        }

        writer.Flush();
        stream.Position = 0;
        return stream;
    }
}
