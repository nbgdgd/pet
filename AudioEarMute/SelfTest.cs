using System.Text;

namespace AudioEarMute;

/// <summary>
/// Verifies the approach on the actual hardware: starts the engine, creates a guaranteed
/// stereo session with a quiet tone, switches to "left only" and reads the volumes back.
/// Run with <c>AudioEarMute.exe --diagnose</c>.
/// </summary>
public static class SelfTest
{
    public static int Run()
    {
        var log = new StringBuilder();
        var statusSeen = 0;

        using var engine = new AudioEngine(status =>
        {
            if (Interlocked.Increment(ref statusSeen) <= 3)
            {
                log.AppendLine(
                    $"[engine] mode={status.Mode} device={status.DeviceName} " +
                    $"sessions={status.AppliedSessions} mono={status.SkippedMonoSessions} " +
                    $"error={status.Error ?? "none"}");
            }
        });

        engine.Start(EarMode.Stereo);

        // A quiet short tone: the point is a live stereo session, not a concert.
        TestTone.Play(seconds: 3, amplitude: 0.08);
        Thread.Sleep(1200);

        engine.SetMode(EarMode.LeftOnly);
        Thread.Sleep(600);

        var report = engine.DiagnoseAsync().GetAwaiter().GetResult();

        engine.SetMode(EarMode.Stereo);
        Thread.Sleep(400);
        TestTone.Stop();

        // Write UTF-8 straight to standard output. Console.OutputEncoding does not help
        // here: a GUI app has no console, so the assignment does not take effect.
        using (var output = new StreamWriter(Console.OpenStandardOutput(),
                   new UTF8Encoding(encoderShouldEmitUTF8Identifier: false)))
        {
            output.Write(log.ToString());
            output.WriteLine();
            output.Write(report);
        }

        // Compared against the constant rather than a literal, so renaming the verdict
        // cannot silently break the exit code.
        return report.Contains(AudioEngine.VerdictWorks, StringComparison.Ordinal) ? 0 : 1;
    }
}
