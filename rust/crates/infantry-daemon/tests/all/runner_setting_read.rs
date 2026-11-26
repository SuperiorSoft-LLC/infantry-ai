// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
//! ef60c402: a runner daemon reads its own settings (e.g. `limits.runners.orphanGraceSec`) in-process through
//! `Composed::request`. The frame that answers a setting by key is `settings_get`; the reply frame `settings` is served by no unit.
use infantry_daemon::harness::stub::Stub;
use infantry_daemon::sessions::{Config, Daemon};
use infantry_protocol::Envelope;
use std::sync::Arc;

#[test]
fn a_setting_set_on_the_runner_is_read_back_in_process() {
    let dir = std::env::temp_dir().join(format!("infantry-daemon-runner-setting-{}", uuid::Uuid::new_v4()));
    std::fs::create_dir_all(&dir).unwrap();
    let rt = tokio::runtime::Runtime::new().unwrap();
    let (set, unserved, got) = rt.block_on(async {
        let daemon = Daemon::new(Arc::new(Stub::default()), Config::new(dir.clone(), dir.clone())).unwrap();
        let c = infantry_daemon::compose::build(&daemon, &dir).unwrap();
        tokio::task::spawn_blocking(move || {
            let key = serde_json::json!({"key": "limits.runners.orphanGraceSec"});
            let set = c.request(&Envelope::new("settings_set", serde_json::json!({"key": "limits.runners.orphanGraceSec", "value": 60}).to_string()), true);
            let unserved = c.request(&Envelope::new("settings", key.to_string()), true);
            let got = c.request(&Envelope::new(infantry_protocol::frames::SETTINGS_GET, key.to_string()), true);
            (set, unserved, got)
        })
        .await
        .unwrap()
    });
    set.expect("settings_set answers");
    assert!(unserved.is_err(), "the reply frame `settings` is not a request frame");
    let v: serde_json::Value = serde_json::from_str(&got.expect("settings_get answers")[0].data).unwrap();
    assert_eq!(v["value"], 60);
    drop(rt);
    let _ = std::fs::remove_dir_all(&dir);
}
