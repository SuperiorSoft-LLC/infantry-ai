// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
//! The Pace history and forecast: every plan-window reading sampled into `samples.jsonl` (one JSON line per sample,
//! the same line format the Kotlin Pace plugin wrote), then turned into the `pace_report` answer: per provider window
//! the points (with the window segment each belongs to, so a reset breaks the line), the momentum over two look-backs
//! and the forecast of where the window lands at reset. Provider-generic: nothing here names a provider.
use std::collections::BTreeMap;
use std::io::Write;
use std::path::PathBuf;
use std::sync::Mutex;

use infantry_protocol::usage::UsageReport;
use serde_json::{json, Map, Value};

use crate::util::fmt_num;

pub const HOUR_MS: f64 = 3_600_000.0;
/// `resetsAt` moving by more than this is a new window (providers jitter their reset time by a few minutes).
pub const RESET_TOLERANCE_MS: i64 = 30 * 60_000;
/// A percent falling by more than this inside one window is a reset the provider did not announce.
pub const DROP_POINTS: f64 = 5.0;
pub const MIN_ELAPSED: f64 = 0.10;
pub const RAW_CAP: usize = 200;
pub const DEFAULT_MAX_POINTS: usize = 400;
pub const SAMPLE_EVERY_MS: i64 = 60_000;
pub const PRUNE_EVERY_MS: i64 = 3_600_000;

#[derive(Debug, Clone, PartialEq)]
pub struct PaceSample {
    pub provider: String,
    pub window: String,
    pub observed_at: i64,
    pub percent: f64,
    pub pace_delta: Option<f64>,
    pub projected_percent: Option<f64>,
    pub resets_at: Option<i64>,
    pub window_minutes: i32,
    pub prev_percent: Option<f64>,
    pub delta_percent: Option<f64>,
    /// Per-session tokens since the previous sample (`k`), kept as written.
    pub tokens: Option<Value>,
}

/// The tunables (setting keys `pace.*`).
#[derive(Debug, Clone, Copy, PartialEq)]
pub struct PaceSettings {
    pub retention_days: i32,
    pub lookback_short_hours: f64,
    pub lookback_long_hours: f64,
    pub dead_band: f64,
    pub min_samples: usize,
    pub min_span_hours: f64,
    pub burn_window_hours: f64,
}

impl Default for PaceSettings {
    fn default() -> Self {
        PaceSettings { retention_days: 60, lookback_short_hours: 6.0, lookback_long_hours: 24.0, dead_band: 0.25, min_samples: 3, min_span_hours: 2.0, burn_window_hours: 1.0 }
    }
}

impl PaceSettings {
    /// Each key read through `get` (missing / unparsable = the default).
    pub fn from(get: impl Fn(&str) -> Option<String>) -> Self {
        let d = PaceSettings::default();
        let num = |k: &str| get(k).and_then(|v| v.trim().parse::<f64>().ok());
        PaceSettings {
            retention_days: num("pace.retentionDays").map(|n| (n as i32).max(0)).unwrap_or(d.retention_days),
            lookback_short_hours: num("pace.lookbackShortHours").filter(|n| *n > 0.0).unwrap_or(d.lookback_short_hours),
            lookback_long_hours: num("pace.lookbackLongHours").filter(|n| *n > 0.0).unwrap_or(d.lookback_long_hours),
            dead_band: num("pace.deadBand").filter(|n| *n >= 0.0).unwrap_or(d.dead_band),
            min_samples: num("pace.minSamples").map(|n| (n as usize).max(2)).unwrap_or(d.min_samples),
            min_span_hours: num("pace.minSpanHours").filter(|n| *n >= 0.0).unwrap_or(d.min_span_hours),
            burn_window_hours: num("pace.burnWindowHours").filter(|n| *n > 0.0).unwrap_or(d.burn_window_hours),
        }
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Momentum {
    Rising,
    Flat,
    Falling,
    Unknown,
}

impl Momentum {
    pub fn name(self) -> &'static str {
        match self {
            Momentum::Rising => "RISING",
            Momentum::Flat => "FLAT",
            Momentum::Falling => "FALLING",
            Momentum::Unknown => "UNKNOWN",
        }
    }
}

#[derive(Debug, Clone, PartialEq)]
pub struct MomentumReading {
    pub lookback_hours: f64,
    pub samples: usize,
    pub pace_delta_slope: Option<f64>,
    pub projected_slope: Option<f64>,
    pub momentum: Momentum,
}

#[derive(Debug, Clone, PartialEq)]
pub struct RateLine {
    pub label: String,
    pub rate_per_hour: f64,
    pub landing_percent: f64,
    pub hours_to_cross: Option<f64>,
    pub stays_under: bool,
}

#[derive(Debug, Clone, PartialEq)]
pub struct Forecast {
    pub ready: bool,
    pub reason: Option<String>,
    pub current: Option<RateLine>,
    pub recent: Option<RateLine>,
    pub hours_left: Option<f64>,
}

impl Forecast {
    fn not_ready(why: impl Into<String>) -> Self {
        Forecast { ready: false, reason: Some(why.into()), current: None, recent: None, hours_left: None }
    }
    fn landings(&self) -> Vec<f64> {
        self.current.iter().chain(self.recent.iter()).map(|l| l.landing_percent).collect()
    }
    pub fn band_low(&self) -> Option<f64> {
        self.landings().into_iter().reduce(f64::min)
    }
    pub fn band_high(&self) -> Option<f64> {
        self.landings().into_iter().reduce(f64::max)
    }
}

/// Splits one series into windows (oldest first) at a moved reset or a drop of more than [`DROP_POINTS`].
pub fn split_at_resets(series: &[PaceSample]) -> Vec<Vec<PaceSample>> {
    let mut sorted = series.to_vec();
    sorted.sort_by_key(|s| s.observed_at);
    let mut out: Vec<Vec<PaceSample>> = Vec::new();
    let mut cur: Vec<PaceSample> = Vec::new();
    for s in sorted {
        if let Some(p) = cur.last() {
            let moved = matches!((p.resets_at, s.resets_at), (Some(a), Some(b)) if (a - b).abs() > RESET_TOLERANCE_MS);
            if moved || p.percent - s.percent > DROP_POINTS {
                out.push(std::mem::take(&mut cur));
            }
        }
        cur.push(s);
    }
    if !cur.is_empty() {
        out.push(cur);
    }
    out
}

pub fn active(series: &[PaceSample]) -> Vec<PaceSample> {
    split_at_resets(series).pop().unwrap_or_default()
}

/// Least-squares slope of y against time in hours; `None` with fewer than 2 points or no time spread.
pub fn slope(points: &[(i64, f64)]) -> Option<f64> {
    if points.len() < 2 {
        return None;
    }
    let t0 = points[0].0;
    let xs: Vec<f64> = points.iter().map(|p| (p.0 - t0) as f64 / HOUR_MS).collect();
    let n = xs.len() as f64;
    let mx = xs.iter().sum::<f64>() / n;
    let my = points.iter().map(|p| p.1).sum::<f64>() / n;
    let sxx: f64 = xs.iter().map(|x| (x - mx).powi(2)).sum();
    if sxx < 1e-12 {
        return None;
    }
    Some(xs.iter().zip(points).map(|(x, p)| (x - mx) * (p.1 - my)).sum::<f64>() / sxx)
}

/// Burn rate (points per hour) at each sample of one segment, against the newest sample a rolling window before it.
pub fn burn_rates(segment: &[PaceSample], window_hours: f64) -> Vec<(i64, f64)> {
    let cutoff = (window_hours * HOUR_MS) as i64;
    segment
        .iter()
        .filter_map(|p| {
            let r = segment.iter().rev().find(|r| r.observed_at <= p.observed_at - cutoff)?;
            let hours = (p.observed_at - r.observed_at) as f64 / HOUR_MS;
            (hours > 0.0).then(|| (p.observed_at, (p.percent - r.percent) / hours))
        })
        .collect()
}

fn within(seg: &[PaceSample], now: i64, hours: f64) -> Vec<PaceSample> {
    let from = now - (hours * HOUR_MS) as i64;
    seg.iter().filter(|s| s.observed_at >= from && s.observed_at <= now).cloned().collect()
}

pub fn enough(pts: &[PaceSample], s: &PaceSettings) -> bool {
    pts.len() >= s.min_samples && pts.len() >= 1 && (pts[pts.len() - 1].observed_at - pts[0].observed_at) as f64 >= s.min_span_hours * HOUR_MS - 1.0
}

fn classify(slope: Option<f64>, dead_band: f64) -> Momentum {
    match slope {
        None => Momentum::Unknown,
        Some(v) if v > dead_band => Momentum::Rising,
        Some(v) if v < -dead_band => Momentum::Falling,
        Some(_) => Momentum::Flat,
    }
}

/// Momentum over one look-back of the ACTIVE window (a reset is never inside the fit); follows the projected landing.
pub fn momentum(series: &[PaceSample], now: i64, lookback_hours: f64, s: &PaceSettings) -> MomentumReading {
    let pts = within(&active(series), now, lookback_hours);
    if !enough(&pts, s) {
        return MomentumReading { lookback_hours, samples: pts.len(), pace_delta_slope: None, projected_slope: None, momentum: Momentum::Unknown };
    }
    let pace = slope(&pts.iter().filter_map(|p| p.pace_delta.map(|d| (p.observed_at, d))).collect::<Vec<_>>());
    let proj = slope(&pts.iter().filter_map(|p| p.projected_percent.map(|d| (p.observed_at, d))).collect::<Vec<_>>());
    MomentumReading { lookback_hours, samples: pts.len(), pace_delta_slope: pace, projected_slope: proj, momentum: classify(proj.or(pace), s.dead_band) }
}

fn line(label: String, percent: f64, rate: f64, landing: f64) -> RateLine {
    let cross = if percent >= 100.0 {
        Some(0.0)
    } else if landing > 100.0 && rate > 0.0 {
        Some((100.0 - percent) / rate)
    } else {
        None
    };
    RateLine { label, rate_per_hour: rate, landing_percent: landing, hours_to_cross: cross, stays_under: cross.is_none() }
}

/// The two landings of the active window: at the average burn since the window start, and at the recent burn.
pub fn forecast(series: &[PaceSample], window_minutes: i32, now: i64, s: &PaceSettings) -> Forecast {
    let seg = active(series);
    let Some(last) = seg.last() else { return Forecast::not_ready("no samples yet") };
    let Some(resets) = last.resets_at else { return Forecast::not_ready("the provider gave no reset time") };
    if window_minutes <= 0 {
        return Forecast::not_ready("unknown window length");
    }
    let pts = within(&seg, now, s.lookback_short_hours);
    if !enough(&pts, s) {
        return Forecast::not_ready(format!("not enough data yet (needs {} samples over {}h, has {})", s.min_samples, fmt_num(s.min_span_hours), pts.len()));
    }
    let window_ms = window_minutes as f64 * 60_000.0;
    let hours_left = ((resets - now) as f64 / HOUR_MS).max(0.0);
    let elapsed = (1.0 - (resets - now) as f64 / window_ms).clamp(MIN_ELAPSED, 1.0);
    let percent = last.percent;
    let avg = percent / (elapsed * window_ms / HOUR_MS);
    let current = line("current rate".into(), percent, avg, percent / elapsed);
    let recent_rate = slope(&pts.iter().map(|p| (p.observed_at, p.percent)).collect::<Vec<_>>()).unwrap_or(0.0).max(0.0);
    let recent = line(format!("last {}h rate", fmt_num(s.lookback_short_hours)), percent, recent_rate, percent + recent_rate * hours_left);
    Forecast { ready: true, reason: None, current: Some(current), recent: Some(recent), hours_left: Some(hours_left) }
}

/// "at the current rate you land at 107%; at the last 6h rate, 96% (falling); stays under 100%".
pub fn sentence(f: &Forecast, m: Momentum) -> String {
    let (Some(c), Some(r), true) = (&f.current, &f.recent, f.ready) else { return f.reason.clone().unwrap_or_else(|| "not enough data yet".into()) };
    let mm = if m == Momentum::Unknown { String::new() } else { format!(" ({})", m.name().to_lowercase()) };
    let cross = match (c.hours_to_cross, r.hours_to_cross) {
        (Some(a), Some(b)) => format!("; you cross 100% in {}h", fmt_num(crate::util::round1(a.min(b)))),
        (None, Some(b)) => format!("; at the recent rate you cross 100% in {}h", fmt_num(crate::util::round1(b))),
        (Some(a), None) => format!("; at the current rate you cross 100% in {}h", fmt_num(crate::util::round1(a))),
        _ => "; stays under 100%".into(),
    };
    format!(
        "at the current rate you land at {}%; at the {} rate, {}%{mm}{cross}",
        c.landing_percent.round() as i64,
        r.label.trim_end_matches(" rate"),
        r.landing_percent.round() as i64
    )
}

/// At most `max` points, evenly spaced, keeping the first and the newest.
pub fn thin(points: &[PaceSample], max: usize) -> Vec<PaceSample> {
    if max < 2 || points.len() <= max {
        return points.to_vec();
    }
    let step = (points.len() - 1) as f64 / (max - 1) as f64;
    let mut idx: Vec<usize> = (0..max).map(|i| ((i as f64 * step).round() as usize).min(points.len() - 1)).collect();
    idx.dedup();
    idx.into_iter().map(|i| points[i].clone()).collect()
}

/// The history file, held in memory per (provider, window) after load. Thread-safe.
pub struct PaceStore {
    file: PathBuf,
    series: Mutex<BTreeMap<(String, String), Vec<PaceSample>>>,
}

fn opt<T: Into<Value>>(v: Option<T>) -> Value {
    v.map(Into::into).unwrap_or(Value::Null)
}

pub fn encode(s: &PaceSample) -> String {
    let mut o = Map::new();
    o.insert("p".into(), json!(s.provider));
    o.insert("w".into(), json!(s.window));
    o.insert("t".into(), json!(s.observed_at));
    o.insert("u".into(), json!(s.percent));
    o.insert("d".into(), opt(s.pace_delta));
    o.insert("j".into(), opt(s.projected_percent));
    o.insert("r".into(), opt(s.resets_at));
    o.insert("m".into(), json!(s.window_minutes));
    if let Some(v) = s.prev_percent {
        o.insert("pp".into(), json!(v));
    }
    if let Some(v) = s.delta_percent {
        o.insert("dp".into(), json!(v));
    }
    if let Some(k) = &s.tokens {
        o.insert("k".into(), k.clone());
    }
    Value::Object(o).to_string()
}

/// A torn or foreign line is `None`, never fatal.
pub fn decode(line: &str) -> Option<PaceSample> {
    let o: Value = serde_json::from_str(line.trim()).ok()?;
    Some(PaceSample {
        provider: o.get("p")?.as_str()?.into(),
        window: o.get("w")?.as_str()?.into(),
        observed_at: o.get("t")?.as_i64()?,
        percent: o.get("u")?.as_f64()?,
        pace_delta: o.get("d").and_then(Value::as_f64),
        projected_percent: o.get("j").and_then(Value::as_f64),
        resets_at: o.get("r").and_then(Value::as_i64),
        window_minutes: o.get("m").and_then(Value::as_i64).unwrap_or(0) as i32,
        prev_percent: o.get("pp").and_then(Value::as_f64),
        delta_percent: o.get("dp").and_then(Value::as_f64),
        tokens: o.get("k").filter(|k| k.is_array()).cloned(),
    })
}

impl PaceStore {
    pub fn open(file: impl Into<PathBuf>) -> Self {
        let file = file.into();
        let mut series: BTreeMap<(String, String), Vec<PaceSample>> = BTreeMap::new();
        if let Ok(text) = std::fs::read_to_string(&file) {
            for s in text.lines().filter_map(decode) {
                series.entry((s.provider.clone(), s.window.clone())).or_default().push(s);
            }
        }
        for l in series.values_mut() {
            l.sort_by_key(|s| s.observed_at);
        }
        PaceStore { file, series: Mutex::new(series) }
    }

    fn lock(&self) -> std::sync::MutexGuard<'_, BTreeMap<(String, String), Vec<PaceSample>>> {
        self.series.lock().unwrap_or_else(|p| p.into_inner())
    }

    /// Appends a reading unless it has no percent, repeats the newest sample (the cached read has not refreshed) or is
    /// older than it. Returns whether it was kept.
    #[allow(clippy::too_many_arguments)]
    pub fn record(&self, provider: &str, window: &str, observed_at: i64, percent: Option<f64>, pace_delta: Option<f64>, projected: Option<f64>, resets_at: Option<i64>, window_minutes: i32) -> bool {
        let Some(percent) = percent else { return false };
        let mut series = self.lock();
        let list = series.entry((provider.into(), window.into())).or_default();
        let last = list.last();
        if let Some(l) = last {
            if (l.observed_at == observed_at && l.percent == percent && l.resets_at == resets_at) || observed_at < l.observed_at {
                return false;
            }
        }
        let s = PaceSample {
            provider: provider.into(),
            window: window.into(),
            observed_at,
            percent,
            pace_delta,
            projected_percent: projected,
            resets_at,
            window_minutes,
            prev_percent: last.map(|l| l.percent),
            delta_percent: last.map(|l| percent - l.percent),
            tokens: None,
        };
        let line = encode(&s);
        list.push(s);
        if let Some(d) = self.file.parent() {
            let _ = std::fs::create_dir_all(d);
        }
        if let Ok(mut f) = std::fs::OpenOptions::new().create(true).append(true).open(&self.file) {
            let _ = writeln!(f, "{line}");
        }
        true
    }

    /// Samples every meter window of a report (the governing projection rides on the governing window only).
    pub fn record_report(&self, report: &UsageReport) -> usize {
        let mut kept = 0;
        for m in &report.meters {
            for w in &m.windows {
                let projected = if m.window.as_deref() == Some(w.label.as_str()) { m.projected_percent } else { None };
                if self.record(&m.meter, &w.label, w.observed_at, w.percent, w.pace_delta, projected, w.resets_at, w.window_minutes) {
                    kept += 1;
                }
            }
        }
        kept
    }

    /// Drops samples older than `retention_days` (0 = keep all) and rewrites the file when something went.
    pub fn prune(&self, retention_days: i32, now: i64) -> usize {
        if retention_days <= 0 {
            return 0;
        }
        let cutoff = now - retention_days as i64 * 24 * 3_600_000;
        let mut series = self.lock();
        let mut dropped = 0;
        for l in series.values_mut() {
            let before = l.len();
            l.retain(|s| s.observed_at >= cutoff);
            dropped += before - l.len();
        }
        series.retain(|_, l| !l.is_empty());
        if dropped > 0 {
            let mut all: Vec<&PaceSample> = series.values().flatten().collect();
            all.sort_by_key(|s| s.observed_at);
            let text: String = all.iter().map(|s| encode(s) + "\n").collect();
            let tmp = self.file.with_extension("jsonl.tmp");
            if std::fs::write(&tmp, text).is_ok() {
                let _ = std::fs::rename(&tmp, &self.file);
            }
        }
        dropped
    }

    pub fn keys(&self) -> Vec<(String, String)> {
        self.lock().keys().cloned().collect()
    }

    pub fn samples(&self, provider: &str, window: &str) -> Vec<PaceSample> {
        self.lock().get(&(provider.to_string(), window.to_string())).cloned().unwrap_or_default()
    }

    pub fn count(&self) -> usize {
        self.lock().values().map(Vec::len).sum()
    }
}

/// What a `pace_get` asks for; every field optional.
#[derive(Debug, Clone, PartialEq)]
pub struct PaceQuery {
    pub provider: Option<String>,
    pub window: Option<String>,
    pub since_ms: i64,
    pub max_points: usize,
    pub include_samples: bool,
}

impl PaceQuery {
    /// From the frame's JSON body: `provider`, `window`, `sinceMs` or `sinceHours`, `samples`, `maxPoints`.
    pub fn parse(body: &str, now: i64) -> PaceQuery {
        let v: Value = serde_json::from_str(body).unwrap_or(Value::Null);
        let s = |k: &str| v.get(k).and_then(Value::as_str).map(str::trim).filter(|s| !s.is_empty()).map(str::to_owned);
        let since = v
            .get("sinceMs")
            .and_then(Value::as_i64)
            .or_else(|| v.get("sinceHours").and_then(Value::as_f64).filter(|h| *h > 0.0).map(|h| now - (h * HOUR_MS) as i64))
            .unwrap_or(0);
        PaceQuery {
            provider: s("provider"),
            window: s("window"),
            since_ms: since,
            max_points: v.get("maxPoints").and_then(Value::as_u64).map(|n| n as usize).filter(|n| *n >= 2).unwrap_or(DEFAULT_MAX_POINTS),
            include_samples: v.get("samples").and_then(Value::as_bool).unwrap_or(false),
        }
    }
}

fn momentum_json(m: &MomentumReading) -> Value {
    json!({"lookbackHours": m.lookback_hours, "samples": m.samples, "paceDeltaSlope": m.pace_delta_slope, "projectedSlope": m.projected_slope, "momentum": m.momentum.name()})
}

fn line_json(l: &Option<RateLine>) -> Value {
    match l {
        None => Value::Null,
        Some(l) => json!({"label": l.label, "ratePerHour": l.rate_per_hour, "landingPercent": l.landing_percent, "hoursToCross": l.hours_to_cross, "staysUnder": l.stays_under}),
    }
}

/// The `pace_report` body.
pub fn report(store: &PaceStore, s: &PaceSettings, q: &PaceQuery, now: i64) -> Value {
    let eq = |a: &str, b: &Option<String>| b.as_deref().map_or(true, |b| a.eq_ignore_ascii_case(b));
    let mut series = Vec::new();
    let mut text = Vec::new();
    for (p, w) in store.keys().into_iter().filter(|(p, w)| eq(p, &q.provider) && eq(w, &q.window)) {
        let all = store.samples(&p, &w);
        let shown: Vec<PaceSample> = all.iter().filter(|x| x.observed_at >= q.since_ms).cloned().collect();
        let latest = all.last();
        let minutes = all.iter().rev().find(|x| x.window_minutes > 0).map_or(0, |x| x.window_minutes);
        let short = momentum(&all, now, s.lookback_short_hours, s);
        let long = momentum(&all, now, s.lookback_long_hours, s);
        let headline = if short.momentum != Momentum::Unknown { short.momentum } else { long.momentum };
        let f = forecast(&all, minutes, now, s);
        let sent = sentence(&f, headline);
        let mut seg_of: BTreeMap<i64, usize> = BTreeMap::new();
        let mut burn_of: BTreeMap<i64, f64> = BTreeMap::new();
        for (i, seg) in split_at_resets(&all).iter().enumerate() {
            for x in seg {
                seg_of.insert(x.observed_at, i);
            }
            for (t, b) in burn_rates(seg, s.burn_window_hours) {
                burn_of.insert(t, b);
            }
        }
        let points: Vec<Value> = thin(&shown, q.max_points)
            .iter()
            .map(|x| json!({"t": x.observed_at, "u": x.percent, "d": x.pace_delta, "j": x.projected_percent, "s": seg_of.get(&x.observed_at).copied().unwrap_or(0), "b": burn_of.get(&x.observed_at)}))
            .collect();
        let mut obj = json!({
            "provider": p, "window": w, "windowMinutes": minutes, "samples": all.len(),
            "resetsAt": latest.and_then(|l| l.resets_at),
            "latest": latest.map(|l| json!({"t": l.observed_at, "percent": l.percent, "paceDelta": l.pace_delta, "projectedPercent": l.projected_percent})),
            "points": points,
            "momentum": headline.name(),
            "momentumShort": momentum_json(&short),
            "momentumLong": momentum_json(&long),
            "forecast": {
                "ready": f.ready, "reason": f.reason, "sentence": sent, "hoursLeft": f.hours_left,
                "current": line_json(&f.current), "recent": line_json(&f.recent), "bandLow": f.band_low(), "bandHigh": f.band_high(),
            },
        });
        if q.include_samples {
            let skip = shown.len().saturating_sub(RAW_CAP);
            obj["rawSamples"] = shown[skip..]
                .iter()
                .map(|x| json!({"t": x.observed_at, "percent": x.percent, "prev": x.prev_percent, "delta": x.delta_percent, "tokens": x.tokens.clone().unwrap_or(json!([]))}))
                .collect();
        }
        text.push(format!(
            "{p} / {w}: {}{}, momentum {} ({} samples)\n    {sent}",
            latest.map(|l| format!("{}% used", fmt_num(l.percent))).unwrap_or_else(|| "no reading".into()),
            latest.and_then(|l| l.pace_delta).map(|d| format!(", {}{} vs even pace", if d >= 0.0 { "+" } else { "" }, fmt_num(d))).unwrap_or_default(),
            headline.name(),
            all.len()
        ));
        series.push(obj);
    }
    let text = if text.is_empty() { "no pacing history recorded yet (the recorder samples the plan meters every minute)".to_string() } else { text.join("\n") };
    json!({
        "generatedAt": now, "retentionDays": s.retention_days, "lookbackShortHours": s.lookback_short_hours,
        "lookbackLongHours": s.lookback_long_hours, "burnWindowHours": s.burn_window_hours, "deadBand": s.dead_band,
        "series": series, "text": text,
    })
}

#[cfg(test)]
mod tests {
    use super::*;

    const H: i64 = 3_600_000;

    #[test]
    fn store_roundtrips_splits_at_resets_and_forecasts() {
        let f = std::env::temp_dir().join(format!("infantry-pace-{}.jsonl", std::process::id()));
        let _ = std::fs::remove_file(&f);
        let st = PaceStore::open(&f);
        let resets = 100 * H;
        // a 10h window, 50% gone at t=95h; 10 points an hour burn
        for i in 0..5 {
            assert!(st.record("claude", "Current session", 92 * H + i * H / 2, Some(20.0 + 5.0 * i as f64), None, None, Some(resets), 600));
        }
        assert!(!st.record("claude", "Current session", 92 * H, Some(20.0), None, None, Some(resets), 600), "older than the newest");
        let reopened = PaceStore::open(&f);
        assert_eq!(reopened.count(), 5);
        let all = reopened.samples("claude", "Current session");
        assert_eq!(all[1].delta_percent, Some(5.0));
        let fc = forecast(&all, 600, 94 * H, &PaceSettings::default());
        assert!(fc.ready);
        // 40% used at 40% elapsed -> lands at 100; recent 10/h * 6h left -> 100
        assert!((fc.current.as_ref().unwrap().landing_percent - 100.0).abs() < 1e-6);
        assert!((fc.recent.as_ref().unwrap().landing_percent - 100.0).abs() < 1e-6);
        let mut more = all.clone();
        more.push(PaceSample { observed_at: 101 * H, percent: 1.0, resets_at: Some(110 * H), ..all[0].clone() });
        assert_eq!(split_at_resets(&more).len(), 2);
        let r = report(&reopened, &PaceSettings::default(), &PaceQuery::parse(r#"{"maxPoints":3}"#, 94 * H), 94 * H);
        assert_eq!(r["series"][0]["points"].as_array().unwrap().len(), 3);
        let _ = std::fs::remove_file(&f);
    }
}
