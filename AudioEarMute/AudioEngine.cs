using System.Collections.Concurrent;
using System.Diagnostics;
using System.Runtime.InteropServices;
using System.Text;

namespace AudioEarMute;

/// <summary>Engine state after a mode was applied.</summary>
public sealed record EngineStatus(
    EarMode Mode,
    string DeviceName,
    int AppliedSessions,
    int SkippedMonoSessions,
    string? Error);

/// <summary>
/// All Core Audio work. Lives on its own MTA thread: without MTA,
/// <c>IAudioSessionManager2::RegisterSessionNotification</c> never delivers
/// notifications about new sessions. The UI thread talks to the engine through a
/// command queue and never touches COM objects directly.
/// </summary>
public sealed class AudioEngine : IDisposable
{
    /// <summary>Verdict line meaning the approach works on this hardware.</summary>
    public const string VerdictWorks = "VERDICT: per-channel volume is applied. The approach works.";

    private enum CommandKind
    {
        Apply,
        Diagnose,
        Shutdown,
    }

    private sealed class Command
    {
        public CommandKind Kind { get; init; }
        public EarMode Mode { get; init; }
        public TaskCompletionSource<string>? Report { get; init; }
    }

    /// <summary>Safety-net interval for re-applying the mode.</summary>
    /// <remarks>
    /// <c>OnSessionCreated</c> does not fire when an app reactivates its own expired
    /// session, and this periodic pass covers that.
    /// </remarks>
    private const int ReapplyIntervalMs = 2000;

    private const string UnknownDeviceName = "Output device";

    private readonly BlockingCollection<Command> _commands = new();
    private readonly Thread _thread;
    private readonly Action<EngineStatus> _onStatus;

    // Touched only on the MTA thread.
    private IMMDeviceEnumerator? _enumerator;
    private IMMDevice? _device;
    private IAudioSessionManager2? _sessionManager;
    private SessionNotificationSink? _sessionSink;
    private DeviceNotificationSink? _deviceSink;
    private string _deviceName = "—";
    private EarMode _currentMode = EarMode.Stereo;
    private volatile bool _reattachRequested;

    public AudioEngine(Action<EngineStatus> onStatus)
    {
        _onStatus = onStatus;
        _thread = new Thread(Run)
        {
            IsBackground = true,
            Name = "AudioEarMute.CoreAudio",
        };
        _thread.SetApartmentState(ApartmentState.MTA);
    }

    public void Start(EarMode initialMode)
    {
        _currentMode = initialMode;
        _thread.Start();
    }

    /// <summary>Changes the mode. Returns immediately; the work happens on the MTA thread.</summary>
    public void SetMode(EarMode mode) => Post(new Command { Kind = CommandKind.Apply, Mode = mode });

    /// <summary>Builds a session report that reads back the volumes it wrote.</summary>
    public Task<string> DiagnoseAsync()
    {
        var report = new TaskCompletionSource<string>(TaskCreationOptions.RunContinuationsAsynchronously);
        if (!Post(new Command { Kind = CommandKind.Diagnose, Report = report }))
        {
            report.TrySetResult("The engine is stopped.");
        }

        return report.Task;
    }

    private bool Post(Command command)
    {
        try
        {
            _commands.Add(command);
            return true;
        }
        catch (Exception ex) when (ex is InvalidOperationException or ObjectDisposedException)
        {
            return false;
        }
    }

    public void Dispose()
    {
        Post(new Command { Kind = CommandKind.Shutdown });

        // Give it a chance to restore stereo on every session before exiting. If it does
        // not make it, the start-up cleanup applies (1,1) anyway when the mode is Stereo.
        _thread.Join(TimeSpan.FromSeconds(3));

        try
        {
            _commands.Dispose();
        }
        catch (Exception ex) when (ex is InvalidOperationException or ObjectDisposedException)
        {
        }
    }

    private void Run()
    {
        try
        {
            Initialize();

            // Clean up after a possible crash of the previous run: Windows persists
            // per-channel session volumes and will not restore them on its own.
            Apply(_currentMode);

            while (true)
            {
                if (!_commands.TryTake(out var command, ReapplyIntervalMs))
                {
                    Apply(_currentMode);
                    continue;
                }

                switch (command.Kind)
                {
                    case CommandKind.Apply:
                        _currentMode = command.Mode;
                        Apply(_currentMode);
                        break;

                    case CommandKind.Diagnose:
                        command.Report?.TrySetResult(BuildDiagnosticsReport());
                        break;

                    case CommandKind.Shutdown:
                        return;
                }
            }
        }
        catch (Exception ex)
        {
            _onStatus(new EngineStatus(_currentMode, _deviceName, 0, 0, ex.Message));
        }
        finally
        {
            // No ear should stay deaf after we exit.
            TryRestoreStereo();
            Teardown();
        }
    }

    private void Initialize()
    {
        _enumerator = (IMMDeviceEnumerator)new MMDeviceEnumeratorComObject();

        _deviceSink = new DeviceNotificationSink(OnDefaultDeviceChanged);
        _enumerator.RegisterEndpointNotificationCallback(_deviceSink);

        AttachToDefaultDevice();
    }

    private void OnDefaultDeviceChanged()
    {
        // Arrives on a foreign thread — only queue the work here.
        _reattachRequested = true;
        Post(new Command { Kind = CommandKind.Apply, Mode = _currentMode });
    }

    private void AttachToDefaultDevice()
    {
        DetachDevice();

        if (_enumerator is null)
        {
            return;
        }

        var hr = _enumerator.GetDefaultAudioEndpoint(EDataFlow.Render, ERole.Multimedia, out var device);
        if (HResult.Failed(hr) || device is null)
        {
            _deviceName = "No output device";
            return;
        }

        _device = device;
        _deviceName = ReadFriendlyName(device);

        var iid = typeof(IAudioSessionManager2).GUID;
        hr = device.Activate(ref iid, ClsCtx.All, IntPtr.Zero, out var raw);
        if (HResult.Failed(hr) || raw is not IAudioSessionManager2 manager)
        {
            return;
        }

        _sessionManager = manager;

        // The order matters: without enumerating sessions first, Windows does not
        // deliver notifications about new ones.
        if (!HResult.Failed(manager.GetSessionEnumerator(out var primer)))
        {
            SafeRelease(primer);
        }

        _sessionSink = new SessionNotificationSink(() =>
            Post(new Command { Kind = CommandKind.Apply, Mode = _currentMode }));
        manager.RegisterSessionNotification(_sessionSink);
    }

    private void DetachDevice()
    {
        if (_sessionManager is not null && _sessionSink is not null)
        {
            try
            {
                _sessionManager.UnregisterSessionNotification(_sessionSink);
            }
            catch (COMException)
            {
            }
        }

        _sessionSink = null;
        SafeRelease(_sessionManager);
        _sessionManager = null;
        SafeRelease(_device);
        _device = null;
    }

    private void Teardown()
    {
        if (_enumerator is not null && _deviceSink is not null)
        {
            try
            {
                _enumerator.UnregisterEndpointNotificationCallback(_deviceSink);
            }
            catch (COMException)
            {
            }
        }

        _deviceSink = null;
        DetachDevice();
        SafeRelease(_enumerator);
        _enumerator = null;
    }

    private void TryRestoreStereo()
    {
        try
        {
            Apply(EarMode.Stereo, reportStatus: false);
        }
        catch (Exception ex) when (ex is COMException or InvalidCastException)
        {
        }
    }

    private static string ReadFriendlyName(IMMDevice device)
    {
        var key = PropertyKeys.DeviceFriendlyName;
        PropVariant value = default;

        try
        {
            if (HResult.Failed(device.OpenPropertyStore(0 /* STGM_READ */, out var store)) || store is null)
            {
                return UnknownDeviceName;
            }

            try
            {
                if (HResult.Failed(store.GetValue(ref key, out value)))
                {
                    return UnknownDeviceName;
                }

                return value.AsString() ?? UnknownDeviceName;
            }
            finally
            {
                Ole32.PropVariantClear(ref value);
                SafeRelease(store);
            }
        }
        catch (Exception ex) when (ex is COMException or InvalidCastException)
        {
            return UnknownDeviceName;
        }
    }

    private void Apply(EarMode mode, bool reportStatus = true)
    {
        if (_reattachRequested)
        {
            _reattachRequested = false;
            AttachToDefaultDevice();
        }

        if (_sessionManager is null)
        {
            AttachToDefaultDevice();
        }

        if (_sessionManager is null)
        {
            if (reportStatus)
            {
                _onStatus(new EngineStatus(mode, _deviceName, 0, 0, "No output device"));
            }

            return;
        }

        var (left, right) = MuteLogic.ChannelVolumes(mode);
        var applied = 0;
        var mono = 0;
        string? error = null;

        ForEachSession((session, _) =>
        {
            switch (ApplyToSession(session, left, right))
            {
                case SessionSkipReason.None:
                    applied++;
                    break;
                case SessionSkipReason.UnsupportedChannelCount:
                    mono++;
                    break;
            }
        }, ex => error = ex.Message);

        if (reportStatus)
        {
            _onStatus(new EngineStatus(mode, _deviceName, applied, mono, error));
        }
    }

    /// <summary>Applies the volumes to one session. Returns why it was skipped, if it was.</summary>
    private static SessionSkipReason ApplyToSession(IAudioSessionControl session, float left, float right)
    {
        if (session is not IChannelAudioVolume channelVolume)
        {
            return SessionSkipReason.UnsupportedChannelCount;
        }

        if (HResult.Failed(session.GetState(out var state)))
        {
            return SessionSkipReason.Expired;
        }

        if (HResult.Failed(channelVolume.GetChannelCount(out var channels)))
        {
            return SessionSkipReason.UnsupportedChannelCount;
        }

        var decision = MuteLogic.Evaluate(state == AudioSessionState.Expired, channels);
        if (decision != SessionSkipReason.None)
        {
            return decision;
        }

        var context = Guid.Empty;
        channelVolume.SetChannelVolume(0, left, ref context);
        channelVolume.SetChannelVolume(1, right, ref context);
        return SessionSkipReason.None;
    }

    /// <summary>
    /// Enumerates the sessions of the current device. An exception on one session does
    /// not stop the walk: a session dying between enumeration and the write is normal.
    /// </summary>
    private void ForEachSession(Action<IAudioSessionControl, int> body, Action<Exception> onError)
    {
        IAudioSessionEnumerator? sessions = null;
        var toRelease = new List<object>();

        try
        {
            if (_sessionManager is null ||
                HResult.Failed(_sessionManager.GetSessionEnumerator(out sessions)) || sessions is null)
            {
                return;
            }

            if (HResult.Failed(sessions.GetCount(out var count)))
            {
                return;
            }

            for (var i = 0; i < count; i++)
            {
                IAudioSessionControl? session = null;
                try
                {
                    if (HResult.Failed(sessions.GetSession(i, out session)) || session is null)
                    {
                        continue;
                    }

                    toRelease.Add(session);
                    body(session, i);
                }
                catch (Exception ex) when (ex is COMException or InvalidCastException)
                {
                    // The session vanished. Move on.
                }
            }
        }
        catch (Exception ex) when (ex is COMException or InvalidCastException)
        {
            onError(ex);
        }
        finally
        {
            foreach (var item in toRelease)
            {
                SafeRelease(item);
            }

            SafeRelease(sessions);
        }
    }

    private string BuildDiagnosticsReport()
    {
        var (left, right) = MuteLogic.ChannelVolumes(_currentMode);
        var report = new StringBuilder();

        report.AppendLine($"Device:  {_deviceName}");
        report.AppendLine($"Mode:    {MuteLogic.Describe(_currentMode)}");
        report.AppendLine($"Writing: L={left:0.00}  R={right:0.00}");
        report.AppendLine();
        report.AppendLine("Process                    State    Ch.   Written     Read back   Result");
        report.AppendLine(new string('-', 78));

        var verdictFailures = 0;
        var checkedSessions = 0;

        ForEachSession((session, _) =>
        {
            var name = DescribeSessionProcess(session);
            var state = HResult.Failed(session.GetState(out var s)) ? "?" : s.ToString();

            if (session is not IChannelAudioVolume channelVolume ||
                HResult.Failed(channelVolume.GetChannelCount(out var channels)))
            {
                report.AppendLine($"{Pad(name, 26)} {Pad(state, 8)} —     no IChannelAudioVolume");
                return;
            }

            if (channels != 2)
            {
                report.AppendLine(
                    $"{Pad(name, 26)} {Pad(state, 8)} {Pad(channels.ToString(), 5)} skipped: not stereo");
                return;
            }

            var context = Guid.Empty;
            var wrote = !HResult.Failed(channelVolume.SetChannelVolume(0, left, ref context)) &&
                        !HResult.Failed(channelVolume.SetChannelVolume(1, right, ref context));

            channelVolume.GetChannelVolume(0, out var readLeft);
            channelVolume.GetChannelVolume(1, out var readRight);

            // The objective criterion: what was written must read back.
            var matches = Math.Abs(readLeft - left) < 0.01f && Math.Abs(readRight - right) < 0.01f;
            checkedSessions++;
            if (!matches)
            {
                verdictFailures++;
            }

            report.AppendLine(
                $"{Pad(name, 26)} {Pad(state, 8)} {Pad(channels.ToString(), 5)} " +
                $"{Pad($"{left:0.00}/{right:0.00}", 11)} {Pad($"{readLeft:0.00}/{readRight:0.00}", 11)} " +
                (wrote && matches ? "OK" : "MISMATCH"));
        }, ex => report.AppendLine($"Enumeration error: {ex.Message}"));

        report.AppendLine();

        if (checkedSessions == 0)
        {
            report.AppendLine("VERDICT: no stereo sessions found. Play audio in any app and try again.");
        }
        else if (verdictFailures == 0)
        {
            report.AppendLine(VerdictWorks);
        }
        else
        {
            report.AppendLine(
                $"VERDICT: {verdictFailures} of {checkedSessions} sessions rejected per-channel volume.");
            report.AppendLine("The mixer ignores IChannelAudioVolume on this hardware.");
        }

        return report.ToString();
    }

    private static string DescribeSessionProcess(IAudioSessionControl session)
    {
        try
        {
            if (session is IAudioSessionControl2 control2)
            {
                if (control2.IsSystemSoundsSession() == HResult.Ok)
                {
                    return "System sounds";
                }

                if (!HResult.Failed(control2.GetProcessId(out var pid)))
                {
                    using var process = Process.GetProcessById(pid);
                    return process.ProcessName;
                }
            }
        }
        catch (Exception ex) when (ex is COMException or InvalidCastException or ArgumentException or
                                       InvalidOperationException)
        {
        }

        return "(unknown)";
    }

    private static string Pad(string value, int width) =>
        value.Length >= width ? value[..width] : value.PadRight(width);

    private static void SafeRelease(object? comObject)
    {
        if (comObject is null || !Marshal.IsComObject(comObject))
        {
            return;
        }

        try
        {
            Marshal.ReleaseComObject(comObject);
        }
        catch (Exception ex) when (ex is ArgumentException or InvalidComObjectException)
        {
        }
    }

    /// <summary>Receives notifications about newly created audio sessions.</summary>
    private sealed class SessionNotificationSink : IAudioSessionNotification
    {
        private readonly Action _onCreated;

        public SessionNotificationSink(Action onCreated) => _onCreated = onCreated;

        public int OnSessionCreated(IAudioSessionControl newSession)
        {
            _onCreated();
            return HResult.Ok;
        }
    }

    /// <summary>Receives notifications about the default output device changing.</summary>
    private sealed class DeviceNotificationSink : IMMNotificationClient
    {
        private readonly Action _onDefaultChanged;

        public DeviceNotificationSink(Action onDefaultChanged) => _onDefaultChanged = onDefaultChanged;

        public int OnDeviceStateChanged(string deviceId, int newState) => HResult.Ok;

        public int OnDeviceAdded(string deviceId) => HResult.Ok;

        public int OnDeviceRemoved(string deviceId) => HResult.Ok;

        public int OnDefaultDeviceChanged(EDataFlow flow, ERole role, string defaultDeviceId)
        {
            if (flow == EDataFlow.Render && role == ERole.Multimedia)
            {
                _onDefaultChanged();
            }

            return HResult.Ok;
        }

        public int OnPropertyValueChanged(string deviceId, PropertyKey key) => HResult.Ok;
    }
}
