// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
//! CHAT-IMG-2: a plugin posts an image into a session's chat through the host API (the `frames` import sending the
//! daemon's `chat_image` frame), with the same checks as the `infantry_chat_image` tool. A composed daemon on a scratch
//! state dir; nothing of the owner's is touched.
//!
//! Its own test binary: it points `INFANTRY_PLUGINS_DIR` at an empty temp folder, a process-wide setting.
use base64::Engine;
use infantry_daemon::harness::stub::Stub;
use infantry_daemon::sessions::{Config, Daemon};
use infantry_plugin_host::imports::PluginImports;
use infantry_plugin_host::manifest::{DaemonDecl, Tier};
use infantry_protocol::Envelope;
use serde_json::{json, Value};
use std::collections::BTreeSet;
use std::sync::Arc;

const PNG: [u8; 12] = [0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 0, 0, 0, 1];

#[tokio::test(flavor = "multi_thread")]
async fn a_plugin_posts_an_image_into_a_sessions_chat() {
    let dir = std::env::temp_dir().join(format!("infantry-chat-img-2-{}", uuid::Uuid::new_v4()));
    let (state, plugins, proj, out) = (dir.join("state"), dir.join("plugins"), dir.join("proj"), dir.join("results"));
    for d in [plugins.join("official"), state.clone(), proj.clone(), out.join(".ssh")] {
        std::fs::create_dir_all(d).unwrap();
    }
    std::fs::write(out.join("shot.png"), PNG).unwrap();
    std::fs::write(out.join(".ssh").join("k.png"), PNG).unwrap();
    std::env::set_var("INFANTRY_PLUGINS_DIR", &plugins);
    let daemon = Daemon::new(Arc::new(Stub::default()), Config::new(state.clone(), dir.clone())).unwrap();
    let c = infantry_daemon::compose::build(&daemon, &state).unwrap();
    let saved = c.request(&Envelope::new("project_save", json!({"name": "img", "directory": proj.display().to_string()}).to_string()), true).unwrap();
    let pid = serde_json::from_str::<Value>(&saved[0].data).unwrap()["id"].as_str().unwrap().to_string();

    let decl: DaemonDecl = serde_json::from_value(json!({ "needs": ["frames"] })).unwrap();
    let services = c.plugins.as_ref().expect("the plugin host is up").services().clone();
    let p = PluginImports::new("Test_News", Tier::Official, decl, services, ["frames".to_string()].into(), BTreeSet::new());
    let made = p.frame_request("new", &json!({"name": "Reader", "cwd": proj.display().to_string()}).to_string(), None, 5000).unwrap();
    let sid = made.session_id.expect("a bound session");
    c.daemon.set_project(&sid, &pid).unwrap();

    let send = |data: Value| -> Value {
        let f = p.frame_request("chat_image", &data.to_string(), None, 5000).unwrap();
        assert_eq!(f.r#type, "chat_image_result");
        serde_json::from_str(&f.data).unwrap()
    };
    let r = send(json!({"sessionId": sid, "path": out.join("shot.png").display().to_string(), "caption": "headline"}));
    assert_eq!(r["isError"], false, "{r}");
    let enc = base64::engine::general_purpose::STANDARD.encode(PNG);
    assert_eq!(send(json!({"sessionId": sid, "data": enc}))["isError"], false);
    let r = send(json!({"sessionId": sid, "path": out.join(".ssh").join("k.png").display().to_string()}));
    assert!(r["text"].as_str().unwrap().contains("secrets"), "{r}");
    let r = send(json!({"sessionId": sid, "path": state.join("infantry.db").display().to_string()}));
    assert_eq!(r["isError"], true, "the daemon's state is refused: {r}");
    assert!(proj.join(".infantry/uploads/chat").read_dir().unwrap().count() >= 1, "stored in the project's uploads");

    drop(c);
    let _ = std::fs::remove_dir_all(&dir);
}
