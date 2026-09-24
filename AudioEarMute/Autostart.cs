using System.Diagnostics;
using System.Text;
using Microsoft.Win32;

namespace AudioEarMute;

/// <summary>What provides start-up at logon.</summary>
public enum AutostartMechanism
{
    /// <summary>Start-up is off.</summary>
    None,

    /// <summary>A logon task in Task Scheduler. The mechanism used by the unpackaged build.</summary>
    ScheduledTask,

    /// <summary>An old <c>HKCU\...\Run</c> value left by earlier versions.</summary>
    LegacyRunKey,

    /// <summary>
    /// The MSIX build. Windows owns start-up through the <c>windows.startupTask</c>
    /// manifest extension and the user toggles it in Settings, Startup apps.
    /// </summary>
    PackagedStartupTask,
}

/// <summary>
/// Starts the app at logon.
/// </summary>
/// <remarks>
/// <para>
/// The unpackaged build registers a Task Scheduler logon task. An earlier version used
/// <c>HKCU\...\Run</c>, but Explorer runs those entries sequentially, so a slow neighbour
/// delays everyone behind it. On the development machine Proton VPN and Proton Drive took
/// 30 seconds each, and the tray icon appeared 1 minute 48 seconds after logon. A logon
/// task starts in parallel and waits for nobody.
/// </para>
/// <para>
/// The task is registered for the current user with <c>LeastPrivilege</c>, so no
/// administrator rights are needed.
/// </para>
/// <para>
/// The MSIX build does none of this: Windows starts the app from the manifest declaration.
/// See <see cref="PackageInfo"/>.
/// </para>
/// </remarks>
public static class Autostart
{
    private const string RunKeyPath = @"Software\Microsoft\Windows\CurrentVersion\Run";
    private const string ValueName = "AudioEarMute";

    /// <summary>Name of the task in Task Scheduler.</summary>
    public const string TaskName = "AudioEarMute";

    /// <summary>
    /// Path to this executable. Single-file publishing needs <c>Environment.ProcessPath</c>
    /// specifically: the assembly location would point at the temporary extraction folder.
    /// </summary>
    public static string ExecutablePath =>
        Environment.ProcessPath ?? Application.ExecutablePath;

    public static bool IsEnabled => Mechanism != AutostartMechanism.None;

    /// <summary>
    /// True when the app cannot change the setting itself and the user has to use
    /// Windows Settings instead. That is the case for the Store build.
    /// </summary>
    public static bool IsManagedByWindows => PackageInfo.IsPackaged;

    public static AutostartMechanism Mechanism
    {
        get
        {
            // Inside a package the manifest declares the startup task, so there is
            // nothing to look up and nothing this class may change.
            if (PackageInfo.IsPackaged)
            {
                return AutostartMechanism.PackagedStartupTask;
            }

            if (TaskExists())
            {
                return AutostartMechanism.ScheduledTask;
            }

            return ReadRunKeyValue() is not null
                ? AutostartMechanism.LegacyRunKey
                : AutostartMechanism.None;
        }
    }

    /// <summary>Path the app will be started from at logon, or <c>null</c>.</summary>
    public static string? RegisteredPath => Mechanism switch
    {
        AutostartMechanism.PackagedStartupTask => ExecutablePath,
        AutostartMechanism.ScheduledTask => ReadTaskCommand(),
        AutostartMechanism.LegacyRunKey => ReadRunKeyValue(),
        _ => null,
    };

    public static bool Enable() => Enable(ExecutablePath);

    public static bool Enable(string executablePath)
    {
        if (PackageInfo.IsPackaged)
        {
            // Windows owns this. Reporting success would be a lie; the caller shows
            // a pointer to Settings instead.
            return false;
        }

        // Always clear the Run value: otherwise, after moving to a task, the app would
        // start twice and the second copy would pop the "already running" dialog.
        RemoveRunKeyValue();

        var xmlPath = Path.Combine(Path.GetTempPath(), $"AudioEarMute-task-{Guid.NewGuid():N}.xml");
        try
        {
            // schtasks insists on UTF-16 — with UTF-8 it answers "invalid XML".
            File.WriteAllText(xmlPath, BuildTaskXml(executablePath, CurrentUserId()), Encoding.Unicode);
            return RunSchTasks($"/Create /TN \"{TaskName}\" /XML \"{xmlPath}\" /F") == 0;
        }
        catch (Exception ex) when (ex is IOException or UnauthorizedAccessException)
        {
            return false;
        }
        finally
        {
            try
            {
                File.Delete(xmlPath);
            }
            catch (Exception ex) when (ex is IOException or UnauthorizedAccessException)
            {
            }
        }
    }

    public static bool Disable()
    {
        if (PackageInfo.IsPackaged)
        {
            return false;
        }

        var runKeyCleared = RemoveRunKeyValue();
        var taskCleared = !TaskExists() || RunSchTasks($"/Delete /TN \"{TaskName}\" /F") == 0;
        return runKeyCleared && taskCleared;
    }

    /// <summary>
    /// If start-up is on but the registered path no longer exists, re-register the
    /// current executable. Otherwise moving the program would silently break start-up.
    /// </summary>
    public static void RepairPathIfMoved()
    {
        if (PackageInfo.IsPackaged)
        {
            // A package cannot move, and its path is managed by Windows.
            return;
        }

        var mechanism = Mechanism;
        if (mechanism == AutostartMechanism.None)
        {
            return;
        }

        var current = ExecutablePath;
        if (string.IsNullOrEmpty(current))
        {
            return;
        }

        // An old Run value is migrated to a task on the very first launch.
        if (mechanism == AutostartMechanism.LegacyRunKey)
        {
            Enable(current);
            return;
        }

        var registered = RegisteredPath;
        if (registered is null || PathsMatch(registered, current))
        {
            return;
        }

        // The old path may still be valid — two copies of the program, say. Leave it
        // alone then, because rewriting would point start-up at an arbitrary copy.
        if (File.Exists(Unquote(registered)))
        {
            return;
        }

        Enable(current);
    }

    /// <summary>Compares paths allowing for quotes and case — Windows paths are case insensitive.</summary>
    public static bool PathsMatch(string registered, string actual)
    {
        try
        {
            return string.Equals(
                Path.GetFullPath(Unquote(registered)).TrimEnd('\\'),
                Path.GetFullPath(actual).TrimEnd('\\'),
                StringComparison.OrdinalIgnoreCase);
        }
        catch (Exception ex) when (ex is ArgumentException or NotSupportedException or PathTooLongException)
        {
            // Garbage in the registration does not count as a match.
            return false;
        }
    }

    public static string Unquote(string value) => value.Trim().Trim('"');

    /// <summary>
    /// Task XML. A separate method so that its construction is covered by tests without
    /// touching Task Scheduler.
    /// </summary>
    public static string BuildTaskXml(string executablePath, string userId)
    {
        var workingDirectory = Path.GetDirectoryName(executablePath) ?? string.Empty;

        return $"""
                <?xml version="1.0" encoding="UTF-16"?>
                <Task version="1.4" xmlns="http://schemas.microsoft.com/windows/2004/02/mit/task">
                  <RegistrationInfo>
                    <Description>Mutes one earpiece so that ear can rest. Runs in the notification area.</Description>
                    <URI>\{TaskName}</URI>
                  </RegistrationInfo>
                  <Triggers>
                    <LogonTrigger>
                      <Enabled>true</Enabled>
                      <UserId>{Escape(userId)}</UserId>
                    </LogonTrigger>
                  </Triggers>
                  <Principals>
                    <Principal id="Author">
                      <UserId>{Escape(userId)}</UserId>
                      <LogonType>InteractiveToken</LogonType>
                      <RunLevel>LeastPrivilege</RunLevel>
                    </Principal>
                  </Principals>
                  <Settings>
                    <MultipleInstancesPolicy>IgnoreNew</MultipleInstancesPolicy>
                    <DisallowStartIfOnBatteries>false</DisallowStartIfOnBatteries>
                    <StopIfGoingOnBatteries>false</StopIfGoingOnBatteries>
                    <AllowHardTerminate>false</AllowHardTerminate>
                    <StartWhenAvailable>false</StartWhenAvailable>
                    <RunOnlyIfNetworkAvailable>false</RunOnlyIfNetworkAvailable>
                    <IdleSettings>
                      <StopOnIdleEnd>false</StopOnIdleEnd>
                      <RestartOnIdle>false</RestartOnIdle>
                    </IdleSettings>
                    <AllowStartOnDemand>true</AllowStartOnDemand>
                    <Enabled>true</Enabled>
                    <Hidden>false</Hidden>
                    <RunOnlyIfIdle>false</RunOnlyIfIdle>
                    <WakeToRun>false</WakeToRun>
                    <ExecutionTimeLimit>PT0S</ExecutionTimeLimit>
                    <Priority>7</Priority>
                  </Settings>
                  <Actions Context="Author">
                    <Exec>
                      <Command>{Escape(executablePath)}</Command>
                      <WorkingDirectory>{Escape(workingDirectory)}</WorkingDirectory>
                    </Exec>
                  </Actions>
                </Task>
                """;
    }

    /// <summary>
    /// XML escaping. A path containing an ampersand would otherwise make the document
    /// invalid, and Task Scheduler would reject it without a useful explanation.
    /// </summary>
    public static string Escape(string value) => value
        .Replace("&", "&amp;")
        .Replace("<", "&lt;")
        .Replace(">", "&gt;")
        .Replace("\"", "&quot;");

    private static string CurrentUserId()
    {
        var domain = Environment.UserDomainName;
        var user = Environment.UserName;
        return string.IsNullOrEmpty(domain) ? user : $"{domain}\\{user}";
    }

    /// <summary>
    /// The task definition file. Task Scheduler stores it as UTF-16 with a BOM, and the
    /// task owner can read it without administrator rights.
    /// </summary>
    /// <remarks>
    /// The file is read instead of the output of <c>schtasks /XML</c>: when redirected,
    /// schtasks prints in the console code page (CP866 on the development machine) even
    /// though the XML declares UTF-16. .NET has no single-byte code pages without the
    /// System.Text.Encoding.CodePages package, so a non-ASCII path would turn into
    /// garbage. The file has a BOM, so File.ReadAllText detects the encoding itself.
    /// </remarks>
    private static string TaskFilePath => Path.Combine(
        Environment.GetFolderPath(Environment.SpecialFolder.System), "Tasks", TaskName);

    private static bool TaskExists()
    {
        try
        {
            if (File.Exists(TaskFilePath))
            {
                return true;
            }
        }
        catch (Exception ex) when (ex is IOException or UnauthorizedAccessException)
        {
        }

        // The file may be unreadable under unusual permissions — ask the scheduler.
        return RunSchTasks($"/Query /TN \"{TaskName}\"") == 0;
    }

    private static string? ReadTaskCommand()
    {
        try
        {
            return ExtractCommand(File.ReadAllText(TaskFilePath));
        }
        catch (Exception ex) when (ex is IOException or UnauthorizedAccessException
                                       or FileNotFoundException or DirectoryNotFoundException)
        {
            return null;
        }
    }

    /// <summary>Pulls the executable path out of the task XML. Separate so tests can cover it.</summary>
    public static string? ExtractCommand(string taskXml)
    {
        var start = taskXml.IndexOf("<Command>", StringComparison.OrdinalIgnoreCase);
        var end = taskXml.IndexOf("</Command>", StringComparison.OrdinalIgnoreCase);
        if (start < 0 || end <= start)
        {
            return null;
        }

        start += "<Command>".Length;
        var value = System.Net.WebUtility.HtmlDecode(taskXml[start..end]).Trim();
        return value.Length == 0 ? null : value;
    }

    /// <summary>Runs schtasks and returns its exit code. The output is not parsed.</summary>
    private static int RunSchTasks(string arguments)
    {
        try
        {
            using var process = Process.Start(new ProcessStartInfo
            {
                FileName = "schtasks.exe",
                Arguments = arguments,
                UseShellExecute = false,
                CreateNoWindow = true,
                RedirectStandardOutput = true,
                RedirectStandardError = true,
            });

            if (process is null)
            {
                return 1;
            }

            // Drain both streams before waiting: a full pipe buffer would block schtasks.
            process.StandardOutput.ReadToEnd();
            process.StandardError.ReadToEnd();

            // Wait with a limit: a hung schtasks must not freeze the UI.
            return process.WaitForExit(15000) ? process.ExitCode : 1;
        }
        catch (Exception ex) when (ex is System.ComponentModel.Win32Exception or InvalidOperationException
                                       or IOException)
        {
            return 1;
        }
    }

    private static string? ReadRunKeyValue()
    {
        try
        {
            using var key = Registry.CurrentUser.OpenSubKey(RunKeyPath);
            return key?.GetValue(ValueName) as string;
        }
        catch (Exception ex) when (ex is System.Security.SecurityException or UnauthorizedAccessException
                                       or IOException)
        {
            return null;
        }
    }

    private static bool RemoveRunKeyValue()
    {
        try
        {
            using var key = Registry.CurrentUser.OpenSubKey(RunKeyPath, writable: true);
            key?.DeleteValue(ValueName, throwOnMissingValue: false);
            return true;
        }
        catch (Exception ex) when (ex is System.Security.SecurityException or UnauthorizedAccessException
                                       or IOException)
        {
            return false;
        }
    }
}
