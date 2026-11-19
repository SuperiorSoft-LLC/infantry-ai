// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
//! Light proof: a script's `ih.chat_note{}` and `ih.chat_image{}` go through the dispatcher (alias table, builtin aliases)
//! to the real chat tools and post a visible timeline item in the target session.
use infantry_scripting::chat::{ChatTools, IMAGE, NOTE};
use infantry_scripting::dispatcher::{RunOptions, ScriptDispatcher};
use infantry_scripting::engine::{ScriptCaller, ScriptLimits};
use infantry_scripting::host::{ScriptHost, ToolInfo, ToolInvoker, ToolResult};
use infantry_scripting::lua::LuaEngine;
use infantry_scripting::runlog::ScriptRunLog;
use serde_json::{Map, Value};
use std::collections::BTreeMap;
use std::sync::{Arc, Mutex};

struct Host(ChatTools);

impl ScriptHost for Host {
    fn catalog(&self) -> Vec<ToolInfo> {
        ChatTools::catalog()
    }
    fn invoke(&self, name: &str, args: &Map<String, Value>, inv: &ToolInvoker) -> ToolResult {
        self.0.handle(name, args, inv).unwrap_or_else(|| ToolResult::err("no such tool"))
    }
}

#[test]
fn a_script_posts_a_note_and_an_image_into_a_session_chat() {
    let log = Arc::new(ScriptRunLog::new(Arc::new(|| 50), Arc::new(|| 5_000), None));
    let pushed: Arc<Mutex<Vec<Value>>> = Default::default();
    let p = pushed.clone();
    log.set_on_stats(Arc::new(move |v: &Value| p.lock().unwrap().push(v.clone())));
    let tmp = tempfile::tempdir().unwrap();
    let png = [0x89u8, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 0, 0, 0, 1];
    std::fs::write(tmp.path().join("shot.png"), png).unwrap();
    let dir = tmp.path().to_path_buf();
    let chat = ChatTools::new(
        log.clone(),
        Arc::new(|s: &str| (s == "worker-1").then(|| "p1".to_string())),
        Arc::new(move |_: &str| Some(dir.clone())),
        Arc::new(|_: &str, _: &str| Ok(())),
        Arc::new(|_: &str| None),
        Arc::new(|| 5_000),
    );
    let aliases: BTreeMap<String, String> = BTreeMap::new();
    let d = ScriptDispatcher::new(Arc::new(Host(chat)), Arc::new(move || aliases.clone()), Arc::new(|| 2));
    let src = r#"
        ih.chat_note{sessionId='worker-1', text='deploy finished', level='ok'}
        ih.chat_image{sessionId='worker-1', path='shot.png', caption='the dashboard'}
    "#;
    let r = d.run(&LuaEngine::new(), src, &Map::new(), ScriptLimits::default(), &ScriptCaller::new("pm-1", "pm"), &ToolInvoker::automation(Some("p1"), "script:t"), RunOptions::default(), &mut |_| {});
    assert!(r.ok(), "{}", r.render("t"));
    assert_eq!(r.records[0].tool, NOTE);
    assert_eq!(r.records[1].tool, IMAGE);
    let items = pushed.lock().unwrap();
    assert_eq!(items.len(), 2);
    assert_eq!((items[0]["sessionId"].as_str(), items[0]["text"].as_str()), (Some("worker-1"), Some("AUTOMATION · deploy finished")));
    assert!(items[1]["meta"]["image"].as_str().unwrap().contains("/.infantry/uploads/chat/"));
    assert!(items[1]["meta"]["imageData"].is_string());
    assert_eq!(log.timeline_items(10).len(), 2, "a reload of the chat shows both again");
}
