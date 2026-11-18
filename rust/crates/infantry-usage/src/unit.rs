// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
//! The usage frames on the [`FrameUnit`] contract. `usage_get`, `limits` (when the cache is old) and
//! `usage_meter_get` may block on I/O (a live read is at most 12 s per source): the daemon dispatches them on a
//! blocking thread. Every answer echoes the request's `request_id`.
use std::sync::Arc;

use infantry_core::frames::{FrameCtx, FrameUnit, Refusal};
use infantry_protocol::envelope::Envelope;
use infantry_protocol::frames;
use infantry_protocol::usage::UsageSet;
use serde_json::Value;

use crate::governor::{USAGE_WORKERS, USAGE_WORKERS_GET};
use crate::service::UsageService;

pub const PACE_GET: &str = "pace_get";
pub const PACE_REPORT: &str = "pace_report";

pub const FRAMES: &[&str] = &[frames::USAGE_GET, frames::USAGE_SET, frames::LIMITS, frames::PROVIDER_STATES, frames::USAGE_METER_GET, PACE_GET, USAGE_WORKERS_GET];

pub struct UsageUnit {
    svc: Arc<UsageService>,
}

impl UsageUnit {
    pub fn new(svc: Arc<UsageService>) -> Self {
        UsageUnit { svc }
    }
}

/// `"force"`, `{"force": true}` or `true` asks for a fresh read.
fn wants_force(data: &str) -> bool {
    let d = data.trim();
    d.eq_ignore_ascii_case("force")
        || match serde_json::from_str::<Value>(d) {
            Ok(Value::Bool(b)) => b,
            Ok(v) => v.get("force").and_then(Value::as_bool).unwrap_or(false),
            Err(_) => false,
        }
}

fn reply(env: &Envelope, r#type: &str, body: impl serde::Serialize) -> Result<Vec<Envelope>, Refusal> {
    let data = serde_json::to_string(&body).map_err(|e| Refusal::new(&env.r#type, e))?;
    let mut out = Envelope::new(r#type, data);
    out.request_id = env.request_id.clone();
    Ok(vec![out])
}

impl FrameUnit for UsageUnit {
    fn name(&self) -> &'static str {
        "usage"
    }

    fn frames(&self) -> &'static [&'static str] {
        FRAMES
    }

    fn handle(&self, env: &Envelope, _ctx: &FrameCtx) -> Result<Vec<Envelope>, Refusal> {
        let t = env.r#type.as_str();
        match t {
            frames::USAGE_GET => {
                let r = if wants_force(&env.data) { self.svc.report_fresh(true) } else { self.svc.report() };
                reply(env, frames::USAGE_REPORT, r)
            }
            frames::USAGE_SET => {
                let req: UsageSet = serde_json::from_str(&env.data).map_err(|_| Refusal::new(t, "body is not a usage_set request"))?;
                let r = self.svc.set(&req).map_err(|e| Refusal::new(t, e))?;
                reply(env, frames::USAGE_REPORT, r)
            }
            frames::LIMITS => reply(env, frames::LIMITS, self.svc.limits(wants_force(&env.data))),
            frames::PROVIDER_STATES => reply(env, frames::PROVIDER_STATES, self.svc.provider_states()),
            frames::USAGE_METER_GET => reply(env, frames::USAGE_METER, self.svc.meter_report().map_err(|e| Refusal::new(t, e))?),
            PACE_GET => reply(env, PACE_REPORT, self.svc.pace_report(&env.data)),
            USAGE_WORKERS_GET => reply(env, USAGE_WORKERS, self.svc.workers()),
            other => Err(Refusal::new(other, "not a usage frame")),
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::config::MemoryStore;
    use crate::limits::{FixedSource, LimitService, LimitSource};
    use crate::service::UsageDeps;
    use infantry_core::bus::EventBus;
    use infantry_protocol::usage::{LimitWindow, UsageReport};

    #[test]
    fn frames_answer_with_the_request_id_and_usage_set_refuses_with_the_reason() {
        let dir = std::env::temp_dir().join(format!("infantry-usage-unit-{}", std::process::id()));
        let now = 1_800_000_000_000i64;
        let src: Arc<dyn LimitSource> = Arc::new(FixedSource::new(
            "claude",
            Arc::new(move |t| {
                vec![LimitWindow { provider: "claude".into(), label: "Current week".into(), window_minutes: 10_080, percent: Some(97.0), resets_at: Some(t + 3_600_000), observed_at: t, source: "live".into(), pace_delta: Some(-2.0) }]
            }),
        ));
        let limits = Arc::new(LimitService::new(vec![src], Arc::new(|| true), Arc::new(move || now), 0, Arc::new(|_| {})));
        let mut deps = UsageDeps::new(&dir, Arc::new(|| vec!["claude".into()]));
        deps.store = Some(Arc::new(MemoryStore::default()));
        deps.limits = Some(limits);
        deps.clock = Arc::new(move || now);
        let unit = UsageUnit::new(UsageService::open(deps));
        let ctx = FrameCtx { conn_id: 1, owner: true, bus: EventBus::new(8) };
        let mut get = Envelope::new(frames::USAGE_GET, "force");
        get.request_id = Some("r1".into());
        let out = unit.handle(&get, &ctx).unwrap();
        assert_eq!((out[0].r#type.as_str(), out[0].request_id.as_deref()), ("usage_report", Some("r1")));
        let r: UsageReport = serde_json::from_str(&out[0].data).unwrap();
        assert_eq!((r.meters[0].allowance, r.meters[0].can_start), (Some(1), false), "hard stop at 97%");
        let err = unit.handle(&Envelope::new(frames::USAGE_SET, r#"{"meter":"claude"}"#), &ctx).unwrap_err();
        assert_eq!(err.0, "usage_set refused: usage_set changes nothing");
        let set = Envelope::new(frames::USAGE_SET, format!(r#"{{"meter":"claude","overrideUntil":{}}}"#, now + 60_000));
        let r: UsageReport = serde_json::from_str(&unit.handle(&set, &ctx).unwrap()[0].data).unwrap();
        assert!(r.meters[0].can_start);
        let w = unit.handle(&Envelope::new(USAGE_WORKERS_GET, ""), &ctx).unwrap();
        assert!(w[0].data.contains("\"freeSlots\":null"));
        assert!(unit.handle(&Envelope::new(PACE_GET, "{}"), &ctx).unwrap()[0].data.contains("\"series\":[]"));
        assert!(unit.handle(&Envelope::new(frames::USAGE_METER_GET, ""), &ctx).is_ok());
        let _ = std::fs::remove_dir_all(&dir);
    }
}
