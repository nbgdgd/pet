using System.Runtime.InteropServices;

namespace AudioEarMute;

// Declarations of the Windows Core Audio (WASAPI) COM interfaces.
// Signatures only, no logic. The method order in each interface mirrors the vtable
// order from mmdeviceapi.h / audiopolicy.h and must not be changed.

internal enum EDataFlow
{
    Render = 0,
    Capture = 1,
    All = 2,
}

internal enum ERole
{
    Console = 0,
    Multimedia = 1,
    Communications = 2,
}

internal enum AudioSessionState
{
    Inactive = 0,
    Active = 1,
    Expired = 2,
}

[StructLayout(LayoutKind.Sequential, Pack = 4)]
internal struct PropertyKey
{
    public Guid FormatId;
    public int PropertyId;
}

internal static class DeviceState
{
    public const int Active = 0x1;
    public const int All = 0xF;
}

internal static class ClsCtx
{
    public const int InprocServer = 0x1;
    public const int All = 0x17;
}

internal static class HResult
{
    public const int Ok = 0;

    public static bool Failed(int hr) => hr < 0;
}

[ComImport]
[Guid("BCDE0395-E52F-467C-8E3D-C4579291692E")]
internal class MMDeviceEnumeratorComObject
{
}

[ComImport]
[Guid("A95664D2-9614-4F35-A746-DE8DB63617E6")]
[InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
internal interface IMMDeviceEnumerator
{
    [PreserveSig]
    int EnumAudioEndpoints(EDataFlow dataFlow, int stateMask, out IntPtr devices);

    [PreserveSig]
    int GetDefaultAudioEndpoint(EDataFlow dataFlow, ERole role, out IMMDevice? endpoint);

    [PreserveSig]
    int GetDevice([MarshalAs(UnmanagedType.LPWStr)] string id, out IMMDevice? device);

    [PreserveSig]
    int RegisterEndpointNotificationCallback(IMMNotificationClient client);

    [PreserveSig]
    int UnregisterEndpointNotificationCallback(IMMNotificationClient client);
}

[ComImport]
[Guid("D666063F-1587-4E43-81F1-B948E807363F")]
[InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
internal interface IMMDevice
{
    [PreserveSig]
    int Activate(ref Guid iid, int clsCtx, IntPtr activationParams,
        [MarshalAs(UnmanagedType.IUnknown)] out object? iface);

    [PreserveSig]
    int OpenPropertyStore(int access, out IPropertyStore? properties);

    [PreserveSig]
    int GetId([MarshalAs(UnmanagedType.LPWStr)] out string id);

    [PreserveSig]
    int GetState(out int state);
}

[ComImport]
[Guid("886d8eeb-8cf2-4446-8d02-cdba1dbdcf99")]
[InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
internal interface IPropertyStore
{
    [PreserveSig]
    int GetCount(out int count);

    [PreserveSig]
    int GetAt(int index, out PropertyKey key);

    [PreserveSig]
    int GetValue(ref PropertyKey key, out PropVariant value);

    [PreserveSig]
    int SetValue(ref PropertyKey key, ref PropVariant value);

    [PreserveSig]
    int Commit();
}

// A cut-down PropVariant: only strings are read from it (the device name).
// Size is set explicitly because a real PROPVARIANT occupies 24 bytes on x64 due to
// the larger union members. Understating it would corrupt the stack during marshalling.
[StructLayout(LayoutKind.Explicit, Size = 24)]
internal struct PropVariant
{
    [FieldOffset(0)] public short VarType;
    [FieldOffset(8)] public IntPtr PointerValue;

    public string? AsString() =>
        VarType == 31 /* VT_LPWSTR */ ? Marshal.PtrToStringUni(PointerValue) : null;
}

internal static class PropertyKeys
{
    public static PropertyKey DeviceFriendlyName => new()
    {
        FormatId = new Guid("a45c254e-df1c-4efd-8020-67d146a850e0"),
        PropertyId = 14,
    };
}

internal static class Ole32
{
    [DllImport("ole32.dll")]
    public static extern int PropVariantClear(ref PropVariant pvar);
}

[ComImport]
[Guid("7991EEC9-7E89-4D85-8390-6C703CEC60C0")]
[InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
internal interface IMMNotificationClient
{
    [PreserveSig]
    int OnDeviceStateChanged([MarshalAs(UnmanagedType.LPWStr)] string deviceId, int newState);

    [PreserveSig]
    int OnDeviceAdded([MarshalAs(UnmanagedType.LPWStr)] string deviceId);

    [PreserveSig]
    int OnDeviceRemoved([MarshalAs(UnmanagedType.LPWStr)] string deviceId);

    [PreserveSig]
    int OnDefaultDeviceChanged(EDataFlow flow, ERole role,
        [MarshalAs(UnmanagedType.LPWStr)] string defaultDeviceId);

    [PreserveSig]
    int OnPropertyValueChanged([MarshalAs(UnmanagedType.LPWStr)] string deviceId, PropertyKey key);
}

// Inherits IAudioSessionManager, so the first two methods belong to that interface.
[ComImport]
[Guid("77AA99A0-1BD6-484F-8BC7-2C654C9A9B6F")]
[InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
internal interface IAudioSessionManager2
{
    [PreserveSig]
    int GetAudioSessionControl(IntPtr sessionGuid, int streamFlags, out IAudioSessionControl? sessionControl);

    [PreserveSig]
    int GetSimpleAudioVolume(IntPtr sessionGuid, int streamFlags, out IntPtr audioVolume);

    [PreserveSig]
    int GetSessionEnumerator(out IAudioSessionEnumerator? sessionEnum);

    [PreserveSig]
    int RegisterSessionNotification(IAudioSessionNotification notification);

    [PreserveSig]
    int UnregisterSessionNotification(IAudioSessionNotification notification);

    [PreserveSig]
    int RegisterDuckNotification([MarshalAs(UnmanagedType.LPWStr)] string sessionId, IntPtr duckNotification);

    [PreserveSig]
    int UnregisterDuckNotification(IntPtr duckNotification);
}

[ComImport]
[Guid("E2F5BB11-0570-40CA-ACDD-3AA01277DEE8")]
[InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
internal interface IAudioSessionEnumerator
{
    [PreserveSig]
    int GetCount(out int sessionCount);

    [PreserveSig]
    int GetSession(int sessionIndex, out IAudioSessionControl? session);
}

[ComImport]
[Guid("F4B1A599-7266-4319-A8CA-E70ACB11E8CD")]
[InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
internal interface IAudioSessionControl
{
    [PreserveSig]
    int GetState(out AudioSessionState state);

    [PreserveSig]
    int GetDisplayName([MarshalAs(UnmanagedType.LPWStr)] out string name);

    [PreserveSig]
    int SetDisplayName([MarshalAs(UnmanagedType.LPWStr)] string value, ref Guid eventContext);

    [PreserveSig]
    int GetIconPath([MarshalAs(UnmanagedType.LPWStr)] out string path);

    [PreserveSig]
    int SetIconPath([MarshalAs(UnmanagedType.LPWStr)] string value, ref Guid eventContext);

    [PreserveSig]
    int GetGroupingParam(out Guid groupingId);

    [PreserveSig]
    int SetGroupingParam(ref Guid groupingId, ref Guid eventContext);

    [PreserveSig]
    int RegisterAudioSessionNotification(IntPtr newNotifications);

    [PreserveSig]
    int UnregisterAudioSessionNotification(IntPtr newNotifications);
}

// Inherits IAudioSessionControl — the first nine methods mirror its vtable.
[ComImport]
[Guid("BFB7FF88-7239-4FC9-8FA2-07C950BE9C6D")]
[InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
internal interface IAudioSessionControl2
{
    [PreserveSig]
    int GetState(out AudioSessionState state);

    [PreserveSig]
    int GetDisplayName([MarshalAs(UnmanagedType.LPWStr)] out string name);

    [PreserveSig]
    int SetDisplayName([MarshalAs(UnmanagedType.LPWStr)] string value, ref Guid eventContext);

    [PreserveSig]
    int GetIconPath([MarshalAs(UnmanagedType.LPWStr)] out string path);

    [PreserveSig]
    int SetIconPath([MarshalAs(UnmanagedType.LPWStr)] string value, ref Guid eventContext);

    [PreserveSig]
    int GetGroupingParam(out Guid groupingId);

    [PreserveSig]
    int SetGroupingParam(ref Guid groupingId, ref Guid eventContext);

    [PreserveSig]
    int RegisterAudioSessionNotification(IntPtr newNotifications);

    [PreserveSig]
    int UnregisterAudioSessionNotification(IntPtr newNotifications);

    [PreserveSig]
    int GetSessionIdentifier([MarshalAs(UnmanagedType.LPWStr)] out string retVal);

    [PreserveSig]
    int GetSessionInstanceIdentifier([MarshalAs(UnmanagedType.LPWStr)] out string retVal);

    [PreserveSig]
    int GetProcessId(out int retVal);

    [PreserveSig]
    int IsSystemSoundsSession();

    [PreserveSig]
    int SetDuckingPreference(bool optOut);
}

[ComImport]
[Guid("641DD20B-4D41-49CC-ABA3-174B9477BB08")]
[InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
internal interface IAudioSessionNotification
{
    [PreserveSig]
    int OnSessionCreated(IAudioSessionControl newSession);
}

// The core of the solution: per-channel volume of an audio session.
// The software mixer applies it before the stream reaches the device, so Bluetooth
// Absolute Volume has no effect on it.
[ComImport]
[Guid("1C158861-B533-4B30-B1CF-E853E51C59B8")]
[InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
internal interface IChannelAudioVolume
{
    [PreserveSig]
    int GetChannelCount(out uint channelCount);

    [PreserveSig]
    int SetChannelVolume(uint index, float level, ref Guid eventContext);

    [PreserveSig]
    int GetChannelVolume(uint index, out float level);

    [PreserveSig]
    int SetAllVolumes(uint count, [In, MarshalAs(UnmanagedType.LPArray)] float[] volumes,
        ref Guid eventContext);

    [PreserveSig]
    int GetAllVolumes(uint count, [Out, MarshalAs(UnmanagedType.LPArray)] float[] volumes);
}
