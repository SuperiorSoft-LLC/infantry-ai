// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
//! P9.6 webhook ingress (Kotlin `WebhookIngress`): `POST /hooks/{id}` needs no daemon bearer, so this IS the
//! authentication. Admitted only with `X-Infantry-Signature: v1=<hex HMAC-SHA256 of "v1:<ts>:<delivery>:<raw body>">`,
//! `X-Infantry-Timestamp` (epoch seconds) and `X-Infantry-Delivery` (the `X-AgentHQ-*` names are still read).
//!
//! Checks, in order: body cap -> trigger and secret -> header shape -> signature -> replay window -> duplicate -> fire.
//! Every failure before the signature is the same 401. The body is hashed (stored on the event), never interpreted.
use crate::service::{Ports, Service, DUPLICATE_EVENT_PREFIX};
use crate::store::Sql;
use hmac::{Hmac, Mac};
use sha2::{Digest, Sha256};
use std::collections::HashMap;

pub const MAX_BODY_BYTES: usize = 64 * 1024;
pub const REPLAY_WINDOW_SECONDS: i64 = 300;
pub const UNAUTHORIZED: &str = "missing or invalid webhook signature";
pub const SIGNATURE_HEADERS: [&str; 2] = ["X-Infantry-Signature", "X-AgentHQ-Signature"];
pub const TIMESTAMP_HEADERS: [&str; 2] = ["X-Infantry-Timestamp", "X-AgentHQ-Timestamp"];
pub const DELIVERY_HEADERS: [&str; 2] = ["X-Infantry-Delivery", "X-AgentHQ-Delivery"];
const SIGNATURE_PREFIX: &str = "v1=";
const DELIVERY_MAX: usize = 128;
const TIMESTAMP_DIGITS_MAX: usize = 13;

#[derive(Debug, Clone, PartialEq)]
pub enum Outcome {
    /// 200, with what the trigger did with the event.
    Accepted(String),
    Rejected(u16, String),
}

fn hex(b: &[u8]) -> String {
    b.iter().map(|x| format!("{x:02x}")).collect()
}

fn hmac_hex(secret: &str, ts: i64, delivery: &str, body: &[u8]) -> String {
    let mut m = <Hmac<Sha256> as Mac>::new_from_slice(secret.as_bytes()).expect("any key length");
    m.update(format!("v1:{ts}:{delivery}:").as_bytes());
    m.update(body);
    hex(&m.finalize().into_bytes())
}

/// The signature a sender puts in `X-Infantry-Signature`.
pub fn sign(secret: &str, ts: i64, delivery: &str, body: &[u8]) -> String {
    format!("{SIGNATURE_PREFIX}{}", hmac_hex(secret, ts, delivery, body))
}

fn eq_ct(a: &[u8], b: &[u8]) -> bool {
    a.len() == b.len() && a.iter().zip(b).fold(0u8, |acc, (x, y)| acc | (x ^ y)) == 0
}

fn delivery_ok(s: &str) -> bool {
    (1..=DELIVERY_MAX).contains(&s.len()) && s.chars().all(|c| c.is_ascii_alphanumeric() || matches!(c, '.' | '_' | '-'))
}

/// The first of `names` among `headers` (case-insensitive), the new name before the legacy one.
pub fn header<'a>(headers: &'a [(String, String)], names: &[&str]) -> Option<&'a str> {
    names.iter().find_map(|n| headers.iter().find(|(k, _)| k.eq_ignore_ascii_case(n)).map(|(_, v)| v.as_str()))
}

#[derive(Default)]
pub struct Ingress {
    /// delivery key -> when it stops being replayable; an in-memory backstop to the durable rows.
    seen: HashMap<String, i64>,
}

impl Ingress {
    fn verify<S: Sql, P: Ports>(svc: &Service<S, P>, id: &str, sig: Option<&str>, ts: Option<&str>, delivery: Option<&str>, body: &[u8]) -> Option<(String, i64, String)> {
        let id = id.trim();
        let secret = svc.webhook_secret(id)?;
        let ts = ts.map(str::trim).filter(|t| (1..=TIMESTAMP_DIGITS_MAX).contains(&t.len()) && t.chars().all(|c| c.is_ascii_digit()))?.parse::<i64>().ok()?;
        let delivery = delivery.map(str::trim).filter(|d| delivery_ok(d))?.to_string();
        let presented = sig.map(str::trim).filter(|s| s.len() == SIGNATURE_PREFIX.len() + 64 && s.starts_with(SIGNATURE_PREFIX) && s[3..].chars().all(|c| c.is_ascii_hexdigit()))?[3..].to_ascii_lowercase();
        let expected = hmac_hex(&secret, ts, &delivery, body);
        eq_ct(expected.as_bytes(), presented.as_bytes()).then(|| (id.to_string(), ts, delivery))
    }

    pub fn deliver<S: Sql, P: Ports>(&mut self, svc: &mut Service<S, P>, id: &str, sig: Option<&str>, ts: Option<&str>, delivery: Option<&str>, body: &[u8]) -> Outcome {
        if body.len() > MAX_BODY_BYTES {
            return Outcome::Rejected(413, format!("body is larger than {MAX_BODY_BYTES} bytes"));
        }
        let Some((id, ts, delivery)) = Self::verify(svc, id, sig, ts, delivery, body) else { return Outcome::Rejected(401, UNAUTHORIZED.into()) };
        let now = svc.ports.now();
        let window_ms = REPLAY_WINDOW_SECONDS * 1_000;
        if (now - ts * 1_000).abs() > window_ms {
            return Outcome::Rejected(401, format!("timestamp is outside the {REPLAY_WINDOW_SECONDS}s replay window"));
        }
        let digest = hex(&Sha256::digest(body));
        self.seen.retain(|_, until| *until >= now);
        let key = format!("{id}/{delivery}");
        let durable = match svc.events(&id) {
            Ok(evs) => evs.iter().any(|e| e.delivery_id.as_deref() == Some(delivery.as_str())),
            Err(_) => return Outcome::Rejected(401, UNAUTHORIZED.into()),
        };
        if self.seen.contains_key(&key) || durable {
            let _ = svc.record_duplicate(&id, &delivery, Some(digest));
            return Outcome::Accepted("duplicate".into());
        }
        self.seen.insert(key.clone(), ts * 1_000 + window_ms);
        if svc.fire(&id, "webhook", Some(delivery.clone()), Some(digest)).is_err() {
            self.seen.remove(&key);
            return Outcome::Rejected(401, UNAUTHORIZED.into());
        }
        let label = svc
            .events(&id)
            .unwrap_or_default()
            .into_iter()
            .rev()
            .find(|e| e.delivery_id.as_deref() == Some(delivery.as_str()) && e.source == "webhook" && !e.event_id.starts_with(DUPLICATE_EVENT_PREFIX))
            .map(|e| e.disposition)
            .unwrap_or_else(|| "accepted".into());
        Outcome::Accepted(label)
    }
}
