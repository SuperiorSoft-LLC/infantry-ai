// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
//! The managed script library through the composed daemon: the bundled scripts are seeded with a title and a
//! description, `infantry_script_save` refuses a missing title or description, and the Scripts view's frames list,
//! save, version, restore, disable and delete a script. Project-free (global scope), no port bound.
use infantry_daemon::harness::stub::Stub;
use infantry_daemon::sessions::{Config, Daemon};
use infantry_protocol::Envelope;
use infantry_scripting::host::{ScriptHost, ToolInvoker};
use serde_json::{json, Map, Value};
use std::sync::Arc;

fn compose() -> (std::path::PathBuf, Arc<infantry_daemon::compose::Composed>) {
    let dir = std::env::temp_dir().join(format!("infantry-daemon-scripts-{}", uuid::Uuid::new_v4()));
    std::fs::create_dir_all(&dir).unwrap();
    let daemon = Daemon::new(Arc::new(Stub::default()), Config::new(dir.clone(), dir.clone())).unwrap();
    let c = infantry_daemon::compose::build(&daemon, &dir).unwrap();
    (dir, c)
}

fn ask(c: &infantry_daemon::compose::Composed, ty: &str, body: Value, owner: bool) -> Result<Value, String> {
    let out = c.request(&Envelope::new(ty, body.to_string()), owner)?;
    Ok(serde_json::from_str(&out.into_iter().next().expect("one answer").data).unwrap())
}

fn args(v: Value) -> Map<String, Value> {
    v.as_object().unwrap().clone()
}

#[tokio::test(flavor = "multi_thread")]
async fn the_scripts_view_frames_and_the_save_tool_work() {
    let (dir, c) = compose();
    // the bundled starter set is in the library with a title and a description each
    let list = ask(&c, "scripts_list", json!({}), false).unwrap();
    let rows = list["scripts"].as_array().unwrap();
    let cap = rows.iter().find(|r| r["name"] == "worker-cap").expect("worker-cap is seeded");
    assert_eq!(cap["title"], "Worker cap");
    assert!(cap["description"].as_str().unwrap().starts_with("how to change the number of parallel ticket workers"), "{}", cap["description"]);
    assert_eq!(cap["scope"], "global");

    // the tool refuses a save without title or description, and lists what exists
    let owner = ToolInvoker { owner: true, ..Default::default() };
    let r = c.tools.invoke("infantry_script_save", &args(json!({"name": "mine", "scope": "global", "source": "return {}"})), &owner);
    assert!(r.is_error && r.text.contains("needs title"), "{}", r.text);
    let r = c.tools.invoke("infantry_script_save", &args(json!({"name": "mine", "scope": "global", "title": "Mine", "source": "return {}"})), &owner);
    assert!(r.is_error && r.text.contains("needs description"), "{}", r.text);
    let r = c.tools.invoke("infantry_script_save", &args(json!({"name": "mine", "scope": "global", "title": "Mine", "description": "does a thing", "tags": "a, b", "source": "return {}"})), &owner);
    assert!(!r.is_error, "{}", r.text);
    let l = c.tools.invoke("infantry_script_list", &args(json!({"query": "does a thing"})), &owner);
    assert!(l.text.contains("\"Mine\": does a thing") && l.text.contains("tags: a,b"), "{}", l.text);

    // the view: open, edit (new version), restore, disable, delete; reads are open, writes are the owner's
    let id = ask(&c, "scripts_list", json!({}), false).unwrap()["scripts"].as_array().unwrap().iter().find(|r| r["name"] == "mine").unwrap()["id"].as_str().unwrap().to_string();
    let save = |body: &str, owner: bool| ask(&c, "scripts_save", json!({"id": id, "title": "Mine", "description": "does a thing", "body": body, "tags": ["a"]}), owner);
    assert!(save("return {x=1}", false).unwrap_err().contains("only the owner"));
    let v2 = save("return {x=1}", true).unwrap();
    assert_eq!((v2["version"].as_i64(), v2["body"].as_str()), (Some(2), Some("return {x=1}")));
    assert!(save("return (", true).unwrap_err().contains("does not parse"));
    let back = ask(&c, "scripts_restore", json!({"id": id, "version": 1}), true).unwrap();
    assert_eq!((back["version"].as_i64(), back["body"].as_str(), back["versions"].as_array().unwrap().len()), (Some(3), Some("return {}"), 3));
    let off = ask(&c, "scripts_enable", json!({"id": id, "enabled": false}), true).unwrap();
    assert_eq!((off["enabled"].as_bool(), off["status"].as_str()), (Some(false), Some("disabled")));
    let gone = ask(&c, "scripts_delete", json!({"id": id}), true).unwrap();
    assert_eq!(gone["ok"], true);
    assert!(ask(&c, "scripts_get", json!({"id": id}), false).is_err());
    let _ = std::fs::remove_dir_all(dir);
}
