# Persistent helper process for closeWindow() in agent.js.
#
# Spawned once and kept alive so every enforcement tick doesn't pay the cost of
# starting powershell.exe + Add-Type C# compilation from scratch. Reads one
# hwnd (decimal string) per line from stdin, sends a real Ctrl+W via SendInput
# to that window, and writes one result line to stdout: OK, SKIP, or ERR.
#
# SKIP means the target window was no longer foreground by the time we could
# act (SetForegroundWindow can silently fail under Windows' foreground-lock
# rules) — sending the keystroke anyway could close whatever the user is
# actually looking at, so we verify GetForegroundWindow() first instead.

$sig = @"
using System;
using System.Runtime.InteropServices;
public class FLSendInput {
    [StructLayout(LayoutKind.Sequential)]
    public struct KEYBDINPUT { public ushort wVk; public ushort wScan; public uint dwFlags; public uint time; public IntPtr dwExtraInfo; }
    [StructLayout(LayoutKind.Sequential)]
    public struct MOUSEINPUT { public int dx; public int dy; public uint mouseData; public uint dwFlags; public uint time; public IntPtr dwExtraInfo; }
    [StructLayout(LayoutKind.Sequential)]
    public struct HARDWAREINPUT { public uint uMsg; public ushort wParamL; public ushort wParamH; }
    [StructLayout(LayoutKind.Explicit)]
    public struct InputUnion {
        [FieldOffset(0)] public MOUSEINPUT mi;
        [FieldOffset(0)] public KEYBDINPUT ki;
        [FieldOffset(0)] public HARDWAREINPUT hi;
    }
    [StructLayout(LayoutKind.Sequential)]
    public struct INPUT { public uint type; public InputUnion u; }
    [DllImport("user32.dll", SetLastError=true)] public static extern uint SendInput(uint nInputs, INPUT[] pInputs, int cbSize);
    [DllImport("user32.dll")] public static extern bool SetForegroundWindow(IntPtr hWnd);
    [DllImport("user32.dll")] public static extern IntPtr GetForegroundWindow();
    public const uint INPUT_KEYBOARD = 1;
    public const uint KEYEVENTF_KEYUP = 0x0002;
    public const ushort VK_CONTROL = 0x11;
    public const ushort VK_W = 0x57;
    public static string CtrlW(IntPtr hWnd) {
        SetForegroundWindow(hWnd);
        System.Threading.Thread.Sleep(50);
        if (GetForegroundWindow() != hWnd) return "SKIP";
        INPUT[] inputs = new INPUT[4];
        for (int i = 0; i < 4; i++) inputs[i].type = INPUT_KEYBOARD;
        inputs[0].u.ki.wVk = VK_CONTROL;
        inputs[1].u.ki.wVk = VK_W;
        inputs[2].u.ki.wVk = VK_W;       inputs[2].u.ki.dwFlags = KEYEVENTF_KEYUP;
        inputs[3].u.ki.wVk = VK_CONTROL; inputs[3].u.ki.dwFlags = KEYEVENTF_KEYUP;
        SendInput(4, inputs, Marshal.SizeOf(typeof(INPUT)));
        return "OK";
    }
}
"@
Add-Type -TypeDefinition $sig

while ($true) {
    $line = [Console]::In.ReadLine()
    if ($null -eq $line) { break }
    $line = $line.Trim()
    if ($line -eq "") { continue }
    try {
        $hwnd = [IntPtr][long]$line
        $result = [FLSendInput]::CtrlW($hwnd)
    } catch {
        $result = "ERR"
    }
    [Console]::Out.WriteLine($result)
    [Console]::Out.Flush()
}
