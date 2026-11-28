// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
//! Pairing secrets on the runner side: the one-time [`PairCodes`] code, the long-lived [`Bearer`] and its copyable
//! [`BearerToken`] text (`irb1.<name>.<fp|plain>.<secret>`), as the Kotlin runner issues and reads them.

use sha2::{Digest, Sha256};
use std::collections::VecDeque;
use std::path::{Path, PathBuf};

/// Crockford-style base32 without I, L, O, U (16 symbols = 80 bits).
const CODE_ALPHABET: &[u8] = b"0123456789ABCDEFGHJKMNPQRSTVWXYZ";
const CODE_SIZE: usize = 16;
const GROUP: usize = 4;
const MS_PER_HOUR: i64 = 3_600_000;
/// The bearer secret's alphabet (lower-case, same look-alike rule): 32 symbols = 160 bits.
const SECRET_ALPHABET: &[u8] = b"0123456789abcdefghjkmnpqrstvwxyz";
const SECRET_SIZE: usize = 32;

pub const NO_LIVE_CODE: &str = "no pairing code is live on this runner: that code was already used or has expired (run `infantry runner pair` there for a new one)";
pub const WRONG_BEARER: &str = "wrong bearer: it is not this runner's live bearer (it was rotated, or copied wrong); copy the 'bearer:' line from the NEWEST 'INFANTRY RUNNER' block in the runner's log (it is printed again after every rotation), or run `infantry runner bearer` there";

pub(crate) fn random_bytes(n: usize) -> Vec<u8> {
    let mut b = vec![0u8; n];
    getrandom::getrandom(&mut b).expect("OS random source");
    b
}

/// `n` symbols of `alphabet` (32 symbols: 5 bits each, no modulo bias).
fn random_symbols(alphabet: &[u8], n: usize) -> String {
    debug_assert_eq!(alphabet.len(), 32);
    random_bytes(n).iter().map(|b| alphabet[(b & 31) as usize] as char).collect()
}

pub(crate) fn sha256(s: &str) -> [u8; 32] {
    Sha256::digest(s.as_bytes()).into()
}

pub(crate) fn sha256_hex(s: &str) -> String {
    sha256(s).iter().map(|b| format!("{b:02x}")).collect()
}

/// Constant-time equality of two equal-length digests.
pub(crate) fn same(a: &[u8], b: &[u8]) -> bool {
    a.len() == b.len() && a.iter().zip(b).fold(0u8, |acc, (x, y)| acc | (x ^ y)) == 0
}

/// Runner names: `^[a-z0-9][a-z0-9-]{0,31}$`.
pub fn valid_name(name: &str) -> bool {
    let b = name.as_bytes();
    !b.is_empty()
        && b.len() <= 32
        && (b[0].is_ascii_lowercase() || b[0].is_ascii_digit())
        && b.iter().all(|c| c.is_ascii_lowercase() || c.is_ascii_digit() || *c == b'-')
}

fn valid_secret(s: &str) -> bool {
    s.len() == SECRET_SIZE && s.bytes().all(|c| SECRET_ALPHABET.contains(&c))
}

/// The one live pairing code: single use, expires, and every redeem attempt counts against an hourly limit (past it, the
/// live code is burned).
pub struct PairCodes {
    expiry_ms: i64,
    attempts_per_hour: usize,
    live: Option<Live>,
    attempts: VecDeque<i64>,
}

struct Live {
    digest: [u8; 32],
    name: String,
    expires_at: i64,
}

impl PairCodes {
    pub fn new(expiry_ms: i64, attempts_per_hour: usize) -> Self {
        PairCodes { expiry_ms, attempts_per_hour, live: None, attempts: VecDeque::new() }
    }

    /// Issues a new code for runner `name` (replacing any live one), shown as `XXXX-XXXX-XXXX-XXXX`.
    pub fn issue(&mut self, name: &str, now: i64) -> String {
        let raw = random_symbols(CODE_ALPHABET, CODE_SIZE);
        self.live = Some(Live { digest: sha256(&raw), name: name.to_string(), expires_at: now + self.expiry_ms });
        raw.as_bytes().chunks(GROUP).map(|c| std::str::from_utf8(c).unwrap()).collect::<Vec<_>>().join("-")
    }

    pub fn live_name(&self, now: i64) -> Option<&str> {
        self.live.as_ref().filter(|l| now < l.expires_at).map(|l| l.name.as_str())
    }

    /// Whether `presented` has the shape of a one-time code (16 code symbols once normalised as [`PairCodes::redeem`] does).
    /// A bearer secret (32 symbols) never has it, so a bearer try is never counted against the code limit.
    pub fn looks_like_code(presented: &str) -> bool {
        presented.to_uppercase().chars().filter(|c| c.is_ascii() && CODE_ALPHABET.contains(&(*c as u8))).count() == CODE_SIZE
    }

    /// `Ok(())` spends the code; `Err` is the refusal reason.
    pub fn redeem(&mut self, code: &str, now: i64) -> Result<(), String> {
        while self.attempts.front().is_some_and(|t| *t <= now - MS_PER_HOUR) {
            self.attempts.pop_front();
        }
        if self.attempts.len() >= self.attempts_per_hour {
            self.live = None;
            return Err(format!(
                "too many pairing attempts (limits.runners.pairAttemptsPerHour = {}); the code is burned, run `infantry runner pair` again later",
                self.attempts_per_hour
            ));
        }
        self.attempts.push_back(now);
        let Some(current) = self.live.as_ref() else { return Err(NO_LIVE_CODE.into()) };
        if now >= current.expires_at {
            self.live = None;
            return Err("the pairing code expired (limits.runners.pairCodeMin); run `infantry runner pair` again".into());
        }
        let normal: String = code.to_uppercase().chars().filter(|c| CODE_ALPHABET.contains(&(*c as u8)) && c.is_ascii()).collect();
        if !same(&sha256(&normal), &current.digest) {
            return Err("wrong pairing code".into());
        }
        self.live = None;
        Ok(())
    }
}

/// The runner's long-lived bearer (name + secret), kept in `<state>/runner-link/bearer` (`name\nsecret\n`, the Kotlin
/// layout). Valid until rotated.
pub struct Bearer {
    file: PathBuf,
}

#[derive(Clone, PartialEq, Eq)]
pub struct BearerValue {
    pub name: String,
    pub secret: String,
}

impl std::fmt::Debug for BearerValue {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        write!(f, "BearerValue(name={}, secret=redacted)", self.name)
    }
}

impl Bearer {
    pub fn of(link_dir: &Path) -> Self {
        Bearer { file: link_dir.join("bearer") }
    }

    pub fn read(&self) -> Option<BearerValue> {
        let text = std::fs::read_to_string(&self.file).ok()?;
        let mut lines = text.lines().map(str::trim).filter(|l| !l.is_empty());
        let name = lines.next()?.to_string();
        let secret = lines.next()?.to_string();
        (valid_name(&name) && valid_secret(&secret)).then_some(BearerValue { name, secret })
    }

    /// The bearer, made now when there is none (or rotated / renamed).
    pub fn ensure(&self, name: Option<&str>, rotate: bool) -> std::io::Result<BearerValue> {
        let current = self.read();
        let wanted = name.filter(|n| !n.is_empty()).map(str::to_string).or(current.as_ref().map(|c| c.name.clone())).unwrap_or_else(|| "runner".into());
        if !valid_name(&wanted) {
            return Err(std::io::Error::new(
                std::io::ErrorKind::InvalidInput,
                format!("runner name '{wanted}' must be 1-32 of a-z, 0-9 and '-' (starting with a letter or digit)"),
            ));
        }
        if let Some(c) = current {
            if !rotate && c.name == wanted {
                return Ok(c);
            }
        }
        let fresh = BearerValue { name: wanted, secret: random_symbols(SECRET_ALPHABET, SECRET_SIZE) };
        if let Some(dir) = self.file.parent() {
            std::fs::create_dir_all(dir)?;
        }
        let tmp = self.file.with_extension("tmp");
        std::fs::write(&tmp, format!("{}\n{}\n", fresh.name, fresh.secret))?;
        std::fs::rename(&tmp, &self.file)?;
        Ok(fresh)
    }

    pub fn accepts(&self, presented: &str) -> bool {
        let Some(live) = self.read() else { return false };
        let p = presented.trim();
        !p.is_empty() && same(&sha256(p), &sha256(&live.secret))
    }
}

/// The copyable bearer text `irb1.<name>.<sha-256 hex | plain>.<secret>`.
#[derive(Clone, PartialEq, Eq)]
pub struct BearerToken {
    pub name: String,
    pub fingerprint: Option<String>,
    pub secret: String,
}

impl std::fmt::Debug for BearerToken {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        write!(f, "BearerToken(name={}, fingerprint={}, secret=redacted)", self.name, self.fingerprint.as_deref().unwrap_or("plain"))
    }
}

impl BearerToken {
    pub const PREFIX: &'static str = "irb1";

    pub fn text(&self) -> String {
        format!("{}.{}.{}.{}", Self::PREFIX, self.name, self.fingerprint.as_deref().unwrap_or("plain"), self.secret)
    }

    /// Parses the pasted text; errors are fixed text that never echoes the input.
    pub fn parse(text: &str) -> Result<BearerToken, &'static str> {
        let clean = text.trim().trim_matches('\'').trim_matches('"').trim();
        let parts: Vec<&str> = clean.split('.').collect();
        if parts.len() != 4 || parts[0] != Self::PREFIX {
            return Err("That is not a runner bearer: it starts with irb1. and is printed by the runner as 'bearer:' when it starts.");
        }
        if !valid_name(parts[1]) {
            return Err("The runner name inside the bearer is malformed; copy the whole bearer line again.");
        }
        let fingerprint = if parts[2] == "plain" {
            None
        } else {
            let fp = parts[2].to_ascii_lowercase();
            if fp.len() != 64 || !fp.bytes().all(|c| c.is_ascii_hexdigit()) {
                return Err("The certificate fingerprint inside the bearer is malformed; copy the whole bearer line again.");
            }
            Some(fp)
        };
        if !valid_secret(parts[3]) {
            return Err("The bearer is cut short or malformed; copy the whole bearer line again.");
        }
        Ok(BearerToken { name: parts[1].into(), fingerprint, secret: parts[3].into() })
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn code_is_single_use_and_normalised() {
        let mut c = PairCodes::new(60_000, 10);
        let code = c.issue("r1", 0);
        assert_eq!(code.len(), 19);
        assert_eq!(c.redeem("wrong", 1), Err("wrong pairing code".into()));
        assert_eq!(c.redeem(&code.to_lowercase().replace('-', " "), 2), Ok(()));
        assert_eq!(c.redeem(&code, 3), Err(NO_LIVE_CODE.into()));
    }

    #[test]
    fn code_expires_and_burns_past_the_limit() {
        let mut c = PairCodes::new(10, 2);
        let code = c.issue("r", 0);
        assert!(c.redeem(&code, 11).unwrap_err().contains("expired"));
        let code = c.issue("r", 20);
        let _ = c.redeem("x", 21);
        assert!(c.redeem(&code, 22).unwrap_err().contains("too many"));
    }

    #[test]
    fn bearer_round_trip() {
        let dir = std::env::temp_dir().join(format!("link-bearer-{}", uuid::Uuid::new_v4()));
        let b = Bearer::of(&dir);
        let v = b.ensure(Some("box-1"), false).unwrap();
        assert!(b.accepts(&v.secret) && !b.accepts("nope") && !b.accepts(""));
        assert_eq!(b.ensure(None, false).unwrap(), v);
        let rotated = b.ensure(None, true).unwrap();
        assert!(!b.accepts(&v.secret) && b.accepts(&rotated.secret));
        let t = BearerToken { name: v.name.clone(), fingerprint: None, secret: rotated.secret.clone() };
        assert_eq!(BearerToken::parse(&format!(" '{}' ", t.text())).unwrap(), t);
        assert!(BearerToken::parse("irb1.box-1.plain.short").is_err());
        assert!(!format!("{t:?}").contains(&rotated.secret));
        std::fs::remove_dir_all(dir).unwrap();
    }
}
