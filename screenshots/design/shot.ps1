param([string]$Name, [int]$X = -1, [int]$Y = -1, [int]$WaitMs = 1500, [switch]$Hover, [string]$Keys = "")
Add-Type -AssemblyName System.Drawing
Add-Type -AssemblyName System.Windows.Forms
Add-Type @"
using System; using System.Runtime.InteropServices;
public class W2 {
  [DllImport("user32.dll")] public static extern bool SetForegroundWindow(IntPtr h);
  [DllImport("user32.dll")] public static extern bool GetWindowRect(IntPtr h, out RECT r);
  [DllImport("user32.dll")] public static extern bool SetCursorPos(int x, int y);
  [DllImport("user32.dll")] public static extern void mouse_event(uint f, uint x, uint y, uint d, UIntPtr e);
  [StructLayout(LayoutKind.Sequential)] public struct RECT { public int L, T, R, B; }
}
"@
$p = Get-Process AniBlaze | Where-Object { $_.MainWindowHandle -ne 0 } | Select-Object -First 1
[W2]::SetForegroundWindow($p.MainWindowHandle) | Out-Null
$r = New-Object W2+RECT; [W2]::GetWindowRect($p.MainWindowHandle, [ref]$r) | Out-Null
if ($X -ge 0) {
  # Координаты — пиксели скриншота; курсор в этом процессе ложится с масштабом ~1.333 и сдвигом 25 (замерено).
  $sx = [int](($r.L + $X - 25) / 1.333); $sy = [int](($r.T + $Y - 25) / 1.333)
  [W2]::SetCursorPos($sx, $sy) | Out-Null; Start-Sleep -Milliseconds 100
  # Compose обрабатывает клик только после события движения: SetCursorPos его не даёт.
  [W2]::mouse_event(1, 1, 0, 0, [UIntPtr]::Zero); Start-Sleep -Milliseconds 40
  [W2]::mouse_event(1, [uint32]::MaxValue, 0, 0, [UIntPtr]::Zero); Start-Sleep -Milliseconds 120
  if (-not $Hover) { [W2]::mouse_event(2, 0, 0, 0, [UIntPtr]::Zero); Start-Sleep -Milliseconds 60; [W2]::mouse_event(4, 0, 0, 0, [UIntPtr]::Zero) }
}
if ($Keys -ne "") { Start-Sleep -Milliseconds 200; [System.Windows.Forms.SendKeys]::SendWait($Keys) }
Start-Sleep -Milliseconds $WaitMs
$dir = "E:\AndroidProjects\AniBlaze\screenshots\design"
$bmp = New-Object System.Drawing.Bitmap(($r.R - $r.L), ($r.B - $r.T)); $g = [System.Drawing.Graphics]::FromImage($bmp)
$g.CopyFromScreen($r.L, $r.T, 0, 0, $bmp.Size); $bmp.Save("$dir\$Name.png"); $g.Dispose(); $bmp.Dispose()
"saved $Name"
