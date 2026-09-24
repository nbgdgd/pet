using System.Drawing.Drawing2D;
using System.Runtime.InteropServices;

namespace AudioEarMute;

/// <summary>
/// The app's UI: tray icon, menu and global hotkeys. Runs on an STA thread and never
/// touches Core Audio COM objects — it only posts commands to <see cref="AudioEngine"/>.
/// </summary>
public sealed class TrayApp : ApplicationContext
{
    private const int WmHotkey = 0x0312;

    private const int HotkeyIdLeftOnly = 1;
    private const int HotkeyIdRightOnly = 2;
    private const int HotkeyIdStereo = 3;

    [DllImport("user32.dll", SetLastError = true)]
    private static extern bool RegisterHotKey(IntPtr hWnd, int id, uint modifiers, uint vk);

    [DllImport("user32.dll", SetLastError = true)]
    private static extern bool UnregisterHotKey(IntPtr hWnd, int id);

    [DllImport("user32.dll", SetLastError = true)]
    private static extern bool DestroyIcon(IntPtr handle);

    private readonly Config _config;
    private readonly AudioEngine _engine;
    private readonly NotifyIcon _notifyIcon;
    private readonly MessageWindow _messageWindow;
    private readonly ToolStripMenuItem _statusItem;
    private readonly ToolStripMenuItem _stereoItem;
    private readonly ToolStripMenuItem _leftOnlyItem;
    private readonly ToolStripMenuItem _rightOnlyItem;
    private readonly ToolStripMenuItem _autostartItem;

    private readonly List<int> _registeredHotkeys = new();
    private readonly List<string> _hotkeyProblems = new();

    private Icon? _currentIcon;
    private EarMode _mode;

    public TrayApp()
    {
        _config = Config.Load();
        _mode = _config.Mode;

        _messageWindow = new MessageWindow();
        _messageWindow.HotkeyPressed += OnHotkeyPressed;

        _statusItem = new ToolStripMenuItem { Enabled = false };
        _stereoItem = new ToolStripMenuItem("Stereo", null, (_, _) => ChangeMode(EarMode.Stereo));
        _leftOnlyItem = new ToolStripMenuItem("Left only — right ear rests", null,
            (_, _) => ChangeMode(EarMode.LeftOnly));
        _rightOnlyItem = new ToolStripMenuItem("Right only — left ear rests", null,
            (_, _) => ChangeMode(EarMode.RightOnly));

        _autostartItem = new ToolStripMenuItem("Start at logon", null, (_, _) => ToggleAutostart());

        var menu = new ContextMenuStrip();
        menu.Items.Add(_statusItem);
        menu.Items.Add(new ToolStripSeparator());
        menu.Items.Add(_stereoItem);
        menu.Items.Add(_leftOnlyItem);
        menu.Items.Add(_rightOnlyItem);
        menu.Items.Add(new ToolStripSeparator());
        menu.Items.Add(new ToolStripMenuItem("Test tone (440 Hz left / 880 Hz right)", null,
            (_, _) => TestTone.Play()));
        menu.Items.Add(new ToolStripMenuItem("Diagnostics…", null, (_, _) => ShowDiagnostics()));
        menu.Items.Add(_autostartItem);
        menu.Items.Add(new ToolStripSeparator());
        menu.Items.Add(new ToolStripMenuItem("Exit", null, (_, _) => ExitThread()));

        // The program may have been moved — fix the registered path before it goes stale.
        Autostart.RepairPathIfMoved();
        RefreshAutostartItem();

        _notifyIcon = new NotifyIcon
        {
            ContextMenuStrip = menu,
            Visible = true,
            Text = "AudioEarMute",
        };
        _notifyIcon.MouseClick += OnTrayClick;

        _engine = new AudioEngine(OnEngineStatus);

        // Safety net for exits that bypass ExitThreadCore (session end, unhandled
        // exception): no ear should stay deaf.
        AppDomain.CurrentDomain.ProcessExit += (_, _) => _engine.Dispose();

        RegisterHotkeys();
        UpdateUi(applied: null, mono: null, device: null, error: null);
        _engine.Start(_mode);
    }

    private void OnTrayClick(object? sender, MouseEventArgs e)
    {
        if (e.Button == MouseButtons.Left)
        {
            ChangeMode(MuteLogic.Next(_mode));
        }
    }

    private void OnHotkeyPressed(int id)
    {
        switch (id)
        {
            case HotkeyIdLeftOnly:
                ChangeMode(EarMode.LeftOnly);
                break;
            case HotkeyIdRightOnly:
                ChangeMode(EarMode.RightOnly);
                break;
            case HotkeyIdStereo:
                ChangeMode(EarMode.Stereo);
                break;
        }
    }

    private void ChangeMode(EarMode mode)
    {
        _mode = mode;
        _config.Mode = mode;
        _config.Save();
        _engine.SetMode(mode);
        UpdateUi(applied: null, mono: null, device: null, error: null);
    }

    /// <summary>Arrives from the engine's MTA thread — marshal it to the UI thread.</summary>
    private void OnEngineStatus(EngineStatus status)
    {
        if (_messageWindow.IsDisposed)
        {
            return;
        }

        try
        {
            _messageWindow.BeginInvoke(() =>
                UpdateUi(status.AppliedSessions, status.SkippedMonoSessions, status.DeviceName, status.Error));
        }
        catch (Exception ex) when (ex is InvalidOperationException or ObjectDisposedException)
        {
            // The window closed between the check and the call — nothing left to update.
        }
    }

    private void UpdateUi(int? applied, int? mono, string? device, string? error)
    {
        _stereoItem.Checked = _mode == EarMode.Stereo;
        _leftOnlyItem.Checked = _mode == EarMode.LeftOnly;
        _rightOnlyItem.Checked = _mode == EarMode.RightOnly;

        _statusItem.Text = MuteLogic.Describe(_mode);

        var tooltip = MuteLogic.Describe(_mode);

        if (error is not null)
        {
            tooltip += $"\n{error}";
        }
        else if (applied is not null)
        {
            tooltip += $"\nSessions: {applied}";
            if (mono > 0)
            {
                tooltip += $", mono unsupported: {mono}";
            }
        }

        if (device is not null)
        {
            tooltip += $"\n{device}";
        }

        if (_hotkeyProblems.Count > 0)
        {
            tooltip += $"\nUnavailable: {string.Join(", ", _hotkeyProblems)}";
        }

        // NotifyIcon.Text is capped at 63 characters — anything longer is silently rejected.
        _notifyIcon.Text = tooltip.Length > 63 ? tooltip[..63] : tooltip;

        SetIcon(_mode);
    }

    private void SetIcon(EarMode mode)
    {
        var previous = _currentIcon;
        _currentIcon = RenderIcon(mode);
        _notifyIcon.Icon = _currentIcon;
        previous?.Dispose();
    }

    /// <summary>The icon is drawn in code so no .ico resource has to ship.</summary>
    private static Icon RenderIcon(EarMode mode)
    {
        using var bitmap = new Bitmap(32, 32);
        using (var graphics = Graphics.FromImage(bitmap))
        {
            graphics.SmoothingMode = SmoothingMode.AntiAlias;
            graphics.TextRenderingHint = System.Drawing.Text.TextRenderingHint.AntiAlias;
            graphics.Clear(Color.Transparent);

            var accent = mode == EarMode.Stereo ? Color.FromArgb(120, 200, 255) : Color.FromArgb(255, 190, 90);
            using var brush = new SolidBrush(accent);
            using var font = new Font("Segoe UI", mode == EarMode.Stereo ? 13f : 18f, FontStyle.Bold,
                GraphicsUnit.Pixel);
            using var format = new StringFormat
            {
                Alignment = StringAlignment.Center,
                LineAlignment = StringAlignment.Center,
            };

            graphics.DrawString(MuteLogic.ShortLabel(mode), font, brush, new RectangleF(0, 0, 32, 32), format);
        }

        var handle = bitmap.GetHicon();
        try
        {
            // Clone it: Icon.FromHandle does not own the handle, and the handle must be freed.
            using var shared = Icon.FromHandle(handle);
            return (Icon)shared.Clone();
        }
        finally
        {
            DestroyIcon(handle);
        }
    }

    private void RegisterHotkeys()
    {
        TryRegister(HotkeyIdStereo, _config.Hotkeys.Stereo);
        TryRegister(HotkeyIdLeftOnly, _config.Hotkeys.LeftOnly);
        TryRegister(HotkeyIdRightOnly, _config.Hotkeys.RightOnly);
    }

    private void TryRegister(int id, string spec)
    {
        if (!MuteLogic.TryParseHotkey(spec, out var modifiers, out var key))
        {
            _hotkeyProblems.Add(spec);
            return;
        }

        var flags = (uint)(modifiers | HotkeyModifiers.NoRepeat);
        if (RegisterHotKey(_messageWindow.Handle, id, flags, (uint)key))
        {
            _registeredHotkeys.Add(id);
        }
        else
        {
            // The combination is taken by another program. The tray menu still works.
            _hotkeyProblems.Add(spec);
        }
    }

    private void RefreshAutostartItem()
    {
        if (Autostart.IsManagedByWindows)
        {
            // In the Store build the manifest declares the startup task, so the switch
            // lives in Windows Settings. Showing a checkbox we cannot honour would lie.
            _autostartItem.Text = "Start at logon — manage in Windows Settings";
            _autostartItem.Checked = false;
            _autostartItem.CheckOnClick = false;
            return;
        }

        // Read the real state rather than the intent: the checkmark must reflect fact.
        _autostartItem.Checked = Autostart.IsEnabled;
    }

    private void ToggleAutostart()
    {
        if (Autostart.IsManagedByWindows)
        {
            MessageBox.Show(
                "Start at logon for the Microsoft Store version is managed by Windows.\n\n" +
                "Open Settings, then Apps, then Startup, and switch AudioEarMute on or off there.",
                "AudioEarMute", MessageBoxButtons.OK, MessageBoxIcon.Information);
            return;
        }

        var enabling = !Autostart.IsEnabled;
        var succeeded = enabling ? Autostart.Enable() : Autostart.Disable();

        if (!succeeded)
        {
            MessageBox.Show(
                $"Could not turn start at logon {(enabling ? "on" : "off")}. Task Scheduler is unavailable.",
                "AudioEarMute", MessageBoxButtons.OK, MessageBoxIcon.Warning);
        }

        RefreshAutostartItem();
    }

    private void ShowDiagnostics()
    {
        var form = new DiagnosticsForm();
        form.Show();
        _ = form.RunAsync(_engine);
    }

    protected override void ExitThreadCore()
    {
        foreach (var id in _registeredHotkeys)
        {
            UnregisterHotKey(_messageWindow.Handle, id);
        }

        _registeredHotkeys.Clear();

        _notifyIcon.Visible = false;

        // Stop the engine before exiting: it restores stereo on every session.
        _engine.Dispose();

        _notifyIcon.Dispose();
        _currentIcon?.Dispose();
        _messageWindow.Dispose();

        base.ExitThreadCore();
    }

    /// <summary>An invisible window that receives <c>WM_HOTKEY</c>.</summary>
    private sealed class MessageWindow : Form
    {
        public event Action<int>? HotkeyPressed;

        public MessageWindow()
        {
            ShowInTaskbar = false;
            FormBorderStyle = FormBorderStyle.FixedToolWindow;
            StartPosition = FormStartPosition.Manual;
            Location = new Point(-32000, -32000);
            Size = new Size(1, 1);

            // Touching Handle forces window creation: without it RegisterHotKey
            // would have nowhere to post messages.
            _ = Handle;
        }

        protected override void SetVisibleCore(bool value) => base.SetVisibleCore(false);

        protected override void WndProc(ref Message m)
        {
            if (m.Msg == WmHotkey)
            {
                HotkeyPressed?.Invoke(m.WParam.ToInt32());
            }

            base.WndProc(ref m);
        }
    }
}
