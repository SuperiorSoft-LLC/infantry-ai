// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
//! USAGE-FIT: what drives the plan meter. Pure computation over the pace samples (every percent step of a window) and
//! the recorded model requests. No model calls, no writes.
//!
//! A STEP is a sample whose percent is above the previous step's (inside one window; a reset starts a new one). Its
//! INTERVAL runs from the previous step's time to its own: the meter lags, so only whole between-step intervals count.
//! The tokens of the requests inside the interval, divided by the points the step gained, are its tokens per point.

use crate::meter::RequestRow;
use crate::pace::{PaceSample, PaceStore};
use serde_json::{json, Map, Value};
use std::collections::BTreeMap;

pub const MIN_FIT_STEPS: usize = 5;
pub const MAX_SERIES: usize = 400;
pub const DEFAULT_MIN_STEPS: usize = 8;
pub const LOOKBACK_DAYS: i64 = 90;
pub const TTL_MS: i64 = 30_000;
/// The most requests one analysis (or one pace pass) reads.
pub const MAX_REQUESTS: i64 = 500_000;
const RESET_JUMP_MS: i64 = 5 * 60_000;

/// One request narrowed to the meter analysed; an unknown token count is 0.
#[derive(Debug, Clone, PartialEq)]
pub struct FitRequest {
    pub ts: i64,
    pub role: String,
    pub model: String,
    pub input: i64,
    pub cache_read: i64,
    pub cache_write: i64,
    pub output: i64,
}

impl From<RequestRow> for FitRequest {
    fn from(r: RequestRow) -> Self {
        FitRequest {
            ts: r.ts,
            role: r.role,
            model: r.model.unwrap_or_else(|| "unknown".into()),
            input: r.input.unwrap_or(0),
            cache_read: r.cache_read.unwrap_or(0),
            cache_write: r.cache_write.unwrap_or(0),
            output: r.output.unwrap_or(0),
        }
    }
}

#[derive(Debug, Clone)]
pub struct FitStep {
    pub from_ms: i64,
    pub to_ms: i64,
    pub points: f64,
    pub requests: usize,
    pub input: i64,
    pub cache_read: i64,
    pub cache_write: i64,
    pub output: i64,
    pub cache_read_by_role: BTreeMap<String, i64>,
    pub cache_read_by_model: BTreeMap<String, i64>,
    pub output_by_role: BTreeMap<String, i64>,
}

impl FitStep {
    fn hours(&self) -> f64 {
        (self.to_ms - self.from_ms) as f64 / 3_600_000.0
    }
}

#[derive(Debug, Clone, Copy, PartialEq)]
pub struct Spread {
    pub mean: f64,
    pub sd: f64,
    pub median: f64,
}

#[derive(Debug, Clone, PartialEq)]
pub struct Candidate {
    pub key: String,
    pub label: String,
    pub columns: usize,
    pub r2: f64,
    pub adj_r2: f64,
}

#[derive(Debug, Clone)]
pub struct FitResult {
    pub n: usize,
    pub best: Candidate,
    pub candidates: Vec<Candidate>,
    pub tokens_per_point: Vec<(String, Option<f64>)>,
    pub r2: f64,
    pub background_per_hour: Option<f64>,
    pub background_share: Option<f64>,
    pub residual_sd: f64,
    pub fitted: Vec<f64>,
}

/// "weekly" or "session" for a window label; None for any other window.
pub fn kind_of(label: &str) -> Option<&'static str> {
    let l = label.to_lowercase();
    if l.contains("week") {
        Some("weekly")
    } else if l.contains("5h") || l.contains("session") {
        Some("session")
    } else {
        None
    }
}

/// The between-step intervals (from, to, points) of one window's samples, oldest first.
pub fn intervals(samples: &[PaceSample]) -> Vec<(i64, i64, f64)> {
    let mut sorted: Vec<&PaceSample> = samples.iter().collect();
    sorted.sort_by_key(|s| s.observed_at);
    let mut out = Vec::new();
    let mut prev: Option<&PaceSample> = None;
    for s in sorted {
        let Some(p) = prev else {
            prev = Some(s);
            continue;
        };
        let reset = matches!((p.resets_at, s.resets_at), (Some(a), Some(b)) if (a - b).abs() > RESET_JUMP_MS);
        if reset || s.percent < p.percent {
            prev = Some(s);
        } else if s.percent > p.percent {
            out.push((p.observed_at, s.observed_at, s.percent - p.percent));
            prev = Some(s);
        } // same percent: the step time stays the last step's, so the interval is whole
    }
    out
}

fn add(m: &mut BTreeMap<String, i64>, k: &str, v: i64) {
    *m.entry(k.to_string()).or_insert(0) += v;
}

/// Sums the requests of each interval.
pub fn steps(intervals: &[(i64, i64, f64)], requests: &[FitRequest]) -> Vec<FitStep> {
    intervals
        .iter()
        .map(|&(from, to, points)| {
            let mut s = FitStep {
                from_ms: from,
                to_ms: to,
                points,
                requests: 0,
                input: 0,
                cache_read: 0,
                cache_write: 0,
                output: 0,
                cache_read_by_role: BTreeMap::new(),
                cache_read_by_model: BTreeMap::new(),
                output_by_role: BTreeMap::new(),
            };
            for r in requests.iter().filter(|r| r.ts > from && r.ts <= to) {
                s.requests += 1;
                s.input += r.input;
                s.cache_read += r.cache_read;
                s.cache_write += r.cache_write;
                s.output += r.output;
                add(&mut s.cache_read_by_role, &r.role, r.cache_read);
                add(&mut s.cache_read_by_model, &r.model, r.cache_read);
                add(&mut s.output_by_role, &r.role, r.output);
            }
            s
        })
        .collect()
}

pub fn spread(values: &[f64]) -> Option<Spread> {
    if values.is_empty() {
        return None;
    }
    let n = values.len() as f64;
    let mean = values.iter().sum::<f64>() / n;
    let sd = if values.len() < 2 { 0.0 } else { (values.iter().map(|v| (v - mean).powi(2)).sum::<f64>() / (n - 1.0)).sqrt() };
    let mut sorted = values.to_vec();
    sorted.sort_by(|a, b| a.partial_cmp(b).unwrap_or(std::cmp::Ordering::Equal));
    let mid = sorted.len() / 2;
    let median = if sorted.len() % 2 == 1 { sorted[mid] } else { (sorted[mid - 1] + sorted[mid]) / 2.0 };
    Some(Spread { mean, sd, median })
}

/// Least squares y ~ sum(b_j * col_j), no intercept; columns scaled for stability. None when singular.
pub fn least_squares(cols: &[Vec<f64>], y: &[f64]) -> Option<Vec<f64>> {
    let k = cols.len();
    if k == 0 || y.len() < k {
        return None;
    }
    let mut scale = Vec::with_capacity(k);
    for c in cols {
        let m = c.iter().fold(0.0f64, |a, v| a.max(v.abs()));
        if m <= 0.0 {
            return None;
        }
        scale.push(m);
    }
    let mut a = vec![vec![0.0; k + 1]; k];
    for i in 0..k {
        for j in 0..k {
            a[i][j] = (0..y.len()).map(|r| (cols[i][r] / scale[i]) * (cols[j][r] / scale[j])).sum();
        }
        a[i][k] = (0..y.len()).map(|r| (cols[i][r] / scale[i]) * y[r]).sum();
    }
    for c in 0..k {
        let piv = (c..k).max_by(|&x, &z| a[x][c].abs().partial_cmp(&a[z][c].abs()).unwrap_or(std::cmp::Ordering::Equal))?;
        if a[piv][c].abs() < 1e-12 {
            return None;
        }
        a.swap(c, piv);
        for r in 0..k {
            if r != c {
                let f = a[r][c] / a[c][c];
                for j in c..=k {
                    a[r][j] -= f * a[c][j];
                }
            }
        }
    }
    Some((0..k).map(|i| a[i][k] / a[i][i] / scale[i]).collect())
}

fn r2(y: &[f64], fitted: &[f64]) -> Option<f64> {
    let mean = y.iter().sum::<f64>() / y.len() as f64;
    let tot: f64 = y.iter().map(|v| (v - mean).powi(2)).sum();
    if tot <= 0.0 {
        return None;
    }
    let res: f64 = y.iter().zip(fitted).map(|(a, b)| (a - b).powi(2)).sum();
    Some(1.0 - res / tot)
}

struct Fit {
    key: String,
    label: String,
    names: Vec<String>,
    cols: Vec<Vec<f64>>,
}

fn fitted_of(cols: &[Vec<f64>], b: &[f64], n: usize) -> Vec<f64> {
    (0..n).map(|r| cols.iter().zip(b).map(|(c, bj)| bj * c[r]).sum()).collect()
}

fn evaluate(f: &Fit, y: &[f64]) -> Option<(Candidate, Vec<f64>)> {
    let n = y.len();
    if n < f.cols.len() + 2 {
        return None;
    }
    let b = least_squares(&f.cols, y)?;
    let r2 = r2(y, &fitted_of(&f.cols, &b, n))?;
    let k = f.cols.len();
    let adj = 1.0 - (1.0 - r2) * (n - 1) as f64 / (n - k) as f64;
    Some((Candidate { key: f.key.clone(), label: f.label.clone(), columns: k, r2, adj_r2: adj }, b))
}

fn fit_of(key: &str, label: &str, names: &[&str], cols: Vec<Vec<f64>>) -> Fit {
    Fit { key: key.into(), label: label.into(), names: names.iter().map(|s| s.to_string()).collect(), cols }
}

/// Which token type (and model) best explains the points of each step; None with fewer than [`MIN_FIT_STEPS`] steps.
pub fn fit(steps: &[FitStep]) -> Option<FitResult> {
    let n = steps.len();
    if n < MIN_FIT_STEPS {
        return None;
    }
    let y: Vec<f64> = steps.iter().map(|s| s.points).collect();
    let col = |f: &dyn Fn(&FitStep) -> f64| -> Vec<f64> { steps.iter().map(f).collect() };
    let (inp, cr, cw, out) = (col(&|s| s.input as f64), col(&|s| s.cache_read as f64), col(&|s| s.cache_write as f64), col(&|s| s.output as f64));
    let mut fits = vec![
        fit_of("cacheRead", "cache-read tokens", &["cache-read"], vec![cr.clone()]),
        fit_of("input", "fresh input tokens", &["input"], vec![inp.clone()]),
        fit_of("cacheWrite", "cache-write tokens", &["cache-write"], vec![cw.clone()]),
        fit_of("output", "output tokens (thinking included)", &["output"], vec![out.clone()]),
        fit_of("requests", "number of requests", &["requests"], vec![col(&|s| s.requests as f64)]),
        fit_of("allTokens", "all three token types", &["input", "cache-read", "cache-write"], vec![inp.clone(), cr.clone(), cw.clone()]),
    ];
    if steps.iter().any(|s| s.output > 0) {
        fits.push(fit_of("allTokensOutput", "all four token types", &["input", "cache-read", "cache-write", "output"], vec![inp, cr, cw, out]));
    }
    let mut models: BTreeMap<String, i64> = BTreeMap::new();
    for s in steps {
        for (m, v) in &s.cache_read_by_model {
            add(&mut models, m, *v);
        }
    }
    let mut models: Vec<(String, i64)> = models.into_iter().filter(|(_, v)| *v > 0).collect();
    models.sort_by(|a, b| b.1.cmp(&a.1));
    models.truncate(4);
    if models.len() >= 2 {
        fits.push(Fit {
            key: "cacheReadByModel".into(),
            label: "cache-read tokens, split by model".into(),
            names: models.iter().map(|(m, _)| format!("cache-read {m}")).collect(),
            cols: models.iter().map(|(m, _)| col(&|s| *s.cache_read_by_model.get(m).unwrap_or(&0) as f64)).collect(),
        });
    }
    let scored: Vec<(&Fit, (Candidate, Vec<f64>))> = fits.iter().filter_map(|f| evaluate(f, &y).map(|e| (f, e))).collect();
    let (best_fit, best_eval) = scored.iter().max_by(|a, b| a.1 .0.adj_r2.partial_cmp(&b.1 .0.adj_r2).unwrap_or(std::cmp::Ordering::Equal))?;
    // "Hours in the interval" as a column: the part of a point no request explains (account-wide, unseen usage).
    let mut with_bg = Fit { key: best_fit.key.clone(), label: best_fit.label.clone(), names: best_fit.names.clone(), cols: best_fit.cols.clone() };
    with_bg.names.push("background".into());
    with_bg.cols.push(col(&|s| s.hours()));
    let bg_eval = evaluate(&with_bg, &y);
    let use_bg = bg_eval.as_ref().is_some_and(|(c, b)| {
        b.last().is_some_and(|v| *v > 0.0) && b[..b.len() - 1].iter().all(|v| *v > 0.0) && c.adj_r2 > best_eval.0.adj_r2
    });
    let (chosen_fit, chosen_eval) = if use_bg { (&with_bg, bg_eval.unwrap()) } else { (*best_fit, best_eval.clone()) };
    let b = &chosen_eval.1;
    let fitted = fitted_of(&chosen_fit.cols, b, n);
    let residual_sd = if n < 2 { 0.0 } else { (y.iter().zip(&fitted).map(|(a, f)| (a - f).powi(2)).sum::<f64>() / (n - 1) as f64).sqrt() };
    let bg = use_bg.then(|| *b.last().unwrap());
    let total: f64 = y.iter().sum();
    let bg_share = bg.filter(|_| total > 0.0).map(|bg| (bg * steps.iter().map(FitStep::hours).sum::<f64>() / total).clamp(0.0, 1.0));
    let per_point = chosen_fit
        .names
        .iter()
        .enumerate()
        .map(|(j, name)| (name.clone(), if name == "background" { None } else { Some(b[j]).filter(|v| *v > 0.0).map(|v| 1.0 / v) }))
        .collect();
    let mut candidates: Vec<Candidate> = scored.iter().map(|(_, e)| e.0.clone()).collect();
    candidates.sort_by(|a, b| b.adj_r2.partial_cmp(&a.adj_r2).unwrap_or(std::cmp::Ordering::Equal));
    Some(FitResult {
        n,
        r2: chosen_eval.0.r2,
        best: chosen_eval.0,
        candidates,
        tokens_per_point: per_point,
        background_per_hour: bg,
        background_share: bg_share,
        residual_sd,
        fitted,
    })
}

/// The model family (opus / sonnet / haiku) found as a token in a model id; an unknown model is kept as-is.
pub fn model_family(model: &str) -> String {
    let l = model.to_lowercase();
    let tokens: Vec<&str> = l.split(|c: char| !c.is_ascii_alphanumeric()).collect();
    ["opus", "sonnet", "haiku"].iter().find(|f| tokens.contains(f)).map(|f| f.to_string()).unwrap_or_else(|| model.to_string())
}

fn num(v: Option<f64>) -> Value {
    v.filter(|x| x.is_finite()).map(|x| json!(x)).unwrap_or(Value::Null)
}

fn spread_json(s: Option<Spread>) -> Value {
    s.map(|s| json!({"mean": s.mean, "sd": s.sd, "median": s.median})).unwrap_or(Value::Null)
}

fn per_point(steps: &[FitStep], f: fn(&FitStep) -> f64) -> Option<Spread> {
    spread(&steps.iter().map(|s| f(s) / s.points).collect::<Vec<_>>())
}

/// The whole analysis of one window kind, as the `usage_fit` window JSON.
pub fn analyse(kind: &str, label: &str, samples: &[PaceSample], requests: &[FitRequest], min_steps: usize) -> Value {
    let mut sorted = requests.to_vec();
    sorted.sort_by_key(|r| r.ts);
    let steps = steps(&intervals(samples), &sorted);
    let in_window: Vec<&FitRequest> = sorted.iter().filter(|r| steps.iter().any(|s| r.ts > s.from_ms && r.ts <= s.to_ms)).collect();
    let total_cr = in_window.iter().map(|r| r.cache_read).sum::<i64>() as f64;
    let span_from = steps.iter().map(|s| s.from_ms).min();
    let span_to = steps.iter().map(|s| s.to_ms).max().map(|t| t.max(samples.iter().map(|s| s.observed_at).max().unwrap_or(t)));
    let in_span: Vec<&FitRequest> = match (span_from, span_to) {
        (Some(a), Some(b)) => sorted.iter().filter(|r| r.ts > a && r.ts <= b).collect(),
        _ => Vec::new(),
    };
    #[derive(Default)]
    struct G {
        n: i64,
        i: i64,
        cr: i64,
        cw: i64,
        o: i64,
    }
    let fold = |l: &[&FitRequest]| -> BTreeMap<(String, String), G> {
        let mut m: BTreeMap<(String, String), G> = BTreeMap::new();
        for r in l {
            let g = m.entry((r.role.clone(), model_family(&r.model))).or_default();
            g.n += 1;
            g.i += r.input;
            g.cr += r.cache_read;
            g.cw += r.cache_write;
            g.o += r.output;
        }
        m
    };
    let stepped = fold(&in_window);
    let mut groups: Vec<(i64, i64, Value)> = fold(&in_span)
        .into_iter()
        .map(|(k, all)| {
            let z = G::default();
            let v = stepped.get(&k).unwrap_or(&z);
            let share = if total_cr > 0.0 { v.cr as f64 / total_cr } else { 0.0 };
            (v.cr, all.cr, json!({
                "role": k.0, "model": k.1, "requests": v.n, "input": v.i, "cacheRead": v.cr, "cacheWrite": v.cw, "share": share,
                "totalRequests": all.n, "totalInput": all.i, "totalCacheRead": all.cr, "totalCacheWrite": all.cw, "output": v.o, "totalOutput": all.o,
            }))
        })
        .collect();
    groups.sort_by(|a, b| b.0.cmp(&a.0).then(b.1.cmp(&a.1)));
    let points: f64 = steps.iter().map(|s| s.points).sum();
    let fit = fit(&steps);
    let shown = &steps[steps.len().saturating_sub(MAX_SERIES)..];
    let offset = steps.len() - shown.len();
    let obj = |m: &BTreeMap<String, i64>| Value::Object(m.iter().map(|(k, v)| (k.clone(), json!(v))).collect::<Map<_, _>>());
    let series: Vec<Value> = shown
        .iter()
        .enumerate()
        .map(|(i, s)| {
            json!({
                "t": s.to_ms, "from": s.from_ms, "points": s.points, "requests": s.requests, "input": s.input,
                "cacheRead": s.cache_read, "cacheWrite": s.cache_write, "output": s.output,
                "cacheReadPerPoint": s.cache_read as f64 / s.points,
                "byRole": obj(&s.cache_read_by_role), "outputByRole": obj(&s.output_by_role),
                "fitted": num(fit.as_ref().and_then(|f| f.fitted.get(offset + i).copied())),
            })
        })
        .collect();
    let fit_json = fit.as_ref().map(|f| {
        json!({
            "n": f.n, "r2": f.r2, "residualSd": f.residual_sd,
            "best": {"key": f.best.key, "label": f.best.label},
            "backgroundPerHour": num(f.background_per_hour), "backgroundShare": num(f.background_share),
            "candidates": f.candidates.iter().map(|c| json!({"key": c.key, "label": c.label, "columns": c.columns, "r2": c.r2, "adjR2": c.adj_r2})).collect::<Vec<_>>(),
            "tokensPerPoint": f.tokens_per_point.iter().map(|(n, v)| json!({"name": n, "value": num(*v)})).collect::<Vec<_>>(),
        })
    });
    let ratio = |sum: f64| if points > 0.0 { Some(sum / points) } else { None };
    json!({
        "kind": kind, "label": label, "steps": steps.len(), "minSteps": min_steps, "enough": steps.len() >= min_steps,
        "pointsTotal": points,
        "cacheReadPerPoint": num(ratio(steps.iter().map(|s| s.cache_read as f64).sum())),
        "requestsPerPoint": num(ratio(steps.iter().map(|s| s.requests as f64).sum())),
        "perPoint": {
            "cacheRead": spread_json(per_point(&steps, |s| s.cache_read as f64)),
            "requests": spread_json(per_point(&steps, |s| s.requests as f64)),
            "input": spread_json(per_point(&steps, |s| s.input as f64)),
            "cacheWrite": spread_json(per_point(&steps, |s| s.cache_write as f64)),
            "output": spread_json(per_point(&steps, |s| s.output as f64)),
        },
        "groups": groups.into_iter().map(|g| g.2).collect::<Vec<_>>(),
        "series": series,
        "fit": fit_json.unwrap_or(Value::Null),
    })
}

/// The `usage_fit` answer: one entry per window kind found for `provider` (the label with the most samples).
pub fn report(provider: &str, store: &PaceStore, requests: &[FitRequest], min_steps: usize, now: i64) -> Value {
    let keys: Vec<(String, String)> = store.keys().into_iter().filter(|k| k.0 == provider).collect();
    let mut windows = Vec::new();
    for kind in ["weekly", "session"] {
        let Some(key) = keys.iter().filter(|k| kind_of(&k.1) == Some(kind)).max_by_key(|k| store.samples(&k.0, &k.1).len()) else { continue };
        windows.push(analyse(kind, &key.1, &store.samples(&key.0, &key.1), requests, min_steps));
    }
    json!({ "generatedAt": now, "provider": provider, "windows": windows })
}

#[cfg(test)]
mod tests {
    use super::*;

    fn sample(t: i64, pct: f64) -> PaceSample {
        PaceSample {
            provider: "claude".into(),
            window: "Weekly".into(),
            observed_at: t,
            percent: pct,
            pace_delta: None,
            projected_percent: None,
            resets_at: Some(10_000_000_000),
            window_minutes: 10_080,
            prev_percent: None,
            delta_percent: None,
            tokens: None,
        }
    }

    fn req(ts: i64, cache_read: i64) -> FitRequest {
        FitRequest { ts, role: "worker".into(), model: "claude-opus-5-5".into(), input: 10, cache_read, cache_write: 0, output: 0 }
    }

    #[test]
    fn steps_count_whole_intervals_and_the_fit_finds_cache_read() {
        // Each step gains one point per 1000 cache-read tokens (plus noise in the other columns).
        let mut samples = vec![sample(0, 0.0)];
        let mut reqs = Vec::new();
        for i in 1..=10i64 {
            let pts = (i % 3 + 1) as f64;
            let prev = samples.last().unwrap().percent;
            samples.push(sample(i * 100_000, prev + pts));
            samples.push(sample(i * 100_000 + 10, prev + pts)); // a repeat: the interval stays whole
            reqs.push(req(i * 100_000 - 5, (pts * 1000.0) as i64));
        }
        let iv = intervals(&samples);
        assert_eq!(iv.len(), 10);
        let st = steps(&iv, &reqs);
        assert!(st.iter().all(|s| s.requests == 1));
        let f = fit(&st).expect("10 steps fit");
        assert_eq!(f.best.key, "cacheRead");
        let tpp = f.tokens_per_point[0].1.unwrap();
        assert!((tpp - 1000.0).abs() < 1.0, "{tpp}");
        assert_eq!(model_family("claude-opus-5-5"), "opus");
        assert_eq!(kind_of("Weekly (all models)"), Some("weekly"));
    }

    #[test]
    fn a_reset_or_a_drop_starts_a_new_step_chain() {
        let mut a = sample(0, 50.0);
        let b = sample(1, 60.0);
        let mut c = sample(2, 5.0);
        c.resets_at = Some(20_000_000_000);
        a.resets_at = b.resets_at;
        let d = { let mut d = sample(3, 7.0); d.resets_at = c.resets_at; d };
        assert_eq!(intervals(&[a, b, c, d]), vec![(0, 1, 10.0), (2, 3, 2.0)]);
    }
}
