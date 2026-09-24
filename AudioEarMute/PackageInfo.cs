using System.Runtime.InteropServices;
using System.Text;

namespace AudioEarMute;

/// <summary>
/// Tells whether the process runs from an MSIX package (the Microsoft Store build)
/// or as a plain unpackaged executable.
/// </summary>
/// <remarks>
/// The two builds must behave differently for start-up:
/// <list type="bullet">
/// <item>Packaged — Windows owns start-up through the <c>windows.startupTask</c>
/// manifest extension. The user toggles it in Settings, Startup apps. Creating a
/// scheduled task or writing to the Run key from inside the package would be
/// redundant, gets registry-virtualised, and is discouraged by Store certification.</item>
/// <item>Unpackaged — the app registers its own scheduled task, see <see cref="Autostart"/>.</item>
/// </list>
/// Detection uses <c>GetCurrentPackageFullName</c> rather than a WinRT projection, so the
/// project keeps its plain <c>net8.0-windows</c> target and needs no Windows SDK
/// reference just to answer this question.
/// </remarks>
public static class PackageInfo
{
    /// <summary>The process has no package identity (winerror.h).</summary>
    private const int AppModelErrorNoPackage = 15700;

    private const int ErrorInsufficientBuffer = 122;

    [DllImport("kernel32.dll", CharSet = CharSet.Unicode, SetLastError = false)]
    private static extern int GetCurrentPackageFullName(ref int packageFullNameLength,
        StringBuilder? packageFullName);

    private static readonly Lazy<string?> PackageFullNameValue = new(ReadPackageFullName);

    /// <summary>Package full name, or <c>null</c> when the app runs unpackaged.</summary>
    public static string? PackageFullName => PackageFullNameValue.Value;

    /// <summary>True when running from an MSIX package.</summary>
    public static bool IsPackaged => PackageFullName is not null;

    private static string? ReadPackageFullName()
    {
        try
        {
            var length = 0;
            var result = GetCurrentPackageFullName(ref length, null);

            if (result == AppModelErrorNoPackage)
            {
                return null;
            }

            if (result != ErrorInsufficientBuffer || length <= 0)
            {
                return null;
            }

            var buffer = new StringBuilder(length);
            result = GetCurrentPackageFullName(ref length, buffer);

            return result == 0 ? buffer.ToString() : null;
        }
        catch (Exception ex) when (ex is EntryPointNotFoundException or DllNotFoundException)
        {
            // Ancient Windows without the app model API — treat as unpackaged.
            return null;
        }
    }
}
