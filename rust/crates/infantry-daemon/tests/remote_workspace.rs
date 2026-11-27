// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
//! RR-7 end to end: a runner behind the link (RR-2) on port 0, an orchestrator `Daemon` paired to it, a local BARE repository
//! as the project's `origin`. A ticket pinned to the runner gets its worktree on the runner in a clone of origin, on the branch
//! a local start would cut; a commit made there is pushed and visible in origin (and in the orchestrator's repository); and
//! the CLI's usage record is read on the runner through the runner-side probe.
use infantry_daemon::compose::remote_ws::RunnerProbe;
use infantry_runner_pool::workspace::RemoteWorkspaces;
use infantry_runner_source::remote::{RemoteHost, RemoteWorkspace, TicketSpec};
use infantry_runner_source::RunnerEntry;
use infantry_daemon::harness::{driven::Driven, Harness as _, HarnessRegistry};
use infantry_daemon::sessions::{Config, Daemon};
use infantry_harness_claude::ClaudeHarness;
use infantry_projects::ticket_branches::branch_for;
use infantry_runner_link::pairing::{Bearer, BearerToken};
use infantry_runner_link::process::ProcessBackend;
use infantry_runner_link::{serve, LinkServer, LinkStore};
use infantry_runner_local::{LocalConfig, LocalRunner};
use infantry_runner_pool::RunnerPool;
use infantry_harness::*;
use infantry_daemon::sessions::NewOpts;
use std::path::{Path, PathBuf};
use std::sync::Arc;

/// A fake agent CLI: prints its working directory, then stays alive.
struct FakeCli;
struct Lines;
impl Translator for Lines {
    fn feed(&mut self, input: TranslatorInput) -> Vec<infantry_harness::HarnessEvent> {
        match input {
            TranslatorInput::Stdout(l) => vec![infantry_harness::HarnessEvent::Stdout(l)],
            TranslatorInput::Exited { code } => vec![
                infantry_harness::HarnessEvent::TurnEnded { end: infantry_harness::TurnEnd::Completed, reason: None },
                infantry_harness::HarnessEvent::Exited { code: Some(code) },
            ],
            _ => Vec::new(),
        }
    }
}
impl infantry_harness::Harness for FakeCli {
    fn id(&self) -> &str {
        "fakecli"
    }
    fn capabilities(&self) -> Capabilities {
        Capabilities { delivery: TurnDelivery::SpawnPerTurn, pty_screen: false, file_tail: false, hook_channel: false, http: false, resume: false, usage: false, model_selection: false, approval_modes: vec![ApprovalMode::AllowAll] }
    }
    fn models(&self, _p: &dyn Probe) -> Vec<ModelInfo> {
        Vec::new()
    }
    fn sign_in_problem(&self, _p: &dyn Probe) -> Option<String> {
        None
    }
    fn launch(&self, req: &LaunchRequest) -> Result<LaunchPlan, LaunchError> {
        #[cfg(windows)]
        let spec = ProcessSpec::new("cmd", ["/C", "cd && ping -n 60 127.0.0.1 > nul"]);
        #[cfg(not(windows))]
        let spec = ProcessSpec::new("sh", ["-c", "pwd; sleep 60"]);
        Ok(LaunchPlan { spec: spec.cwd(req.cwd.to_string_lossy()), stdin: None, translator: Box::new(Lines), tail: Vec::new() })
    }
}

/// The daemon side of the seam, for a test: the registry's runners, no lent credentials, one project checkout.
struct Host {
    daemon: Arc<Daemon>,
    repo: PathBuf,
}
impl RemoteHost for Host {
    fn runner(&self, name: &str) -> Result<RunnerEntry, String> {
        self.daemon.runner_registry().resolve(name).map_err(|r| r.to_string())
    }
    fn git_credentials(&self, _runner: &str, _project: Option<&str>) -> Option<infantry_runner_api::LentCredentials> {
        None
    }
    fn repo_of(&self, _project: Option<&str>) -> Option<PathBuf> {
        Some(self.repo.clone())
    }
    fn base_branch(&self, _project: Option<&str>) -> Option<String> {
        Some("main".into())
    }
    fn setting(&self, _key: &str) -> Option<serde_json::Value> {
        None
    }
}

fn temp(tag: &str) -> PathBuf {
    let d = std::env::temp_dir().join(format!("rr7-{tag}-{}-{}", std::process::id(), std::time::SystemTime::now().duration_since(std::time::UNIX_EPOCH).unwrap().as_nanos()));
    std::fs::create_dir_all(&d).unwrap();
    d
}

fn git(dir: &Path, args: &[&str]) -> String {
    let o = infantry_proc::command("git")
        .args(["-c", "user.name=Owner", "-c", "user.email=owner@example.com", "-c", "protocol.file.allow=always"])
        .args(args)
        .current_dir(dir)
        .output()
        .unwrap();
    assert!(o.status.success(), "git {args:?} in {}: {}", dir.display(), String::from_utf8_lossy(&o.stderr));
    String::from_utf8_lossy(&o.stdout).trim().to_string()
}

#[test]
fn a_ticket_on_a_remote_runner_works_in_its_own_branch_and_pushes_it_to_origin() {
    // origin = a local bare repo; the project's own checkout is a clone of it with one commit on main.
    let root = temp("repos");
    let origin = root.join("origin.git");
    std::fs::create_dir_all(&origin).unwrap();
    git(&origin, &["init", "--bare", "-b", "main"]);
    let proj = root.join("proj");
    git(&root, &["clone", &origin.to_string_lossy(), &proj.to_string_lossy()]);
    git(&proj, &["checkout", "-B", "main"]);
    std::fs::write(proj.join("a.txt"), "one").unwrap();
    git(&proj, &["add", "."]);
    git(&proj, &["commit", "-m", "first"]);
    git(&proj, &["push", "origin", "main"]);
    let base_sha = git(&proj, &["rev-parse", "main"]);

    // The runner behind the link, and the orchestrator paired to it.
    let (rdir, odir) = (temp("runner"), temp("orch"));
    let runner = Arc::new(LocalRunner::new(LocalConfig { scratch_dir: Some(rdir.join("runner")), ..Default::default() }));
    let bearer = Bearer::of(&rdir);
    let v = bearer.ensure(Some("box"), false).unwrap();
    let token = BearerToken { name: v.name, fingerprint: None, secret: v.secret };
    let server = LinkServer::new(LinkStore::in_memory(), None, Some(bearer), Box::new(ProcessBackend::new(runner.clone())), None).with_log(|_| {});
    let serving = serve(Arc::new(server), "127.0.0.1", 0, None).unwrap();
    let rt = tokio::runtime::Builder::new_multi_thread().worker_threads(2).enable_all().build().unwrap();
    let _g = rt.enter();
    let mut cfg = Config::new(odir.clone(), odir.clone());
    let claude = Arc::new(ClaudeHarness::default());
    cfg.registry = HarnessRegistry::new(vec![claude.clone()]);
    let daemon = Daemon::new(Arc::new(Driven::new(claude.clone())), cfg).unwrap();
    let pool = RunnerPool::open(&odir);
    pool.set_allow_plain(Arc::new(|| true));
    pool.add(&format!("ws://127.0.0.1:{}", serving.port), &token.secret, None, Some("box"), "orch").unwrap();
    pool.check("box");
    daemon.runner_registry().add_source(pool);
    let ws = RemoteWorkspaces::new(Arc::new(Host { daemon: daemon.clone(), repo: proj.clone() }), rt.handle().clone());

    // 1. A pinned ticket gets its worktree ON the runner, on the branch a local start cuts, from origin/main.
    let req = TicketSpec {
        ticket_id: "ticket-abcd1234".into(),
        title: "Add the thing".into(),
        project_id: Some("p1".into()),
        base_branch: None,
        recorded_branch: None,
        recorded_base: None,
    };
    let w = ws.ensure("box", &req).expect("the workspace is made on the runner");
    assert_eq!(w.branch, branch_for("ticket-abcd1234", "Add the thing"));
    assert_eq!(w.base, base_sha, "cut from origin/main");
    let wt = PathBuf::from(&w.path);
    assert!(wt.join(".git").exists(), "the worktree is on the runner's host: {}", w.path);
    // Compared canonically: the temp dir may be reached through an alias (CI's 8.3 name RUNNER~1, a junction) while the
    // runner reports the resolved path.
    let canon = |p: &Path| std::fs::canonicalize(p).unwrap();
    assert!(canon(&wt).starts_with(canon(&rdir.join("runner"))), "under the runner's own folder, never the orchestrator's checkout: {}", w.path);
    assert_eq!(git(&wt, &["rev-parse", "--abbrev-ref", "HEAD"]), w.branch);
    assert_eq!(git(&wt, &["config", "user.email"]), "owner@example.com", "the orchestrator's git identity is in the clone");

    // 2. A commit made there is pushed and visible in origin (and the branch comes back to the orchestrator's repo).
    std::fs::write(wt.join("b.txt"), "two").unwrap();
    git(&wt, &["add", "."]);
    git(&wt, &["commit", "-m", "work on the runner"]);
    let head = git(&wt, &["rev-parse", "HEAD"]);
    let pushed = ws.push("box", Some("p1"), &w.path, &w.branch).expect("the push works");
    assert_eq!(pushed, head);
    assert_eq!(git(&origin, &["rev-parse", &format!("refs/heads/{}", w.branch)]), head, "visible in origin");
    assert_eq!(git(&proj, &["rev-parse", &format!("refs/heads/{}", w.branch)]), head, "the branch is back in the orchestrator's repo");

    // 3. A second start of the same ticket reuses the branch and the worktree (never a second branch).
    let again = TicketSpec { recorded_branch: Some(w.branch.clone()), recorded_base: Some(w.base.clone()), ..req.clone() };
    let w2 = ws.ensure("box", &again).expect("reuse");
    assert_eq!((w2.branch.as_str(), w2.path.as_str()), (w.branch.as_str(), w.path.as_str()));

    // 4. Usage stats are read ON the runner: the CLI's transcript lives in the runner's config dir.
    let cfgdir = rdir.join("claude-home");
    std::fs::create_dir_all(cfgdir.join("projects/p")).unwrap();
    let line = r#"{"type":"assistant","timestamp":"2026-10-21T10:00:01Z","message":{"id":"m1","role":"assistant","model":"model-x","content":[],"usage":{"input_tokens":100,"cache_creation_input_tokens":0,"cache_read_input_tokens":900,"output_tokens":20}}}"#;
    std::fs::write(cfgdir.join("projects/p/sess1.jsonl"), format!("{line}\n")).unwrap();
    std::env::set_var("CLAUDE_CONFIG_DIR", &cfgdir);
    let entry = daemon.runner_registry().resolve("box").unwrap();
    let probe = RunnerProbe::new(entry.runner, rt.handle().clone());
    let events = Driven::new(claude).store_stats_via(&probe, "sess1").expect("the transcript is read through the runner");
    let stats = infantry_harness::stats::fold(&events);
    assert_eq!(stats.models, vec!["model-x".to_string()]);

    // 5. A session placed on the runner with that worktree runs IN it (the runner's folder), and records the branch.
    let odir2 = temp("orch2");
    let mut cfg2 = Config::new(odir2.clone(), odir2.clone());
    cfg2.registry = HarnessRegistry::new(vec![Arc::new(FakeCli)]);
    let daemon2 = Daemon::new(Arc::new(Driven::new(Arc::new(FakeCli))), cfg2).unwrap();
    let pool2 = RunnerPool::open(&odir2);
    pool2.set_allow_plain(Arc::new(|| true));
    pool2.add(&format!("ws://127.0.0.1:{}", serving.port), &token.secret, None, Some("box"), "orch2").unwrap();
    pool2.check("box");
    daemon2.runner_registry().add_source(pool2);
    let sid = daemon2
        .launch(NewOpts {
            provider: Some("fakecli".into()),
            runner: Some("box".into()),
            prompt: Some("go".into()),
            runner_cwd: Some(w.path.clone()),
            worktree: Some((w.branch.clone(), w.base.clone(), "root".into())),
            ..Default::default()
        })
        .expect("the session starts on the runner");
    let rec = daemon2.record(&sid).unwrap();
    assert_eq!(rec.runner, "box");
    assert_eq!(rec.worktree_path.as_deref(), Some(w.path.as_str()), "the session's folder is the worktree ON the runner");
    assert_eq!(rec.worktree_branch.as_deref(), Some(w.branch.as_str()));
    let _ = daemon2.stop_session(&sid, "test done");
}
