// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
//! The `limits.secrets.*` settings the vault reads, each with its declared default and range (Kotlin `LimitRegistry`).
//! A value outside the range is clamped; an unset or non-numeric value is the default.
use std::sync::Arc;

use infantry_core::settings::Settings;

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct LimitDef {
    pub key: &'static str,
    pub default: i64,
    pub min: i64,
    pub max: i64,
}

pub const RENDER_MAX_BYTES: LimitDef = LimitDef { key: "limits.secrets.renderMaxBytes", default: 1_048_576, min: 1_024, max: 268_435_456 };
pub const MAX_FIELDS: LimitDef = LimitDef { key: "limits.secrets.maxFields", default: 64, min: 1, max: 10_000 };
pub const MAX_SECRETS: LimitDef = LimitDef { key: "limits.secrets.maxSecrets", default: 256, min: 1, max: 100_000 };
pub const REVEAL_SECONDS: LimitDef = LimitDef { key: "limits.secrets.revealSeconds", default: 30, min: 1, max: 600 };
pub const CLIPBOARD_CLEAR_SECONDS: LimitDef = LimitDef { key: "limits.secrets.clipboardClearSeconds", default: 30, min: 1, max: 600 };
pub const VERIFY_CACHE_SECONDS: LimitDef = LimitDef { key: "limits.secrets.verifyCacheSeconds", default: 0, min: 0, max: 3_600 };
pub const VERIFY_TIMEOUT_SECONDS: LimitDef = LimitDef { key: "limits.secrets.verifyTimeoutSeconds", default: 120, min: 10, max: 900 };
pub const BACKUP_KDF_ITERATIONS: LimitDef = LimitDef { key: "limits.secrets.backupKdfIterations", default: 600_000, min: 210_000, max: 10_000_000 };
pub const BACKUP_MIN_PASSPHRASE: LimitDef = LimitDef { key: "limits.secrets.backupMinPassphrase", default: 12, min: 8, max: 256 };
pub const BACKUP_MAX_BYTES: LimitDef = LimitDef { key: "limits.secrets.backupMaxBytes", default: 16_777_216, min: 1_024, max: 268_435_456 };
pub const MAX_PER_RUN: LimitDef = LimitDef { key: "limits.secrets.maxPerRun", default: 16, min: 0, max: 1_024 };

pub const ALL: &[LimitDef] = &[
    RENDER_MAX_BYTES, MAX_FIELDS, MAX_SECRETS, REVEAL_SECONDS, CLIPBOARD_CLEAR_SECONDS, VERIFY_CACHE_SECONDS,
    VERIFY_TIMEOUT_SECONDS, BACKUP_KDF_ITERATIONS, BACKUP_MIN_PASSPHRASE, BACKUP_MAX_BYTES, MAX_PER_RUN,
];

/// The vault's view of the settings. `Limits::default()` answers every default (no settings attached).
#[derive(Clone, Default)]
pub struct Limits {
    settings: Option<Arc<dyn Settings>>,
}

impl Limits {
    pub fn new(settings: Arc<dyn Settings>) -> Self {
        Limits { settings: Some(settings) }
    }

    pub fn get(&self, def: LimitDef) -> i64 {
        let raw = self.settings.as_ref().and_then(|s| s.get(def.key)).and_then(|v| v.as_i64().or_else(|| v.as_f64().map(|f| f as i64)).or_else(|| v.as_str().and_then(|s| s.trim().parse().ok())));
        raw.map(|v| v.clamp(def.min, def.max)).unwrap_or(def.default)
    }
}

impl std::fmt::Debug for Limits {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.write_str("Limits")
    }
}
