// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
//! PACE-UNIFY-1: `usage.pacing`, `usage.manual` and `usage.overrides` have one source, the daemon settings. Before, the
//! usage service read a private `usage-settings.json`, so `settings_set usage.pacing {maxWorkers: 1}` changed nothing:
//! the panel said "up to 5 ticket workers" and pacing allowed 5.
use infantry_daemon::harness::stub::Stub;
use infantry_daemon::sessions::{Config, Daemon};
use infantry_protocol::usage::UsageReport;
use infantry_protocol::Envelope;
use serde_json::{json, Value};
use std::sync::Arc;

fn with_daemon<T: Send + 'static>(prep: impl FnOnce(&std::path::Path), f: impl FnOnce(Arc<infantry_daemon::compose::Composed>) -> T + Send + 'static) -> (T, std::path::PathBuf) {
    let dir = std::env::temp_dir().join(format!("infantry-daemon-usage-one-source-{}", uuid::Uuid::new_v4()));
    std::fs::create_dir_all(&dir).unwrap();
    prep(&dir);
    let rt = tokio::runtime::Runtime::new().unwrap();
    let out = rt.block_on(async {
        let daemon = Daemon::new(Arc::new(Stub::default()), Config::new(dir.clone(), dir.clone())).unwrap();
        let c = infantry_daemon::compose::build(&daemon, &dir).unwrap();
        tokio::task::spawn_blocking(move || f(c)).await.unwrap()
    });
    drop(rt);
    (out, dir)
}

fn ask(c: &infantry_daemon::compose::Composed, ty: &str, data: Value) -> Value {
    let out = c.request(&Envelope::new(ty, data.to_string()), true).unwrap_or_else(|e| panic!("{ty} refused: {e:?}"));
    serde_json::from_str(&out[0].data).unwrap()
}

#[test]
fn pacing_set_through_settings_is_the_rule_the_usage_report_uses() {
    let (rule, dir) = with_daemon(|_| {}, |c| {
        let rule = json!({"enabled": true, "mode": "pace", "maxWorkers": 1, "tiers": [{"below": null, "workers": 1}], "okProjected": 100, "hardStopPercent": 95});
        ask(&c, "settings_set", json!({"key": "usage.pacing", "value": rule}));
        let out = c.request(&Envelope::new("usage_get", ""), true).unwrap();
        serde_json::from_str::<UsageReport>(&out[0].data).unwrap().rule
    });
    assert_eq!(rule.max_workers, 1, "the Usage report must carry the maxWorkers set through settings, not the default 5");
    let _ = std::fs::remove_dir_all(&dir);
}

#[test]
fn an_override_set_through_usage_set_lands_in_settings() {
    let until = 4_000_000_000_000i64;
    let (got, dir) = with_daemon(|_| {}, move |c| {
        ask(&c, "usage_set", json!({"meter": "claude", "overrideUntil": until, "overrideWorkers": 2}));
        ask(&c, "settings_get", json!({"key": "usage.overrides"}))
    });
    assert_eq!(got["value"]["claude"]["workers"], 2);
    assert_eq!(got["value"]["claude"]["until"], until);
    let _ = std::fs::remove_dir_all(&dir);
}

#[test]
fn the_old_file_moves_into_settings_once_and_is_kept() {
    let (got, dir) = with_daemon(
        |d| std::fs::write(d.join("usage-settings.json"), r#"{"usage.overrides":{"claude":{"workers":1,"until":4000000000000}}}"#).unwrap(),
        |c| ask(&c, "settings_get", json!({"key": "usage.overrides"})),
    );
    assert_eq!(got["value"]["claude"]["workers"], 1);
    assert!(dir.join("usage-settings.json").exists(), "the old file is kept");
    let _ = std::fs::remove_dir_all(&dir);
}
