// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
//! End to end over a TEMP repository only: never the owner's checkout.
use infantry_core::frames::{Envelope, EventBus, FrameCtx, Registry};
use infantry_projects::git::{GitCli, SystemGit};
use infantry_projects::service::GitViewConfig;
use infantry_projects::ticket_branches::{TicketBranchOutcome, TicketBranchRequest};
use infantry_projects::worktree::WorktreeProvisioner;
use infantry_projects::Projects;
use serde_json::{json, Value};
use std::path::Path;
use std::sync::Arc;

fn git() -> SystemGit {
    let mut g = SystemGit::new();
    for (k, v) in [("GIT_AUTHOR_NAME", "T"), ("GIT_AUTHOR_EMAIL", "t@e.x"), ("GIT_COMMITTER_NAME", "T"), ("GIT_COMMITTER_EMAIL", "t@e.x"), ("GIT_CONFIG_NOSYSTEM", "1")] {
        g.extra_env.push((k.into(), v.into()));
    }
    g
}

fn sh(g: &SystemGit, dir: &Path, args: &[&str]) {
    let r = g.run(dir, args).unwrap();
    assert!(r.ok(), "git {args:?}: {}", r.err());
}

fn ask(reg: &Registry, ty: &str, data: Value) -> Envelope {
    let ctx = FrameCtx { conn_id: 1, owner: true, bus: EventBus::new(16) };
    let data = match data {
        Value::String(s) => s,
        v => v.to_string(),
    };
    match reg.dispatch(&Envelope::new(ty, data), &ctx).expect("owned").map(|mut v| v.remove(0)) {
        Ok(e) => e,
        Err(r) => Envelope::new("error", r.0),
    }
}

fn body(e: &Envelope) -> Value {
    serde_json::from_str(&e.data).unwrap_or(Value::Null)
}

#[test]
fn project_git_frames_over_a_temp_repo() {
    let tmp = tempfile::tempdir().unwrap();
    let repo = tmp.path().join("repo");
    std::fs::create_dir_all(&repo).unwrap();
    let g = git();
    sh(&g, &repo, &["init", "-q", "-b", "main"]);
    std::fs::write(repo.join("a.txt"), "one\n").unwrap();
    sh(&g, &repo, &["add", "."]);
    sh(&g, &repo, &["commit", "-q", "-m", "first"]);

    let shared: Arc<dyn GitCli> = Arc::new(g.clone());
    let p = Projects::new(shared, None, GitViewConfig::default()).unwrap();
    let mut reg = Registry::new();
    reg.register(Arc::new(p.projects_unit(None))).unwrap();
    reg.register(Arc::new(p.git_unit())).unwrap();
    reg.register(Arc::new(p.worktrees_unit(Some(repo.clone())))).unwrap();

    let created = ask(&reg, "project_save", json!({"name": "Demo", "directory": repo.to_string_lossy()}));
    assert_eq!(created.r#type, "project", "{}", created.data);
    let id = body(&created)["id"].as_str().unwrap().to_string();

    // clean, then one modified + one untracked file
    let st = body(&ask(&reg, "project_git_status", Value::String(id.clone())));
    assert_eq!(st["available"], true, "{st}");
    assert_eq!(st["branch"], "main");
    assert_eq!(st["changes"].as_array().unwrap().len(), 0);
    std::fs::write(repo.join("a.txt"), "one\ntwo\n").unwrap();
    std::fs::write(repo.join("new.md"), "hi\n").unwrap();
    let st = body(&ask(&reg, "project_git_status", Value::String(id.clone())));
    assert_eq!(st["changes"].as_array().unwrap().len(), 2);

    let d = body(&ask(&reg, "project_git_diff", json!({"projectId": id, "path": "a.txt"})));
    assert!(d["patch"].as_str().unwrap().contains("+two"), "{d}");
    assert!(d.get("worktree").is_none(), "absent when not asked");
    let u = body(&ask(&reg, "project_git_diff", json!({"projectId": id, "path": "new.md"})));
    assert!(u["patch"].as_str().unwrap().contains("+hi"), "{u}");
    let bad = ask(&reg, "project_git_diff", json!({"projectId": id, "path": "../x"}));
    assert!(bad.data.starts_with("project_git_diff refused: path must not leave"), "{}", bad.data);

    // checkout refuses a dirty tree, then works on a clean one and switches back
    let r = body(&ask(&reg, "project_git_checkout", json!({"projectId": id, "branch": "feat", "create": true})));
    assert_eq!(r["code"], "dirty");
    sh(&g, &repo, &["add", "."]);
    sh(&g, &repo, &["commit", "-q", "-m", "second"]);
    let r = body(&ask(&reg, "project_git_checkout", json!({"projectId": id, "branch": "feat", "create": true})));
    assert_eq!(r["switched"], true, "{r}");
    std::fs::write(repo.join("f.rs"), "fn x() {}\n").unwrap();
    sh(&g, &repo, &["add", "."]);
    sh(&g, &repo, &["commit", "-q", "-m", "feature work"]);
    let r = body(&ask(&reg, "project_git_checkout", json!({"projectId": id, "branch": "main"})));
    assert_eq!(r["current"], "main", "{r}");

    let log = body(&ask(&reg, "project_git_log", json!({"projectId": id, "limit": 1})));
    assert_eq!(log["commits"].as_array().unwrap().len(), 1);
    assert_eq!(log["truncated"], true);
    let br = body(&ask(&reg, "project_git_branches", json!({"projectId": id})));
    assert_eq!(br["branches"].as_array().unwrap().len(), 2);
    let ds = body(&ask(&reg, "project_git_branch_changes", json!({"projectId": id, "branch": "feat"})));
    assert_eq!(ds["base"], "main", "{ds}");
    assert_eq!(ds["changes"][0]["path"], "f.rs");
    assert_eq!(ds["changes"][0]["added"], 1);
    let bd = body(&ask(&reg, "project_git_branch_diff", json!({"projectId": id, "branch": "feat", "path": "f.rs"})));
    assert!(bd["patch"].as_str().unwrap().contains("+fn x()"), "{bd}");

    // ticket branch cut from the base, listed as a worktree, with its own status
    let tb = p.branches.ensure(&TicketBranchRequest {
        ticket_id: "ticket-abcd1234".into(),
        title: "Add the thing".into(),
        repo: repo.clone(),
        base_branch: "main".into(),
        recorded_branch: None,
        recorded_base: None,
    });
    let TicketBranchOutcome::Ready { worktree, note, .. } = tb else { panic!("{tb:?}") };
    assert_eq!(worktree.branch, "ticket/abcd1234-add-the-thing");
    assert!(note.unwrap().starts_with("cut from main "));
    let wl = body(&ask(&reg, "project_git_worktrees", json!({"projectId": id})));
    assert_eq!(wl["worktrees"].as_array().unwrap().len(), 2, "{wl}");
    let wpath = wl["worktrees"][1]["path"].as_str().unwrap().to_string();
    let ws = body(&ask(&reg, "project_git_worktree_status", json!({"projectId": id, "path": wpath})));
    assert_eq!(ws["branch"], "ticket/abcd1234-add-the-thing", "{ws}");
    assert_eq!(ws["ahead"], 0, "{ws}");
    let nope = ask(&reg, "project_git_worktree_status", json!({"projectId": id, "path": tmp.path().to_string_lossy()}));
    assert!(nope.data.contains("refused: not a worktree"), "{}", nope.data);

    // session worktree create / list / remove
    let rec = p.worktrees.create("sess-0001", &repo, Some("Fix bug"), None).unwrap();
    assert!(rec.branch.starts_with("infantry/fix-bug-"));
    let all = body(&ask(&reg, "worktrees", Value::String(String::new())));
    assert_eq!(all[0]["exists"], true, "{all}");
    let rm = body(&ask(&reg, "worktree_remove", Value::String("sess-0001".into())));
    assert_eq!(rm["removed"], true, "{rm}");
    assert_eq!(rm["branchKept"], true);

    // project config + base + workspaces
    let c = body(&ask(&reg, "project_config", json!({"projectId": id, "laneCaps": {"dev": 2}, "data": {"build.root": "x"}})));
    assert_eq!(c["laneCaps"]["dev"], 2, "{c}");
    let b = body(&ask(&reg, "project_base", json!({"projectId": id, "branch": "main"})));
    assert_eq!(b["baseBranch"], "main", "{b}");
    let b = ask(&reg, "project_base", json!({"projectId": id, "branch": "nope"}));
    assert!(b.data.contains("does not resolve"), "{}", b.data);
    ask(&reg, "workspace_save", json!({"projectId": id, "name": "Main"}));
    let wsl = body(&ask(&reg, "workspace_list", Value::String(id.clone())));
    assert_eq!(wsl[0]["name"], "Main");
    let unknown = ask(&reg, "project_git_status", Value::String("project-zzz".into()));
    assert_eq!(unknown.data, "project_git_status refused: no project 'project-zzz'");
}

#[test]
fn no_directory_is_unavailable_not_clean() {
    let p = Projects::new(Arc::new(git()), None, GitViewConfig::default()).unwrap();
    let pr = p.store.save(&infantry_projects::wire::ProjectSave { name: Some("x".into()), ..Default::default() }).unwrap();
    let s = p.service.status(&pr);
    assert!(!s.available);
    assert_eq!(s.reason, "this project has no directory configured");
}
