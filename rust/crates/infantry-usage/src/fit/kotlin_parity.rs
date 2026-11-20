// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
//! The Kotlin `UsageFitTest` ported with the same inputs and expected outputs.
use super::*;

const RESET: i64 = 1_000_000_000;

fn sample(t: i64, pct: f64) -> PaceSample {
    sample_at(t, pct, RESET)
}

fn sample_at(t: i64, pct: f64, resets: i64) -> PaceSample {
    PaceSample {
        provider: "claude".into(),
        window: "Weekly limit".into(),
        observed_at: t,
        percent: pct,
        pace_delta: None,
        projected_percent: None,
        resets_at: Some(resets),
        window_minutes: 10080,
        prev_percent: None,
        delta_percent: None,
        tokens: None,
    }
}

fn req(ts: i64, cr: i64, role: &str, model: &str) -> FitRequest {
    FitRequest { ts, role: role.into(), model: model.into(), input: 10, cache_read: cr, cache_write: 5, output: 0 }
}

fn step(from: i64, to: i64, points: f64, requests: usize, input: i64, cache_read: i64, cache_write: i64, by_role: &[(&str, i64)], by_model: &[(&str, i64)]) -> FitStep {
    let map = |l: &[(&str, i64)]| l.iter().map(|(k, v)| (k.to_string(), *v)).collect::<BTreeMap<_, _>>();
    FitStep {
        from_ms: from,
        to_ms: to,
        points,
        requests,
        input,
        cache_read,
        cache_write,
        output: 0,
        cache_read_by_role: map(by_role),
        cache_read_by_model: map(by_model),
        output_by_role: BTreeMap::new(),
    }
}

#[test]
fn between_step_intervals_sum_only_the_requests_inside_them() {
    let samples = vec![sample(0, 10.0), sample(100, 10.0), sample(200, 11.0), sample(300, 11.0), sample(400, 13.0)];
    let iv = intervals(&samples);
    assert_eq!(iv, vec![(0, 200, 1.0), (200, 400, 2.0)]);
    let reqs = vec![req(0, 99, "backend", "sonnet"), req(50, 100, "backend", "sonnet"), req(200, 7, "backend", "sonnet"), req(250, 300, "backend", "sonnet"), req(400, 1, "backend", "sonnet"), req(401, 999, "backend", "sonnet")];
    let st = steps(&iv, &reqs);
    assert_eq!(st[0].cache_read, 107); // (0,200]: ts 50 and 200, not ts 0
    assert_eq!(st[1].cache_read, 301); // (200,400]: 250 and 400
    assert_eq!(st[0].requests, 2);
}

#[test]
fn a_reset_starts_a_new_window_and_a_drop_does_not_make_a_step() {
    let samples = vec![sample(0, 50.0), sample(10, 51.0), sample_at(20, 1.0, RESET + 10_000_000), sample_at(30, 2.0, RESET + 10_000_000)];
    assert_eq!(intervals(&samples), vec![(0, 10, 1.0), (20, 30, 1.0)]);
}

#[test]
fn per_point_mean_spread_and_median() {
    let s = spread(&[2.0, 4.0, 6.0]).unwrap();
    assert!((s.mean - 4.0).abs() < 1e-9 && (s.sd - 2.0).abs() < 1e-9 && (s.median - 4.0).abs() < 1e-9);
    assert!(spread(&[]).is_none());
}

#[test]
fn least_squares_recovers_a_known_slope_and_the_fit_names_the_driver() {
    // points = cacheRead / 1000 exactly; input is noise-free but unrelated.
    let n = 12i64;
    let steps: Vec<FitStep> = (0..n)
        .map(|i| {
            let cr = 1000 * (i % 4 + 1) * 10;
            step(i * 100, i * 100 + 90, cr as f64 / 1000.0, 3, (i * 37) % 11, cr, 5 + i % 3, &[("backend", cr)], &[("sonnet", cr)])
        })
        .collect();
    let f = fit(&steps).expect("a fit");
    assert_eq!(f.best.key, "cacheRead");
    assert!(f.r2 > 0.99, "r2 {}", f.r2);
    assert_eq!(f.n, n as usize);
    assert!((f.tokens_per_point[0].1.unwrap() - 1000.0).abs() < 1.0);
    assert_eq!(f.fitted.len(), n as usize);
}

#[test]
fn fit_by_model_separates_a_heavy_and_a_light_model() {
    let steps: Vec<FitStep> = (0..14i64)
        .map(|i| {
            let a = 1000 * (i % 5 + 1);
            let b = 1000 * ((i * 3) % 7 + 1);
            // sonnet costs 1 point per 1000, haiku 1 point per 4000
            step(i * 100, i * 100 + 90, a as f64 / 1000.0 + b as f64 / 4000.0, 2, 1, a + b, 1, &[("r", a + b)], &[("sonnet", a), ("haiku", b)])
        })
        .collect();
    let f = fit(&steps).expect("a fit");
    assert_eq!(f.best.key, "cacheReadByModel");
    let by: BTreeMap<_, _> = f.tokens_per_point.iter().cloned().collect();
    assert!((by["cache-read sonnet"].unwrap() - 1000.0).abs() < 5.0);
    assert!((by["cache-read haiku"].unwrap() - 4000.0).abs() < 20.0);
}

#[test]
fn few_steps_give_no_fit_and_an_honest_not_enough_flag() {
    let w = analyse("weekly", "Weekly limit", &[sample(0, 1.0), sample(100, 2.0), sample(200, 3.0)], &[req(50, 1000, "backend", "sonnet"), req(150, 2000, "backend", "sonnet")], 8);
    assert_eq!(w["steps"], 2);
    assert!(w["fit"].is_null());
    assert_eq!(w["enough"], false);
}

#[test]
fn no_data_at_all_is_empty_and_does_not_crash() {
    let w = analyse("weekly", "Weekly limit", &[], &[], 8);
    assert_eq!(w["steps"], 0);
    assert!(w["cacheReadPerPoint"].is_null());
    assert!(w["fit"].is_null());
}

#[test]
fn groups_share_cache_read_by_role_and_model() {
    let w = analyse(
        "weekly",
        "Weekly limit",
        &[sample(0, 1.0), sample(100, 2.0)],
        &[req(10, 600, "backend", "sonnet"), req(20, 300, "pm", "opus"), req(30, 100, "backend", "haiku")],
        8,
    );
    let g = w["groups"].as_array().unwrap();
    assert_eq!(g.len(), 3);
    assert_eq!(g[0]["role"], "backend");
    assert!((g[0]["share"].as_f64().unwrap() - 0.6).abs() < 1e-9);
    assert!((w["cacheReadPerPoint"].as_f64().unwrap() - 1000.0).abs() < 1e-9);
}

#[test]
fn one_model_family_is_one_group_and_the_totals_include_requests_outside_the_steps() {
    let w = analyse(
        "weekly",
        "Weekly limit",
        &[sample(0, 1.0), sample(100, 2.0), sample(200, 2.0), sample(300, 2.0)],
        &[req(10, 600, "pm", "opus"), req(20, 300, "pm", "claude-opus-5-5"), req(250, 50, "pm", "anthropic/claude-opus-5-5"), req(30, 100, "x", "weird")],
        8,
    );
    let g = w["groups"].as_array().unwrap();
    let pm = g.iter().find(|x| x["role"] == "pm").unwrap();
    assert_eq!(pm["model"], "opus");
    assert_eq!((pm["totalRequests"].as_i64(), pm["requests"].as_i64()), (Some(3), Some(2)));
    assert_eq!(g.iter().find(|x| x["role"] == "x").unwrap()["model"], "weird");
}

#[test]
fn output_is_a_fit_candidate_and_is_summed_per_step_and_role() {
    let samples: Vec<PaceSample> = (0..=9i64).map(|i| sample(i * 100, (i * (i + 1) / 2) as f64)).collect();
    let reqs: Vec<FitRequest> = (1..=9i64)
        .map(|i| FitRequest {
            ts: i * 100 - 50,
            role: if i % 2 == 0 { "pm" } else { "backend" }.into(),
            model: "opus".into(),
            input: 10 + (i * 37) % 11,
            cache_read: 1000 + i * 7,
            cache_write: 5 + (i * 13) % 7 + i,
            output: i * 1000,
        })
        .collect();
    let w = analyse("weekly", "Weekly limit", &samples, &reqs, 5);
    let series = w["series"].as_array().unwrap();
    assert_eq!(series.iter().map(|s| s["output"].as_i64().unwrap()).sum::<i64>(), 45_000);
    assert_eq!(series[0]["outputByRole"]["backend"], 1000);
    let keys: Vec<&str> = w["fit"]["candidates"].as_array().unwrap().iter().map(|c| c["key"].as_str().unwrap()).collect();
    assert!(keys.contains(&"output") && keys.contains(&"allTokensOutput"), "{keys:?}");
}
