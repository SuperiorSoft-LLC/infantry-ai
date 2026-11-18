# SPDX-License-Identifier: MPL-2.0
# Copyright (c) SuperiorSoft LLC
#
# VAULT-REVEAL-1: asks the signed-in Windows user to prove it is them, for OsUserVerifiers.Windows.
# Arguments: <Mode: check|verify> <Reason64: the prompt's line, UTF-8, base64>.
# Prints ONE line and always exits 0:
#   available:<method>          (check: what a verify would use)
#   verified:<method>           (verify: the user proved it)
#   denied:<method>:<why>       (verify: cancelled, wrong password, another user, retries exhausted)
#   unavailable:<why>           (no prompt can be shown: no desktop session, an API error)
# <method> is windows-hello (UserConsentVerifier: PIN, fingerprint or face) or windows-password (the Windows credential
# prompt, its password checked with LogonUser). The password never leaves this process and is never printed.
param([string]$Mode = 'check', [string]$Reason64 = '')
$ErrorActionPreference = 'Stop'

$reason = 'Infantry AI wants to make sure it is you.'
if ($Reason64) { try { $reason = [Text.Encoding]::UTF8.GetString([Convert]::FromBase64String($Reason64)) } catch { } }

function Wait-WinRt($op, [Type]$type) {
    $task = $script:asTask.MakeGenericMethod($type).Invoke($null, @($op))
    $null = $task.Wait(-1)
    return $task.Result
}

function Get-HelloState {
    try {
        Add-Type -AssemblyName System.Runtime.WindowsRuntime
        $null = [Windows.Security.Credentials.UI.UserConsentVerifier,Windows.Security.Credentials.UI,ContentType=WindowsRuntime]
        $script:asTask = [System.WindowsRuntimeSystemExtensions].GetMethods() | Where-Object {
            $_.Name -eq 'AsTask' -and $_.GetParameters().Count -eq 1 -and $_.GetParameters()[0].ParameterType.Name -eq 'IAsyncOperation`1'
        } | Select-Object -First 1
        $a = Wait-WinRt ([Windows.Security.Credentials.UI.UserConsentVerifier]::CheckAvailabilityAsync()) ([Windows.Security.Credentials.UI.UserConsentVerifierAvailability])
        return "$a"
    } catch {
        return 'Error'
    }
}

$credPrompt = @'
using System;
using System.Runtime.InteropServices;
using System.Text;

public static class InfantryCredPrompt {
    [StructLayout(LayoutKind.Sequential, CharSet = CharSet.Unicode)]
    struct CREDUI_INFO { public int cbSize; public IntPtr hwndParent; public string pszMessageText; public string pszCaptionText; public IntPtr hbmBanner; }

    [DllImport("credui.dll", CharSet = CharSet.Unicode)]
    static extern int CredUIPromptForWindowsCredentials(ref CREDUI_INFO info, int authError, ref uint authPackage, IntPtr inBuffer, uint inSize,
        out IntPtr outBuffer, out uint outSize, ref bool save, int flags);
    [DllImport("credui.dll", CharSet = CharSet.Unicode, SetLastError = true)]
    static extern bool CredPackAuthenticationBuffer(int flags, string user, string password, IntPtr buffer, ref int size);
    [DllImport("credui.dll", CharSet = CharSet.Unicode, SetLastError = true)]
    static extern bool CredUnPackAuthenticationBuffer(int flags, IntPtr buffer, uint size, StringBuilder user, ref int userLen,
        StringBuilder domain, ref int domainLen, StringBuilder password, ref int passwordLen);
    [DllImport("advapi32.dll", CharSet = CharSet.Unicode, SetLastError = true)]
    static extern bool LogonUser(string user, string domain, string password, int logonType, int provider, out IntPtr token);
    [DllImport("kernel32.dll")] static extern bool CloseHandle(IntPtr handle);
    [DllImport("ole32.dll")] static extern void CoTaskMemFree(IntPtr p);

    const int CREDUIWIN_GENERIC = 0x1;
    const int ERROR_CANCELLED = 1223;
    const int ERROR_LOGON_FAILURE = 1326;
    const int LOGON32_LOGON_INTERACTIVE = 2;
    const int LOGON32_LOGON_NETWORK = 3;
    const int ATTEMPTS = 3;

    static void Wipe(IntPtr buffer, uint size) {
        if (buffer == IntPtr.Zero) return;
        for (int i = 0; i < size; i++) Marshal.WriteByte(buffer, i, 0);
        CoTaskMemFree(buffer);
    }

    public static string Verify(string caption, string message) {
        string domain = Environment.UserDomainName, user = Environment.UserName;
        // The prompt opens on the current user, so only the password is typed.
        IntPtr inBuf = IntPtr.Zero; int inSize = 0;
        CredPackAuthenticationBuffer(0, domain + "\\" + user, "", IntPtr.Zero, ref inSize);
        if (inSize > 0) {
            inBuf = Marshal.AllocCoTaskMem(inSize);
            if (!CredPackAuthenticationBuffer(0, domain + "\\" + user, "", inBuf, ref inSize)) { Marshal.FreeCoTaskMem(inBuf); inBuf = IntPtr.Zero; inSize = 0; }
        }
        try {
            var info = new CREDUI_INFO();
            info.cbSize = Marshal.SizeOf(typeof(CREDUI_INFO));
            info.pszCaptionText = caption;
            info.pszMessageText = message;
            int authError = 0;
            for (int attempt = 0; attempt < ATTEMPTS; attempt++) {
                uint package = 0; bool save = false; IntPtr outBuf; uint outSize;
                int rc = CredUIPromptForWindowsCredentials(ref info, authError, ref package, inBuf, (uint)inSize, out outBuf, out outSize, ref save, CREDUIWIN_GENERIC);
                if (rc == ERROR_CANCELLED) return "denied:windows-password:Canceled";
                if (rc != 0) return "unavailable:credential prompt error " + rc;
                var u = new StringBuilder(514); var d = new StringBuilder(514); var p = new StringBuilder(514);
                int ul = u.Capacity, dl = d.Capacity, pl = p.Capacity;
                bool unpacked = CredUnPackAuthenticationBuffer(0, outBuf, outSize, u, ref ul, d, ref dl, p, ref pl);
                Wipe(outBuf, outSize);
                if (!unpacked) return "unavailable:could not read the credential prompt";
                string typed = u.ToString(), typedDomain = d.ToString();
                int slash = typed.IndexOf('\\');
                if (slash >= 0) { typedDomain = typed.Substring(0, slash); typed = typed.Substring(slash + 1); }
                if (!string.Equals(typed, user, StringComparison.OrdinalIgnoreCase)) { p.Clear(); return "denied:windows-password:another user"; }
                string logonDomain = string.IsNullOrEmpty(typedDomain) ? domain : typedDomain;
                IntPtr token;
                bool good = LogonUser(user, logonDomain, p.ToString(), LOGON32_LOGON_INTERACTIVE, 0, out token);
                int err = good ? 0 : Marshal.GetLastWin32Error();
                if (!good && err != ERROR_LOGON_FAILURE) {
                    good = LogonUser(user, logonDomain, p.ToString(), LOGON32_LOGON_NETWORK, 0, out token);
                    err = good ? 0 : Marshal.GetLastWin32Error();
                }
                p.Clear();
                if (good) { CloseHandle(token); return "verified:windows-password"; }
                authError = err;
            }
            return "denied:windows-password:wrong password";
        } finally {
            if (inBuf != IntPtr.Zero) Marshal.FreeCoTaskMem(inBuf);
        }
    }
}
'@

$hello = Get-HelloState
if ($Mode -eq 'check') {
    if ($hello -eq 'Available') { 'available:windows-hello' } else { 'available:windows-password' }
    exit 0
}

if ($hello -eq 'Available') {
    try {
        $r = Wait-WinRt ([Windows.Security.Credentials.UI.UserConsentVerifier]::RequestVerificationAsync($reason)) ([Windows.Security.Credentials.UI.UserConsentVerificationResult])
        $word = "$r"
        if ($word -eq 'Verified') { 'verified:windows-hello'; exit 0 }
        if ($word -eq 'Canceled' -or $word -eq 'RetriesExhausted') { "denied:windows-hello:$word"; exit 0 }
        # DeviceNotPresent, NotConfiguredForUser, DisabledByPolicy, DeviceBusy: fall back to the password prompt.
    } catch {
        # A desktop process Windows will not show the Hello prompt for: fall back to the password prompt.
    }
}

try {
    Add-Type -TypeDefinition $credPrompt -Language CSharp
    [InfantryCredPrompt]::Verify('Infantry AI', $reason)
} catch {
    "unavailable:$($_.Exception.GetType().Name)"
}
exit 0
