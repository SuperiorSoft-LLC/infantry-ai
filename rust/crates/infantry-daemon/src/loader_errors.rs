// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
//! Windows loader errors fail fast instead of showing a modal dialog (ticket 0f8ba535).
//!
//! A child that cannot load a DLL (a plugin helper built without a static CRT on a PC without VCRUNTIME140.dll) gets a
//! modal "System Error" box from the Windows loader when its error mode lacks `SEM_FAILCRITICALERRORS`. Nobody clicks
//! it, so the child never exits and every caller waiting on it waits for its whole timeout. Children inherit the error
//! mode, so setting it once in the daemon makes such a spawn exit at once with `STATUS_DLL_NOT_FOUND` (0xC0000135).
//! `SEM_NOGPFAULTERRORBOX` is left alone on purpose: crash reports still work.

/// Adds `SEM_FAILCRITICALERRORS | SEM_NOOPENFILEERRORBOX` to the process error mode, keeping the bits already set.
/// Returns the mode now in force (0 off Windows, where there is nothing to do).
pub fn fail_fast() -> u32 {
    #[cfg(windows)]
    {
        use windows_sys::Win32::System::Diagnostics::Debug::{GetErrorMode, SetErrorMode, SEM_FAILCRITICALERRORS, SEM_NOOPENFILEERRORBOX};
        // SAFETY: plain Win32 calls on the current process, no pointers.
        unsafe {
            SetErrorMode(GetErrorMode() | SEM_FAILCRITICALERRORS | SEM_NOOPENFILEERRORBOX);
            GetErrorMode()
        }
    }
    #[cfg(not(windows))]
    {
        0
    }
}

#[cfg(all(test, windows))]
mod tests {
    use windows_sys::Win32::System::Diagnostics::Debug::{SEM_FAILCRITICALERRORS, SEM_NOOPENFILEERRORBOX};

    #[test]
    fn sets_the_fail_fast_bits() {
        let m = super::fail_fast();
        assert_eq!(m & SEM_FAILCRITICALERRORS, SEM_FAILCRITICALERRORS);
        assert_eq!(m & SEM_NOOPENFILEERRORBOX, SEM_NOOPENFILEERRORBOX);
    }
}
