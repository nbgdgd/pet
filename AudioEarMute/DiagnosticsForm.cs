namespace AudioEarMute;

/// <summary>
/// Session report: what was written to the per-channel volume and what read back.
/// A mismatch is objective evidence that the mixer ignores IChannelAudioVolume
/// on this hardware.
/// </summary>
public sealed class DiagnosticsForm : Form
{
    private readonly TextBox _output;
    private readonly Button _refresh;
    private readonly Button _copy;

    public DiagnosticsForm()
    {
        Text = "AudioEarMute — diagnostics";
        Width = 820;
        Height = 520;
        StartPosition = FormStartPosition.CenterScreen;
        MinimizeBox = false;

        _output = new TextBox
        {
            Multiline = true,
            ReadOnly = true,
            ScrollBars = ScrollBars.Both,
            WordWrap = false,
            Dock = DockStyle.Fill,
            Font = new Font("Consolas", 9f),
            Text = "Collecting…",
        };

        _refresh = new Button { Text = "Refresh", Width = 110, Height = 30, Enabled = false };
        _copy = new Button { Text = "Copy", Width = 110, Height = 30, Enabled = false };

        var buttons = new FlowLayoutPanel
        {
            Dock = DockStyle.Bottom,
            FlowDirection = FlowDirection.RightToLeft,
            Height = 42,
            Padding = new Padding(6),
        };
        buttons.Controls.Add(_copy);
        buttons.Controls.Add(_refresh);

        Controls.Add(_output);
        Controls.Add(buttons);

        _copy.Click += (_, _) =>
        {
            if (!string.IsNullOrEmpty(_output.Text))
            {
                Clipboard.SetText(_output.Text);
            }
        };
    }

    /// <summary>Asks the engine for a report and shows it.</summary>
    public async Task RunAsync(AudioEngine engine)
    {
        _refresh.Click += async (_, _) => await LoadAsync(engine);
        await LoadAsync(engine);
    }

    private async Task LoadAsync(AudioEngine engine)
    {
        _refresh.Enabled = false;
        _copy.Enabled = false;
        _output.Text = "Collecting…";

        string report;
        try
        {
            report = await engine.DiagnoseAsync();
        }
        catch (Exception ex)
        {
            report = $"Could not build the report: {ex.Message}";
        }

        if (IsDisposed)
        {
            return;
        }

        _output.Text = report;
        _refresh.Enabled = true;
        _copy.Enabled = true;
    }
}
