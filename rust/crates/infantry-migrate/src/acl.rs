// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
//! File access lists. The Kotlin daemon locks its secrets down (keystore, tokens, runner bearer, MCP config) with an
//! owner-only, PROTECTED access list: one that does not inherit from its folder. A plain copy would inherit the new
//! folder's list instead, so a copied secret could become readable by more accounts than the original.
//!
//! The rule: a file whose list is protected gets the same protected list on its copy. Every other file keeps
//! inheriting, which gives the same result because the copy sits in a sibling folder with the same parents.
//!
//! On Unix the "list" is the mode bits: a file with no group/other bits keeps that mode.

use std::path::Path;

/// One file's access list, enough to compare two of them. `sddl` holds only the DACL part (no owner, no audit).
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Access {
    /// The list does not inherit from the folder (Windows), or the file has no group/other bits (Unix).
    pub protected: bool,
    pub sddl: String,
}

#[cfg(windows)]
mod imp {
    use super::Access;
    use std::os::windows::ffi::OsStrExt;
    use std::path::Path;
    use windows_sys::Win32::Foundation::{LocalFree, ERROR_SUCCESS};
    use windows_sys::Win32::Security::Authorization::{
        ConvertSecurityDescriptorToStringSecurityDescriptorW, GetNamedSecurityInfoW, SetNamedSecurityInfoW, SDDL_REVISION_1, SE_FILE_OBJECT,
    };
    use windows_sys::Win32::Security::{
        GetSecurityDescriptorControl, ACL, DACL_SECURITY_INFORMATION, PROTECTED_DACL_SECURITY_INFORMATION, PSECURITY_DESCRIPTOR, SE_DACL_PROTECTED,
    };

    fn wide(p: &Path) -> Vec<u16> {
        p.as_os_str().encode_wide().chain(std::iter::once(0)).collect()
    }

    /// The security descriptor of `p` (DACL only); freed on drop.
    struct Descriptor {
        sd: PSECURITY_DESCRIPTOR,
        dacl: *mut ACL,
    }

    impl Drop for Descriptor {
        fn drop(&mut self) {
            if !self.sd.is_null() {
                unsafe { LocalFree(self.sd as _) };
            }
        }
    }

    fn descriptor(p: &Path) -> Result<Descriptor, String> {
        let name = wide(p);
        let mut d = Descriptor { sd: std::ptr::null_mut(), dacl: std::ptr::null_mut() };
        let rc = unsafe {
            GetNamedSecurityInfoW(
                name.as_ptr(),
                SE_FILE_OBJECT,
                DACL_SECURITY_INFORMATION,
                std::ptr::null_mut(),
                std::ptr::null_mut(),
                &mut d.dacl,
                std::ptr::null_mut(),
                &mut d.sd,
            )
        };
        if rc != ERROR_SUCCESS {
            return Err(format!("{}: reading its access list failed (error {rc})", p.display()));
        }
        Ok(d)
    }

    fn protected(d: &Descriptor) -> bool {
        let (mut control, mut rev) = (0u16, 0u32);
        let ok = unsafe { GetSecurityDescriptorControl(d.sd, &mut control, &mut rev) };
        ok != 0 && control & SE_DACL_PROTECTED != 0
    }

    pub fn access(p: &Path) -> Result<Access, String> {
        let d = descriptor(p)?;
        let mut out: *mut u16 = std::ptr::null_mut();
        let mut len = 0u32;
        let ok = unsafe { ConvertSecurityDescriptorToStringSecurityDescriptorW(d.sd, SDDL_REVISION_1, DACL_SECURITY_INFORMATION, &mut out, &mut len) };
        if ok == 0 || out.is_null() {
            return Err(format!("{}: its access list could not be read as text", p.display()));
        }
        let text = unsafe {
            let mut n = 0usize;
            while *out.add(n) != 0 {
                n += 1;
            }
            let s = String::from_utf16_lossy(std::slice::from_raw_parts(out, n));
            LocalFree(out as _);
            s
        };
        Ok(Access { protected: protected(&d), sddl: text })
    }

    pub fn copy_protected(src: &Path, dst: &Path) -> Result<bool, String> {
        let d = descriptor(src)?;
        if !protected(&d) {
            return Ok(false);
        }
        let name = wide(dst);
        let rc = unsafe {
            SetNamedSecurityInfoW(
                name.as_ptr(),
                SE_FILE_OBJECT,
                DACL_SECURITY_INFORMATION | PROTECTED_DACL_SECURITY_INFORMATION,
                std::ptr::null_mut(),
                std::ptr::null_mut(),
                d.dacl,
                std::ptr::null_mut(),
            )
        };
        if rc != ERROR_SUCCESS {
            return Err(format!("{}: setting its access list failed (error {rc})", dst.display()));
        }
        Ok(true)
    }
}

#[cfg(unix)]
mod imp {
    use super::Access;
    use std::os::unix::fs::PermissionsExt;
    use std::path::Path;

    pub fn access(p: &Path) -> Result<Access, String> {
        let mode = std::fs::metadata(p).map_err(|e| format!("{}: {e}", p.display()))?.permissions().mode() & 0o7777;
        Ok(Access { protected: mode & 0o077 == 0, sddl: format!("{mode:o}") })
    }

    pub fn copy_protected(src: &Path, dst: &Path) -> Result<bool, String> {
        let a = access(src)?;
        if !a.protected {
            return Ok(false);
        }
        let mode = u32::from_str_radix(&a.sddl, 8).unwrap_or(0o600);
        std::fs::set_permissions(dst, std::fs::Permissions::from_mode(mode)).map_err(|e| format!("{}: {e}", dst.display()))?;
        Ok(true)
    }
}

/// The access list of `p`.
pub fn access(p: &Path) -> Result<Access, String> {
    imp::access(p)
}

/// Gives `dst` the access list of `src` when that list is protected. True when it was copied.
pub fn copy_protected(src: &Path, dst: &Path) -> Result<bool, String> {
    imp::copy_protected(src, dst)
}
