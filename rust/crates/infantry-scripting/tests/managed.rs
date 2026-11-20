// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
//! The managed library end to end (without the daemon): save rules, hooks that name a script by id and survive a
//! rename, stats in the overview, and the one-time import of loose files and hooks.json.
use infantry_core::db::Db;
use infantry_scripting::engine::ScriptEngines;
use infantry_scripting::hooks::HookStore;
use infantry_scripting::library::ScriptLibrary;
use infantry_scripting::manage::ScriptManager;
use serde_json::Map;
use std::path::PathBuf;
use std::sync::Arc;

fn setup(tag: &str) -> (Arc<ScriptLibrary>, ScriptManager, PathBuf) {
    let dir = std::env::temp_dir().join(format!("ms-managed-{tag}-{}", std::process::id()));
    let _ = std::fs::remove_dir_all(&dir);
    std::fs::create_dir_all(&dir).unwrap();
    let lib = Arc::new(ScriptLibrary::new(Arc::new(Db::open(dir.join("t.db")).unwrap())).unwrap());
    let d2 = dir.clone();
    let hooks = Arc::new(HookStore::new(Arc::new(move |p: &str| Some(d2.join(p))), None).with_library(lib.clone()));
    let m = ScriptManager { library: lib.clone(), hooks, engines: ScriptEngines::standard(), plugins: None, min_every_sec: Arc::new(|| 10) };
    (lib, m, dir)
}

#[test]
fn save_refuses_a_missing_title_or_description_and_a_bad_parse() {
    let (_l, m, _d) = setup("save");
    let e = m.save("project", "p1", "a", None, "", "does x", "return 1", None, "me").unwrap_err();
    assert!(e.contains("title"), "{e}");
    let e = m.save("project", "p1", "a", None, "A", "  ", "return 1", None, "me").unwrap_err();
    assert!(e.contains("description"), "{e}");
    assert!(m.save("project", "p1", "a", None, "A", "does x", "return (", None, "me").unwrap_err().contains("does not parse"));
    let (s, replaced) = m.save("project", "p1", "a", None, "A", "does x", "return 1", Some(vec!["t".into()]), "me").unwrap();
    assert!(!replaced && s.tags == ["t"]);
}

#[test]
fn hooks_name_the_script_by_id_and_show_in_the_overview() {
    let (lib, m, _d) = setup("hooks");
    let (s, _) = m.save("project", "p1", "on-done", None, "On done", "reacts when a ticket is done", "return 1", None, "me").unwrap();
    let hid = m.hook_add("p1", &s.id, "lane enter done", Map::new(), None, false, "owner").unwrap();
    let rows = m.overview("p1");
    let row = rows.iter().find(|r| r["name"] == "on-done").unwrap();
    assert_eq!(row["hooks"][0]["id"], hid.as_str());
    assert_eq!(row["hooks"][0]["on"], "lane enter done");
    assert_eq!(row["status"], "never-run");
    // a run shows in the stats
    lib.record_run("p1", "on-done", false, Some(40), Some("boom"), 7).unwrap();
    let row = m.overview("p1").into_iter().find(|r| r["name"] == "on-done").unwrap();
    assert_eq!((row["stats"]["runs"].as_i64(), row["status"].as_str(), row["stats"]["lastError"].as_str()), (Some(1), Some("failing"), Some("boom")));
    // a hook stored under the script id follows a rename of the script's name column
    lib.db().write(|c| c.execute("UPDATE ms_scripts SET name = 'renamed' WHERE id = ?1", [&s.id])).unwrap();
    assert_eq!(m.hooks.list_own("p1")[0].script, "renamed");
    // disabling and removing a hook
    m.hook_set_enabled("p1", &hid, false, false).unwrap();
    assert!(!m.hooks.list_own("p1")[0].enabled);
    m.hook_remove("p1", &hid, false).unwrap();
    assert!(m.hooks.list_own("p1").is_empty());
    assert!(m.hook_add("p1", &s.id, "every 1s", Map::new(), None, false, "owner").unwrap_err().contains("below the minimum"));
}

#[test]
fn files_and_hooks_json_are_imported_once() {
    let (lib, m, dir) = setup("import");
    let scripts = dir.join("p1").join("scripts");
    std::fs::create_dir_all(&scripts).unwrap();
    std::fs::write(scripts.join("greet.lua"), "-- Greets the room.\nreturn 1").unwrap();
    std::fs::write(scripts.join("greet-2.lua"), "return 2").unwrap();
    std::fs::write(scripts.join("hooks.json"), r#"{"version":1,"hooks":[{"id":"h1","on":"lane enter done","script":"greet"}]}"#).unwrap();
    let log = lib.migrate_files(None, None, &[("p1".into(), dir.join("p1"))], &["lua".to_string()]);
    assert!(log.iter().any(|l| l.contains("imported 2 script(s) of project p1")), "{log:?}");
    let g = lib.find("project", "p1", "greet").unwrap().unwrap();
    assert_eq!((g.title.as_str(), g.description.as_str()), ("Greet", "Greets the room."));
    assert!(lib.find("project", "p1", "greet-2").unwrap().unwrap().flag.is_some());
    assert_eq!(m.hooks.list_own("p1")[0].script, "greet");
    assert_eq!(lib.hooks("p1").unwrap()[0].1.as_deref(), Some(g.id.as_str()), "the hook holds the script id");
    // a second start imports nothing and deleting the file does not bring the script back
    std::fs::remove_file(scripts.join("greet-2.lua")).unwrap();
    m.delete(&lib.find("project", "p1", "greet-2").unwrap().unwrap().id).unwrap();
    assert!(lib.migrate_files(None, None, &[("p1".into(), dir.join("p1"))], &["lua".to_string()]).is_empty());
    assert!(lib.find("project", "p1", "greet-2").unwrap().is_none());
    // export writes files + sidecars that import back under another project
    let out = dir.join("export");
    let rows = lib.list(Some("p1")).unwrap();
    assert_eq!(lib.export_to(&rows, &out).unwrap(), 1);
    assert!(out.join("greet.lua.meta.json").is_file());
}
