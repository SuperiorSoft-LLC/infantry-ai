// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
//! The secrets vault (layer L1, platform level).
//!
//! - [`keystore`]: the sealed `keystore.json` (Kotlin v1 and v2 formats), the master key ([`os_vault`]: DPAPI on
//!   Windows, Keychain / Secret Service behind cfg).
//! - [`template`]: the ONE placeholder syntax `{{secret:NAME}}` / `{{secret:NAME#FIELD}}`, file rendering, find and the
//!   run-secret listing (`secret_render`, `secret_find`, `run_secrets`).
//! - [`scrub`]: the output scrubber the daemon applies to every session and tool stream ([`scrub::SecretMask`]) and the
//!   byte scrubber of one run ([`scrub::ByteScrubber`]).
//! - [`verify`]: the OS user check (Windows Hello via `UserConsentVerifier`); none available = refuse.
//! - [`backup`]: the passphrase-sealed backup file (Kotlin `VaultBackup` format, PBKDF2-HMAC-SHA256 + AES-256-GCM).
//! - [`vault`]: reveal, copy, export and import behind the OS check, and the audit log (`vault-audit.jsonl`).
//! - [`handler`] + [`unit`]: the `secret_*` frames as one [`infantry_core::frames::FrameUnit`].
//!
//! SECURITY: no value ever goes in an error, a refusal, a log line or a `Debug` dump. Only `secret_reveal` (owner, after
//! the OS check) and `plugin_secret_get` (owner connection) answer a value, and neither is ever pushed.
pub mod backup;
pub mod handler;
pub mod keystore;
pub mod limits;
pub mod os_vault;
pub mod scrub;
pub mod template;
pub mod unit;
pub mod vault;
pub mod verify;

mod fsutil;

pub use handler::{KeystoreHandler, KnownSecret};
pub use keystore::Keystore;
pub use scrub::{ByteScrubber, SecretMask};
pub use unit::VaultUnit;
pub use vault::SecretVault;

/// Why a vault call did not go through. `Invalid` is the caller's ("refused: ..."); `Failed` is ours ("failed: ...").
/// Neither ever carries a value.
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum VaultError {
    Invalid(String),
    Failed(String),
}

impl VaultError {
    pub fn invalid(m: impl Into<String>) -> Self {
        VaultError::Invalid(m.into())
    }
    pub fn failed(m: impl Into<String>) -> Self {
        VaultError::Failed(m.into())
    }
    /// The wire text for frame `frame`: `"<frame> refused: ..."` or `"<frame> failed: ..."`.
    pub fn wire(&self, frame: &str) -> String {
        match self {
            VaultError::Invalid(m) => format!("{frame} refused: {m}"),
            VaultError::Failed(m) => format!("{frame} failed: {m}"),
        }
    }
}

impl std::fmt::Display for VaultError {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        match self {
            VaultError::Invalid(m) | VaultError::Failed(m) => f.write_str(m),
        }
    }
}

impl std::error::Error for VaultError {}
