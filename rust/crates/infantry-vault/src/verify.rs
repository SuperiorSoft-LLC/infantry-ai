// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
//! The OS user check behind reveal, copy and backup (VAULT-REVEAL-1): the person at this computer proves they are the
//! signed-in user. Windows: Windows Hello (WinRT `UserConsentVerifier`: PIN, fingerprint or face). Where Hello is not
//! available (not set up, no device, disabled by policy, another platform) the check answers unavailable and the vault
//! refuses: a value is never handed out unprotected.
use std::sync::mpsc;
use std::time::Duration;

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Outcome {
    Verified,
    Denied,
    Unavailable,
}

impl Outcome {
    pub fn wire(self) -> &'static str {
        match self {
            Outcome::Verified => "verified",
            Outcome::Denied => "denied",
            Outcome::Unavailable => "unavailable",
        }
    }
}

/// What one check came to. `method` names how (`windows-hello`); `detail` why not.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Verification {
    pub outcome: Outcome,
    pub method: String,
    pub detail: String,
}

impl Verification {
    pub fn verified(method: &str) -> Self {
        Verification { outcome: Outcome::Verified, method: method.into(), detail: String::new() }
    }
    pub fn denied(method: &str, why: &str) -> Self {
        Verification { outcome: Outcome::Denied, method: method.into(), detail: why.into() }
    }
    pub fn unavailable(why: impl Into<String>) -> Self {
        Verification { outcome: Outcome::Unavailable, method: String::new(), detail: why.into() }
    }
    pub fn is_verified(&self) -> bool {
        self.outcome == Outcome::Verified
    }
}

/// The port a platform plugs in. `verify` blocks until the person answers or the prompt fails; it never panics.
pub trait OsUserVerifier: Send + Sync {
    /// Shown to the owner ("Windows Hello").
    fn label(&self) -> &'static str;
    /// What a verify would use now, with no prompt.
    fn check(&self) -> Verification;
    /// Shows the OS prompt with `reason` and waits up to `timeout`.
    fn verify(&self, reason: &str, timeout: Duration) -> Verification;
}

/// A platform with no verifier: nothing is ever verified.
pub struct NotYet {
    pub label: &'static str,
    pub message: &'static str,
}

impl OsUserVerifier for NotYet {
    fn label(&self) -> &'static str {
        self.label
    }
    fn check(&self) -> Verification {
        Verification::unavailable(self.message)
    }
    fn verify(&self, _reason: &str, _timeout: Duration) -> Verification {
        Verification::unavailable(self.message)
    }
}

/// A fixed answer (tests, a headless daemon that must refuse).
pub struct Fixed(pub Verification);

impl OsUserVerifier for Fixed {
    fn label(&self) -> &'static str {
        "test verifier"
    }
    fn check(&self) -> Verification {
        self.0.clone()
    }
    fn verify(&self, _reason: &str, _timeout: Duration) -> Verification {
        self.0.clone()
    }
}

/// The verifier of this platform.
pub fn detect() -> Box<dyn OsUserVerifier> {
    #[cfg(windows)]
    {
        Box::new(WindowsHello)
    }
    #[cfg(target_os = "macos")]
    {
        Box::new(NotYet { label: "macOS Touch ID", message: "Reveal and copy need macOS Touch ID / password verification, which is not available yet." })
    }
    #[cfg(not(any(windows, target_os = "macos")))]
    {
        Box::new(NotYet { label: "OS user verification", message: "Reveal and copy need OS user verification, which is not available on this system yet." })
    }
}

/// Runs `f` on its own thread and waits up to `timeout` (a WinRT wait cannot be cancelled from here).
fn bounded<F: FnOnce() -> Verification + Send + 'static>(timeout: Duration, f: F) -> Verification {
    let (tx, rx) = mpsc::channel();
    let spawned = std::thread::Builder::new().name("vault-verify".into()).spawn(move || {
        let _ = tx.send(f());
    });
    if spawned.is_err() {
        return Verification::unavailable("the verification prompt could not start");
    }
    rx.recv_timeout(timeout).unwrap_or_else(|_| Verification::denied("windows-hello", "the prompt was not answered in time"))
}

#[cfg(windows)]
pub struct WindowsHello;

#[cfg(windows)]
mod hello {
    use super::Verification;
    use windows::core::{factory, HSTRING};
    use windows::Security::Credentials::UI::{UserConsentVerificationResult, UserConsentVerifier, UserConsentVerifierAvailability};
    use windows::Win32::System::WinRT::IUserConsentVerifierInterop;
    use windows::Win32::UI::WindowsAndMessaging::GetForegroundWindow;
    use windows_future::IAsyncOperation;

    pub const METHOD: &str = "windows-hello";

    fn availability_text(a: UserConsentVerifierAvailability) -> Option<&'static str> {
        match a {
            UserConsentVerifierAvailability::Available => None,
            UserConsentVerifierAvailability::DeviceNotPresent => Some("Windows Hello has no PIN, fingerprint or face device here"),
            UserConsentVerifierAvailability::NotConfiguredForUser => Some("Windows Hello is not set up for this Windows user"),
            UserConsentVerifierAvailability::DisabledByPolicy => Some("Windows Hello is disabled by policy"),
            UserConsentVerifierAvailability::DeviceBusy => Some("the Windows Hello device is busy"),
            _ => Some("Windows Hello is not available"),
        }
    }

    pub fn check() -> Verification {
        match UserConsentVerifier::CheckAvailabilityAsync().and_then(|op| op.join()) {
            Ok(a) => match availability_text(a) {
                None => Verification::verified(METHOD),
                Some(why) => Verification::unavailable(why),
            },
            Err(_) => Verification::unavailable("Windows Hello could not be reached"),
        }
    }

    fn request(reason: &HSTRING) -> windows::core::Result<UserConsentVerificationResult> {
        // A desktop process asks for its foreground window, so the prompt comes up in front; the plain call is the fallback.
        let hwnd = unsafe { GetForegroundWindow() };
        if !hwnd.is_invalid() {
            if let Ok(interop) = factory::<UserConsentVerifier, IUserConsentVerifierInterop>() {
                let op: windows::core::Result<IAsyncOperation<UserConsentVerificationResult>> = unsafe { interop.RequestVerificationForWindowAsync(hwnd, reason) };
                if let Ok(r) = op.and_then(|op| op.join()) {
                    return Ok(r);
                }
            }
        }
        UserConsentVerifier::RequestVerificationAsync(reason).and_then(|op| op.join())
    }

    pub fn verify(reason: &str) -> Verification {
        let c = check();
        if !c.is_verified() {
            return c;
        }
        match request(&HSTRING::from(reason)) {
            Ok(UserConsentVerificationResult::Verified) => Verification::verified(METHOD),
            Ok(UserConsentVerificationResult::Canceled) => Verification::denied(METHOD, "cancelled"),
            Ok(UserConsentVerificationResult::RetriesExhausted) => Verification::denied(METHOD, "too many tries"),
            Ok(UserConsentVerificationResult::DeviceBusy) => Verification::denied(METHOD, "the device is busy"),
            Ok(UserConsentVerificationResult::NotConfiguredForUser) => Verification::unavailable("Windows Hello is not set up for this Windows user"),
            Ok(UserConsentVerificationResult::DisabledByPolicy) => Verification::unavailable("Windows Hello is disabled by policy"),
            Ok(UserConsentVerificationResult::DeviceNotPresent) => Verification::unavailable("Windows Hello has no PIN, fingerprint or face device here"),
            Ok(_) => Verification::denied(METHOD, "not verified"),
            Err(_) => Verification::unavailable("the Windows Hello prompt could not be shown"),
        }
    }
}

#[cfg(windows)]
impl OsUserVerifier for WindowsHello {
    fn label(&self) -> &'static str {
        "Windows Hello"
    }
    fn check(&self) -> Verification {
        bounded(Duration::from_secs(10), hello::check)
    }
    fn verify(&self, reason: &str, timeout: Duration) -> Verification {
        let reason = reason.to_string();
        bounded(timeout, move || hello::verify(&reason))
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn bounded_times_out_as_denied() {
        let v = bounded(Duration::from_millis(20), || {
            std::thread::sleep(Duration::from_millis(500));
            Verification::verified("x")
        });
        assert_eq!(v.outcome, Outcome::Denied);
        assert!(Fixed(Verification::verified("t")).verify("r", Duration::from_secs(1)).is_verified());
    }
}
