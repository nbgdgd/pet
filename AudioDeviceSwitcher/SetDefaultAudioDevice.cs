using System;
using System.Collections.Generic;
using System.Runtime.InteropServices;

namespace AudioDeviceLib
{
    [ComImport, Guid("BCDE0395-E52F-467C-8E3D-C4579291692E")]
    class MMDeviceEnumerator { }

    [ComImport, Guid("A95664D2-9614-4F35-A746-DE8DB63617E6"), InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
    interface IMMDeviceEnumerator
    {
        int EnumAudioEndpoints(int dataFlow, int dwStateMask, out IMMDeviceCollection devices);
        int GetDefaultAudioEndpoint(int dataFlow, int role, out IMMDevice device);
        int GetDevice([MarshalAs(UnmanagedType.LPWStr)] string id, out IMMDevice device);
        int RegisterEndpointNotificationCallback(IMMNotificationClient client);
        int UnregisterEndpointNotificationCallback(IMMNotificationClient client);
    }

    [ComImport, Guid("0BD7A1BE-7A1A-44DB-8397-CC5392387B5E"), InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
    interface IMMDeviceCollection
    {
        int GetCount(out int count);
        int Item(int index, out IMMDevice device);
    }

    [ComImport, Guid("D666063F-1587-4E43-81F1-B948E807363F"), InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
    interface IMMDevice
    {
        int Activate([MarshalAs(UnmanagedType.LPStruct)] Guid iid, int dwClsCtx, IntPtr pActivationParams, [MarshalAs(UnmanagedType.IUnknown)] out object ppInterface);
        int OpenPropertyStore(int stgmAccess, out IPropertyStore propertyStore);
        int GetId([MarshalAs(UnmanagedType.LPWStr)] out string id);
        int GetState(out int state);
    }

    [ComImport, Guid("886D8EEB-8CF2-4446-8D02-CDBA1DBDCF99"), InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
    interface IPropertyStore
    {
        int GetCount(out int count);
        int GetAt(int index, out PropertyKey key);
        int GetValue(ref PropertyKey key, out PropVariant value);
        int SetValue(ref PropertyKey key, ref PropVariant value);
        int Commit();
    }

    [ComImport, Guid("886D8EEB-8CF2-4446-8D02-CDBA1DBDCF99"), InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
    interface IMMNotificationClient
    {
        void OnDeviceStateChanged([MarshalAs(UnmanagedType.LPWStr)] string deviceId, int newState);
        void OnDeviceAdded([MarshalAs(UnmanagedType.LPWStr)] string deviceId);
        void OnDeviceRemoved([MarshalAs(UnmanagedType.LPWStr)] string deviceId);
        void OnDefaultDeviceChanged(int flow, int role, [MarshalAs(UnmanagedType.LPWStr)] string defaultDeviceId);
        void OnPropertyValueChanged([MarshalAs(UnmanagedType.LPWStr)] string deviceId, int key);
    }

    [StructLayout(LayoutKind.Sequential)]
    struct PropertyKey
    {
        public Guid fmtId;
        public int pid;
    }

    [StructLayout(LayoutKind.Sequential)]
    struct PropVariant
    {
        public ushort vt;
        public ushort wReserved1;
        public ushort wReserved2;
        public ushort wReserved3;
        public IntPtr data1;
        public IntPtr data2;
    }

    [ComImport, Guid("F8679F50-850A-41CF-9C72-430F290290C8"), InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
    interface IPolicyConfig
    {
        int GetMixFormat(string pszDeviceName, IntPtr ppFormat);
        int GetDeviceFormat(string pszDeviceName, int bDefault, IntPtr ppFormat);
        int SetDeviceFormat(string pszDeviceName, IntPtr pFormat, IntPtr ppFormat);
        int GetProcessingPeriod(string pszDeviceName, int bDefault, IntPtr pmftDefaultPeriod, IntPtr pmftMinimumPeriod);
        int SetProcessingPeriod(string pszDeviceName, IntPtr pmftPeriod, IntPtr ppFormat);
        int GetShareMode(string pszDeviceName, IntPtr pMode);
        int SetShareMode(string pszDeviceName, IntPtr pMode);
        int GetPropertyValue(string pszDeviceName, int cbKey, ref PropertyKey key, out PropVariant pv);
        int SetPropertyValue(string pszDeviceName, int cbKey, ref PropertyKey key, ref PropVariant pv);
        int SetDefaultEndpoint([MarshalAs(UnmanagedType.LPWStr)] string pszDeviceName, int role);
        int SetEndpointVisibility(string pszDeviceName, int bVisible);
    }

    public class AudioDevice
    {
        public string Id { get; set; }
        public string Name { get; set; }
    }

    class Program
    {
        const int E_RENDER = 0;
        const int DEVICE_STATE_ACTIVE = 1;

        static readonly Guid PKEY_Device_FriendlyName = new Guid("a45c254e-df1c-4efd-8020-67d146a850e0");

        static IMMDeviceEnumerator GetEnumerator()
        {
            return new MMDeviceEnumerator() as IMMDeviceEnumerator;
        }

        static string GetDeviceName(IMMDevice device)
        {
            IPropertyStore store = null;
            try
            {
                device.OpenPropertyStore(0, out store);
                if (store == null) return null;
                PropertyKey key = new PropertyKey { fmtId = PKEY_Device_FriendlyName, pid = 14 };
                PropVariant val;
                store.GetValue(ref key, out val);
                if (val.vt == 31)
                {
                    return Marshal.PtrToStringUni(val.data1);
                }
                return null;
            }
            catch { return null; }
            finally { if (store != null) Marshal.ReleaseComObject(store); }
        }

        public static List<AudioDevice> GetPlaybackDevices()
        {
            var result = new List<AudioDevice>();
            var enumerator = GetEnumerator();
            IMMDeviceCollection collection = null;
            try
            {
                enumerator.EnumAudioEndpoints(E_RENDER, DEVICE_STATE_ACTIVE, out collection);
                int count;
                collection.GetCount(out count);
                for (int i = 0; i < count; i++)
                {
                    IMMDevice device;
                    collection.Item(i, out device);
                    string id;
                    device.GetId(out id);
                    string name = GetDeviceName(device);
                    result.Add(new AudioDevice { Id = id, Name = name });
                    Marshal.ReleaseComObject(device);
                }
            }
            finally
            {
                if (collection != null) Marshal.ReleaseComObject(collection);
                Marshal.ReleaseComObject(enumerator);
            }
            return result;
        }

        public static bool SetDefaultEndpoint(string deviceId)
        {
            try
            {
                Guid CLSID_PolicyConfig = new Guid("870AF99C-171D-4F9E-AF0D-E63DF40C2BC9");
                Type policyConfigType = Type.GetTypeFromCLSID(CLSID_PolicyConfig);
                object policyConfig = Activator.CreateInstance(policyConfigType);
                IPolicyConfig pc = policyConfig as IPolicyConfig;
                int hr = pc.SetDefaultEndpoint(deviceId, 0);
                if (hr != 0)
                {
                    pc.SetDefaultEndpoint(deviceId, 1);
                    pc.SetDefaultEndpoint(deviceId, 2);
                }
                Marshal.ReleaseComObject(policyConfig);
                return true;
            }
            catch (Exception ex)
            {
                Console.Error.WriteLine(string.Format("SetDefaultEndpoint failed: {0}", ex.Message));
                return false;
            }
        }

        public static bool IsDeviceConnected(string searchTerm)
        {
            var devices = GetPlaybackDevices();
            foreach (var dev in devices)
            {
                if (dev.Name != null && dev.Name.IndexOf(searchTerm, StringComparison.OrdinalIgnoreCase) >= 0)
                    return true;
                if (dev.Id.IndexOf(searchTerm, StringComparison.OrdinalIgnoreCase) >= 0)
                    return true;
            }
            return false;
        }

        static int Main(string[] args)
        {
            if (args.Length == 0)
            {
                var devices = GetPlaybackDevices();
                foreach (var d in devices)
                    Console.WriteLine(string.Format("{0}\t{1}", d.Name, d.Id));
                return 0;
            }

            if (args[0] == "--set-default" && args.Length > 1)
            {
                bool ok = SetDefaultEndpoint(args[1]);
                return ok ? 0 : 1;
            }

            if (args[0] == "--check" && args.Length > 1)
            {
                bool connected = IsDeviceConnected(args[1]);
                Console.WriteLine(connected ? "1" : "0");
                return 0;
            }

            Console.Error.WriteLine("Usage: AudioDeviceHelper.exe [--set-default <deviceId> | --check <searchTerm>]");
            return 1;
        }
    }
}