// Установщик AniBlaze: один exe со встроенным образом приложения внутри.
//
// Почему свой, а не `jpackage --type exe`: тому нужен WiX Toolset, которого на машине
// нет, а самораспаковка 7-Zip отпадает — модуль 7z.sfx из обычной поставки умеет
// только распаковывать, запускать установку он не может (проверено). Компилятор C#
// (csc.exe из .NET Framework 4.8) есть в самой Windows, поэтому установщик собирается
// вообще без сторонних инструментов.
//
// Образ приложения лежит в exe ресурсом `payload.zip`, рядом — `uninstall.ps1`.
// Ставим в папку пользователя: прав администратора не нужно.
using System;
using System.Diagnostics;
using System.Drawing;
using System.IO;
using System.IO.Compression;
using System.Linq;
using System.Reflection;
using System.Threading;
using System.Windows.Forms;
using Microsoft.Win32;

static class Setup
{
    public const string AppName = "AniBlaze";
    public const string Version = "1.0.2";

    [STAThread]
    static int Main(string[] args)
    {
        // Автоматическая установка для сборки и проверок: без окон.
        if (args.Any(a => a.Equals("/silent", StringComparison.OrdinalIgnoreCase)))
        {
            string target = Arg(args, "/dir") ?? DefaultDir();
            bool shortcuts = !args.Any(a => a.Equals("/noshortcuts", StringComparison.OrdinalIgnoreCase));
            bool register = !args.Any(a => a.Equals("/noregister", StringComparison.OrdinalIgnoreCase));
            try
            {
                Install(target, shortcuts, shortcuts, register, (t, p) => Console.WriteLine(p + "% " + t));
                if (args.Any(a => a.Equals("/launch", StringComparison.OrdinalIgnoreCase)))
                    Process.Start(Path.Combine(target, "AniBlaze.exe"));
                return 0;
            }
            catch (Exception e)
            {
                Console.Error.WriteLine(e.Message);
                return 1;
            }
        }
        Application.EnableVisualStyles();
        Application.SetCompatibleTextRenderingDefault(false);
        Application.Run(new SetupForm());
        return 0;
    }

    static string Arg(string[] args, string name)
    {
        int i = Array.FindIndex(args, a => a.Equals(name, StringComparison.OrdinalIgnoreCase));
        return (i >= 0 && i + 1 < args.Length) ? args[i + 1] : null;
    }

    public static string DefaultDir()
    {
        return Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData),
            "Programs", AppName);
    }

    /// Ставит приложение. Прогресс отдаётся вызывающему: окно рисует полосу, тихий
    /// режим печатает строки.
    public static void Install(string dir, bool desktopShortcut, bool menuShortcut, bool register,
        Action<string, int> report)
    {
        report("Закрываю запущенное приложение…", 2);
        foreach (var p in Process.GetProcessesByName("AniBlaze"))
        {
            try { p.Kill(); p.WaitForExit(5000); } catch { }
        }

        report("Распаковываю файлы…", 5);
        Directory.CreateDirectory(dir);
        using (var zip = Resource("payload.zip"))
        using (var archive = new ZipArchive(zip, ZipArchiveMode.Read))
        {
            int total = archive.Entries.Count, done = 0;
            foreach (var entry in archive.Entries)
            {
                string path = Path.Combine(dir, entry.FullName.Replace('/', '\\'));
                if (entry.Length == 0 && entry.FullName.EndsWith("/"))
                {
                    Directory.CreateDirectory(path);
                }
                else
                {
                    Directory.CreateDirectory(Path.GetDirectoryName(path));
                    // Перезаписываем принудительно: exe и dll прошлой установки могут
                    // быть помечены «только для чтения» (jpackage так делает).
                    if (File.Exists(path)) File.SetAttributes(path, FileAttributes.Normal);
                    entry.ExtractToFile(path, true);
                }
                done++;
                if (done % 25 == 0 || done == total)
                    report("Распаковываю файлы… " + done + " из " + total, 5 + done * 80 / Math.Max(1, total));
            }
        }

        string exe = Path.Combine(dir, "AniBlaze.exe");
        if (!File.Exists(exe)) throw new IOException("После распаковки нет " + exe);

        // Деинсталлятор кладём рядом с приложением: на него ссылается запись в
        // «Установка и удаление программ».
        string uninstall = Path.Combine(dir, "uninstall.ps1");
        using (var src = Resource("uninstall.ps1"))
        using (var dst = File.Create(uninstall)) src.CopyTo(dst);

        report("Создаю ярлыки…", 90);
        if (menuShortcut)
        {
            string menu = Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.ApplicationData),
                @"Microsoft\Windows\Start Menu\Programs\AniBlaze");
            Directory.CreateDirectory(menu);
            CreateShortcut(Path.Combine(menu, "AniBlaze.lnk"), exe, dir);
        }
        if (desktopShortcut)
        {
            CreateShortcut(Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.DesktopDirectory),
                "AniBlaze.lnk"), exe, dir);
        }

        if (register)
        {
            report("Регистрирую в «Установка и удаление программ»…", 96);
            using (var key = Registry.CurrentUser.CreateSubKey(
                @"Software\Microsoft\Windows\CurrentVersion\Uninstall\AniBlaze"))
            {
                string command = "powershell.exe -NoProfile -ExecutionPolicy Bypass -File \"" + uninstall + "\"";
                key.SetValue("DisplayName", "AniBlaze");
                key.SetValue("DisplayVersion", Version);
                key.SetValue("Publisher", "AniBlaze");
                key.SetValue("InstallLocation", dir);
                key.SetValue("DisplayIcon", exe);
                key.SetValue("UninstallString", command);
                key.SetValue("QuietUninstallString", command + " -Silent");
                key.SetValue("EstimatedSize", (int)(DirectorySize(dir) / 1024), RegistryValueKind.DWord);
                key.SetValue("NoModify", 1, RegistryValueKind.DWord);
                key.SetValue("NoRepair", 1, RegistryValueKind.DWord);
            }
        }
        report("Готово", 100);
    }

    static long DirectorySize(string dir)
    {
        long sum = 0;
        foreach (var f in Directory.GetFiles(dir, "*", SearchOption.AllDirectories))
        {
            try { sum += new FileInfo(f).Length; } catch { }
        }
        return sum;
    }

    static Stream Resource(string name)
    {
        var stream = Assembly.GetExecutingAssembly().GetManifestResourceStream(name);
        if (stream == null) throw new IOException("В установщике нет ресурса " + name);
        return stream;
    }

    /// Ярлык через WScript.Shell поздним связыванием: так не нужен interop-сборка
    /// IWshRuntimeLibrary, которой у csc под рукой нет.
    static void CreateShortcut(string linkPath, string target, string workDir)
    {
        var shellType = Type.GetTypeFromProgID("WScript.Shell");
        object shell = Activator.CreateInstance(shellType);
        object link = shellType.InvokeMember("CreateShortcut", BindingFlags.InvokeMethod, null, shell,
            new object[] { linkPath });
        var linkType = link.GetType();
        linkType.InvokeMember("TargetPath", BindingFlags.SetProperty, null, link, new object[] { target });
        linkType.InvokeMember("WorkingDirectory", BindingFlags.SetProperty, null, link, new object[] { workDir });
        linkType.InvokeMember("IconLocation", BindingFlags.SetProperty, null, link, new object[] { target });
        linkType.InvokeMember("Description", BindingFlags.SetProperty, null, link,
            new object[] { "AniBlaze — аниме и кино" });
        linkType.InvokeMember("Save", BindingFlags.InvokeMethod, null, link, null);
    }
}

/// Окно установки: папка, ярлыки, полоса прогресса. Одно окно, без мастера на пять
/// шагов — ставить нужно ровно одну программу в одну папку.
class SetupForm : Form
{
    readonly TextBox path = new TextBox();
    readonly Button browse = new Button();
    readonly CheckBox desktop = new CheckBox();
    readonly CheckBox menu = new CheckBox();
    readonly CheckBox launch = new CheckBox();
    readonly ProgressBar bar = new ProgressBar();
    readonly Label status = new Label();
    readonly Button install = new Button();

    public SetupForm()
    {
        Text = "Установка AniBlaze " + Setup.Version;
        ClientSize = new Size(520, 268);
        FormBorderStyle = FormBorderStyle.FixedDialog;
        MaximizeBox = false;
        StartPosition = FormStartPosition.CenterScreen;
        BackColor = Color.FromArgb(24, 22, 28);
        ForeColor = Color.FromArgb(235, 235, 240);
        Font = new Font("Segoe UI", 9f);
        try { Icon = Icon.ExtractAssociatedIcon(Application.ExecutablePath); } catch { }

        var title = new Label
        {
            Text = "AniBlaze",
            Font = new Font("Segoe UI", 16f, FontStyle.Bold),
            ForeColor = Color.FromArgb(255, 106, 61),
        };
        title.SetBounds(20, 16, 300, 32);
        Controls.Add(title);

        var subtitle = new Label
        {
            Text = "Аниме и кино: каталог, плеер, расписание.\n" +
                   "Ставится в папку пользователя — права администратора не нужны.",
        };
        subtitle.SetBounds(20, 50, 480, 36);
        Controls.Add(subtitle);

        var pathLabel = new Label { Text = "Папка установки:" };
        pathLabel.SetBounds(20, 94, 140, 18);
        Controls.Add(pathLabel);

        path.Text = Setup.DefaultDir();
        path.BackColor = Color.FromArgb(36, 33, 42);
        path.ForeColor = ForeColor;
        path.BorderStyle = BorderStyle.FixedSingle;
        path.SetBounds(20, 114, 380, 24);
        Controls.Add(path);

        browse.Text = "Обзор…";
        browse.FlatStyle = FlatStyle.Flat;
        browse.SetBounds(408, 113, 88, 26);
        browse.Click += (s, e) =>
        {
            using (var dialog = new FolderBrowserDialog { Description = "Куда установить AniBlaze" })
            {
                if (dialog.ShowDialog(this) == DialogResult.OK)
                    path.Text = Path.Combine(dialog.SelectedPath, "AniBlaze");
            }
        };
        Controls.Add(browse);

        desktop.Text = "Ярлык на рабочем столе";
        desktop.Checked = true;
        desktop.SetBounds(20, 150, 220, 22);
        Controls.Add(desktop);

        menu.Text = "Ярлык в меню «Пуск»";
        menu.Checked = true;
        menu.SetBounds(256, 150, 240, 22);
        Controls.Add(menu);

        launch.Text = "Запустить после установки";
        launch.Checked = true;
        launch.SetBounds(20, 176, 260, 22);
        Controls.Add(launch);

        bar.Style = ProgressBarStyle.Continuous;
        bar.SetBounds(20, 206, 476, 12);
        Controls.Add(bar);

        status.SetBounds(20, 224, 350, 32);
        Controls.Add(status);

        install.Text = "Установить";
        install.FlatStyle = FlatStyle.Flat;
        install.BackColor = Color.FromArgb(255, 106, 61);
        install.ForeColor = Color.Black;
        install.SetBounds(376, 226, 120, 30);
        install.Click += OnInstall;
        Controls.Add(install);
        AcceptButton = install;
    }

    void OnInstall(object sender, EventArgs e)
    {
        string dir = path.Text.Trim();
        if (dir.Length == 0) { MessageBox.Show(this, "Укажите папку установки."); return; }
        install.Enabled = browse.Enabled = path.Enabled = false;
        bool wantDesktop = desktop.Checked, wantMenu = menu.Checked, wantLaunch = launch.Checked;

        // Распаковка идёт в отдельном потоке: иначе окно замирает на четверть гигабайта.
        var worker = new Thread(() =>
        {
            try
            {
                Setup.Install(dir, wantDesktop, wantMenu, true, (text, percent) =>
                    BeginInvoke((Action)(() => { status.Text = text; bar.Value = Math.Max(0, Math.Min(100, percent)); })));
                BeginInvoke((Action)(() =>
                {
                    if (wantLaunch) Process.Start(Path.Combine(dir, "AniBlaze.exe"));
                    MessageBox.Show(this, "AniBlaze установлен в\n" + dir, "Готово",
                        MessageBoxButtons.OK, MessageBoxIcon.Information);
                    Close();
                }));
            }
            catch (Exception ex)
            {
                BeginInvoke((Action)(() =>
                {
                    status.Text = "Ошибка";
                    MessageBox.Show(this, ex.Message, "Не удалось установить",
                        MessageBoxButtons.OK, MessageBoxIcon.Error);
                    install.Enabled = browse.Enabled = path.Enabled = true;
                }));
            }
        });
        worker.IsBackground = true;
        worker.SetApartmentState(ApartmentState.STA); // WScript.Shell для ярлыков
        worker.Start();
    }
}
