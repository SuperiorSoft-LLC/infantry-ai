// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
//! CONTEXT-LIMIT (ef447eb8): every session carries an effective context limit with its source. No pin: the setting
//! `limits.session.defaultContextTokens` (default 500k). A spawn's pin wins; `off` is 0; a modify changes the record and
//! says when it applies; a harness that cannot cap says so.
use infantry_daemon::harness::stub::Stub;
use infantry_daemon::sessions::{Config, Daemon, NewOpts};
use serde_json::{json, Value};
use std::sync::Arc;

struct Fixture {
    dir: std::path::PathBuf,
    daemon: Arc<Daemon>,
}

impl Drop for Fixture {
    fn drop(&mut self) {
        let _ = std::fs::remove_dir_all(&self.dir);
    }
}

fn fixture() -> Fixture {
    let dir = std::env::temp_dir().join(format!("infantry-daemon-context-limit-{}", uuid::Uuid::new_v4()));
    std::fs::create_dir_all(&dir).unwrap();
    let daemon = Daemon::new(Arc::new(Stub::default()), Config::new(dir.clone(), dir.clone())).unwrap();
    infantry_daemon::compose::build(&daemon, &dir).unwrap();
    Fixture { dir, daemon }
}

fn limit_of(f: &Fixture, sid: &str) -> (Option<i32>, Option<String>) {
    let r = f.daemon.records().into_iter().find(|r| r.id == sid).unwrap();
    (r.context_limit, r.context_limit_source)
}

#[tokio::test(flavor = "multi_thread")]
async fn a_session_without_a_pin_gets_the_default_and_a_pin_wins() {
    let f = fixture();
    let plain = f.daemon.launch(NewOpts::default()).unwrap();
    assert_eq!(limit_of(&f, &plain), (Some(500_000), Some("default".into())), "no pin, no setting: 500k from the default");
    let pinned = f.daemon.launch(NewOpts { context_limit: Some(1_000_000), ..Default::default() }).unwrap();
    assert_eq!(limit_of(&f, &pinned), (Some(1_000_000), Some("session".into())));
    let off = f.daemon.launch(NewOpts { context_limit: Some(0), ..Default::default() }).unwrap();
    assert_eq!(limit_of(&f, &off), (Some(0), Some("session".into())), "off is a pin of 0, not 'unset'");
    let rec = f.daemon.records().into_iter().find(|r| r.id == plain).unwrap();
    assert_eq!(rec.context_limit_support.as_deref(), Some("unsupported"), "the stub cannot cap and says so");
    assert!(rec.context_limit_how.is_some());
}

#[tokio::test(flavor = "multi_thread")]
async fn modify_changes_the_record_and_says_when_it_applies() {
    let f = fixture();
    let sid = f.daemon.launch(NewOpts::default()).unwrap();
    let r = f.daemon.set_context_limit(&sid, Some(250_000)).unwrap();
    assert_eq!(r["contextLimit"], 250_000);
    assert_eq!(r["source"], "session");
    assert!(r["effect"].as_str().unwrap().contains("recorded only"), "an unsupported harness is not claimed to apply it: {r}");
    assert_eq!(limit_of(&f, &sid), (Some(250_000), Some("session".into())));
    let back: Value = f.daemon.set_context_limit(&sid, None).unwrap();
    assert_eq!(back["contextLimit"], 500_000, "cleared = the settings default again");
    assert_eq!(back["source"], "default");
    assert!(f.daemon.set_context_limit("nope", Some(1)).is_err());
    let _ = json!(null);
}
