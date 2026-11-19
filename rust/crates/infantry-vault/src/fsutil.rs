// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
//! Owner-only files: 0600 on Unix. On Windows a file under the user's profile already inherits an owner-only ACL from
//! `%LOCALAPPDATA%`; a render output elsewhere gets an explicit owner-only DACL ([`write_private`]).
use std::io::Write;
use std::path::Path;

pub fn restrict(p: &Path) {
    #[cfg(unix)]
    {
        use std::os::unix::fs::PermissionsExt;
        let _ = std::fs::set_permissions(p, std::fs::Permissions::from_mode(0o600));
    }
    #[cfg(not(unix))]
    {
        let _ = p;
    }
}

/// Writes `bytes` to `target` through a temp file that is owner-only from its first byte, then renames it over.
pub fn write_private(target: &Path, bytes: &[u8]) -> std::io::Result<()> {
    let dir = target.parent().filter(|p| !p.as_os_str().is_empty()).unwrap_or(Path::new("."));
    std::fs::create_dir_all(dir)?;
    let name = target.file_name().map(|n| n.to_string_lossy().into_owned()).unwrap_or_else(|| "out".into());
    let nanos = std::time::SystemTime::now().duration_since(std::time::UNIX_EPOCH).map(|d| d.as_nanos()).unwrap_or(0);
    let tmp = dir.join(format!(".{name}.{nanos}.tmp"));
    let result = (|| {
        let mut opts = std::fs::OpenOptions::new();
        opts.write(true).create_new(true);
        #[cfg(unix)]
        {
            use std::os::unix::fs::OpenOptionsExt;
            opts.mode(0o600);
        }
        let mut f = opts.open(&tmp)?;
        #[cfg(windows)]
        win::owner_only(&tmp);
        f.write_all(bytes)?;
        f.sync_all()?;
        drop(f);
        std::fs::rename(&tmp, target)
    })();
    let _ = std::fs::remove_file(&tmp);
    result
}

#[cfg(windows)]
mod win {
    use std::os::windows::ffi::OsStrExt;
    use std::path::Path;
    use windows_sys::Win32::Foundation::LocalFree;
    use windows_sys::Win32::Security::Authorization::{ConvertStringSecurityDescriptorToSecurityDescriptorW, SDDL_REVISION_1};
    use windows_sys::Win32::Security::{SetFileSecurityW, DACL_SECURITY_INFORMATION, PROTECTED_DACL_SECURITY_INFORMATION, PSECURITY_DESCRIPTOR};

    /// A protected DACL with one entry: full control for the file's owner (`OW`).
    pub fn owner_only(p: &Path) {
        let path: Vec<u16> = p.as_os_str().encode_wide().chain(Some(0)).collect();
        let sddl: Vec<u16> = "D:P(A;;FA;;;OW)".encode_utf16().chain(Some(0)).collect();
        let mut sd: PSECURITY_DESCRIPTOR = std::ptr::null_mut();
        // SAFETY: both strings are NUL-terminated and live for the calls; the descriptor is LocalAlloc'd and freed.
        unsafe {
            if ConvertStringSecurityDescriptorToSecurityDescriptorW(sddl.as_ptr(), SDDL_REVISION_1, &mut sd, std::ptr::null_mut()) != 0 {
                SetFileSecurityW(path.as_ptr(), DACL_SECURITY_INFORMATION | PROTECTED_DACL_SECURITY_INFORMATION, sd);
                LocalFree(sd as _);
            }
        }
    }
}
