// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
//! The ORCHESTRATOR side: pair with a runner (code or bearer secret -> credential), then typed calls over the link.
//! [`LinkClient::ping`] is the liveness check: hello (contract check) then health, timed.

use crate::store::PairedRunner;
use crate::transport::{self, Exchange, LinkFailure, Security};
use crate::wire::{self, *};
use serde::de::DeserializeOwned;
use serde::Serialize;
use std::sync::Mutex;
use std::time::{Duration, Instant};

/// The one wording of an outdated runner's refusal (Kotlin `RunnerPool.outdatedText`).
pub fn outdated_text(name: &str, contract: Option<&str>, min: &str) -> String {
    let c = contract.filter(|c| !c.trim().is_empty()).unwrap_or("unknown");
    format!("runner '{name}' is older than the app (contract {c}, the app needs {min} or newer): {OUTDATED_HINT}")
}

/// Why a typed call failed: no answer, the runner's refusal, or an answer that did not decode.
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum CallError {
    Link(LinkFailure),
    Refused(String),
    Malformed(String),
}

impl std::fmt::Display for CallError {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        match self {
            CallError::Link(e) => write!(f, "{e}"),
            CallError::Refused(r) => f.write_str(r),
            CallError::Malformed(m) => write!(f, "malformed runner answer: {m}"),
        }
    }
}

impl std::error::Error for CallError {}

/// One liveness round trip.
#[derive(Debug, Clone)]
pub struct Ping {
    pub hello: HelloAnswer,
    pub health: HealthAnswer,
    /// hello + health, wall clock.
    pub rtt: Duration,
    /// The health call alone (one connection, one frame each way).
    pub health_rtt: Duration,
}

pub struct LinkClient {
    pub url: String,
    pub orchestrator_id: String,
    credential: Mutex<Option<String>>,
    pub timeout: Duration,
    /// The pin (wss) and the plain-ws switch (`runners.allowPlainTailnet`, off unless set).
    pub security: Security,
    /// Every exchange, verbatim, when recording (fixtures).
    pub trace: Option<Mutex<Vec<Exchange>>>,
}

impl LinkClient {
    pub fn new(url: impl Into<String>, orchestrator_id: impl Into<String>, credential: Option<String>) -> Self {
        LinkClient { url: url.into(), orchestrator_id: orchestrator_id.into(), credential: Mutex::new(credential), timeout: Duration::from_secs(30), security: Security::default(), trace: None }
    }

    pub fn of(r: &PairedRunner, orchestrator_id: &str) -> Self {
        let mut c = Self::new(r.url.clone(), orchestrator_id, Some(r.credential.clone()));
        c.security.pin = Some(r.fingerprint.clone()).filter(|f| !f.is_empty());
        c
    }

    /// Pins the runner certificate (lower-case hex SHA-256 from the bearer).
    pub fn with_pin(mut self, pin: Option<String>) -> Self {
        self.security.pin = pin.filter(|p| !p.is_empty());
        self
    }

    /// Allows plain `ws://` (the setting `runners.allowPlainTailnet`).
    pub fn allow_plain(mut self, allow: bool) -> Self {
        self.security.allow_plain = allow;
        self
    }

    pub fn recording(mut self) -> Self {
        self.trace = Some(Mutex::new(Vec::new()));
        self
    }

    pub fn has_credential(&self) -> bool {
        self.credential.lock().unwrap().is_some()
    }

    /// One raw call: `(type, data)` -> the answer frame.
    pub fn call(&self, t: &str, data: &str) -> Result<Answer, LinkFailure> {
        self.call_with(t, data, self.timeout)
    }

    pub fn call_with(&self, t: &str, data: &str, timeout: Duration) -> Result<Answer, LinkFailure> {
        let cred = self.credential.lock().unwrap().clone();
        let x = transport::call(&self.url, Some(&self.orchestrator_id), cred.as_deref(), t, data, timeout, &self.security)?;
        let a = x.answer.clone();
        if let Some(tr) = &self.trace {
            tr.lock().unwrap().push(x);
        }
        Ok(a)
    }

    /// A typed call: the request JSON in, the answer decoded (an error frame is [`CallError::Refused`]).
    pub fn typed<Q: Serialize, A: DeserializeOwned>(&self, t: &str, request: &Q) -> Result<A, CallError> {
        self.typed_with(t, request, self.timeout)
    }

    pub fn typed_with<Q: Serialize, A: DeserializeOwned>(&self, t: &str, request: &Q, timeout: Duration) -> Result<A, CallError> {
        let data = serde_json::to_string(request).expect("request");
        let a = self.call_with(t, &data, timeout).map_err(CallError::Link)?;
        if !a.ok() {
            return Err(CallError::Refused(a.data));
        }
        serde_json::from_str(&a.data).map_err(|e| CallError::Malformed(e.to_string()))
    }

    /// Pairs with `code` (a one-time code or the runner's bearer secret); keeps the credential and returns the record.
    pub fn pair(&self, code: &str, orchestrator_name: &str) -> Result<PairedRunner, CallError> {
        *self.credential.lock().unwrap() = None;
        let req = PairRequest {
            code: code.into(),
            orchestrator_id: self.orchestrator_id.clone(),
            orchestrator_name: orchestrator_name.into(),
            grant_projects: vec![],
            grant_harnesses: vec![],
        };
        let a: PairAnswer = self.typed(wire::PAIR, &req)?;
        *self.credential.lock().unwrap() = Some(a.credential.clone());
        Ok(PairedRunner {
            name: a.runner_name.clone(),
            url: self.url.clone(),
            fingerprint: self.security.pin.clone().unwrap_or_default(),
            credential: a.credential,
            issued_at: crate::server::now_ms(),
            runner_id: a.runner_id,
            contract: a.contract,
        })
    }

    /// Hello, refused as outdated when the runner speaks a contract older than [`MIN_RUNNER_CONTRACT`].
    pub fn hello(&self) -> Result<HelloAnswer, CallError> {
        let h: HelloAnswer = self.typed(wire::HELLO, &serde_json::json!({}))?;
        if contract_older(Some(&h.contract), MIN_RUNNER_CONTRACT) {
            return Err(CallError::Refused(outdated_text(&h.runner_name, Some(&h.contract), MIN_RUNNER_CONTRACT)));
        }
        Ok(h)
    }

    pub fn health(&self) -> Result<HealthAnswer, CallError> {
        self.typed(wire::HEALTH, &serde_json::json!({}))
    }

    /// Ping/pong: hello (who, which contract) then health, timed.
    pub fn ping(&self) -> Result<Ping, CallError> {
        let t0 = Instant::now();
        let hello = self.hello()?;
        let t1 = Instant::now();
        let health = self.health()?;
        let end = Instant::now();
        Ok(Ping { hello, health, rtt: end - t0, health_rtt: end - t1 })
    }

    pub fn detach(&self) -> Result<(), CallError> {
        let _: serde_json::Value = self.typed(wire::DETACH, &serde_json::json!({}))?;
        Ok(())
    }
}
