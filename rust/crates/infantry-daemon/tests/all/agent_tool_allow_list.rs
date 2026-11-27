// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
//! AR-8: a role's `tools` list is its sessions' MCP allow-list. `plugin_tools` for such a session lists only the named
//! tools plus the communication family; `plugin_tool_call` of any other tool is refused daemon-side. A role with no
//! `tools` keeps the full set. A composed daemon on a scratch state dir and a free loopback port.
use infantry_conformance::client::Client;
use infantry_daemon::auth::AccessPolicy;
use infantry_daemon::harness::stub::Stub;
use infantry_daemon::server::{self, App};
use infantry_daemon::sessions::{Config, Daemon, NewOpts};
use infantry_knowledge::roles::RoleSave;
use infantry_protocol::Envelope;
use serde_json::{json, Value};
use std::sync::Arc;
use std::time::Duration;

const T: Duration = Duration::from_secs(10);

#[tokio::test(flavor = "multi_thread")]
async fn a_role_tools_list_is_the_session_allow_list() {
    let dir = std::env::temp_dir().join(format!("infantry-ar8-{}", uuid::Uuid::new_v4()));
    std::fs::create_dir_all(&dir).unwrap();
    let daemon = Daemon::new(Arc::new(Stub::default()), Config::new(dir.clone(), dir.clone())).unwrap();
    let c = infantry_daemon::compose::build(&daemon, &dir).unwrap();
    c.knowledge.roles.save(&RoleSave { id: "narrow".into(), name: "Narrow".into(), tools: Some(vec!["infantry_board".into()]), ..Default::default() }).unwrap();
    let (tx, rx) = std::sync::mpsc::channel();
    let d = daemon.clone();
    std::thread::spawn(move || {
        let rt = tokio::runtime::Runtime::new().unwrap();
        rt.block_on(async move {
            let policy = Arc::new(AccessPolicy::new("127.0.0.1", None).unwrap());
            let listener = tokio::net::TcpListener::bind("127.0.0.1:0").await.unwrap();
            tx.send(listener.local_addr().unwrap().port()).unwrap();
            server::serve(listener, App { daemon: d, policy }, std::future::pending()).await.unwrap();
        });
    });
    let port = rx.recv_timeout(T).unwrap();
    let mut ws = Client::connect(&format!("ws://127.0.0.1:{port}"), None).unwrap();
    ws.wait_for(T, |e| e.r#type == "ready").unwrap();
    let narrow = daemon.launch(NewOpts { role: Some("narrow".into()), ..Default::default() }).unwrap();
    let full = daemon.launch(NewOpts { role: Some("dev".into()), ..Default::default() }).unwrap();

    let mut n = 0;
    let mut req = |ty: &str, data: Value| -> Value {
        n += 1;
        let rid = format!("r{n}");
        let mut e = Envelope::new(ty, data.to_string());
        e.request_id = Some(rid.clone());
        ws.send(&e).unwrap();
        let r = ws.wait_for(T, |r| r.request_id.as_deref() == Some(rid.as_str())).unwrap();
        serde_json::from_str(&r.data).unwrap_or(Value::String(r.data))
    };
    let names = |v: &Value| -> Vec<String> { v.as_array().unwrap().iter().filter_map(|d| d["name"].as_str().map(String::from)).collect() };

    // The list: the named tool plus the communication family, nothing else.
    let listed = names(&req("plugin_tools", json!({"client": {"sessionId": narrow}})));
    let mut want: Vec<String> = infantry_daemon::compose::COMMUNICATION_TOOLS.iter().map(|s| s.to_string()).collect();
    want.push("infantry_board".into());
    let mut got = listed.clone();
    got.sort();
    want.sort();
    assert_eq!(got, want, "the narrow role's list");

    // A role without tools, and a client with no session, keep the full set.
    let all = names(&req("plugin_tools", Value::Null));
    assert!(all.len() > want.len());
    assert_eq!(names(&req("plugin_tools", json!({"client": {"sessionId": full}}))).len(), all.len());

    // Enforced on the call, not only by hiding: an unlisted tool is refused with the AR-8 text.
    let r = req("plugin_tool_call", json!({"name": "infantry_ticket_create", "arguments": {"title": "x"}, "client": {"sessionId": narrow}}));
    assert_eq!(r["isError"], json!(true));
    assert_eq!(r["text"], json!("tool infantry_ticket_create is not enabled for this agent"));
    // A spoofed client role does not widen a session's list: the session record's role wins.
    let r = req("plugin_tool_call", json!({"name": "infantry_ticket_create", "arguments": {"title": "x"}, "client": {"sessionId": narrow, "role": "dev"}}));
    assert_eq!(r["isError"], json!(true));
    // The communication family and the listed tool still run.
    let r = req("plugin_tool_call", json!({"name": "infantry_list_sessions", "arguments": {}, "client": {"sessionId": narrow}}));
    assert_ne!(r["text"], json!("tool infantry_list_sessions is not enabled for this agent"));
    let r = req("plugin_tool_call", json!({"name": "infantry_board", "arguments": {}, "client": {"sessionId": narrow}}));
    assert!(!r["text"].as_str().unwrap_or("").contains("is not enabled for this agent"), "{r}");
    // The full-set session is unchanged.
    let r = req("plugin_tool_call", json!({"name": "infantry_ticket_create", "arguments": {"title": "x"}, "client": {"sessionId": full}}));
    assert!(!r["text"].as_str().unwrap_or("").contains("is not enabled for this agent"), "{r}");
    ws.close();
    drop(c);
    let _ = std::fs::remove_dir_all(&dir);
}
