// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
//! The runner side of the plugin (RR-2): a daemon in `--mode runner` serves the runner link on one port from the start,
//! with the backend the composition root passes (a `ProcessBackend` over the daemon's LocalRunner). The bearer lives in
//! `<state>/runner-link/bearer` and is printed in an `==== INFANTRY RUNNER <name> ====` block on every start, rotation,
//! rename and pair. A PAIR carrying the live bearer from another orchestrator takes the runner over (the link server
//! audits `runner_link_pair_takeover`); a one-time code never moves a paired runner. `infantry runner pair` (runner_admin
//! `pair`) issues one such code on this side; `pair_status` says whether it was used.
use infantry_core::frames::{Envelope, FrameCtx, FrameUnit, Refusal};
use infantry_runner_link::pairing::{Bearer, BearerToken};
use infantry_runner_link::transport::LinkHandler;
use infantry_runner_link::wire::{self, Answer};
use infantry_runner_link::{serve, Backend, LinkServer, LinkServing, LinkStore, RunnerCert};
use serde_json::{json, Map, Value};
use std::path::{Path, PathBuf};
use std::sync::{Arc, Mutex};

pub const LINK_PORT_KEY: &str = "limits.runners.linkPort";
pub const DEFAULT_LINK_PORT: u16 = 8790;
pub const LINK_BIND_KEY: &str = "runners.linkBindHost";
pub const DEFAULT_LINK_BIND: &str = "127.0.0.1";
pub const ADVERTISE_KEY: &str = "runners.advertiseHosts";
pub const LINK_DIR: &str = "runner-link";

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
    /// `runners.allowPlainTailnet`: serve plain `ws://` (no certificate, a plain bearer). Off = wss with the pinned certificate.
    pub allow_plain: bool,
    /// The one-time pair code's life (`limits.runners.pairCodeMin`, in ms here) and its redeem attempts per hour
    /// (`limits.runners.pairAttemptsPerHour`).
    pub pair_code_ms: i64,
    pub pair_attempts_per_hour: usize,
}

pub struct RunnerServe {
    bearer: Bearer,
    store_dir: PathBuf,
    server: Arc<LinkServer>,
    serving: LinkServing,
    /// The pinned certificate; None only when the link is served as plain ws.
    cert: Option<RunnerCert>,
    opts: ServeOptions,
    /// Serialises bearer changes with the banner print.
    lock: Mutex<()>,
    /// When the last `pair` code was issued (ms); None = never on this boot.
    pair_issued_at: Mutex<Option<i64>>,
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
            LinkServer::new(LinkStore::open(&dir), Some(infantry_runner_link::pairing::PairCodes::new(opts.pair_code_ms, opts.pair_attempts_per_hour)), Some(Bearer::of(&dir)), backend, Some(dir.join("audit.jsonl")))
                .with_identity("runner", &opts.app_version),
        );
        let handler = Arc::new(Announcing { serve: Default::default(), server: server.clone() });
        let cert = if opts.allow_plain { None } else { Some(RunnerCert::load_or_create(&dir).map_err(|e| e.to_string())?) };
        let tls = cert.as_ref().map(RunnerCert::server_config).transpose()?;
        let serving = serve(handler.clone(), &opts.bind_host, opts.port, tls).map_err(|e| format!("the runner link cannot bind {}:{} ({e})", opts.bind_host, opts.port))?;
        let this = Arc::new(RunnerServe { bearer, store_dir: dir, server, serving, cert, opts, lock: Mutex::new(()), pair_issued_at: Mutex::new(None) });
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
        format!("{}://{host}:{}", if self.cert.is_some() { "wss" } else { "ws" }, self.opts.public_port.unwrap_or(self.serving.port))
    }

    pub fn bearer_text(&self) -> Option<String> {
        self.bearer.read().map(|v| BearerToken { name: v.name, fingerprint: self.pin(), secret: v.secret }.text())
    }

    /// The certificate SHA-256 the bearer carries; None when the link is plain.
    pub fn pin(&self) -> Option<String> {
        self.cert.as_ref().map(|c| c.fingerprint.clone())
    }

    pub fn last_paired(&self) -> Option<String> {
        self.server.store().orchestrator().map(|o| o.name)
    }

    /// The `INFANTRY RUNNER` block (stdout is what `docker logs` shows).
    pub fn print_banner(&self) {
        let _g = self.lock.lock().unwrap_or_else(|e| e.into_inner());
        let Some(v) = self.bearer.read() else { return };
        let token = BearerToken { name: v.name.clone(), fingerprint: self.pin(), secret: v.secret }.text();
        println!("==== INFANTRY RUNNER {} ====", v.name);
        println!("endpoint: {}", self.endpoint());
        println!("bearer:   {token}");
        if self.cert.is_none() {
            println!("WARNING:  serving PLAIN ws (runners.allowPlainTailnet is on): the credential and every command travel unencrypted; private networks only");
        }
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

impl RunnerServe {
    /// `runner_admin` `pair_preview`: the harnesses this runner has, for the pair prompt.
    pub fn pair_preview(&self) -> Value {
        let ids: Vec<String> = self.server.info().harnesses.into_iter().map(|h| h.id).collect();
        json!({"ok": true, "installedHarnesses": ids})
    }

    /// `runner_admin` `pair`: a fresh one-time code (replacing any live one) for runner `name` (the bearer's name when None).
    /// The code pairs an orchestrator once (`infantry runner add <name> <endpoint> --code <code>`); it never moves a runner
    /// already paired elsewhere (the bearer does).
    pub fn pair_op(&self, name: Option<&str>) -> Value {
        let name = match name.map(str::trim).filter(|n| !n.is_empty()) {
            Some(n) if !infantry_runner_link::pairing::valid_name(n) => return json!({"ok": false, "error": format!("runner name '{n}' must be 1-32 of a-z, 0-9 and '-'")}),
            Some(n) => n.to_string(),
            None => self.bearer.read().map(|v| v.name).unwrap_or_else(|| "runner".into()),
        };
        let Some(code) = self.server.issue_code(&name) else { return json!({"ok": false, "error": "this runner takes no pair codes"}) };
        *self.pair_issued_at.lock().unwrap_or_else(|e| e.into_inner()) = Some(infantry_runner_link::server::now_ms());
        let port = self.opts.public_port.unwrap_or(self.serving.port);
        let mut v = json!({"ok": true, "name": name, "code": code, "expiryMin": self.opts.pair_code_ms / 60_000, "port": port, "plain": self.cert.is_none(), "endpoint": self.endpoint()});
        if let Some(fp) = self.pin() {
            v["fingerprint"] = json!(fp);
        }
        if let Some(p) = self.last_paired() {
            v["pairedWith"] = json!(p);
        }
        v
    }

    /// `runner_admin` `pair_status`: `paired` once an orchestrator paired after the last `pair`, `waiting` while that code is
    /// live, else `expired` (also when no code was issued).
    pub fn pair_status(&self) -> Value {
        let since = *self.pair_issued_at.lock().unwrap_or_else(|e| e.into_inner());
        let paired = self.server.store().orchestrator().filter(|o| since.is_some_and(|t| o.issued_at >= t));
        if let Some(o) = paired {
            return json!({"ok": true, "state": "paired", "orchestratorName": o.name, "orchestratorId": o.id});
        }
        json!({"ok": true, "state": if since.is_some() && self.server.code_live() { "waiting" } else { "expired" }})
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
            Some("pair") => self.serve.pair_op(text(&d, "name").as_deref()),
            Some("pair_preview") => self.serve.pair_preview(),
            Some("pair_status") => self.serve.pair_status(),
            Some(other) => json!({"ok": false, "error": format!("unknown op '{other}' on a runner (bearer, pair, pair_preview, pair_status)")}),
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
            allow_plain: false,
            pair_code_ms: 60_000,
            pair_attempts_per_hour: 10,
        }
    }

    fn start(dir: &Path) -> Arc<RunnerServe> {
        let backend = ProcessBackend::new(Arc::new(infantry_runner_local::LocalRunner::default()));
        RunnerServe::start(dir, Box::new(backend), opts()).unwrap()
    }

    fn start_plain(dir: &Path) -> Arc<RunnerServe> {
        let backend = ProcessBackend::new(Arc::new(infantry_runner_local::LocalRunner::default()));
        RunnerServe::start(dir, Box::new(backend), ServeOptions { allow_plain: true, ..opts() }).unwrap()
    }

    fn token(rs: &RunnerServe) -> BearerToken {
        BearerToken::parse(&rs.bearer_text().unwrap()).unwrap()
    }

    #[test]
    fn add_check_spawn_and_takeover() {
        let tmp = std::env::temp_dir().join(format!("rr2-{}", uuid::Uuid::new_v4()));
        let rs = start(&tmp.join("runner"));
        let url = format!("wss://127.0.0.1:{}", rs.port());
        assert_eq!(rs.endpoint(), format!("wss://localhost:{}", rs.port()));
        let pin = token(&rs).fingerprint.expect("a wss runner's bearer carries the pin");

        let a = crate::RunnerPool::open(&tmp.join("a"));
        let b = crate::RunnerPool::open(&tmp.join("b"));
        let secret = token(&rs).secret;
        let added = a.add(&url, &secret, Some(&pin), None, "orch-a").unwrap();
        assert_eq!(added.fingerprint, pin);
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
        let moved = b.add(&url, &secret, Some(&pin), None, "orch-b").unwrap();
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
        assert!(crate::RunnerPool::open(&tmp.join("c")).add(&url, &secret, Some(&pin), None, "orch-c").is_err());
        assert!(crate::RunnerPool::open(&tmp.join("d")).add(&url, &token(&rs).secret, Some(&pin), None, "orch-d").is_ok());
        let _ = std::fs::remove_dir_all(tmp);
    }

    #[test]
    fn plain_ws_is_refused_unless_the_setting_is_on_at_both_ends() {
        let tmp = std::env::temp_dir().join(format!("rr3-plain-{}", uuid::Uuid::new_v4()));
        let rs = start_plain(&tmp.join("runner"));
        assert!(rs.endpoint().starts_with("ws://"));
        let t = token(&rs);
        assert!(t.fingerprint.is_none(), "a plain link's bearer says plain");
        let url = format!("ws://127.0.0.1:{}", rs.port());
        let off = crate::RunnerPool::open(&tmp.join("off"));
        let refused = off.add(&url, &t.secret, None, None, "o").unwrap_err().to_string();
        assert!(refused.contains("allowPlainTailnet"), "{refused}");
        let on = crate::RunnerPool::open(&tmp.join("on"));
        on.set_allow_plain(Arc::new(|| true));
        assert_eq!(on.add(&url, &t.secret, None, None, "o").unwrap().name, "box");
        assert_eq!(on.check("box").unwrap().0.word(), "online");
        // A plain bearer cannot pair over wss, and a pinned bearer cannot pair over ws.
        assert!(on.add(&url.replace("ws://", "wss://"), &t.secret, None, None, "o").is_err());
        assert!(on.add(&url, &t.secret, Some(&"a".repeat(64)), None, "o").is_err());
        let _ = std::fs::remove_dir_all(tmp);
    }

    #[test]
    fn the_certificate_survives_a_runner_restart_and_a_wrong_pin_is_named() {
        let tmp = std::env::temp_dir().join(format!("rr3-restart-{}", uuid::Uuid::new_v4()));
        let dir = tmp.join("runner");
        let first = start(&dir);
        let pin = first.pin().unwrap();
        let pool = crate::RunnerPool::open(&tmp.join("o"));
        let url = format!("wss://127.0.0.1:{}", first.port());
        pool.add(&url, &token(&first).secret, Some(&pin), None, "orch").unwrap();
        drop(first);
        // The same state dir restarts on a new port: same fingerprint, and the stored credential still pairs (no re-pair).
        let second = start(&dir);
        assert_eq!(second.pin().unwrap(), pin);
        let mut moved = pool.store().runner("box").unwrap();
        moved.url = format!("wss://127.0.0.1:{}", second.port());
        pool.store().save_runner(moved);
        assert_eq!(pool.check("box").unwrap().0.word(), "online");
        // A runner recreated without its volume has a new certificate: refused, with the plain reason.
        let fresh = start(&tmp.join("fresh-volume"));
        assert_ne!(fresh.pin().unwrap(), pin);
        let mut wrong = pool.store().runner("box").unwrap();
        wrong.url = format!("wss://127.0.0.1:{}", fresh.port());
        pool.store().save_runner(wrong);
        let (state, _) = pool.check("box").unwrap();
        assert!(matches!(&state, crate::RunnerState::Offline { reason } if reason.contains("recreated without its volume")), "{state:?}");
        let _ = std::fs::remove_dir_all(tmp);
    }
}
