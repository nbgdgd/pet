using System.Text;

namespace AudioEarMute;

internal static class Program
{
    [STAThread]
    private static int Main(string[] args)
    {
        // Console modes do not take the mutex: they must work while the app already
        // sits in the notification area.
        if (HasFlag(args, "--diagnose"))
        {
            return SelfTest.Run();
        }

        if (HasFlag(args, "--enable-autostart"))
        {
            return ReportAutostart(Autostart.Enable());
        }

        if (HasFlag(args, "--disable-autostart"))
        {
            return ReportAutostart(Autostart.Disable());
        }

        if (HasFlag(args, "--autostart-status"))
        {
            return ReportAutostart(true);
        }

        // Two instances would overwrite each other's per-channel volumes.
        using var singleInstance = new Mutex(initiallyOwned: true, "AudioEarMute.SingleInstance", out var isFirst);
        if (!isFirst)
        {
            MessageBox.Show("AudioEarMute is already running — look for the tray icon.", "AudioEarMute",
                MessageBoxButtons.OK, MessageBoxIcon.Information);
            return 0;
        }

        ApplicationConfiguration.Initialize();
        Application.Run(new TrayApp());
        return 0;
    }

    private static bool HasFlag(string[] args, string flag) =>
        args.Any(a => a.Equals(flag, StringComparison.OrdinalIgnoreCase));

    private static int ReportAutostart(bool succeeded)
    {
        var mechanism = Autostart.Mechanism;
        var registered = Autostart.RegisteredPath;

        WriteUtf8Line(mechanism switch
        {
            AutostartMechanism.PackagedStartupTask =>
                "Start at logon: managed by Windows for the Store build.\n" +
                "Change it in Settings, Apps, Startup.",
            AutostartMechanism.ScheduledTask =>
                $"Start at logon: on (scheduled task \"{Autostart.TaskName}\")\nRuns: {registered}",
            AutostartMechanism.LegacyRunKey =>
                $"Start at logon: on through the legacy Run key\nRuns: {registered}\n" +
                "It will move to a scheduled task the next time the app starts.",
            _ => "Start at logon: off",
        });

        return succeeded ? 0 : 1;
    }

    private static void WriteUtf8Line(string text)
    {
        // A GUI app has no console, so Console.OutputEncoding has no effect —
        // write UTF-8 straight to standard output.
        using var output = new StreamWriter(Console.OpenStandardOutput(),
            new UTF8Encoding(encoderShouldEmitUTF8Identifier: false));
        output.WriteLine(text);
    }
}
