// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
//! Where the keystore's master key lives in the OS. Windows: the key wrapped by DPAPI (current-user scope, no entropy, the
//! same blob the Kotlin daemon wrote through .NET `ProtectedData`) in `<stateDir>/keystore.key.dpapi`, base64. macOS
//! (Keychain) and Linux (Secret Service) go through the platform's own tool, as the Kotlin daemon did.
//!
//! The values crossing this trait are the base64 of the raw 32-byte key, never a user secret.
use std::path::{Path, PathBuf};

/// Where the master key lives in the OS (one per platform).
pub trait OsVault: Send + Sync {
    fn label(&self) -> &'static str;
    /// `Ok(None)`: no key stored yet. `Err`: a key is stored but this user cannot open it (never overwrite it then).
    fn fetch(&self) -> Result<Option<String>, String>;
    /// Stores a new key; false when the facility is absent or refused.
    fn store(&self, value_b64: &str) -> bool;
}

pub const DPAPI_FILE_NAME: &str = "keystore.key.dpapi";

/// The vault of this platform for state dir `dir`; None where there is none.
pub fn detect(dir: &Path) -> Option<Box<dyn OsVault>> {
    #[cfg(windows)]
    {
        Some(Box::new(Dpapi::new(dir.join(DPAPI_FILE_NAME))))
    }
    #[cfg(target_os = "macos")]
    {
        let _ = dir;
        Some(Box::new(unix::MacKeychain))
    }
    #[cfg(all(unix, not(target_os = "macos")))]
    {
        let _ = dir;
        Some(Box::new(unix::SecretService))
    }
    #[cfg(not(any(windows, unix)))]
    {
        let _ = dir;
        None
    }
}

/// Windows DPAPI: the raw key protected for the current user, base64 in a file.
pub struct Dpapi {
    blob: PathBuf,
}

impl Dpapi {
    pub fn new(blob: PathBuf) -> Self {
        Dpapi { blob }
    }
}

impl OsVault for Dpapi {
    fn label(&self) -> &'static str {
        "Windows DPAPI"
    }

    fn fetch(&self) -> Result<Option<String>, String> {
        use base64::Engine;
        if !self.blob.is_file() {
            return Ok(None);
        }
        let text = std::fs::read_to_string(&self.blob).map_err(|_| "the DPAPI key file cannot be read".to_string())?;
        let wrapped = base64::engine::general_purpose::STANDARD.decode(text.trim()).map_err(|_| "the DPAPI key file is damaged".to_string())?;
        let raw = dpapi::unprotect(&wrapped).ok_or_else(|| "the DPAPI key does not open for this Windows user".to_string())?;
        Ok(Some(base64::engine::general_purpose::STANDARD.encode(raw)))
    }

    fn store(&self, value_b64: &str) -> bool {
        use base64::Engine;
        let Ok(raw) = base64::engine::general_purpose::STANDARD.decode(value_b64.trim()) else { return false };
        let Some(wrapped) = dpapi::protect(&raw) else { return false };
        if let Some(p) = self.blob.parent() {
            let _ = std::fs::create_dir_all(p);
        }
        std::fs::write(&self.blob, base64::engine::general_purpose::STANDARD.encode(wrapped)).is_ok()
    }
}

#[cfg(windows)]
mod dpapi {
    use windows_sys::Win32::Foundation::LocalFree;
    use windows_sys::Win32::Security::Cryptography::{CryptProtectData, CryptUnprotectData, CRYPTPROTECT_UI_FORBIDDEN, CRYPT_INTEGER_BLOB};

    fn call(input: &[u8], protect: bool) -> Option<Vec<u8>> {
        let inp = CRYPT_INTEGER_BLOB { cbData: input.len() as u32, pbData: input.as_ptr() as *mut u8 };
        let mut out = CRYPT_INTEGER_BLOB { cbData: 0, pbData: std::ptr::null_mut() };
        // SAFETY: the input blob points at a live slice for the call; the output is LocalAlloc'd by the API and freed here.
        let ok = unsafe {
            if protect {
                CryptProtectData(&inp, std::ptr::null(), std::ptr::null(), std::ptr::null(), std::ptr::null(), CRYPTPROTECT_UI_FORBIDDEN, &mut out)
            } else {
                CryptUnprotectData(&inp, std::ptr::null_mut(), std::ptr::null(), std::ptr::null(), std::ptr::null(), CRYPTPROTECT_UI_FORBIDDEN, &mut out)
            }
        };
        if ok == 0 || out.pbData.is_null() {
            return None;
        }
        // SAFETY: the API returned cbData bytes at pbData.
        let bytes = unsafe { std::slice::from_raw_parts(out.pbData, out.cbData as usize).to_vec() };
        // SAFETY: zero the plaintext copy the API made, then free it.
        unsafe {
            std::ptr::write_bytes(out.pbData, 0, out.cbData as usize);
            LocalFree(out.pbData as _);
        }
        Some(bytes)
    }

    pub fn protect(raw: &[u8]) -> Option<Vec<u8>> {
        call(raw, true)
    }

    pub fn unprotect(wrapped: &[u8]) -> Option<Vec<u8>> {
        call(wrapped, false)
    }
}

#[cfg(not(windows))]
mod dpapi {
    pub fn protect(_raw: &[u8]) -> Option<Vec<u8>> {
        None
    }
    pub fn unprotect(_wrapped: &[u8]) -> Option<Vec<u8>> {
        None
    }
}

#[cfg(unix)]
mod unix {
    use super::OsVault;
    use std::io::Write;
    use std::process::{Command, Stdio};

    fn run(argv: &[&str], stdin: Option<&str>) -> Option<String> {
        let mut child = infantry_proc::command(argv[0]).args(&argv[1..]).stdin(Stdio::piped()).stdout(Stdio::piped()).stderr(Stdio::null()).spawn().ok()?;
        if let Some(mut si) = child.stdin.take() {
            if let Some(s) = stdin {
                let _ = si.write_all(s.as_bytes());
            }
        }
        let out = child.wait_with_output().ok()?;
        if !out.status.success() {
            return None;
        }
        let s = String::from_utf8_lossy(&out.stdout).trim().to_string();
        (!s.is_empty()).then_some(s)
    }

    #[allow(dead_code)]
    pub struct MacKeychain;
    impl OsVault for MacKeychain {
        fn label(&self) -> &'static str {
            "macOS Keychain"
        }
        fn fetch(&self) -> Result<Option<String>, String> {
            Ok(run(&["security", "find-generic-password", "-s", "infantry-keystore", "-a", "master", "-w"], None))
        }
        fn store(&self, value_b64: &str) -> bool {
            // `security` has no stdin form for -w: the master key (never a user secret) rides the argument list once.
            run(&["security", "add-generic-password", "-U", "-s", "infantry-keystore", "-a", "master", "-w", value_b64], None).is_some()
                || self.fetch().ok().flatten().as_deref() == Some(value_b64)
        }
    }

    #[allow(dead_code)]
    pub struct SecretService;
    impl OsVault for SecretService {
        fn label(&self) -> &'static str {
            "Linux Secret Service"
        }
        fn fetch(&self) -> Result<Option<String>, String> {
            Ok(run(&["secret-tool", "lookup", "service", "infantry-keystore"], None))
        }
        fn store(&self, value_b64: &str) -> bool {
            let Ok(mut child) = infantry_proc::command("secret-tool")
                .args(["store", "--label=Infantry AI keystore", "service", "infantry-keystore"])
                .stdin(Stdio::piped())
                .stdout(Stdio::null())
                .stderr(Stdio::null())
                .spawn()
            else {
                return false;
            };
            if let Some(mut si) = child.stdin.take() {
                let _ = si.write_all(value_b64.as_bytes());
            }
            child.wait().map(|s| s.success()).unwrap_or(false)
        }
    }
}
