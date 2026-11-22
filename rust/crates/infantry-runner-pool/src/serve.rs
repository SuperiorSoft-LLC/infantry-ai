// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
//! The runner side of the plugin (RR-2): a daemon in `--mode runner` serves the runner link on one port from the start,
//! with the backend the composition root passes (a `ProcessBackend` over the daemon's LocalRunner). The bearer lives in
//! `<state>/runner-link/bearer` and is printed in an `==== INFANTRY RUNNER <name> ====` block on every start, rotation,
//! rename and pair. A PAIR carrying the live bearer from another orchestrator takes the runner over (the link server
//! audits `runner_link_pair_takeover`); a one-time code never moves a paired runner, and this side issues none.
use infantry_core::frames::{Envelope, FrameCtx, FrameUnit, Refusal};
use infantry_runner_link::pairing::{Bearer, BearerToken};
use infantry_runner_link::transport::LinkHandler;
use infantry_runner_link::wire::{self, Answer};
use infantry_runner_link::{serve, Backend, LinkServer, LinkServing, LinkStore};
use serde_json::{json, Map, Value};
use std::path::{Path, PathBuf};
use std::sync::{Arc, Mutex};

pub const LINK_PORT_KEY: &str = "limits.runners.linkPort";
pub const DEFAULT_LINK_PORT: u16 = 8790;
pub const LINK_BIND_KEY: &str = "runners.linkBindHost";
pub const DEFAULT_LINK_BIND: &str = "127.0.0.1";
pub const ADVERTISE_KEY: &str = "runners.advertiseHosts";
const LINK_DIR: &str = "runner-link";

/// What the root resolved before the link starts (CLI flag, then env, then setting, then default).
#[derive(Debug, Clone)]
pub struct ServeOptions {
    pub bind_host: String,
    pub port: u16,
    /// The runner's name (`INFANTRY_RUNNER_NAME`); None keeps the stored one (or `runner`).
    pub name: Option<String>,
    /// The host and port the banner prints; None = the next source (the setting, then `localhost`; the bound port).
    pub public_host: Option<String>,
    pub public_port: Option<u16>,
    pub advertise_hosts: Vec<String>,
    pub allow_projects: String,
    pub allow_harnesses: String,
    pub app_version: String,
}

pub struct RunnerServe {
    bearer: Bearer,
    store_dir: PathBuf,
    server: Arc<LinkServer>,
    serving: LinkServing,
    opts: ServeOptions,
    /// Serialises bearer changes with the banner print.
    lock: Mutex<()>,
}

/// Parses the setting `runners.advertiseHosts` (a JSON list, or a comma list).
pub fn advertise_hosts(v: &Value) -> Vec<String> {
    let items: Vec<String> = match v {
        Value::Array(a) => a.iter().filter_map(Value::as_str).map(str::to_string).collect(),
        Value::String(s) => s.split(',').map(str::to_string).collect(),
        _ => Vec::new(),
    };
    items.into_iter().map(|s| s.trim().to_string()).filter(|s| !s.is_empty()).collect()
}

struct Announcing {
    serve: std::sync::OnceLock<std::sync::Weak<RunnerServe>>,
    server: Arc<LinkServer>,
}

impl LinkHandler for Announcing {
    fn handle(&self, t: &str, data: &str, orchestrator_id: Option<&str>, credential: Option<&str>, from: Option<&str>) -> Answer {
        let a = self.server.handle(t, data, orchestrator_id, credential, from);
        if t == wire::PAIR && a.ok() {
            if let Some(s) = self.serve.get().and_then(|w| w.upgrade()) {
                s.print_banner();
            }
        }
        a
    }
}

impl RunnerServe {
    /// Binds the link and prints the banner. The listener is up before this returns, so a PAIR never needs a re-bind.
    pub fn start(state_dir: &Path, backend: Box<dyn Backend>, opts: ServeOptions) -> Result<Arc<RunnerServe>, String> {
        let dir = state_dir.join(LINK_DIR);
        let bearer = Bearer::of(&dir);
        bearer.ensure(opts.name.as_deref(), false).map_err(|e| e.to_string())?;
        let server = Arc::new(
            LinkServer::new(LinkStore::open(&dir), None, Some(Bearer::of(&dir)), backend, Some(dir.join("audit.jsonl")))
                .with_identity("runner", &opts.app_version),
        );
        let handler = Arc::new(Announcing { serve: Default::default(), server: server.clone() });
        let serving = serve(handler.clone(), &opts.bind_host, opts.port).map_err(|e| format!("the runner link cannot bind {}:{} ({e})", opts.bind_host, opts.port))?;
        let this = Arc::new(RunnerServe { bearer, store_dir: dir, server, serving, opts, lock: Mutex::new(()) });
        let _ = handler.serve.set(Arc::downgrade(&this));
        this.print_banner();
        Ok(this)
    }

    pub fn port(&self) -> u16 {
        self.serving.port
    }

    /// The host the banner shows: flag/env, then the first `runners.advertiseHosts`, then `localhost`. Never a detected address.
    pub fn endpoint(&self) -> String {
        let host = self.opts.public_host.clone().or_else(|| self.opts.advertise_hosts.first().cloned()).unwrap_or_else(|| "localhost".into());
        format!("ws://{host}:{}", self.opts.public_port.unwrap_or(self.serving.port))
    }

    pub fn bearer_text(&self) -> Option<String> {
        self.bearer.read().map(|v| BearerToken { name: v.name, fingerprint: None, secret: v.secret }.text())
    }

    pub fn last_paired(&self) -> Option<String> {
        self.server.store().orchestrator().map(|o| o.name)
    }

    /// The `INFANTRY RUNNER` block (stdout is what `docker logs` shows).
    pub fn print_banner(&self) {
        let _g = self.lock.lock().unwrap_or_else(|e| e.into_inner());
        let Some(v) = self.bearer.read() else { return };
        let token = BearerToken { name: v.name.clone(), fingerprint: None, secret: v.secret }.text();
        println!("==== INFANTRY RUNNER {} ====", v.name);
        println!("endpoint: {}", self.endpoint());
        println!("bearer:   {token}");
        println!("allow:    projects {}, harnesses {}", self.opts.allow_projects, self.opts.allow_harnesses);
        if let Some(p) = self.last_paired() {
            println!("last paired with: {p}");
        }
        println!("==== END INFANTRY RUNNER ====");
    }

    /// `runner_admin` `bearer`: the live bearer; `rotate` makes a new secret, `name` renames (a new secret too). Reprints the block.
    pub fn bearer_op(&self, rotate: bool, name: Option<&str>) -> Value {
        let renamed = name.is_some_and(|n| self.bearer.read().map(|v| v.name != n).unwrap_or(true));
        if rotate || renamed {
            let r = {
                let _g = self.lock.lock().unwrap_or_else(|e| e.into_inner());
                self.bearer.ensure(name, rotate)
            };
            if let Err(e) = r {
                return json!({"ok": false, "error": e.to_string()});
            }
            self.print_banner();
        }
        let Some(text) = self.bearer_text() else { return json!({"ok": false, "error": "this runner has no bearer"}) };
        let name = self.bearer.read().map(|v| v.name).unwrap_or_default();
        json!({"ok": true, "name": name, "endpoint": self.endpoint(), "bearer": text, "rotated": rotate, "lastPairedWith": self.last_paired(), "dir": self.store_dir.display().to_string()})
    }
}

/// The owner-only `runner_admin` frame of a runner-mode daemon: `bearer` (print it; `rotate: true` makes a new one).
pub struct RunnerSideAdmin {
    pub serve: Arc<RunnerServe>,
}

impl FrameUnit for RunnerSideAdmin {
    fn name(&self) -> &'static str {
        "runner-pool"
    }

    fn frames(&self) -> &'static [&'static str] {
        &[crate::ADMIN_FRAME]
    }

    fn handle(&self, env: &Envelope, ctx: &FrameCtx) -> Result<Vec<Envelope>, Refusal> {
        if !ctx.owner {
            return Err(Refusal::new(crate::ADMIN_FRAME, "owner only"));
        }
        let Ok(Value::Object(d)) = serde_json::from_str::<Value>(&env.data) else { return Err(Refusal::new(crate::ADMIN_FRAME, "malformed request")) };
        let text = |m: &Map<String, Value>, k: &str| m.get(k).and_then(Value::as_str).map(str::trim).filter(|s| !s.is_empty()).map(str::to_string);
        let answer = match text(&d, "op").as_deref() {
            Some("bearer") => self.serve.bearer_op(d.get("rotate").and_then(Value::as_bool).unwrap_or(false), text(&d, "name").as_deref()),
            Some(other) => json!({"ok": false, "error": format!("unknown op '{other}' on a runner (bearer)")}),
            None => json!({"ok": false, "error": "missing op"}),
        };
        let mut out = Envelope::new(crate::ADMIN_FRAME, answer.to_string());
        out.request_id = env.request_id.clone();
        Ok(vec![out])
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use infantry_runner_api::{ProcessSpec, ProcEvent, Runner};
    use infantry_runner_link::process::ProcessBackend;
    use infantry_runner_link::RemoteRunner;

    fn opts() -> ServeOptions {
        ServeOptions {
            bind_host: "127.0.0.1".into(),
            port: 0,
            name: Some("box".into()),
            public_host: None,
            public_port: None,
            advertise_hosts: vec![],
            allow_projects: "*".into(),
            allow_harnesses: "*".into(),
            app_version: "0.0.0-test".into(),
        }
    }

    fn start(dir: &Path) -> Arc<RunnerServe> {
        let backend = ProcessBackend::new(Arc::new(infantry_runner_local::LocalRunner::default()));
        RunnerServe::start(dir, Box::new(backend), opts()).unwrap()
    }

    fn token(rs: &RunnerServe) -> BearerToken {
        BearerToken::parse(&rs.bearer_text().unwrap()).unwrap()
    }

    #[test]
    fn add_check_spawn_and_takeover() {
        let tmp = std::env::temp_dir().join(format!("rr2-{}", uuid::Uuid::new_v4()));
        let rs = start(&tmp.join("runner"));
        let url = format!("ws://127.0.0.1:{}", rs.port());
        assert_eq!(rs.endpoint(), format!("ws://localhost:{}", rs.port()));

        let a = crate::RunnerPool::open(&tmp.join("a"));
        let b = crate::RunnerPool::open(&tmp.join("b"));
        let secret = token(&rs).secret;
        let added = a.add(&url, &secret, None, "orch-a").unwrap();
        assert_eq!(added.name, "box");
        let (state, ping) = a.check("box").unwrap();
        assert_eq!(state.word(), "online", "{state:?}");
        let hello = ping.unwrap().hello;
        assert_eq!((hello.kind.as_deref(), hello.app_version.as_deref()), (Some("runner"), Some("0.0.0-test")));

        // spawn `echo hi` through RemoteRunner: output and exit 0 arrive over frames_pull.
        let paired = a.store().runner("box").unwrap();
        let remote = RemoteRunner::new("box", infantry_runner_link::LinkClient::of(&paired, &a.orchestrator_id()));
        let rt = tokio::runtime::Builder::new_multi_thread().worker_threads(2).enable_all().build().unwrap();
        let (out, code) = rt.block_on(async {
            let spec = if cfg!(windows) { ProcessSpec::new("cmd", ["/c", "echo hi"]) } else { ProcessSpec::new("sh", ["-c", "echo hi"]) };
            let h = remote.spawn(spec).await.unwrap();
            let mut ev = remote.events(&h.id).await.unwrap();
            let (mut out, mut code) = (String::new(), None);
            while let Some(e) = tokio::time::timeout(std::time::Duration::from_secs(15), ev.recv()).await.unwrap() {
                match e {
                    ProcEvent::Output(b) => out.push_str(&String::from_utf8_lossy(&b)),
                    ProcEvent::Exited(c) => {
                        code = Some(c);
                        break;
                    }
                    _ => {}
                }
            }
            (out, code)
        });
        assert!(out.contains("hi"), "{out:?}");
        assert_eq!(code, Some(0));
        // kill a long sleep through the link: it exits.
        let killed = rt.block_on(async {
            let spec = if cfg!(windows) { ProcessSpec::new("ping", ["-n", "60", "127.0.0.1"]) } else { ProcessSpec::new("sleep", ["60"]) };
            let h = remote.spawn(spec).await.unwrap();
            let mut ev = remote.events(&h.id).await.unwrap();
            remote.kill(&h.id, infantry_runner_api::KillScope::Tree).await.unwrap();
            loop {
                match tokio::time::timeout(std::time::Duration::from_secs(15), ev.recv()).await.unwrap() {
                    Some(ProcEvent::Exited(c)) => break Some(c),
                    Some(_) => {}
                    None => break None,
                }
            }
        });
        assert!(killed.is_some(), "the killed process must report an exit");

        // A second orchestrator takes over with the bearer; the first gets NOT_PAIRED.
        let moved = b.add(&url, &secret, None, "orch-b").unwrap();
        assert_eq!(moved.name, "box");
        assert_eq!(b.check("box").unwrap().0.word(), "online");
        let (stale, _) = a.check("box").unwrap();
        assert!(matches!(&stale, crate::RunnerState::Offline { reason } if reason.to_lowercase().contains("paired")), "{stale:?}");
        assert_eq!(rs.last_paired().as_deref(), Some("orch-b"));
        let audit = std::fs::read_to_string(tmp.join("runner").join("runner-link").join("audit.jsonl")).unwrap();
        assert!(audit.contains("runner_link_pair_takeover"));

        // Rotation: the old bearer no longer pairs.
        let r = rs.bearer_op(true, None);
        assert_eq!(r["ok"], true);
        assert!(crate::RunnerPool::open(&tmp.join("c")).add(&url, &secret, None, "orch-c").is_err());
        assert!(crate::RunnerPool::open(&tmp.join("d")).add(&url, &token(&rs).secret, None, "orch-d").is_ok());
        let _ = std::fs::remove_dir_all(tmp);
    }
}
