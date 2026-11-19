// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
//! The vault backup file (VAULT-REVEAL-1), the same format the Kotlin daemon wrote: every secret sealed under a key
//! derived from a passphrase (PBKDF2-HMAC-SHA256, the count recorded in the file) with AES-256-GCM; the AAD is the header
//! (`format NUL v NUL kdf NUL iterations NUL salt NUL nonce`), so a changed salt, count or format does not open.
use std::collections::BTreeMap;
use std::num::NonZeroU32;

use base64::engine::general_purpose::STANDARD as B64;
use base64::Engine;
use ring::aead::{Aad, LessSafeKey, Nonce, UnboundKey, AES_256_GCM};
use ring::rand::{SecureRandom, SystemRandom};
use serde::{Deserialize, Serialize};

use crate::VaultError;

pub const FORMAT: &str = "infantry-vault-backup";
pub const VERSION: i32 = 1;
pub const KDF: &str = "PBKDF2WithHmacSHA256";
const SALT_BYTES: usize = 16;
const NONCE_BYTES: usize = 12;

#[derive(Serialize, Deserialize)]
struct BackupFile {
    format: String,
    v: i32,
    kdf: String,
    iterations: i64,
    salt: String,
    nonce: String,
    ct: String,
}

/// One secret in a backup. Its `Debug` names no value.
#[derive(Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct Entry {
    pub name: String,
    #[serde(default)]
    pub project: Option<String>,
    pub fields: BTreeMap<String, String>,
}

impl Entry {
    /// How an import names it, and the key of its conflict answer: `name` or `project/name`.
    pub fn id(&self) -> String {
        match &self.project {
            None => self.name.clone(),
            Some(p) => format!("{p}/{}", self.name),
        }
    }
}

impl std::fmt::Debug for Entry {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        write!(f, "Entry(id={}, fields={:?})", self.id(), self.fields.keys().collect::<Vec<_>>())
    }
}

#[derive(Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct Payload {
    pub created_at: i64,
    pub entries: Vec<Entry>,
}

impl std::fmt::Debug for Payload {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        write!(f, "Payload(createdAt={}, entries={})", self.created_at, self.entries.len())
    }
}

pub const WRONG_PASSPHRASE: &str = "the passphrase does not open this backup (or the file was changed)";

fn damaged() -> VaultError {
    VaultError::invalid("this backup is damaged")
}

fn aad(format: &str, v: i32, kdf: &str, iterations: i64, salt: &str, nonce: &str) -> Vec<u8> {
    [format, &v.to_string(), kdf, &iterations.to_string(), salt, nonce].join("\u{0}").into_bytes()
}

fn derive(passphrase: &str, salt: &[u8], iterations: i64) -> Result<LessSafeKey, VaultError> {
    let n = u32::try_from(iterations).ok().and_then(NonZeroU32::new).ok_or_else(damaged)?;
    let mut key = [0u8; 32];
    ring::pbkdf2::derive(ring::pbkdf2::PBKDF2_HMAC_SHA256, n, salt, passphrase.as_bytes(), &mut key);
    let k = UnboundKey::new(&AES_256_GCM, &key).map_err(|_| VaultError::failed("bad key"));
    key.fill(0);
    Ok(LessSafeKey::new(k?))
}

/// Kotlin's pretty JSON (4-space indent), so a file reads the same whichever daemon wrote it.
fn pretty<T: Serialize>(v: &T) -> Result<String, VaultError> {
    let mut out = Vec::new();
    let mut ser = serde_json::Serializer::with_formatter(&mut out, serde_json::ser::PrettyFormatter::with_indent(b"    "));
    v.serialize(&mut ser).map_err(|_| VaultError::failed("the backup could not be encoded"))?;
    String::from_utf8(out).map_err(|_| VaultError::failed("the backup could not be encoded"))
}

pub fn seal(entries: Vec<Entry>, passphrase: &str, iterations: i64, now_ms: i64) -> Result<String, VaultError> {
    let rnd = SystemRandom::new();
    let mut salt = [0u8; SALT_BYTES];
    let mut nonce = [0u8; NONCE_BYTES];
    rnd.fill(&mut salt).and_then(|_| rnd.fill(&mut nonce)).map_err(|_| VaultError::failed("no system randomness"))?;
    let (salt64, nonce64) = (B64.encode(salt), B64.encode(nonce));
    let mut plain = pretty(&Payload { created_at: now_ms, entries })?.into_bytes();
    let key = derive(passphrase, &salt, iterations)?;
    let r = key.seal_in_place_append_tag(Nonce::assume_unique_for_key(nonce), Aad::from(aad(FORMAT, VERSION, KDF, iterations, &salt64, &nonce64)), &mut plain);
    if r.is_err() {
        plain.fill(0);
        return Err(VaultError::failed("sealing failed"));
    }
    let ct = B64.encode(&plain);
    plain.fill(0);
    pretty(&BackupFile { format: FORMAT.into(), v: VERSION, kdf: KDF.into(), iterations, salt: salt64, nonce: nonce64, ct })
}

/// Opens `text`. Refused with a plain reason when it is not a backup, another format or KDF, or an iteration count outside
/// `iter_min..=iter_max` (a crafted file could otherwise pin the daemon); [`WRONG_PASSPHRASE`] when it does not open.
pub fn open(text: &str, passphrase: &str, iter_min: i64, iter_max: i64) -> Result<Payload, VaultError> {
    let not = || VaultError::invalid("this is not an Infantry AI vault backup");
    let f: BackupFile = serde_json::from_str(text).map_err(|_| not())?;
    if f.format != FORMAT {
        return Err(not());
    }
    if f.v != VERSION {
        return Err(VaultError::invalid(format!("this backup is format version {}; this daemon reads version {VERSION}", f.v)));
    }
    if f.kdf != KDF {
        return Err(VaultError::invalid("this backup uses an unknown key derivation"));
    }
    if f.iterations < iter_min || f.iterations > iter_max {
        return Err(VaultError::invalid(format!("this backup's key derivation count is outside {iter_min}..{iter_max}")));
    }
    let salt = B64.decode(&f.salt).map_err(|_| damaged())?;
    let nonce: [u8; NONCE_BYTES] = B64.decode(&f.nonce).map_err(|_| damaged())?.try_into().map_err(|_| damaged())?;
    if salt.len() != SALT_BYTES {
        return Err(damaged());
    }
    let mut ct = B64.decode(&f.ct).map_err(|_| damaged())?;
    let key = derive(passphrase, &salt, f.iterations)?;
    let plain = key
        .open_in_place(Nonce::assume_unique_for_key(nonce), Aad::from(aad(&f.format, f.v, &f.kdf, f.iterations, &f.salt, &f.nonce)), &mut ct)
        .map_err(|_| VaultError::invalid(WRONG_PASSPHRASE))?;
    let payload = std::str::from_utf8(plain).ok().and_then(|s| serde_json::from_str::<Payload>(s).ok()).ok_or_else(damaged);
    ct.fill(0);
    payload
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn seal_opens_with_the_passphrase_only() {
        let e = Entry { name: "a".into(), project: Some("p".into()), fields: [("K".to_string(), "fake-1".to_string())].into() };
        let text = seal(vec![e.clone()], "fake passphrase", 1_000, 42).unwrap();
        assert!(!text.contains("fake-1"));
        let p = open(&text, "fake passphrase", 1, 2_000).unwrap();
        assert_eq!((p.created_at, p.entries[0].id(), &p.entries[0]), (42, "p/a".to_string(), &e));
        assert_eq!(open(&text, "wrong passphrase", 1, 2_000).unwrap_err().to_string(), WRONG_PASSPHRASE);
        assert!(open(&text, "fake passphrase", 5_000, 9_000).is_err(), "count outside the range");
        assert!(open("{}", "x", 1, 2).is_err());
    }
}
