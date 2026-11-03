package com.superiorsoftllc.agenthq.ui

import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.platform.win32.WinDef
import com.sun.jna.win32.StdCallLibrary
import com.superiorsoftllc.agenthq.session.agentHqSetting
import java.awt.Window

/**
 * UI-ROUND-1: rounded outer corners on the undecorated desktop window, asked of the OS (DWM), never drawn by the app.
 * Windows 11 (build 22000+) rounds the border, shadow and hit area itself and squares them when the window is maximized
 * or full screen. Windows 10 and older, macOS and Linux: untouched (no fake rounding, no transparent-window clip).
 */
object WindowCorners {
    /** Setting `ui.window.roundedCorners` (JVM property, then environment); default true, "false" turns it off. Read at window creation. */
    const val SETTING = "ui.window.roundedCorners"
    private const val DWMWA_WINDOW_CORNER_PREFERENCE = 33
    private const val DWMWCP_DONOTROUND = 1
    private const val DWMWCP_ROUND = 2
    private const val WIN11_FIRST_BUILD = 22000

    fun enabled(read: (String) -> String? = { agentHqSetting(it) }): Boolean =
        read(SETTING)?.trim()?.equals("false", ignoreCase = true) != true

    /** Windows 11 = NT 10.0 with build >= 22000 (os.version stays "10.0" on both, so the build comes from `ver`-style osVersion). */
    fun isWindows11(osName: String = System.getProperty("os.name") ?: "", build: Int? = windowsBuild()): Boolean =
        osName.startsWith("Windows") && (build ?: 0) >= WIN11_FIRST_BUILD

    private fun windowsBuild(): Int? = runCatching {
        val v = com.sun.jna.platform.win32.WinNT.OSVERSIONINFOEX()
        // RtlGetVersion is not subject to manifest compatibility shims, unlike GetVersionEx.
        Native.load("ntdll", NtDll::class.java).RtlGetVersion(v)
        v.dwBuildNumber.toInt()
    }.getOrNull()

    /** Applies the round (restored) or square (maximized: DWM does not square an undecorated popup itself, found by test) preference to [window]'s native handle. Returns true when DWM accepted it. Call after the window is displayable. */
    fun apply(window: Window, round: Boolean = true, enabled: Boolean = enabled()): Boolean {
        if (!enabled || !isWindows11()) return false
        return runCatching {
            val hwnd = WinDef.HWND(Native.getWindowPointer(window))
            val pref = com.sun.jna.ptr.IntByReference(if (round) DWMWCP_ROUND else DWMWCP_DONOTROUND)
            Native.load("dwmapi", Dwm::class.java).DwmSetWindowAttribute(hwnd, DWMWA_WINDOW_CORNER_PREFERENCE, pref.pointer, 4) == 0
        }.getOrDefault(false)
    }

    private interface Dwm : StdCallLibrary {
        fun DwmSetWindowAttribute(hwnd: WinDef.HWND, attr: Int, value: Pointer, size: Int): Int
    }

    private interface NtDll : StdCallLibrary {
        fun RtlGetVersion(info: com.sun.jna.platform.win32.WinNT.OSVERSIONINFOEX): Int
    }
}
