using System.Runtime.InteropServices;

namespace DeyttConnect.Windows.Service;

internal static class Program
{
    private const string ServiceName = "DEYTTConnectVpn";

    [STAThread]
    private static int Main(string[] args)
    {
        if (!OperatingSystem.IsWindows() || args.Length != 1 || args[0] != "--service")
            return 2;

        var serviceMain = new ServiceMainDelegate(ServiceMain);
        var table = new[]
        {
            new ServiceTableEntry { ServiceName = ServiceName, ServiceProc = serviceMain },
            new ServiceTableEntry { ServiceName = null, ServiceProc = null },
        };

        return StartServiceCtrlDispatcher(table) ? 0 : Marshal.GetLastWin32Error();
    }

    private static readonly CancellationTokenSource StopToken = new();
    private static readonly EngineController Engine = new();
    private static IntPtr _statusHandle;
    private static ServiceControlHandler? _controlHandler;
    private static ServiceStatus _status;

    private static void ServiceMain(uint argumentCount, IntPtr arguments)
    {
        _controlHandler = HandleControl;
        _statusHandle = RegisterServiceCtrlHandlerEx(ServiceName, _controlHandler, IntPtr.Zero);
        if (_statusHandle == IntPtr.Zero)
            return;

        SetStatus(ServiceStartPending, 0, 30_000);
        try
        {
            var userSid = ReadAllowedUserSid();
            SetStatus(ServiceRunning, AcceptStop | AcceptShutdown, 0);
            PipeServer.RunAsync(userSid, Engine, StopToken.Token).GetAwaiter().GetResult();
            Engine.Stop();
            SetStatus(ServiceStopped, 0, 0);
        }
        catch
        {
            Engine.Stop();
            SetStatus(ServiceStopped, 0, 0, ServiceSpecificError, 1);
        }
    }

    private static string ReadAllowedUserSid()
    {
        using var key = Microsoft.Win32.Registry.LocalMachine.OpenSubKey(
            @"SOFTWARE\DEYTT\Connect", writable: false);
        var sid = key?.GetValue("AllowedUserSid") as string;
        _ = new System.Security.Principal.SecurityIdentifier(
            sid ?? throw new InvalidOperationException("The VPN service is not configured."));
        return sid;
    }

    private static uint HandleControl(uint control, uint eventType, IntPtr eventData, IntPtr context)
    {
        if (control is ControlStop or ControlShutdown)
        {
            SetStatus(ServiceStopPending, 0, 15_000);
            StopToken.Cancel();
            Engine.Stop();
        }
        return 0;
    }

    private static void SetStatus(uint state, uint acceptedControls, uint waitHint,
        uint win32ExitCode = 0, uint serviceSpecificExitCode = 0)
    {
        _status = new ServiceStatus
        {
            ServiceType = ServiceWin32OwnProcess,
            CurrentState = state,
            ControlsAccepted = acceptedControls,
            Win32ExitCode = win32ExitCode,
            ServiceSpecificExitCode = serviceSpecificExitCode,
            CheckPoint = state is ServiceStartPending or ServiceStopPending ? 1u : 0u,
            WaitHint = waitHint,
        };
        if (_statusHandle != IntPtr.Zero)
            _ = SetServiceStatus(_statusHandle, ref _status);
    }

    private const uint ServiceWin32OwnProcess = 0x10;
    private const uint ServiceStopped = 0x1;
    private const uint ServiceStartPending = 0x2;
    private const uint ServiceStopPending = 0x3;
    private const uint ServiceRunning = 0x4;
    private const uint AcceptStop = 0x1;
    private const uint AcceptShutdown = 0x4;
    private const uint ControlStop = 0x1;
    private const uint ControlShutdown = 0x5;
    private const uint ServiceSpecificError = 1066;

    [StructLayout(LayoutKind.Sequential, CharSet = CharSet.Unicode)]
    private struct ServiceTableEntry
    {
        [MarshalAs(UnmanagedType.LPWStr)] public string? ServiceName;
        [MarshalAs(UnmanagedType.FunctionPtr)] public ServiceMainDelegate? ServiceProc;
    }

    [StructLayout(LayoutKind.Sequential)]
    private struct ServiceStatus
    {
        public uint ServiceType;
        public uint CurrentState;
        public uint ControlsAccepted;
        public uint Win32ExitCode;
        public uint ServiceSpecificExitCode;
        public uint CheckPoint;
        public uint WaitHint;
    }

    [UnmanagedFunctionPointer(CallingConvention.Winapi)]
    private delegate void ServiceMainDelegate(uint argumentCount, IntPtr arguments);

    [UnmanagedFunctionPointer(CallingConvention.Winapi)]
    private delegate uint ServiceControlHandler(uint control, uint eventType, IntPtr eventData, IntPtr context);

    [DllImport("advapi32.dll", CharSet = CharSet.Unicode, SetLastError = true)]
    private static extern bool StartServiceCtrlDispatcher(ServiceTableEntry[] serviceTable);

    [DllImport("advapi32.dll", CharSet = CharSet.Unicode, SetLastError = true)]
    private static extern IntPtr RegisterServiceCtrlHandlerEx(string serviceName,
        ServiceControlHandler handler, IntPtr context);

    [DllImport("advapi32.dll", SetLastError = true)]
    private static extern bool SetServiceStatus(IntPtr statusHandle, ref ServiceStatus status);
}
