// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
//! The Kotlin `PaceTest` ported with the same inputs and expected outputs: the Rust numbers must equal the Kotlin ones.
use super::*;

const H: i64 = 3_600_000;
const WEEK_MIN: i32 = 7 * 24 * 60;

fn tmp(tag: &str) -> PathBuf {
    let f = std::env::temp_dir().join(format!("infantry-pace-{tag}-{}.jsonl", std::process::id()));
    let _ = std::fs::remove_file(&f);
    f
}

#[allow(clippy::too_many_arguments)]
fn rec(st: &PaceStore, t: i64, pct: f64, resets: Option<i64>, pace: Option<f64>, proj: Option<f64>, win: &str, provider: &str) -> bool {
    st.record(provider, win, t, Some(pct), pace, proj, resets, WEEK_MIN)
}

fn reading(st: &PaceStore, t: i64, pct: f64, resets: Option<i64>) -> bool {
    rec(st, t, pct, resets, Some(0.0), Some(pct), "Weekly limit", "claude")
}

fn sample(t: i64, pct: f64, resets: Option<i64>, pace: Option<f64>, proj: Option<f64>) -> PaceSample {
    PaceSample {
        provider: "claude".into(),
        window: "Weekly limit".into(),
        observed_at: t,
        percent: pct,
        pace_delta: pace,
        projected_percent: proj,
        resets_at: resets,
        window_minutes: WEEK_MIN,
        prev_percent: None,
        delta_percent: None,
        tokens: None,
    }
}

fn plain(t: i64, pct: f64) -> PaceSample {
    sample(t, pct, None, None, None)
}

fn mom(series: &[PaceSample], now: i64, hours: f64) -> MomentumReading {
    momentum(series, now, hours, &PaceSettings::default())
}

#[test]
fn a_reading_becomes_a_sample_and_an_identical_one_is_not_kept_twice() {
    let st = PaceStore::open(tmp("dedupe"));
    assert!(reading(&st, 1000, 10.0, Some(9_000_000)));
    assert!(!reading(&st, 1000, 10.0, Some(9_000_000)), "same observedAt, percent and reset = a repeat");
    assert!(reading(&st, 2000, 10.0, Some(9_000_000)), "a newer read is kept even at the same percent");
    assert!(!reading(&st, 1500, 11.0, Some(9_000_000)), "an older read than the newest sample is ignored");
    assert_eq!(st.count(), 2);
}

#[test]
fn an_unknown_percent_is_never_recorded() {
    let st = PaceStore::open(tmp("nopct"));
    assert!(!st.record("claude", "Weekly limit", 1, None, None, None, None, WEEK_MIN));
    assert_eq!(st.count(), 0);
}

#[test]
fn history_survives_a_restart() {
    let f = tmp("roundtrip");
    let a = PaceStore::open(&f);
    rec(&a, 1000, 10.0, Some(5_000_000), Some(1.5), Some(12.0), "Weekly limit", "claude");
    rec(&a, 2000, 11.0, Some(5_000_000), None, None, "5h limit", "codex");
    let b = PaceStore::open(&f);
    let keys: std::collections::BTreeSet<_> = b.keys().into_iter().collect();
    let want: std::collections::BTreeSet<_> = [("claude".to_string(), "Weekly limit".to_string()), ("codex".to_string(), "5h limit".to_string())].into_iter().collect();
    assert_eq!(keys, want);
    let s = b.samples("claude", "Weekly limit");
    assert_eq!(s.len(), 1);
    assert_eq!(s[0], sample(1000, 10.0, Some(5_000_000), Some(1.5), Some(12.0)));
    assert_eq!(b.samples("codex", "5h limit")[0].pace_delta, None);
    let _ = std::fs::remove_file(&f);
}

#[test]
fn a_torn_line_is_skipped() {
    let f = tmp("torn");
    std::fs::write(&f, format!("not json\n{}\n{{\"p\":\n", encode(&plain(5, 1.0)))).unwrap();
    assert_eq!(PaceStore::open(&f).count(), 1);
    let _ = std::fs::remove_file(&f);
}

#[test]
fn retention_prunes_old_samples_and_zero_keeps_all() {
    let now = 100 * 24 * H;
    let f = tmp("retention");
    let st = PaceStore::open(&f);
    reading(&st, now - 70 * 24 * H, 1.0, None);
    reading(&st, now - 10 * 24 * H, 2.0, None);
    reading(&st, now - H, 3.0, None);
    assert_eq!(st.prune(0, now), 0);
    assert_eq!(st.count(), 3);
    assert_eq!(st.prune(60, now), 1);
    assert_eq!(st.count(), 2);
    assert_eq!(PaceStore::open(&f).count(), 2, "the file was rewritten");
    let _ = std::fs::remove_file(&f);
}

#[test]
fn a_percent_drop_at_a_changed_resets_at_splits_the_series_and_is_not_a_fall() {
    let r1 = 100 * H;
    let r2 = r1 + 168 * H;
    let series = vec![
        sample(90 * H, 80.0, Some(r1), None, None),
        sample(94 * H, 90.0, Some(r1), None, None),
        sample(98 * H, 96.0, Some(r1), None, None),
        sample(101 * H, 3.0, Some(r2), None, None),
        sample(103 * H, 4.0, Some(r2), None, None),
        sample(105 * H, 5.0, Some(r2), None, None),
    ];
    let segs = split_at_resets(&series);
    assert_eq!(segs.iter().map(Vec::len).collect::<Vec<_>>(), vec![3, 3]);
    assert_eq!(active(&series)[0].percent, 3.0);
}

#[test]
fn a_drop_without_a_reset_time_still_splits() {
    let segs = split_at_resets(&[plain(1, 80.0), plain(2, 90.0), plain(3, 2.0), plain(4, 3.0)]);
    assert_eq!(segs.iter().map(Vec::len).collect::<Vec<_>>(), vec![2, 2]);
}

#[test]
fn momentum_fits_the_active_window_only_so_a_reset_never_reads_as_falling() {
    let r2 = 400 * H;
    let old: Vec<_> = (0..=5).map(|i| sample(300 * H + i * H, 80.0 + (i * 3) as f64, Some(350 * H), Some(5.0), Some(100.0))).collect();
    let fresh: Vec<_> = (0..=4).map(|i| sample(380 * H + i * H, 2.0 + i as f64, Some(r2), Some(i as f64), Some(20.0 + i as f64 * 4.0))).collect();
    let all: Vec<_> = old.into_iter().chain(fresh).collect();
    assert_eq!(mom(&all, 384 * H, 6.0).momentum, Momentum::Rising);
}

fn line_of(slope: f64, n: i64) -> Vec<PaceSample> {
    (0..n).map(|i| sample(i * H, 50.0 + i as f64 * 0.1, Some(1000 * H), Some(0.0), Some(100.0 + slope * i as f64))).collect()
}

#[test]
fn projected_slope_classifies_rising_flat_falling_with_the_dead_band() {
    assert_eq!(mom(&line_of(2.0, 7), 6 * H, 6.0).momentum, Momentum::Rising);
    assert_eq!(mom(&line_of(-2.0, 7), 6 * H, 6.0).momentum, Momentum::Falling);
    assert_eq!(mom(&line_of(0.1, 7), 6 * H, 6.0).momentum, Momentum::Flat);
    let wide = PaceSettings { dead_band: 3.0, ..PaceSettings::default() };
    assert_eq!(momentum(&line_of(2.0, 7), 6 * H, 6.0, &wide).momentum, Momentum::Flat);
    assert!((mom(&line_of(2.0, 7), 6 * H, 6.0).projected_slope.unwrap() - 2.0).abs() < 1e-9);
}

#[test]
fn momentum_is_unknown_without_enough_samples() {
    assert_eq!(mom(&line_of(2.0, 2), H, 6.0).momentum, Momentum::Unknown);
    // three samples squeezed into 20 minutes do not span the 2h minimum
    let tight: Vec<_> = (0..=2).map(|i| sample(i * 600_000, 5.0, Some(1000 * H), None, Some(5.0))).collect();
    assert_eq!(mom(&tight, 1_200_000, 6.0).momentum, Momentum::Unknown);
}

#[test]
fn slope_of_a_straight_line_is_exact() {
    let pts: Vec<(i64, f64)> = (0..=4).map(|i| (i * H, 10.0 + 0.5 * i as f64)).collect();
    assert!((slope(&pts).unwrap() - 0.5).abs() < 1e-9);
    assert_eq!(slope(&[(0, 1.0)]), None);
}

/// Weekly window (168 h) half gone (84 h), 53.5% used; the last 6 h burned 0.5 points per hour.
fn worked() -> (Vec<PaceSample>, i64) {
    let reset = 168 * H;
    let now = 84 * H;
    let series = (0..=6)
        .map(|i| {
            let t = now - (6 - i) * H;
            let pct = 50.5 + 0.5 * i as f64;
            let elapsed = t as f64 / (168 * H) as f64;
            sample(t, pct, Some(reset), Some(pct - elapsed * 100.0), Some(pct / elapsed))
        })
        .collect();
    (series, now)
}

#[test]
fn worked_example_land_at_107_at_the_current_rate_and_96_at_the_last_6h_rate() {
    let (series, now) = worked();
    let f = forecast(&series, WEEK_MIN, now, &PaceSettings::default());
    assert!(f.ready);
    let (c, r) = (f.current.as_ref().unwrap(), f.recent.as_ref().unwrap());
    assert!((c.landing_percent - 107.0).abs() < 1e-9);
    assert!((r.landing_percent - 95.5).abs() < 1e-9);
    assert!((r.rate_per_hour - 0.5).abs() < 1e-9);
    assert!((c.hours_to_cross.unwrap() - 3906.0 / 53.5).abs() < 1e-6);
    assert_eq!(r.hours_to_cross, None);
    assert!(r.stays_under);
    assert!((f.hours_left.unwrap() - 84.0).abs() < 1e-9);
    assert!((f.band_low().unwrap() - 95.5).abs() < 1e-9);
    assert!((f.band_high().unwrap() - 107.0).abs() < 1e-9);
    let m = mom(&series, now, 6.0);
    assert_eq!(m.momentum, Momentum::Falling);
    assert_eq!(sentence(&f, m.momentum), "at the current rate you land at 107%; at the last 6h rate, 96% (falling); at the current rate you cross 100% in 73.0h");
}

#[test]
fn idle_usage_burn_rate_0_pace_delta_drifting_down_momentum_falling() {
    let reset = 168 * H;
    let now = 84 * H;
    let series: Vec<_> = (0..=7)
        .map(|i| {
            let t = now - (7 - i) * H;
            let elapsed = t as f64 / reset as f64;
            sample(t, 50.0, Some(reset), Some(50.0 - elapsed * 100.0), Some(50.0 / elapsed))
        })
        .collect();
    let burn = burn_rates(&series, 1.0);
    assert_eq!(burn.len(), 7, "the first sample has no hour behind it");
    assert!(burn.iter().all(|b| b.1 == 0.0), "nothing done = burn rate 0");
    assert!(series.windows(2).all(|w| w[1].pace_delta.unwrap() < w[0].pace_delta.unwrap()), "pace delta falls every hour");
    assert_eq!(mom(&series, now, 6.0).momentum, Momentum::Falling);
    let st = PaceStore::open(tmp("idle"));
    for s in &series {
        st.record("claude", &s.window, s.observed_at, Some(s.percent), s.pace_delta, s.projected_percent, s.resets_at, WEEK_MIN);
    }
    let r = report(&st, &PaceSettings::default(), &PaceQuery::parse("", now), now);
    assert_eq!(r["series"][0]["points"].as_array().unwrap().last().unwrap()["b"].as_f64(), Some(0.0));
}

#[test]
fn burn_rate_is_points_per_hour_over_the_rolling_window_and_a_reset_does_not_leak_into_it() {
    let s = vec![plain(0, 10.0), plain(H, 12.0), plain(2 * H, 16.0), plain(3 * H, 16.0)];
    assert_eq!(burn_rates(&s, 1.0).iter().map(|b| b.1).collect::<Vec<_>>(), vec![2.0, 4.0, 0.0]);
    assert_eq!(burn_rates(&s, 2.0).iter().map(|b| b.1).collect::<Vec<_>>(), vec![3.0, 2.0]);
    let r1 = 100 * H;
    let two: Vec<_> = (0..=3)
        .map(|i| sample(i * H, 90.0 + i as f64, Some(r1), None, None))
        .chain((4..=7).map(|i| sample(i * H, 2.0 + i as f64, Some(r1 + 168 * H), None, None)))
        .collect();
    let per_segment: Vec<_> = split_at_resets(&two).iter().flat_map(|seg| burn_rates(seg, 1.0)).collect();
    assert!(per_segment.iter().all(|b| b.1 == 1.0), "split first, then rate: the drop at the reset is not a burn");
}

#[test]
fn a_forecast_that_stays_under_says_so() {
    let now = 84 * H;
    let series: Vec<_> = (0..=6).map(|i| sample(now - (6 - i) * H, 20.0 + 0.1 * i as f64, Some(168 * H), None, None)).collect();
    let f = forecast(&series, WEEK_MIN, now, &PaceSettings::default());
    assert!(sentence(&f, Momentum::Flat).ends_with("stays under 100%"));
    assert!(f.current.unwrap().stays_under && f.recent.unwrap().stays_under);
}

#[test]
fn already_over_100_crosses_in_0_hours() {
    let now = 84 * H;
    let series: Vec<_> = (0..=6).map(|i| sample(now - (6 - i) * H, 100.0 + 0.1 * i as f64, Some(168 * H), None, None)).collect();
    assert_eq!(forecast(&series, WEEK_MIN, now, &PaceSettings::default()).current.unwrap().hours_to_cross, Some(0.0));
}

#[test]
fn no_forecast_without_enough_samples() {
    let now = 84 * H;
    let s = PaceSettings::default();
    let f = forecast(&[sample(now - H, 50.0, Some(168 * H), None, None), sample(now, 51.0, Some(168 * H), None, None)], WEEK_MIN, now, &s);
    assert!(!f.ready);
    assert!(f.reason.is_some());
    assert!(sentence(&f, Momentum::Unknown).starts_with("not enough data yet"));
    assert!(!forecast(&[], WEEK_MIN, now, &s).ready);
    assert!(!forecast(&[sample(0, 1.0, None, None, None)], WEEK_MIN, now, &s).ready, "no reset time = no forecast");
}

#[test]
fn settings_read_from_the_config_with_defaults() {
    let m: std::collections::HashMap<&str, &str> = [("pace.retentionDays", "0"), ("pace.deadBand", "2.5"), ("pace.minSamples", "junk")].into_iter().collect();
    let s = PaceSettings::from(|k| m.get(k).map(|v| v.to_string()));
    assert_eq!(s.retention_days, 0);
    assert_eq!(s.dead_band, 2.5);
    assert_eq!(s.min_samples, 3);
    assert_eq!(s.burn_window_hours, 1.0);
    assert_eq!(s.lookback_short_hours, 6.0);
    assert_eq!(PaceSettings::from(|_| None), PaceSettings::default());
}

#[test]
fn token_9_stores_prev_delta_and_per_session_tokens_and_they_survive_a_reload() {
    let f = tmp("token9");
    let st = PaceStore::open(&f);
    let k = json!([{"s": "s1", "r": "backend", "i": 6, "o": 9, "cr": 100}]);
    assert!(st.record_with("claude", "Weekly limit", 1_000, Some(10.0), Some(0.0), Some(10.0), Some(9 * H), WEEK_MIN, Some(k)));
    assert!(st.record_with("claude", "Weekly limit", 2_000, Some(11.0), Some(0.0), Some(11.0), Some(9 * H), WEEK_MIN, None));
    let reopened = PaceStore::open(&f).samples("claude", "Weekly limit");
    assert_eq!(reopened[1].prev_percent, Some(10.0));
    assert_eq!(reopened[1].delta_percent, Some(1.0));
    let t = &reopened[0].tokens.as_ref().unwrap()[0];
    assert_eq!((t["s"].as_str(), t["i"].as_i64(), t["o"].as_i64(), t["cr"].as_i64(), t.get("cw")), (Some("s1"), Some(6), Some(9), Some(100), None));
    let _ = std::fs::remove_file(&f);
}

#[test]
fn token_9_raw_samples_is_off_by_default_and_capped_at_200_when_asked() {
    let st = PaceStore::open(tmp("raw"));
    let now = 1_000 * H;
    for i in 0..300i64 {
        reading(&st, now - (300 - i) * 60_000, 1.0 + i as f64 * 0.1, Some(now + 100 * H));
    }
    let one = |q: &str| report(&st, &PaceSettings::default(), &PaceQuery::parse(q, now), now)["series"][0].clone();
    assert!(one("")["rawSamples"].is_null());
    let raw = one(r#"{"samples":"true"}"#)["rawSamples"].as_array().unwrap().clone();
    assert_eq!(raw.len(), 200);
    assert!(raw.last().unwrap()["delta"].as_f64().unwrap() > 0.0);
    assert!(PaceQuery::parse(r#"{"samples":"true"}"#, 0).include_samples);
}

#[test]
fn the_report_carries_series_momentum_forecast_and_any_provider() {
    let (series, now) = worked();
    let st = PaceStore::open(tmp("report"));
    for s in &series {
        st.record("claude", &s.window, s.observed_at, Some(s.percent), s.pace_delta, s.projected_percent, s.resets_at, WEEK_MIN);
    }
    st.record("brand-new-harness", "Monthly limit", now, Some(4.0), Some(0.0), Some(4.0), Some(1000 * H), 43200);
    let s = PaceSettings::default();
    let r = report(&st, &s, &PaceQuery::parse("", now), now);
    let all = r["series"].as_array().unwrap();
    assert_eq!(all.len(), 2, "a new harness gets a series with no per-provider code");
    let claude = all.iter().find(|x| x["provider"] == "claude").unwrap();
    assert_eq!(claude["momentum"], "FALLING");
    assert_eq!(claude["forecast"]["ready"], true);
    assert_eq!(claude["points"].as_array().unwrap().len(), 7);
    let one = report(&st, &s, &PaceQuery::parse(r#"{"provider":"brand-new-harness"}"#, now), now);
    assert_eq!(one["series"].as_array().unwrap().len(), 1);
    assert_eq!(one["series"][0]["forecast"]["ready"], false);
    let empty = report(&PaceStore::open(tmp("empty")), &s, &PaceQuery::parse("", now), now);
    assert!(empty["text"].as_str().unwrap().starts_with("no pacing history"));
}

#[test]
fn since_filters_points_and_thinning_keeps_first_and_newest() {
    let pts: Vec<_> = (0..100).map(|i| plain(i, i as f64)).collect();
    let t = thin(&pts, 10);
    assert_eq!(t.len(), 10);
    assert_eq!(t[0].percent, 0.0);
    assert_eq!(t[9].percent, 99.0);
    let q = PaceQuery::parse(r#"{"provider":"claude","sinceHours":2}"#, 10 * H);
    assert_eq!(q.since_ms, 8 * H);
    assert_eq!(q.provider.as_deref(), Some("claude"));
    let d = PaceQuery::parse("", 0);
    assert_eq!((d.provider, d.window, d.since_ms, d.max_points, d.include_samples), (None, None, 0, 400, false));
}

#[test]
fn readable_pace_labels_never_a_bare_points_figure() {
    assert_eq!(pace_label(-1.0), "on pace (\u{2212}1)");
    assert_eq!(pace_label(0.0), "on pace (0)");
    assert_eq!(pace_label(4.0), "ahead of pace (+4)");
    assert_eq!(pace_label(-12.4), "under pace (\u{2212}12)");
    let st = PaceStore::open(tmp("label"));
    st.record("claude", "Weekly limit", 1, Some(3.0), Some(-1.0), None, None, WEEK_MIN);
    let r = report(&st, &PaceSettings::default(), &PaceQuery::parse("", 1), 1);
    assert!(r["text"].as_str().unwrap().contains("3% used, on pace (\u{2212}1), momentum"), "{}", r["text"]);
}
