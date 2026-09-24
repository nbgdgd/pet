using Microsoft.Win32;
using System.Diagnostics;
using System.Runtime.InteropServices;

Console.OutputEncoding = System.Text.Encoding.UTF8;
Console.Title = "Audio Channel Router";

// Проверка Bluetooth Absolute Volume
var btKey = @"SYSTEM\ControlSet001\Control\Bluetooth\Audio\AVRCP\CT";
var btAbsoluteVolume = false;
try
{
    var rk = Registry.LocalMachine.OpenSubKey(btKey);
    if (rk?.GetValue("DisableAbsoluteVolume") is int val && val == 1)
        btAbsoluteVolume = true;
    rk?.Close();
}
catch { }

if (!btAbsoluteVolume)
{
    Console.WriteLine("Обнаружены Bluetooth наушники с Absolute Volume.");
    Console.WriteLine("Это мешает раздельной регулировке каналов.");
    Console.WriteLine();
    Console.WriteLine("Отключить Absolute Volume? (y/n): ");
    if (Console.ReadKey(true).KeyChar == 'y')
    {
        try
        {
            var rk = Registry.LocalMachine.CreateSubKey(btKey, true);
            rk?.SetValue("DisableAbsoluteVolume", 1, RegistryValueKind.DWord);
            rk?.Close();
            Console.WriteLine(" OK: DisableAbsoluteVolume = 1");
            Console.WriteLine("Требуется ПЕРЕЗАГРУЗКА компьютера.");
            Console.WriteLine("Нажмите любую клавишу для выхода...");
            Console.ReadLine();
            return;
        }
        catch
        {
            Console.WriteLine(" Ошибка: запустите от имени администратора.");
            Console.ReadLine();
            return;
        }
    }
}

// Теперь баланс работает — используем AudioEndpointVolume
var mode = "стерео";

while (true)
{
    Console.Clear();
    Console.WriteLine("=== AUDIO CHANNEL ROUTER ===");
    Console.WriteLine($"Режим: {mode}");
    Console.WriteLine();
    Console.WriteLine("1 - Только ЛЕВЫЙ (правый тишина)");
    Console.WriteLine("2 - Только ПРАВЫЙ (левый тишина)");
    Console.WriteLine("3 - Стерео");
    Console.WriteLine("0 - Выход");
    Console.Write("> ");

    var k = Console.ReadKey(true).KeyChar;
    Console.WriteLine();

    switch (k)
    {
        case '1': SetVol(1f, 0f); mode = "ТОЛЬКО ЛЕВЫЙ"; break;
        case '2': SetVol(0f, 1f); mode = "ТОЛЬКО ПРАВЫЙ"; break;
        case '3': SetVol(1f, 1f); mode = "Стерео"; break;
        case '0': SetVol(1f, 1f); return;
    }

    Console.WriteLine(" OK");
    Console.WriteLine("\nНажмите любую клавишу...");
    Console.ReadKey(true);
}

static void SetVol(float left, float right)
{
    // 1. AudioEndpointVolume
    try
    {
        var dev = new NAudio.CoreAudioApi.MMDeviceEnumerator()
            .GetDefaultAudioEndpoint(NAudio.CoreAudioApi.DataFlow.Render, NAudio.CoreAudioApi.Role.Multimedia);
        var vol = dev.AudioEndpointVolume;
        if (vol.Channels.Count >= 1) vol.Channels[0].VolumeLevelScalar = left;
        if (vol.Channels.Count >= 2) vol.Channels[1].VolumeLevelScalar = right;
        Console.WriteLine(" AudioEndpointVolume: OK");
    }
    catch (Exception ex) { Console.WriteLine($" AudioEndpointVolume: {ex.Message}"); }

    // 2. waveOutSetVolume
    var v = (uint)((int)(right * 0xFFFF) << 16) | (uint)(int)(left * 0xFFFF);
    waveOutSetVolume(IntPtr.Zero, v);
    Console.WriteLine(" waveOutSetVolume: OK");
}

[DllImport("winmm.dll")]
static extern uint waveOutSetVolume(IntPtr hwo, uint dwVolume);
