// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
//! The built component, loaded by the real plugin host, with a fake http import that serves the recorded feed.
//! The expected card is built by the pure `feed` module, whose output is pinned against the Kotlin shape in its own tests.
use infantry_news::feed;
use infantry_plugin_host::{CallContext, HostLimits, MemoryServices, PluginHost, Tier};
use infantry_plugin_wit::jsonl::HttpResponse;
use std::path::{Path, PathBuf};
use std::process::Command;
use std::sync::{Arc, Mutex};

const FIXTURE: &str = include_str!("fixtures/top.xml");

fn build_component() -> PathBuf {
    let root = Path::new(env!("CARGO_MANIFEST_DIR"));
    let target = root.join("../../../target-wasm-test");
    let status = Command::new(env!("CARGO"))
        .args(["build", "-p", "infantry-news", "--lib", "--target", "wasm32-wasip2", "--target-dir"])
        .arg(&target)
        .current_dir(root)
        .status()
        .expect("cargo build");
    assert!(status.success(), "building the wasm32-wasip2 component failed");
    target.join("wasm32-wasip2/debug/infantry_news.wasm")
}

fn module_dir(tag: &str, edit: impl Fn(&mut serde_json::Value)) -> PathBuf {
    let dir = std::env::temp_dir().join(format!("infantry-news-host-{tag}-{}", std::process::id()));
    std::fs::create_dir_all(&dir).unwrap();
    let mut m: serde_json::Value = serde_json::from_str(include_str!("../module.json")).unwrap();
    edit(&mut m);
    std::fs::write(dir.join("module.json"), m.to_string()).unwrap();
    std::fs::copy(build_component(), dir.join("daemon.component")).unwrap();
    dir
}

#[test]
fn headlines_through_the_host_match_the_pure_answer() {
    let seen = Arc::new(Mutex::new(Vec::<String>::new()));
    let seen2 = seen.clone();
    let services = Arc::new(MemoryServices::new().with_http(move |r| {
        seen2.lock().unwrap().push(r.url.clone());
        Ok(HttpResponse { status: 200, headers: vec![], body: FIXTURE.as_bytes().to_vec() })
    }));
    let host = PluginHost::new(services, HostLimits::default()).unwrap();
    let dir = module_dir("ok", |_| {});
    let p = host.load_as(&dir, Tier::Official).expect("loads");
    let out = p.call_tool("news_headlines", r#"{"max":3}"#, &CallContext::default()).expect("call");
    let got: serde_json::Value = serde_json::from_str(&out).unwrap();

    let s = feed::Settings::default();
    let req = feed::request(None, None, None, "en-US", "US");
    let want = feed::answer(&req, FIXTURE, 3, 20, &s).unwrap();
    assert_eq!(got, want);
    assert_eq!(got["count"], 3);
    assert_eq!(seen.lock().unwrap().as_slice(), [req.url.as_str()]);
    let _ = std::fs::remove_dir_all(dir);
}

#[test]
fn http_error_and_bad_feed_are_one_plain_message() {
    for (status, body, needle) in [(503u16, "", "Google News answered HTTP 503"), (200, "not rss", "the feed is not valid RSS")] {
        let services = Arc::new(MemoryServices::new().with_http(move |_| Ok(HttpResponse { status, headers: vec![], body: body.as_bytes().to_vec() })));
        let host = PluginHost::new(services, HostLimits::default()).unwrap();
        let dir = module_dir(&format!("err{status}"), |_| {});
        let p = host.load_as(&dir, Tier::Official).unwrap();
        let e = p.call_tool("news_headlines", "{}", &CallContext::default()).unwrap_err();
        assert!(format!("{e:?}").contains(needle), "{e:?}");
        let _ = std::fs::remove_dir_all(dir);
    }
}

#[test]
fn without_the_http_grant_it_does_not_instantiate() {
    let host = PluginHost::new(Arc::new(MemoryServices::new()), HostLimits::default()).unwrap();
    let dir = module_dir("nogrant", |m| m["daemon"]["needs"] = serde_json::json!([]));
    let err = host.load_as(&dir, Tier::Official).err().expect("must fail");
    eprintln!("refused as expected: {err}");
    let _ = std::fs::remove_dir_all(dir);
}
