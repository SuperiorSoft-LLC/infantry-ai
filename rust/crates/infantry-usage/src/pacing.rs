// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
//! The pacing judge: how many ticket workers may run on a plan meter, read from how much of the plan window is used
//! and where the burn lands at the window's reset. Unknown usage FAILS OPEN (no pacing, a warning in the report): a
//! dead usage endpoint never stalls the board.
//!
//! A METER is the plan a run draws on, not always its harness: pi or opencode running an `anthropic/...` model spends
//! the Claude subscription, an `opencode-go/...` model spends OpenCode Go.
use std::collections::{BTreeMap, BTreeSet, HashMap};
use std::sync::Arc;

use infantry_protocol::usage::{LimitWindow, ManualUsage, MeterUsage, PacingRule, PacingTier, ProviderLimits, UsageOverride, UsageReport};
use serde_json::Value;

use crate::util::{fmt_num, stamp, until, Clock};

pub const MODE_PACE: &str = "pace";
pub const MODE_BANDS: &str = "bands";
/// Pace mode: from `okProjected + PACE_SPAN` projected at reset only one worker.
pub const PACE_SPAN: f64 = 20.0;
/// Slack (points) so exactly on pace is not nudged down by rounding.
pub const PACE_TOLERANCE: f64 = 0.5;
/// Floor of the elapsed fraction: the first minutes of a window neither divide by ~0 nor flip-flop.
pub const MIN_ELAPSED: f64 = 0.10;
/// The registry default of `limits.pacing.maxWorkersCeiling`.
pub const DEFAULT_CEILING: i32 = 50;
/// A typed percent with no reset lapses after this long.
pub const MANUAL_TTL_MS: i64 = 5 * 60 * 60_000;

/// When a typed percent stops counting: at the window's reset, else 5 hours after it was typed.
pub fn manual_expires_at(m: &ManualUsage) -> i64 {
    m.resets_at.unwrap_or(m.set_at + MANUAL_TTL_MS)
}

/// Maps a run's (harness, model) to the meter it spends. `model` is the resolved model; `None` = unknown.
pub fn meter_for(provider: &str, model: Option<&str>) -> String {
    let p = provider.trim().to_lowercase();
    let m = model.unwrap_or("").trim().to_lowercase();
    if p == "claude" || m.starts_with("anthropic/") || m.starts_with("claude-bridge/") {
        "claude".into()
    } else if m.starts_with("opencode-go/") {
        "opencode-go".into()
    } else {
        p
    }
}

/// One window judged: allowance, the projection (`None` when judged by bands) and why.
#[derive(Debug, Clone, PartialEq)]
pub struct Judgement {
    pub allowance: i32,
    pub projected: Option<f64>,
    pub reason: String,
}

/// Bands: workers allowed at `percent`.
pub fn allowance(rule: &PacingRule, percent: Option<f64>) -> Option<i32> {
    let p = percent?;
    if !rule.enabled {
        return None;
    }
    rule.tiers.iter().find(|t| t.below.map_or(true, |b| p < b)).map(|t| t.workers)
}

/// Judges one live window at `now`; `None` = unreadable percent. Falls back to the bands without reset / length data.
pub fn judge(rule: &PacingRule, w: &LimitWindow, now: i64) -> Option<Judgement> {
    let percent = w.percent?;
    let resets = match w.resets_at {
        Some(r) if rule.mode == MODE_PACE && w.window_minutes > 0 => r,
        _ => return Some(Judgement { allowance: allowance(rule, Some(percent)).unwrap_or(0), projected: None, reason: "bands".into() }),
    };
    let window_ms = w.window_minutes as f64 * 60_000.0;
    let elapsed = (1.0 - (resets - now) as f64 / window_ms).clamp(MIN_ELAPSED, 1.0);
    let projected = percent / elapsed;
    let max = rule.max_workers.max(1);
    let ok = rule.ok_projected;
    let by_projection = if projected <= ok + PACE_TOLERANCE {
        max
    } else if projected >= ok + PACE_SPAN {
        1
    } else {
        ((max as f64) - ((projected - ok) / PACE_SPAN * (max - 1) as f64).ceil()).clamp(1.0, max as f64) as i32
    };
    Some(if percent >= rule.hard_stop_percent {
        Judgement { allowance: 1, projected: Some(projected), reason: format!("at {}% (hard stop {}%)", fmt_num(percent), fmt_num(rule.hard_stop_percent)) }
    } else if percent >= 90.0 && w.pace_delta.unwrap_or(0.0) > 0.0 {
        Judgement { allowance: 1, projected: Some(projected), reason: format!("at {}% and ahead of pace", fmt_num(percent)) }
    } else {
        Judgement { allowance: by_projection, projected: Some(projected), reason: format!("projected {}% at reset", fmt_num(projected)) }
    })
}

/// Validates a rule (setting `usage.pacing`); the error names the problem.
pub fn parse_rule(v: &Value, ceiling: i32) -> Result<PacingRule, String> {
    let rule: PacingRule = serde_json::from_value(v.clone())
        .map_err(|_| r#"must be {"enabled": bool, "tiers": [{"below": percent, "workers": n}, ..., {"workers": n}]}"#.to_string())?;
    validate_rule(&rule, ceiling)?;
    Ok(rule)
}

pub fn validate_rule(rule: &PacingRule, ceiling: i32) -> Result<(), String> {
    let req = |ok: bool, msg: String| if ok { Ok(()) } else { Err(msg) };
    req(rule.mode == MODE_PACE || rule.mode == MODE_BANDS, "mode must be 'pace' or 'bands'".into())?;
    req((1..=ceiling).contains(&rule.max_workers), format!("maxWorkers must be in 1..{ceiling} (setting limits.pacing.maxWorkersCeiling)"))?;
    req(rule.ok_projected > 0.0, "okProjected must be positive".into())?;
    req(rule.hard_stop_percent > 0.0 && rule.hard_stop_percent <= 100.0, "hardStopPercent must be in (0, 100]".into())?;
    req(!rule.tiers.is_empty(), "needs at least one tier".into())?;
    req(rule.tiers.last().is_some_and(|t| t.below.is_none()), "last tier must have no 'below' (it covers every percent left)".into())?;
    req(rule.tiers[..rule.tiers.len() - 1].iter().all(|t| t.below.is_some()), "only the last tier may omit 'below'".into())?;
    let bounds: Vec<f64> = rule.tiers.iter().filter_map(|t| t.below).collect();
    req(bounds.iter().all(|b| *b > 0.0 && *b <= 100.0), "'below' must be in (0, 100]".into())?;
    req(bounds.windows(2).all(|w| w[0] < w[1]), "'below' must increase from tier to tier".into())?;
    req(rule.tiers.iter().all(|t| (0..=ceiling).contains(&t.workers)), format!("'workers' must be in 0..{ceiling} (setting limits.pacing.maxWorkersCeiling)"))
}

pub fn parse_manual(v: &Value) -> Result<BTreeMap<String, ManualUsage>, String> {
    let map: BTreeMap<String, ManualUsage> =
        serde_json::from_value(v.clone()).map_err(|_| r#"must be {meter: {"percent": n, "resetsAt": epochMs?, "setAt": epochMs}}"#.to_string())?;
    for (k, m) in &map {
        if k.trim().is_empty() {
            return Err("meter name is blank".into());
        }
        if !(0.0..=100.0).contains(&m.percent) {
            return Err(format!("percent of '{k}' must be in 0..100"));
        }
    }
    Ok(map)
}

pub fn parse_overrides(v: &Value, ceiling: i32) -> Result<BTreeMap<String, UsageOverride>, String> {
    let map: BTreeMap<String, UsageOverride> =
        serde_json::from_value(v.clone()).map_err(|_| r#"must be {meter: {"workers": n|null, "until": epochMs}}"#.to_string())?;
    for (k, o) in &map {
        if k.trim().is_empty() {
            return Err("meter name is blank".into());
        }
        if o.workers.is_some_and(|w| !(0..=ceiling).contains(&w)) {
            return Err(format!("workers of '{k}' must be in 0..{ceiling} (setting limits.pacing.maxWorkersCeiling) or null"));
        }
    }
    Ok(map)
}

/// Whether one more ticket worker may start.
#[derive(Debug, Clone, PartialEq)]
pub enum PacingVerdict {
    Allow { warning: Option<String> },
    Over { reason: String },
}

type Source<T> = Arc<dyn Fn() -> T + Send + Sync>;

/// Builds the [`UsageReport`] and judges a start against it. Every input is read at call time, so a changed setting or
/// a fresh live read counts at once. `limits` must not block (the cached live read).
#[derive(Clone)]
pub struct UsagePacing {
    pub limits: Source<Vec<ProviderLimits>>,
    /// The readings [`UsagePacing::check`] judges; differs from `limits` only while a mock does not count for pacing.
    pub pacing_limits: Source<Vec<ProviderLimits>>,
    pub rule: Source<PacingRule>,
    pub manual: Source<BTreeMap<String, ManualUsage>>,
    pub overrides: Source<BTreeMap<String, UsageOverride>>,
    /// The meter of every live ticket worker (one entry per worker).
    pub worker_meters: Source<Vec<String>>,
    pub clock: Clock,
}

impl UsagePacing {
    pub fn report(&self) -> UsageReport {
        self.report_from(&(self.limits)())
    }

    pub fn report_from(&self, limits: &[ProviderLimits]) -> UsageReport {
        let now = (self.clock)();
        let live: HashMap<&str, &ProviderLimits> = limits.iter().map(|l| (l.provider.as_str(), l)).collect();
        let manual: BTreeMap<String, ManualUsage> = (self.manual)().into_iter().filter(|(_, m)| manual_expires_at(m) > now).collect();
        let overrides: BTreeMap<String, UsageOverride> = (self.overrides)().into_iter().filter(|(_, o)| o.until > now).collect();
        let mut running: HashMap<String, i32> = HashMap::new();
        for m in (self.worker_meters)() {
            *running.entry(m).or_default() += 1;
        }
        let rule = (self.rule)();
        let names: BTreeSet<String> = live.keys().map(|k| k.to_string()).chain(manual.keys().cloned()).chain(overrides.keys().cloned()).chain(running.keys().cloned()).collect();
        let meters = names
            .iter()
            .map(|m| meter_usage(m, live.get(m.as_str()).copied(), manual.get(m), overrides.get(m), running.get(m).copied().unwrap_or(0), &rule, now))
            .collect();
        UsageReport { meters, rule, observed_at: now }
    }

    /// Whether one more ticket worker may start on `meter` now.
    pub fn check(&self, meter: &str) -> PacingVerdict {
        let report = self.report_from(&(self.pacing_limits)());
        match report.meters.into_iter().find(|m| m.meter == meter) {
            None => PacingVerdict::Allow { warning: None },
            Some(m) if m.can_start => PacingVerdict::Allow { warning: m.warning },
            Some(m) => PacingVerdict::Over { reason: refusal(&m) },
        }
    }
}

fn meter_usage(meter: &str, live: Option<&ProviderLimits>, manual: Option<&ManualUsage>, ov: Option<&UsageOverride>, running: i32, rule: &PacingRule, now: i64) -> MeterUsage {
    let windows: Vec<LimitWindow> = live.map(|l| l.windows.clone()).unwrap_or_default();
    // The governing window allows the fewest workers; ties: the higher percent.
    let governing = windows
        .iter()
        .filter(|w| w.percent.is_some())
        .filter_map(|w| judge(rule, w, now).map(|j| (w, j)))
        .min_by(|(wa, ja), (wb, jb)| ja.allowance.cmp(&jb.allowance).then(wb.percent.unwrap_or(0.0).total_cmp(&wa.percent.unwrap_or(0.0))));
    let (source, percent, label, resets_at, pace) = match (&governing, manual) {
        (Some((w, _)), _) => ("live", w.percent, Some(w.label.clone()), w.resets_at, w.pace_delta),
        (None, Some(m)) => ("manual", Some(m.percent), Some("Manual entry".to_string()), m.resets_at, None),
        _ => ("none", None, None, None, None),
    };
    let judgement = governing.as_ref().map(|(_, j)| j);
    let rule_allowance = if !rule.enabled || percent.is_none() { None } else { judgement.map(|j| j.allowance).or_else(|| allowance(rule, percent)) };
    let reasoning = rule_allowance.map(|_| judgement.map(|j| j.reason.clone()).unwrap_or_else(|| "bands".into()));
    let allowance_now = if let Some(o) = ov { o.workers } else { rule_allowance };
    let stale = live.is_some_and(|l| l.stale);
    let warning = if ov.is_some() {
        None
    } else if percent.is_none() && rule.enabled {
        Some(format!("usage unknown{}: pacing off for {meter} (fail open)", live.and_then(|l| l.reason.as_ref()).map(|r| format!(" ({r})")).unwrap_or_default()))
    } else if governing.is_some() && stale && rule.enabled {
        let l = live.expect("stale implies live");
        Some(format!(
            "usage reading {} min old ({}): pacing on the last good reading for {meter}",
            (now - l.observed_at).max(0) / 60_000,
            l.reason.as_deref().unwrap_or("read failed")
        ))
    } else {
        None
    };
    MeterUsage {
        meter: meter.into(),
        percent,
        window: label,
        resets_at,
        pace_delta: pace,
        source: source.into(),
        windows: windows.clone(),
        reason: live.and_then(|l| l.reason.clone()),
        rule_allowance,
        r#override: ov.cloned(),
        allowance: allowance_now,
        running,
        can_start: allowance_now.map_or(true, |a| running < a),
        warning,
        updated_at: if governing.is_some() { live.map(|l| l.observed_at) } else { None },
        projected_percent: judgement.and_then(|j| j.projected),
        reasoning,
    }
}

/// The refusal an over-allowance start gets, with what to do about it.
pub fn refusal(m: &MeterUsage) -> String {
    let pct = m.percent.map(|p| format!("{}% {}", fmt_num(p), m.window.as_deref().unwrap_or("")).trim().to_string()).unwrap_or_else(|| "unknown usage".into());
    let pace = m.pace_delta.map(|d| format!(" ({}{} vs even pace)", if d >= 0.0 { "+" } else { "" }, fmt_num(d))).unwrap_or_default();
    let by = if m.r#override.is_some() { "override" } else { "rule" };
    let projected = if m.r#override.is_none() { m.projected_percent.map(|p| format!(", projected {}% at reset", fmt_num(p))).unwrap_or_default() } else { String::new() };
    let resets = m.resets_at.map(|r| format!("; resets {}", stamp(r))).unwrap_or_default();
    let allowance = m.allowance.map(|a| a.to_string()).unwrap_or_else(|| "null".into());
    format!(
        "usage pacing: {} at {pct}{pace}{projected}, allowance {allowance} ({by}), running {}{resets}. \
         Wait for a worker to finish, or ask the owner or the PM for an override (MCP infantry_usage_override {{\"meter\":\"{}\",\"value\":\"<n|none>\"}}; CLI infantry usage override {} <n|none>).",
        m.meter, m.running, m.meter, m.meter
    )
}

/// Plain-text rendering shared by `infantry usage` and `infantry_usage`, plus the UI lines.
pub mod text {
    use super::*;

    /// "pace (up to 5 ticket workers while projected <= 100% at reset, 1 from 95% used; ...)", the bands, or "off".
    pub fn rule_line(rule: &PacingRule) -> String {
        if !rule.enabled {
            "off".into()
        } else if rule.mode == MODE_PACE {
            format!(
                "pace (up to {} ticket workers while projected <= {}% at reset, 1 from {}% used; bands fallback without reset data)",
                rule.max_workers,
                fmt_num(rule.ok_projected),
                fmt_num(rule.hard_stop_percent)
            )
        } else {
            rule.tiers.iter().map(|t| format!("{}{}", t.below.map(|b| format!("<{}% -> ", fmt_num(b))).unwrap_or_else(|| "else -> ".into()), t.workers)).collect::<Vec<_>>().join(", ") + " ticket workers"
        }
    }

    pub fn render(report: &UsageReport) -> String {
        let now = report.observed_at;
        let mut out = format!("pacing rule: {}\n", rule_line(&report.rule));
        if report.meters.is_empty() {
            out.push_str("no meters: no live usage source and no ticket worker running\n");
        }
        for m in &report.meters {
            out.push_str(&format!("{}: ", m.meter));
            out.push_str(&m.percent.map(|p| format!("{}% used", fmt_num(p))).unwrap_or_else(|| "usage unknown".into()));
            if let Some(w) = &m.window {
                out.push_str(&format!(" ({w})"));
            }
            if let Some(d) = m.pace_delta {
                out.push_str(&format!(", {}{} vs even pace", if d >= 0.0 { "+" } else { "" }, fmt_num(d)));
            }
            if let Some(r) = m.resets_at {
                out.push_str(&format!(", resets in {}", until(r, now)));
            }
            if let Some(p) = m.projected_percent {
                out.push_str(&format!(", projected {}% at reset", fmt_num(p)));
            }
            out.push_str(&format!(" [{}] | running {}, allowance {}", m.source, m.running, m.allowance.map(|a| a.to_string()).unwrap_or_else(|| "no limit".into())));
            if let Some(o) = &m.r#override {
                out.push_str(&format!(" (override {}, expires in {})", o.workers.map(|w| w.to_string()).unwrap_or_else(|| "no limit".into()), until(o.until, now)));
            }
            out.push_str(if m.can_start { " | may start 1 more" } else { " | FULL: agent ticket starts refused" });
            out.push('\n');
            if m.windows.len() > 1 {
                for w in &m.windows {
                    out.push_str(&format!(
                        "    {}: {}{}\n",
                        w.label,
                        w.percent.map(|p| format!("{}%", fmt_num(p))).unwrap_or_else(|| "?".into()),
                        w.resets_at.map(|r| format!(", resets in {}", until(r, now))).unwrap_or_default()
                    ));
                }
            }
            if let Some(w) = &m.warning {
                out.push_str(&format!("    warning: {w}\n"));
            }
        }
        out.trim_end().to_string()
    }

    /// "allowance raised to no limit · ends in 38h 45m (pacing rule says 1)"; `None` without an override.
    pub fn override_line(m: &MeterUsage, now: i64) -> Option<String> {
        let o = m.r#override.as_ref()?;
        Some(format!(
            "allowance raised to {} · ends in {} (pacing rule says {})",
            o.workers.map(|w| w.to_string()).unwrap_or_else(|| "no limit".into()),
            until(o.until, now),
            m.rule_allowance.map(|w| w.to_string()).unwrap_or_else(|| "no limit".into())
        ))
    }

    /// "12 pts under pace", "10 pts ahead of pace", "on pace".
    pub fn pace_words(delta: Option<f64>) -> Option<String> {
        let d = delta?;
        let pts = d.abs().round() as i64;
        Some(if pts == 0 { "on pace".into() } else if d > 0.0 { format!("{pts} pts ahead of pace") } else { format!("{pts} pts under pace") })
    }

    /// `60:3,80:2,1` -> a bands rule; `off` / `on` toggle `current`; `default` resets; `pace` / `pace:N` = pace mode.
    pub fn parse_rule_text(text: &str, current: &PacingRule) -> Result<PacingRule, String> {
        let t = text.trim().to_lowercase();
        let rule = match t.as_str() {
            "off" => PacingRule { enabled: false, ..current.clone() },
            "on" => PacingRule { enabled: true, ..current.clone() },
            "default" => PacingRule::default(),
            "pace" => PacingRule { enabled: true, mode: MODE_PACE.into(), ..current.clone() },
            _ if t.starts_with("pace:") => {
                let n = t["pace:".len()..].trim().parse().map_err(|_| format!("bad max workers in '{t}'"))?;
                PacingRule { enabled: true, mode: MODE_PACE.into(), max_workers: n, ..current.clone() }
            }
            _ => {
                let mut tiers = Vec::new();
                for part in t.split(',') {
                    let bits: Vec<&str> = part.trim().split(':').collect();
                    tiers.push(match bits.as_slice() {
                        [w] => PacingTier { below: None, workers: w.trim().parse().map_err(|_| format!("bad tier '{part}'"))? },
                        [b, w] => PacingTier {
                            below: Some(b.trim().trim_end_matches('%').parse().map_err(|_| format!("bad percent in '{part}'"))?),
                            workers: w.trim().parse().map_err(|_| format!("bad workers in '{part}'"))?,
                        },
                        _ => return Err(format!("bad tier '{part}' (want below:workers, the last one workers alone)")),
                    });
                }
                PacingRule { enabled: true, mode: MODE_BANDS.into(), tiers, ..PacingRule::default() }
            }
        };
        validate_rule(&rule, i32::MAX)?;
        Ok(rule)
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    const H: i64 = 3_600_000;

    fn w(label: &str, minutes: i32, percent: f64, resets_in: i64, now: i64) -> LimitWindow {
        let resets = now + resets_in;
        LimitWindow {
            provider: "claude".into(),
            label: label.into(),
            window_minutes: minutes,
            percent: Some(percent),
            resets_at: Some(resets),
            observed_at: now,
            source: "live".into(),
            pace_delta: crate::limits::pace_delta(Some(percent), Some(resets), minutes, now),
        }
    }

    #[test]
    fn pace_judge_scales_with_the_projection_and_hard_stops() {
        let rule = PacingRule::default();
        // half the week gone, 40% used: projected 80% -> the max (5)
        assert_eq!(judge(&rule, &w("Current week", 10_080, 40.0, 84 * H, 0), 0).unwrap().allowance, 5);
        // half gone, 55% used: projected 110% -> 5 - ceil(10/20*4) = 3
        let j = judge(&rule, &w("Current week", 10_080, 55.0, 84 * H, 0), 0).unwrap();
        assert_eq!((j.allowance, j.projected), (3, Some(110.0)));
        assert_eq!(judge(&rule, &w("Current week", 10_080, 96.0, 1, 0), 0).unwrap().reason, "at 96% (hard stop 95%)");
        let bands = PacingRule { mode: MODE_BANDS.into(), ..rule };
        assert_eq!(judge(&bands, &w("x", 300, 70.0, H, 0), 0).unwrap().allowance, 2);
    }

    #[test]
    fn report_takes_the_tightest_window_overrides_win_and_unknown_fails_open() {
        let now = 1_000 * H;
        let live = vec![ProviderLimits {
            provider: "claude".into(),
            status: "ok".into(),
            windows: vec![w("Current session", 300, 10.0, 4 * H, now), w("Current week", 10_080, 85.0, 120 * H, now)],
            reason: None,
            observed_at: now,
            stale: false,
            retry_at: None,
        }];
        let unknown = vec![ProviderLimits::unknown("opencode-go", now, "no_credentials")];
        let all: Vec<ProviderLimits> = live.into_iter().chain(unknown).collect();
        let overrides = Arc::new(std::sync::Mutex::new(BTreeMap::new()));
        let ov = overrides.clone();
        let p = UsagePacing {
            limits: Arc::new(move || all.clone()),
            pacing_limits: Arc::new(Vec::new),
            rule: Arc::new(PacingRule::default),
            manual: Arc::new(BTreeMap::new),
            overrides: Arc::new(move || ov.lock().unwrap().clone()),
            worker_meters: Arc::new(|| vec!["claude".into(), "claude".into()]),
            clock: Arc::new(move || now),
        };
        let r = p.report();
        let c = &r.meters[0];
        assert_eq!((c.window.as_deref(), c.allowance, c.running, c.can_start), (Some("Current week"), Some(1), 2, false));
        let o = &r.meters[1];
        assert!(o.can_start && o.warning.as_deref().unwrap().contains("fail open"));
        assert!(refusal(c).contains("allowance 1 (rule), running 2"));
        overrides.lock().unwrap().insert("claude".to_string(), UsageOverride { workers: None, until: now + 2 * H });
        let c = p.report().meters.remove(0);
        assert!(c.can_start && c.allowance.is_none());
        assert_eq!(text::override_line(&c, now).unwrap(), "allowance raised to no limit · ends in 2h 0m (pacing rule says 1)");
        assert!(text::render(&p.report()).contains("claude: 85% used (Current week)"));
    }

    #[test]
    fn rule_text_and_validation() {
        let r = text::parse_rule_text("60:3, 80:2, 1", &PacingRule::default()).unwrap();
        assert_eq!((r.mode.as_str(), r.tiers.len()), ("bands", 3));
        assert!(text::parse_rule_text("80:2, 60:3, 1", &PacingRule::default()).is_err());
        assert_eq!(text::parse_rule_text("pace:3", &PacingRule::default()).unwrap().max_workers, 3);
        assert!(parse_rule(&serde_json::json!({"maxWorkers": 99}), 50).unwrap_err().contains("1..50"));
        assert_eq!(meter_for("pi", Some("anthropic/claude-opus")), "claude");
        assert_eq!(meter_for("opencode", Some("opencode-go/kimi")), "opencode-go");
        assert_eq!(meter_for("Codex", None), "codex");
    }
}
