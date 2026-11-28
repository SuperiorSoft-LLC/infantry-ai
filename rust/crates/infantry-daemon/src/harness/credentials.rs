// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
//! The harness keys and in-app sign-ins (RUST PARITY of Kotlin `HarnessCredentials` + `HarnessLogins`, ONBOARD-1/2): every
//! harness that declares a [`Credential`] gets a key row `harness.<id>.key` in the vault (the guided setup's Sign in step
//! and the key screen fill it), its auth mode is the setting `harness.<id>.authMode`, and a harness with a `login_argv`
//! signs in from the app: the CLI's own login runs piped, its first https link is shown, and a pasted code goes to its stdin.
//!
//! A session's launch gets the key ([`HarnessCredentials::session_env`]): in api-key mode the stored key under the
//! harness's variable; in subscription mode the variable blanked, so a subscription session never bills an API key.
//! Output lines are never logged or kept beyond the link and one short message (a pasted code is never echoed).
use infantry_harness::{Credential, Harness, ProviderKey, LocalProbe, Probe, AUTH_API_KEY, AUTH_SUBSCRIPTION};
use infantry_vault::handler::KnownSecret;
use infantry_vault::keystore::{Keystore, VALUE_FIELD};
use std::any::Any;
use std::collections::HashMap;
use std::io::{BufRead, BufReader, Read, Write};
use std::sync::{Arc, Mutex};

pub const LOGIN_RUNNING: &str = "running";
pub const LOGIN_DONE: &str = "done";
pub const LOGIN_FAILED: &str = "failed";
/// A message shown to the owner is cut here (layout, not a limit).
const SHOWN_MESSAGE_LENGTH: usize = 300;

/// The vault name of a harness's API key.
pub fn key_name(id: &str) -> String {
    format!("harness.{id}.key")
}

/// The vault name of one of a harness's provider keys (`harness.opencode.deepseek.key`; Kotlin `secretNameOf`).
pub fn provider_key_name(harness: &str, key: &str) -> String {
    key_name(&format!("{harness}.{key}"))
}

/// The vault name of a harness's long-lived subscription token (the browser sign-in's result).
pub fn token_name(id: &str) -> String {
    format!("harness.{id}.token")
}

/// The harness id of a key or token name (`harness.claude.key` / `harness.claude.token` -> `claude`).
pub fn harness_of(secret: &str) -> Option<&str> {
    let rest = secret.strip_prefix("harness.")?;
    rest.strip_suffix(".key").or_else(|| rest.strip_suffix(".token"))
}

/// The token a CLI printed on [line]: the first word that starts with [prefix] and carries more than the prefix.
/// The caller keeps it in the vault and nowhere else (never logged, never put in a message).
pub fn find_token(line: &str, prefix: &str) -> Option<String> {
    if prefix.is_empty() {
        return None;
    }
    let at = line.find(prefix)?;
    let rest = &line[at..];
    let end = rest.find(|c: char| !(c.is_ascii_alphanumeric() || matches!(c, '-' | '_'))).unwrap_or(rest.len());
    (end > prefix.len() + 8).then(|| rest[..end].to_string())
}

/// The setting that holds a harness's chosen auth mode.
pub fn mode_setting(id: &str) -> String {
    format!("harness.{id}.authMode")
}

#[derive(Debug, Clone, Default, PartialEq, Eq)]
pub struct LoginState {
    pub state: String,
    pub url: String,
    pub message: String,
}

struct Run {
    state: Mutex<LoginState>,
    child: Mutex<Option<std::process::Child>>,
    stdin: Mutex<Option<std::process::ChildStdin>>,
    cancelled: std::sync::atomic::AtomicBool,
    /// The token flow printed the token and it is in the vault.
    captured: std::sync::atomic::AtomicBool,
    /// The token flow's pseudo-terminal: where a pasted code is written, and how it is stopped.
    term_writer: Mutex<Option<Box<dyn Write + Send>>>,
    term_kill: Mutex<Option<Box<dyn Fn() + Send + Sync>>>,
}

impl Run {
    fn new(state: LoginState) -> Arc<Run> {
        Arc::new(Run {
            state: Mutex::new(state),
            child: Mutex::default(),
            stdin: Mutex::default(),
            cancelled: Default::default(),
            captured: Default::default(),
            term_writer: Mutex::default(),
            term_kill: Mutex::default(),
        })
    }
    /// Stops the process but keeps the outcome (the token arrived by paste; the CLI no longer matters).
    fn kill_process(&self) {
        if let Some(c) = self.child.lock().unwrap_or_else(|e| e.into_inner()).as_mut() {
            let _ = c.kill();
        }
        if let Some(k) = self.term_kill.lock().unwrap_or_else(|e| e.into_inner()).as_ref() {
            k();
        }
    }
    fn kill(&self) {
        self.cancelled.store(true, std::sync::atomic::Ordering::SeqCst);
        self.kill_process();
    }
    fn set(&self, f: impl FnOnce(&mut LoginState)) {
        f(&mut self.state.lock().unwrap_or_else(|e| e.into_inner()));
    }
}

/// A child in a pseudo-terminal: the seam of the token sign-in (the real one is `infantry_runner::pty`, a test fakes it).
pub struct Term {
    pub reader: Box<dyn Read + Send>,
    pub writer: Box<dyn Write + Send>,
    pub kill: Box<dyn Fn() + Send + Sync>,
    /// True while the child runs.
    pub alive: Box<dyn Fn() -> bool + Send + Sync>,
    /// Keeps the pseudo-console open as long as the run lives.
    pub keep: Box<dyn Any + Send>,
}
/// Starts argv in a pseudo-terminal with the extra environment added.
pub type TermSpawn = Arc<dyn Fn(&[String], &[(String, String)]) -> Result<Term, String> + Send + Sync>;

/// The real pseudo-terminal: ConPTY on Windows, openpty elsewhere. `claude setup-token` prints nothing to a pipe.
pub fn pty_spawn() -> TermSpawn {
    Arc::new(|argv, env| {
        let mut full = infantry_harness::shim::launcher(&LocalProbe, &argv[0], "node");
        full.extend(argv[1..].iter().cloned());
        let program = full.remove(0);
        let cmd = infantry_runner::pty::Command { program, args: full, cwd: std::env::temp_dir(), env: env.to_vec(), env_remove: Vec::new() };
        let mut child = infantry_runner::pty::spawn(&cmd, infantry_runner::pty::DEFAULT_ROWS, infantry_runner::pty::DEFAULT_COLS).map_err(|e| e.to_string())?;
        let reader = child.reader.take().ok_or("the terminal has no output")?;
        let writer = std::mem::replace(&mut child.writer, Box::new(std::io::sink()));
        let process = Arc::new(child.process);
        let (p1, p2) = (process.clone(), process);
        Ok(Term { reader, writer, kill: Box::new(move || p1.kill()), alive: Box::new(move || p2.try_wait().is_none()), keep: Box::new(child.console) })
    })
}

pub type SettingFn = Arc<dyn Fn(&str) -> Option<String> + Send + Sync>;

pub struct HarnessCredentials {
    harnesses: Vec<Arc<dyn Harness>>,
    keystore: Arc<Keystore>,
    setting: SettingFn,
    runs: Mutex<HashMap<String, Arc<Run>>>,
    term: TermSpawn,
}

impl HarnessCredentials {
    pub fn new(harnesses: Vec<Arc<dyn Harness>>, keystore: Arc<Keystore>, setting: SettingFn) -> Arc<Self> {
        Self::with_terminal(harnesses, keystore, setting, pty_spawn())
    }

    /// [Self::new] with another pseudo-terminal (a test's).
    pub fn with_terminal(harnesses: Vec<Arc<dyn Harness>>, keystore: Arc<Keystore>, setting: SettingFn, term: TermSpawn) -> Arc<Self> {
        Arc::new(HarnessCredentials { harnesses, keystore, setting, runs: Mutex::default(), term })
    }

    fn declared(&self) -> impl Iterator<Item = (&Arc<dyn Harness>, Credential)> {
        self.harnesses.iter().filter_map(|h| h.credential().map(|c| (h, c)))
    }

    fn key(&self, id: &str) -> Option<String> {
        self.keystore.resolve(&key_name(id), None).ok().flatten().and_then(|f| f.get(VALUE_FIELD).cloned()).filter(|v| !v.trim().is_empty())
    }

    /// The auth mode in force: the setting, else api-key when a key is stored, else the subscription login when the
    /// harness has one, else api-key.
    pub fn mode(&self, id: &str, c: &Credential) -> String {
        if let Some(m) = (self.setting)(&mode_setting(id)).map(|m| m.trim().trim_matches('"').to_string()).filter(|m| c.auth_modes.contains(m)) {
            return m;
        }
        if self.key(id).is_none() && c.auth_modes.iter().any(|m| m == AUTH_SUBSCRIPTION) {
            AUTH_SUBSCRIPTION.into()
        } else {
            AUTH_API_KEY.into()
        }
    }

    fn token(&self, id: &str) -> Option<String> {
        self.keystore.resolve(&token_name(id), None).ok().flatten().and_then(|f| f.get(VALUE_FIELD).cloned()).filter(|v| !v.trim().is_empty())
    }

    /// The probe the harness list and the key rows judge a sign-in with: this process's own, plus each stored subscription
    /// token under the variable its CLI reads (so a harness with a token in the vault counts as signed in).
    pub fn probe(&self) -> TokenProbe {
        let tokens = self.declared().filter(|(_, c)| !c.token_env.is_empty()).filter_map(|(h, c)| self.token(h.id()).map(|t| (c.token_env, t))).collect();
        TokenProbe { tokens }
    }

    /// Keeps [token] as the harness's subscription token.
    fn store_token(&self, id: &str, token: &str) -> Result<(), String> {
        self.keystore.set(&token_name(id), token).map_err(|e| e.to_string())
    }

    pub fn state(&self, id: &str) -> LoginState {
        self.runs.lock().unwrap_or_else(|e| e.into_inner()).get(id).map(|r| r.state.lock().unwrap_or_else(|e| e.into_inner()).clone()).unwrap_or_default()
    }

    /// The key rows the vault shows ([`infantry_vault::handler::HandlerDeps::extra_known`]).
    pub fn known(&self) -> Vec<KnownSecret> {
        self.declared()
            .flat_map(|(h, c)| {
                let id = h.id().to_string();
                let probe = self.probe();
                let mut rows = Vec::new();
                if !c.token_env.is_empty() {
                    let mut t = KnownSecret::new(&token_name(&id), &format!("{} subscription token", c.label), None);
                    t.help = format!("Sign in with your {} subscription: the browser opens, you log in, and the token it shows is pasted here.", c.label);
                    t.login_command = c.token_argv.join(" ");
                    t.can_login = !c.token_argv.is_empty();
                    t.can_logout = true;
                    t.signed_in = h.sign_in_problem(&probe).is_none().to_string();
                    let login = self.state(&token_name(&id));
                    (t.login_state, t.login_url, t.login_message) = (login.state, login.url, login.message);
                    t.login_code_urls = c.login_code_urls.clone();
                    t.login_allowed_hosts = c.login_allowed_hosts.clone();
                    rows.push(t);
                }
                let login = self.state(&id);
                let mut k = KnownSecret::new(&key_name(&id), &c.label, Some(&c.env));
                k.help = c.help.clone();
                k.auth_mode = self.mode(&id, &c);
                k.auth_modes = c.auth_modes.clone();
                k.login_command = c.login_argv.join(" ");
                k.can_login = !c.login_argv.is_empty();
                k.can_logout = k.can_login;
                k.signed_in = if c.login_argv.is_empty() { "unknown".into() } else { h.sign_in_problem(&probe).is_none().to_string() };
                k.login_state = login.state;
                k.login_url = login.url;
                k.login_message = login.message;
                k.login_code_urls = c.login_code_urls.clone();
                k.login_allowed_hosts = c.login_allowed_hosts.clone();
                rows.push(k);
                rows
            })
            .chain(self.harnesses.iter().flat_map(|h| {
                let id = h.id().to_string();
                h.provider_keys().into_iter().map(move |p| {
                    let mut k = KnownSecret::new(&provider_key_name(&id, &p.id), &p.label, Some(&p.env));
                    k.help = p.help;
                    k.auth_mode = AUTH_API_KEY.into();
                    k
                })
            }))
            .collect()
    }

    /// The env a session of [provider] gets: the key in api-key mode, the variable blanked in subscription mode.
    pub fn session_env(&self, provider: &str) -> Vec<(String, String)> {
        let mut env = self.account_env(provider);
        // PROVIDERS-2: each saved provider key under its variable (the account's own variable is never overwritten).
        for k in self.harnesses.iter().filter(|h| h.id() == provider).flat_map(|h| h.provider_keys()) {
            if env.iter().any(|(n, _)| *n == k.env) {
                continue;
            }
            if let Some(v) = self.provider_key(provider, &k) {
                env.push((k.env.clone(), v));
            }
        }
        env
    }

    fn account_env(&self, provider: &str) -> Vec<(String, String)> {
        let Some((_, c)) = self.declared().find(|(h, _)| h.id() == provider) else { return Vec::new() };
        if self.mode(provider, &c) == AUTH_SUBSCRIPTION {
            // The API key variable stays blank (a subscription session never bills a key); the stored token goes under its own.
            let mut env = vec![(c.env.clone(), String::new())];
            if let Some(t) = self.token(provider).filter(|_| !c.token_env.is_empty()) {
                env.push((c.token_env.clone(), t));
            }
            return env;
        }
        self.key(provider).map(|k| vec![(c.env.clone(), k)]).unwrap_or_default()
    }

    /// A provider key's saved value: its own row, else a row under one of its older ids (a key saved before the merge).
    fn provider_key(&self, harness: &str, k: &ProviderKey) -> Option<String> {
        std::iter::once(&k.id).chain(k.old_ids.iter()).find_map(|id| self.key(&format!("{harness}.{id}")))
    }

    /// start / input / cancel the sign-in of the harness whose key or token is [secret] ([`infantry_vault::handler::LoginFn`]).
    pub fn login(self: &Arc<Self>, secret: &str, action: &str, text: &str) -> Result<(), String> {
        let id = harness_of(secret).ok_or_else(|| format!("'{secret}' is not a harness key"))?.to_string();
        let token = secret == token_name(&id);
        let run_key = if token { secret.to_string() } else { id.clone() };
        match action {
            "start" => self.start(&id, token),
            "input" => self.input(&id, token, text),
            "cancel" => {
                if let Some(r) = self.runs.lock().unwrap_or_else(|e| e.into_inner()).remove(&run_key) {
                    r.kill();
                }
                Ok(())
            }
            "signout" => self.sign_out(&id),
            other => Err(format!("unknown sign-in action '{other}'")),
        }
    }

    /// Sign [id] out, or start over when nobody is: stops any sign-in in progress (both flows), runs the tool's own sign-out
    /// command (a failure of it is not an error when nobody was signed in), forgets the token the app holds and clears the
    /// remembered state of the half-finished sign-in. Afterwards the state is read fresh; still signed in (an environment
    /// variable, say) is said in words, never hidden.
    fn sign_out(&self, id: &str) -> Result<(), String> {
        self.sign_out_within(id, std::time::Duration::from_secs(SIGN_OUT_LIMIT_SECS))
    }

    fn sign_out_within(&self, id: &str, limit: std::time::Duration) -> Result<(), String> {
        let (h, c) = self.declared().find(|(h, _)| h.id() == id).map(|(h, c)| (h.clone(), c)).ok_or_else(|| format!("unknown harness '{id}'"))?;
        for key in [id.to_string(), token_name(id)] {
            if let Some(r) = self.runs.lock().unwrap_or_else(|e| e.into_inner()).remove(&key) {
                r.kill();
            }
        }
        let mut ran = None;
        if !c.logout_argv.is_empty() {
            let mut argv = infantry_harness::shim::launcher(&LocalProbe, &c.logout_argv[0], "node");
            argv.extend(c.logout_argv[1..].iter().cloned());
            ran = Some(run_bounded(&argv, &c.env, limit));
        }
        if self.token(id).is_some() {
            self.keystore.clear(&token_name(id), None).map_err(|e| format!("the stored token could not be removed: {e}"))?;
        }
        if h.sign_in_problem(&self.probe()).is_none() {
            return Err(match ran {
                Some(SignOut::Hung) => format!("{} may still be signed in: its sign-out command did not finish within {SIGN_OUT_LIMIT_SECS} seconds and was stopped. The app's own sign-in state is cleared; press Check again, or sign out in the tool itself.", c.label),
                Some(SignOut::Failed(e)) => format!("{} could not be signed out: its sign-out command did not run ({e}).", c.label),
                Some(SignOut::Exit(code)) if code != 0 => format!("{} is still signed in: its sign-out command ended with an error (exit {code}). Sign out in the tool itself, then press Check again.", c.label),
                // Exit 0 and still signed in: the tool did its part; what keeps the sign-in is outside it.
                _ => format!("{} still counts as signed in: it is signed in by an environment variable or a file the app does not remove. Sign out there.", c.label),
            });
        }
        Ok(())
    }

    fn start(self: &Arc<Self>, id: &str, token: bool) -> Result<(), String> {
        let (h, c) = self.declared().find(|(h, _)| h.id() == id).map(|(h, c)| (h.clone(), c)).ok_or_else(|| format!("unknown harness '{id}'"))?;
        let argv_decl = if token { &c.token_argv } else { &c.login_argv };
        if argv_decl.is_empty() {
            return Err(format!("{} has no in-app sign-in; run its login command in a terminal", c.label));
        }
        let run_key = if token { token_name(id) } else { id.to_string() };
        if let Some(old) = self.runs.lock().unwrap_or_else(|e| e.into_inner()).remove(&run_key) {
            old.kill();
        }
        let run = Run::new(LoginState { state: LOGIN_RUNNING.into(), ..Default::default() });
        self.runs.lock().unwrap_or_else(|e| e.into_inner()).insert(run_key.clone(), run.clone());
        if token {
            return self.start_in_terminal(id, &h, &c, run, run_key);
        }
        // The CLI the guided setup installed lives in the app's folder: start what its npm shim runs (no cmd.exe between).
        let mut argv = infantry_harness::shim::launcher(&LocalProbe, &argv_decl[0], "node");
        argv.extend(argv_decl[1..].iter().cloned());
        // A subscription sign-in must not see the API key variable (it would bill that account).
        let spawned = infantry_proc::command(&argv[0])
            .args(&argv[1..])
            .env(&c.env, "")
            .stdin(std::process::Stdio::piped())
            .stdout(std::process::Stdio::piped())
            .stderr(std::process::Stdio::piped())
            .spawn();
        let mut child = match spawned {
            Ok(c) => c,
            Err(e) => {
                run.set(|s| {
                    s.state = LOGIN_FAILED.into();
                    s.message = cut(&format!("The sign-in could not start: {e}"));
                });
                return Ok(());
            }
        };
        let prefix = if token { c.token_prefix.clone() } else { String::new() };
        let readers: Vec<_> = [child.stdout.take().map(|s| Box::new(s) as Box<dyn Read + Send>), child.stderr.take().map(|s| Box::new(s) as Box<dyn Read + Send>)]
            .into_iter()
            .flatten()
            .map(|stream| {
                let (run, me, id, prefix) = (run.clone(), self.clone(), id.to_string(), prefix.clone());
                std::thread::spawn(move || {
                    for line in BufReader::new(stream).lines().map_while(Result::ok) {
                        let shown = plain(&line);
                        // The token the CLI prints goes to the vault and nowhere else: not to the state, not to a log.
                        if let Some(t) = find_token(&shown, &prefix) {
                            me.token_received(&id, &run, &t);
                            continue;
                        }
                        seen(&run, &shown);
                    }
                })
            })
            .collect();
        *run.stdin.lock().unwrap_or_else(|e| e.into_inner()) = child.stdin.take();
        *run.child.lock().unwrap_or_else(|e| e.into_inner()) = Some(child);
        let me = self.clone();
        std::thread::spawn(move || {
            let status = loop {
                let done = run.child.lock().unwrap_or_else(|e| e.into_inner()).as_mut().map(|c| c.try_wait());
                match done {
                    Some(Ok(Some(s))) => break s.success(),
                    Some(Ok(None)) => std::thread::sleep(std::time::Duration::from_millis(300)),
                    _ => break false,
                }
            };
            for r in readers {
                let _ = r.join();
            }
            let current = me.runs.lock().unwrap_or_else(|e| e.into_inner()).get(&run_key).is_some_and(|r| Arc::ptr_eq(r, &run));
            if run.cancelled.load(std::sync::atomic::Ordering::SeqCst) || !current || run.captured.load(std::sync::atomic::Ordering::SeqCst) {
                return;
            }
            // The probe after the exit is what counts: exit 0 and credentials on disk (or a stored token) = signed in.
            let signed_in = late_check(|| h.sign_in_problem(&me.probe()).is_none());
            run.set(|s| {
                if signed_in {
                    s.state = LOGIN_DONE.into();
                    s.message = "Signed in.".into();
                } else {
                    s.state = LOGIN_FAILED.into();
                    if s.message.is_empty() {
                        s.message = if token {
                            "The sign-in did not give a token. Paste the token the sign-in page shows, or start it again.".into()
                        } else {
                            "The sign-in did not finish. Start it again.".into()
                        };
                    }
                }
            });
        });
        Ok(())
    }

    /// The token flow: the CLI's token command (`claude setup-token`) runs in a pseudo-terminal, because it prints nothing to a pipe.
    /// The browser opens from it, its first link is shown, a code pasted by the owner is typed into it, and the token it prints goes
    /// to the vault and nowhere else.
    fn start_in_terminal(self: &Arc<Self>, id: &str, h: &Arc<dyn Harness>, c: &Credential, run: Arc<Run>, run_key: String) -> Result<(), String> {
        // A subscription token must not see the API key variable (it would bill that account).
        let term = match (self.term)(&c.token_argv, &[(c.env.clone(), String::new())]) {
            Ok(t) => t,
            Err(e) => {
                run.set(|s| {
                    s.state = LOGIN_FAILED.into();
                    s.message = cut(&format!("The sign-in could not start: {e}"));
                });
                return Ok(());
            }
        };
        let Term { reader, writer, kill, alive, keep } = term;
        *run.term_writer.lock().unwrap_or_else(|e| e.into_inner()) = Some(writer);
        *run.term_kill.lock().unwrap_or_else(|e| e.into_inner()) = Some(kill);
        let (me, h, id, prefix) = (self.clone(), h.clone(), id.to_string(), c.token_prefix.clone());
        let reader_run = run.clone();
        let (reader_me, reader_id, reader_prefix) = (me.clone(), id.clone(), prefix);
        // Detached: on Windows the reader only ends once the pseudo-console is dropped.
        std::thread::spawn(move || {
            let (mut reader, mut pending, mut buf) = (reader, String::new(), [0u8; 4096]);
            let take = |line: &str| {
                let shown = plain(line);
                if shown.is_empty() {
                    return;
                }
                if let Some(t) = find_token(&shown, &reader_prefix) {
                    reader_me.token_received(&reader_id, &reader_run, &t);
                } else {
                    seen(&reader_run, &shown);
                }
            };
            while let Ok(n) = reader.read(&mut buf) {
                if n == 0 {
                    break;
                }
                pending.push_str(&String::from_utf8_lossy(&buf[..n]));
                while let Some(at) = pending.find(['\n', '\r']) {
                    let line: String = pending.drain(..=at).collect();
                    take(&line);
                }
            }
            take(&pending);
        });
        std::thread::spawn(move || {
            while alive() && !run.cancelled.load(std::sync::atomic::Ordering::SeqCst) && !run.captured.load(std::sync::atomic::Ordering::SeqCst) {
                std::thread::sleep(std::time::Duration::from_millis(300));
            }
            // The last bytes of its output are still on their way when the CLI exits.
            std::thread::sleep(std::time::Duration::from_millis(400));
            run.kill_process();
            let current = me.runs.lock().unwrap_or_else(|e| e.into_inner()).get(&run_key).is_some_and(|r| Arc::ptr_eq(r, &run));
            if !run.cancelled.load(std::sync::atomic::Ordering::SeqCst) && current && !run.captured.load(std::sync::atomic::Ordering::SeqCst) {
                let signed_in = late_check(|| h.sign_in_problem(&me.probe()).is_none());
                run.set(|s| {
                    s.state = if signed_in { LOGIN_DONE } else { LOGIN_FAILED }.into();
                    if signed_in {
                        s.message = "Signed in.".into();
                    } else if s.message.is_empty() {
                        s.message = "The sign-in did not give a token. Paste the token the sign-in page shows, or start it again.".into();
                    }
                });
            }
            drop(keep);
        });
        Ok(())
    }

    /// A token reached us (printed by the CLI or pasted by the owner): into the vault, the run is done.
    fn token_received(&self, id: &str, run: &Run, token: &str) {
        match self.store_token(id, token) {
            Ok(()) => {
                run.captured.store(true, std::sync::atomic::Ordering::SeqCst);
                run.set(|s| {
                    s.state = LOGIN_DONE.into();
                    s.message = "Signed in.".into();
                });
            }
            Err(_) => run.set(|s| s.message = "The token could not be saved.".into()),
        }
    }

    fn input(&self, id: &str, token: bool, text: &str) -> Result<(), String> {
        let line = text.trim().replace(['\r', '\n'], "");
        if line.is_empty() {
            return Err("paste the code the sign-in page shows".into());
        }
        let key = if token { token_name(id) } else { id.to_string() };
        let prefix = self.declared().find(|(h, _)| h.id() == id).map(|(_, c)| c.token_prefix).unwrap_or_default();
        // A pasted token is kept at once, whether or not the CLI still waits (it may need a terminal we do not give it).
        if token && !prefix.is_empty() && line.starts_with(&prefix) {
            let run = self.runs.lock().unwrap_or_else(|e| e.into_inner()).entry(key).or_insert_with(|| Run::new(LoginState::default())).clone();
            self.token_received(id, &run, &line);
            return if run.captured.load(std::sync::atomic::Ordering::SeqCst) {
                run.kill_process();
                Ok(())
            } else {
                Err("the token could not be saved".into())
            };
        }
        let run = self.runs.lock().unwrap_or_else(|e| e.into_inner()).get(&key).cloned();
        // A code that arrives after the sign-in finished (the page reported twice) is not an error: the login is done.
        if run.as_ref().is_some_and(|r| r.state.lock().unwrap_or_else(|e| e.into_inner()).state == LOGIN_DONE) {
            return Ok(());
        }
        let run = run.filter(|r| r.state.lock().unwrap_or_else(|e| e.into_inner()).state == LOGIN_RUNNING).ok_or("no sign-in is running; start it again")?;
        let mut term = run.term_writer.lock().unwrap_or_else(|e| e.into_inner());
        if let Some(w) = term.as_mut() {
            w.write_all(format!("{line}\r").as_bytes()).and_then(|_| w.flush()).map_err(|e| format!("could not send the code: {e}"))?;
            run.set(|s| s.message = "Checking the code...".into());
            return Ok(());
        }
        drop(term);
        let mut stdin = run.stdin.lock().unwrap_or_else(|e| e.into_inner());
        let w = stdin.as_mut().ok_or("the sign-in no longer takes input; start it again")?;
        w.write_all(format!("{line}\n").as_bytes()).and_then(|_| w.flush()).map_err(|e| format!("could not send the code: {e}"))?;
        run.set(|s| s.message = "Checking the code...".into());
        Ok(())
    }
}

/// [`Probe`] over this process plus the subscription tokens the vault holds (variable name -> token).
pub struct TokenProbe {
    tokens: Vec<(String, String)>,
}

impl Probe for TokenProbe {
    fn env(&self, name: &str) -> Option<String> {
        self.tokens.iter().find(|(k, _)| k == name).map(|(_, v)| v.clone()).or_else(|| LocalProbe.env(name))
    }
    fn env_names(&self) -> Vec<String> {
        LocalProbe.env_names()
    }
    fn home(&self) -> Option<std::path::PathBuf> {
        LocalProbe.home()
    }
    fn read_to_string(&self, path: &std::path::Path) -> Option<String> {
        LocalProbe.read_to_string(path)
    }
    fn exists(&self, path: &std::path::Path) -> bool {
        LocalProbe.exists(path)
    }
    fn list_dir(&self, path: &std::path::Path) -> Vec<std::path::PathBuf> {
        LocalProbe.list_dir(path)
    }
}

/// Credentials can land a moment after the sign-in command exits: the first check that says no is asked again, a few
/// times, before the sign-in is called failed. The answer is the tool's own, read fresh each time.
const LATE_CREDENTIAL_CHECKS: u32 = 6;
const LATE_CREDENTIAL_WAIT_MS: u64 = 500;
/// How long a tool's own sign-out command may run before it is ended (its process tree) and reported as not finished.
const SIGN_OUT_LIMIT_SECS: u64 = 20;

enum SignOut {
    Exit(i32),
    Hung,
    Failed(std::io::Error),
}

/// Runs a sign-out command with a bounded wait; past [limit] its process tree is ended.
fn run_bounded(argv: &[String], blank_env: &str, limit: std::time::Duration) -> SignOut {
    let mut child = match infantry_proc::command(&argv[0])
        .args(&argv[1..])
        .env(blank_env, "")
        .stdin(std::process::Stdio::null())
        .stdout(std::process::Stdio::null())
        .stderr(std::process::Stdio::null())
        .spawn()
    {
        Ok(c) => c,
        Err(e) => return SignOut::Failed(e),
    };
    let end = std::time::Instant::now() + limit;
    loop {
        match child.try_wait() {
            Ok(Some(st)) => return SignOut::Exit(st.code().unwrap_or(-1)),
            Ok(None) if std::time::Instant::now() < end => std::thread::sleep(std::time::Duration::from_millis(50)),
            Ok(None) => {
                infantry_proc::kill_pid(child.id());
                let _ = child.kill();
                let _ = child.wait();
                return SignOut::Hung;
            }
            Err(e) => return SignOut::Failed(e),
        }
    }
}

fn late_check(mut signed_in: impl FnMut() -> bool) -> bool {
    for i in 0..=LATE_CREDENTIAL_CHECKS {
        if signed_in() {
            return true;
        }
        if i < LATE_CREDENTIAL_CHECKS {
            std::thread::sleep(std::time::Duration::from_millis(LATE_CREDENTIAL_WAIT_MS));
        }
    }
    false
}

fn cut(s: &str) -> String {
    s.chars().take(SHOWN_MESSAGE_LENGTH).collect()
}

/// [raw] without terminal escapes (colour codes and OSC 8 hyperlinks, whose link text repeats the address).
pub fn plain(raw: &str) -> String {
    let mut out = String::with_capacity(raw.len());
    let mut chars = raw.chars().peekable();
    while let Some(c) = chars.next() {
        if c != '\x1b' {
            out.push(c);
            continue;
        }
        match chars.next() {
            // OSC: up to BEL or ESC \.
            Some(']') => {
                while let Some(n) = chars.next() {
                    if n == '\x07' || (n == '\x1b' && chars.next_if_eq(&'\\').is_some()) {
                        break;
                    }
                }
            }
            // CSI: parameters, then one final byte in @..~.
            Some('[') => {
                for n in chars.by_ref() {
                    if ('@'..='~').contains(&n) {
                        break;
                    }
                }
            }
            _ => {}
        }
    }
    out.trim().to_string()
}

/// The first https link of a line.
pub fn link(line: &str) -> Option<String> {
    let start = line.find("https://")?;
    let rest = &line[start..];
    let end = rest.find(|c: char| c.is_whitespace() || matches!(c, '"' | '\'' | '<' | '>' | '\x1b' | '\x07')).unwrap_or(rest.len());
    Some(rest[..end].to_string())
}

fn seen(run: &Run, raw: &str) {
    let line = plain(raw);
    run.set(|s| {
        if s.url.is_empty() {
            if let Some(u) = link(&line) {
                s.url = u;
            }
        }
        // A refused code ("Invalid code...") is the one line worth showing while the sign-in still runs.
        let lower = line.to_ascii_lowercase();
        if lower.starts_with("invalid code") || lower.starts_with("login failed") {
            s.message = cut(&line);
        }
    });
}

#[cfg(test)]
mod tests {
    fn fake(windows: &[&str], unix: &[&str]) -> Vec<String> {
        (if cfg!(windows) { windows } else { unix }).iter().map(|s| s.to_string()).collect()
    }

    #[test]
    fn a_sign_out_tool_that_hangs_is_ended_after_the_limit() {
        let argv = fake(&["ping", "-n", "30", "127.0.0.1"], &["sleep", "30"]);
        let t = std::time::Instant::now();
        let r = run_bounded(&argv, "FAKE_API_KEY", std::time::Duration::from_millis(600));
        assert!(matches!(r, SignOut::Hung), "the hung tool is reported as not finished");
        assert!(t.elapsed() < std::time::Duration::from_secs(10), "the wait is bounded: {:?}", t.elapsed());
    }

    #[test]
    fn a_sign_out_tool_that_exits_1_is_reported_with_its_code() {
        let argv = fake(&["cmd", "/c", "exit", "1"], &["sh", "-c", "exit 1"]);
        assert!(matches!(run_bounded(&argv, "FAKE_API_KEY", std::time::Duration::from_secs(10)), SignOut::Exit(1)));
        assert!(matches!(run_bounded(&["no-such-tool-xyz".into()], "FAKE_API_KEY", std::time::Duration::from_secs(1)), SignOut::Failed(_)));
    }

    use super::*;
    use infantry_harness::{Capabilities, LaunchError, LaunchPlan, LaunchRequest, ModelInfo, Probe};

    struct Fake(Option<Credential>);
    impl Harness for Fake {
        fn id(&self) -> &str {
            "fake"
        }
        fn capabilities(&self) -> Capabilities {
            Capabilities { delivery: infantry_harness::TurnDelivery::SpawnPerTurn, pty_screen: false, file_tail: false, hook_channel: false, http: false, resume: false, usage: false, model_selection: false, approval_modes: Vec::new() }
        }
        fn models(&self, _: &dyn Probe) -> Vec<ModelInfo> {
            Vec::new()
        }
        fn sign_in_problem(&self, p: &dyn Probe) -> Option<String> {
            // Signed in only through the token variable (no files here).
            p.env("FAKE_OAUTH").is_none().then(|| "not signed in".into())
        }
        fn launch(&self, _: &LaunchRequest) -> Result<LaunchPlan, LaunchError> {
            Err(LaunchError("no".into()))
        }
        fn credential(&self) -> Option<Credential> {
            self.0.clone()
        }
    }

    fn creds(setting: Option<&'static str>) -> (tempfile::TempDir, Arc<HarnessCredentials>) {
        let dir = tempfile::tempdir().unwrap();
        let ks = Arc::new(Keystore::with(dir.path(), Arc::new(|_| None), None, infantry_vault::limits::Limits::default()));
        let c = Credential {
            env: "FAKE_API_KEY".into(),
            label: "Fake".into(),
            help: "h".into(),
            auth_modes: vec![AUTH_API_KEY.into(), AUTH_SUBSCRIPTION.into()],
            login_argv: vec!["fake".into(), "auth".into(), "login".into()],
            logout_argv: vec!["fake".into(), "auth".into(), "logout".into()],
            token_env: "FAKE_OAUTH".into(),
            token_argv: vec!["fake".into(), "setup-token".into()],
            token_prefix: "sk-fake-".into(),
            login_code_urls: vec!["https://login.example/callback".into()],
            login_allowed_hosts: vec!["login.example".into()],
        };
        let hc = HarnessCredentials::new(vec![Arc::new(Fake(Some(c))), Arc::new(Fake(None))], ks, Arc::new(move |_| setting.map(String::from)));
        (dir, hc)
    }


    /// A harness signed in while a marker file exists (a file or variable the app does not own).
    struct Marked(std::path::PathBuf, Credential);
    impl Harness for Marked {
        fn id(&self) -> &str {
            "marked"
        }
        fn capabilities(&self) -> Capabilities {
            Fake(None).capabilities()
        }
        fn models(&self, _: &dyn Probe) -> Vec<ModelInfo> {
            Vec::new()
        }
        fn sign_in_problem(&self, _: &dyn Probe) -> Option<String> {
            (!self.0.exists()).then(|| "not signed in".into())
        }
        fn launch(&self, _: &LaunchRequest) -> Result<LaunchPlan, LaunchError> {
            Err(LaunchError("no".into()))
        }
        fn credential(&self) -> Option<Credential> {
            Some(self.1.clone())
        }
    }

    /// sign_out end to end with a fake tool: [tool] is its sign-out argv, the marker file is what keeps it signed in.
    fn sign_out_with(tool: Vec<String>, limit: std::time::Duration) -> (Result<(), String>, bool) {
        let dir = tempfile::tempdir().unwrap();
        let marker = dir.path().join("signed-in");
        std::fs::write(&marker, "x").unwrap();
        let ks = Arc::new(Keystore::with(dir.path(), Arc::new(|_| None), None, infantry_vault::limits::Limits::default()));
        let c = Credential { env: "FAKE_API_KEY".into(), label: "Marked".into(), logout_argv: tool, ..Default::default() };
        let hc = HarnessCredentials::new(vec![Arc::new(Marked(marker.clone(), c))], ks, Arc::new(|_| None));
        let r = hc.sign_out_within("marked", limit);
        (r, marker.exists())
    }

    #[test]
    fn sign_out_exit_0_and_still_signed_in_names_the_variable_or_file_not_an_error() {
        let (r, _) = sign_out_with(fake(&["cmd", "/c", "exit", "0"], &["sh", "-c", "exit 0"]), std::time::Duration::from_secs(10));
        let m = r.unwrap_err();
        assert!(m.contains("environment variable or a file"), "{m}");
        assert!(!m.contains("error"), "{m}");
    }

    #[test]
    fn sign_out_exit_1_and_still_signed_in_says_error_with_the_code() {
        let (r, _) = sign_out_with(fake(&["cmd", "/c", "exit", "1"], &["sh", "-c", "exit 1"]), std::time::Duration::from_secs(10));
        let m = r.unwrap_err();
        assert!(m.contains("ended with an error (exit 1)"), "{m}");
    }

    #[test]
    fn sign_out_of_a_hanging_tool_says_it_did_not_finish() {
        let t = std::time::Instant::now();
        let (r, _) = sign_out_with(fake(&["ping", "-n", "30", "127.0.0.1"], &["sleep", "30"]), std::time::Duration::from_millis(600));
        let m = r.unwrap_err();
        assert!(m.contains("did not finish within"), "{m}");
        assert!(t.elapsed() < std::time::Duration::from_secs(10));
    }

    #[test]
    fn sign_out_exit_0_and_signed_out_is_ok() {
        let dir = tempfile::tempdir().unwrap();
        let marker = dir.path().join("signed-in");
        std::fs::write(&marker, "x").unwrap();
        let ks = Arc::new(Keystore::with(dir.path(), Arc::new(|_| None), None, infantry_vault::limits::Limits::default()));
        let m = marker.to_string_lossy().to_string();
        let tool = fake(&["cmd", "/c", "del", &m], &["rm", &m]);
        let c = Credential { env: "FAKE_API_KEY".into(), label: "Marked".into(), logout_argv: tool, ..Default::default() };
        let hc = HarnessCredentials::new(vec![Arc::new(Marked(marker.clone(), c))], ks, Arc::new(|_| None));
        assert!(hc.sign_out_within("marked", std::time::Duration::from_secs(10)).is_ok());
        assert!(!marker.exists());
    }

    #[test]
    fn a_sign_in_whose_first_check_says_no_is_checked_again() {
        let mut answers = vec![false, false, true].into_iter();
        assert!(late_check(|| answers.next().unwrap_or(true)), "the credentials landed after the first look: signed in");
        assert!(!late_check(|| false), "never signed in stays not signed in");
    }

    #[test]
    fn sign_out_forgets_the_token_and_the_half_finished_sign_in() {
        let (_d, hc) = creds(None);
        hc.store_token("fake", "sk-fake-abc").unwrap();
        assert!(hc.known().iter().all(|k| k.can_logout), "sign out is always offered");
        assert_eq!(hc.known()[1].signed_in, "true");
        hc.login("harness.fake.key", "signout", "").unwrap();
        assert_eq!(hc.known()[1].signed_in, "false", "read fresh: the stored token is gone");
        assert!(hc.token("fake").is_none());
    }

    #[test]
    fn start_over_works_when_nobody_is_signed_in_and_clears_a_failed_run() {
        let (_d, hc) = creds(None);
        hc.login("harness.fake.key", "start", "").unwrap();
        std::thread::sleep(std::time::Duration::from_millis(400));
        assert_eq!(hc.state("fake").state, LOGIN_FAILED, "the fake tool does not exist: the sign-in failed");
        hc.login("harness.fake.key", "signout", "").unwrap();
        assert_eq!(hc.state("fake"), LoginState::default(), "the remembered half-finished sign-in is gone");
    }

    #[test]
    fn a_declared_harness_gets_a_key_row_with_its_sign_in() {
        let (_d, hc) = creds(None);
        let k = hc.known();
        assert_eq!(k.len(), 2, "a harness with no credential gets no row; one with a token sign-in gets a key row and a token row");
        assert_eq!(k[0].name, "harness.fake.token");
        assert!(k[0].can_login && k[0].login_command == "fake setup-token");
        assert_eq!((k[1].name.as_str(), k[1].env_alias.as_deref()), ("harness.fake.key", Some("FAKE_API_KEY")));
        assert!(k[1].can_login);
        assert_eq!(k[1].login_command, "fake auth login");
        assert_eq!(k[1].signed_in, "false");
        assert_eq!(k[0].login_code_urls, vec!["https://login.example/callback".to_string()], "the harness declares where the code shows; the wire carries it");
        assert_eq!(k[1].login_allowed_hosts, vec!["login.example".to_string()], "the harness declares the hosts the login may stay on");
        assert_eq!(k[1].auth_modes, vec![AUTH_API_KEY.to_string(), AUTH_SUBSCRIPTION.to_string()]);
    }

    /// A harness with no account of its own and two provider keys (pi's shape); Command Code keeps the older id `cmd`.
    struct Keyed;
    impl Harness for Keyed {
        fn id(&self) -> &str {
            "pk"
        }
        fn capabilities(&self) -> Capabilities {
            Fake(None).capabilities()
        }
        fn models(&self, _: &dyn Probe) -> Vec<ModelInfo> {
            Vec::new()
        }
        fn sign_in_problem(&self, _: &dyn Probe) -> Option<String> {
            None
        }
        fn launch(&self, _: &LaunchRequest) -> Result<LaunchPlan, LaunchError> {
            Err(LaunchError("no".into()))
        }
        fn provider_keys(&self) -> Vec<ProviderKey> {
            let mut cmd = ProviderKey::new("commandcode", "CMD_API_KEY", "Command Code", "h");
            cmd.old_ids = vec!["cmd".into()];
            vec![cmd, ProviderKey::new("deepseek", "DEEPSEEK_API_KEY", "DeepSeek", "h")]
        }
    }

    fn keyed() -> (tempfile::TempDir, Arc<HarnessCredentials>) {
        let dir = tempfile::tempdir().unwrap();
        let ks = Arc::new(Keystore::with(dir.path(), Arc::new(|_| None), None, infantry_vault::limits::Limits::default()));
        (dir, HarnessCredentials::new(vec![Arc::new(Keyed)], ks, Arc::new(|_| None)))
    }

    /// The value of [var] in [env], compared inside the test and never printed.
    fn has(env: &[(String, String)], var: &str, value: &str) -> bool {
        env.iter().any(|(k, v)| k == var && v == value)
    }

    #[test]
    fn provider_keys_are_vault_rows_under_their_variables() {
        let (_d, hc) = keyed();
        let rows: Vec<(String, Option<String>)> = hc.known().into_iter().map(|k| (k.name, k.env_alias)).collect();
        assert!(rows.contains(&("harness.pk.commandcode.key".into(), Some("CMD_API_KEY".into()))));
        assert!(rows.contains(&("harness.pk.deepseek.key".into(), Some("DEEPSEEK_API_KEY".into()))));
        assert!(!rows.iter().any(|(n, _)| n.contains(".cmd.")), "one Command Code row, not two");
    }

    #[test]
    fn a_saved_provider_key_reaches_the_session_env() {
        let (_d, hc) = keyed();
        assert!(hc.session_env("pk").is_empty(), "nothing saved, nothing set");
        hc.keystore.set(&provider_key_name("pk", "deepseek"), "ds-value-1").unwrap();
        let env = hc.session_env("pk");
        assert!(has(&env, "DEEPSEEK_API_KEY", "ds-value-1"), "the saved DeepSeek key is the session's variable");
        assert!(!env.iter().any(|(k, _)| k == "CMD_API_KEY"));
    }

    #[test]
    fn a_command_code_key_saved_under_the_old_id_still_works() {
        let (_d, hc) = keyed();
        hc.keystore.set(&provider_key_name("pk", "cmd"), "old-value").unwrap();
        assert!(has(&hc.session_env("pk"), "CMD_API_KEY", "old-value"), "the old row is read as a fallback");
        hc.keystore.set(&provider_key_name("pk", "commandcode"), "new-value").unwrap();
        let env = hc.session_env("pk");
        assert!(has(&env, "CMD_API_KEY", "new-value"), "the merged row wins");
        assert_eq!(env.iter().filter(|(k, _)| k == "CMD_API_KEY").count(), 1);
    }

    #[test]
    fn the_session_gets_the_key_in_api_key_mode_and_a_blank_in_subscription_mode() {
        let (_d, hc) = creds(None);
        // No key, a subscription login offered: subscription mode, the variable blanked.
        assert_eq!(hc.session_env("fake"), vec![("FAKE_API_KEY".to_string(), String::new())]);
        hc.keystore.set(&key_name("fake"), "sk-test").unwrap();
        assert_eq!(hc.session_env("fake"), vec![("FAKE_API_KEY".to_string(), "sk-test".to_string())]);
        let (_d2, sub) = creds(Some("subscription-login"));
        sub.keystore.set(&key_name("fake"), "sk-test").unwrap();
        assert_eq!(sub.session_env("fake"), vec![("FAKE_API_KEY".to_string(), String::new())], "the chosen subscription never bills the key");
        assert!(hc.session_env("other").is_empty());
    }

    #[test]
    fn the_sign_in_link_is_read_from_the_cli_output_without_terminal_escapes() {
        let raw = "\x1b[1mOpen \x1b]8;;https://claude.ai/oauth?x=1\x07https://claude.ai/oauth?x=1\x1b]8;;\x07 in your browser\x1b[0m";
        let line = plain(raw);
        assert_eq!(line, "Open https://claude.ai/oauth?x=1 in your browser");
        assert_eq!(link(&line).as_deref(), Some("https://claude.ai/oauth?x=1"));
    }

    #[test]
    fn a_saved_token_reaches_the_session_with_the_api_key_blanked_and_makes_the_harness_available() {
        let (_d, hc) = creds(None);
        let h = Fake(None);
        assert!(h.sign_in_problem(&hc.probe()).is_some(), "no token yet: not signed in");
        hc.store_token("fake", "sk-fake-abcdefghijklmnop").unwrap();
        // The provider listing and the key rows judge the sign-in with the vault-aware probe.
        assert!(h.sign_in_problem(&hc.probe()).is_none());
        assert_eq!(hc.known()[0].signed_in, "true");
        // The session gets the token under the CLI's variable and the API key variable blank.
        assert_eq!(
            hc.session_env("fake"),
            vec![("FAKE_API_KEY".to_string(), String::new()), ("FAKE_OAUTH".to_string(), "sk-fake-abcdefghijklmnop".to_string())]
        );
        // No row, state or message ever carries the token.
        let shown = format!("{:?}", hc.known()) + &format!("{:?}", hc.state("harness.fake.token"));
        assert!(!shown.contains("abcdefghijklmnop"), "{shown}");
    }

    #[test]
    fn a_pasted_token_is_kept_and_finishes_the_sign_in() {
        let (_d, hc) = creds(None);
        assert!(hc.login("harness.fake.token", "input", "sk-fake-0123456789abcdef").is_ok());
        assert_eq!(hc.token("fake").as_deref(), Some("sk-fake-0123456789abcdef"));
        let st = hc.state("harness.fake.token");
        assert_eq!((st.state.as_str(), st.message.as_str()), (LOGIN_DONE, "Signed in."));
        // A code that arrives once the sign-in is done is a no-op, not an error (the login page may report its address twice).
        assert!(hc.login("harness.fake.token", "input", "a-code").is_ok());
        assert_eq!(hc.state("harness.fake.token").state, LOGIN_DONE);
        // Something that is not a token needs a running sign-in to receive it.
        let (_d2, fresh) = creds(None);
        assert!(fresh.login("harness.fake.token", "input", "a-code").is_err());
    }

    #[test]
    fn the_printed_token_is_found_in_the_cli_output() {
        assert_eq!(find_token("Your token: sk-fake-abcdef1234567890. Keep it safe", "sk-fake-").as_deref(), Some("sk-fake-abcdef1234567890"));
        assert_eq!(find_token("sk-fake-", "sk-fake-"), None, "the prefix alone is not a token");
        assert_eq!(find_token("sk-fake-abcdef1234567890", ""), None, "no prefix declared: nothing is taken from output");
        assert_eq!(harness_of("harness.claude.token"), Some("claude"));
    }

    const TOKEN: &str = "sk-fake-AbCdEfGhIjKlMnOpQrStUvWxYz0123456789_-ZZZZ";

    /// A fake pseudo-terminal that prints [output] and records what it was asked to run.
    #[allow(clippy::type_complexity)]
    fn terminal(output: &'static str) -> (TermSpawn, Arc<Mutex<Vec<(Vec<String>, Vec<(String, String)>)>>>) {
        let asked: Arc<Mutex<Vec<(Vec<String>, Vec<(String, String)>)>>> = Arc::default();
        let log = asked.clone();
        let spawn: TermSpawn = Arc::new(move |argv, env| {
            log.lock().unwrap().push((argv.to_vec(), env.to_vec()));
            Ok(Term {
                reader: Box::new(std::io::Cursor::new(output.as_bytes().to_vec())),
                writer: Box::new(std::io::sink()),
                kill: Box::new(|| {}),
                alive: Box::new(|| false),
                keep: Box::new(()),
            })
        });
        (spawn, asked)
    }

    fn with_terminal_creds(spawn: TermSpawn) -> (tempfile::TempDir, Arc<HarnessCredentials>) {
        let (d, base) = creds(None);
        let hc = HarnessCredentials::with_terminal(base.harnesses.clone(), base.keystore.clone(), Arc::new(|_| None), spawn);
        (d, hc)
    }

    fn wait_for(hc: &HarnessCredentials, name: &str, want: &str) -> LoginState {
        for _ in 0..600 {
            let s = hc.state(name);
            if s.state == want {
                return s;
            }
            std::thread::sleep(std::time::Duration::from_millis(50));
        }
        panic!("the sign-in never reached '{want}': {:?}", hc.state(name));
    }

    #[test]
    fn the_token_sign_in_runs_in_the_pty_seam_captures_the_token_and_never_shows_it() {
        let out: &'static str = Box::leak(format!("Open https://claude.ai/oauth?x=1 to sign in\r\nYour token:\r\n{TOKEN}\r\n").into_boxed_str());
        let (spawn, asked) = terminal(out);
        let (_d, hc) = with_terminal_creds(spawn);
        hc.login("harness.fake.token", "start", "").unwrap();
        let st = wait_for(&hc, "harness.fake.token", LOGIN_DONE);
        let asked = asked.lock().unwrap();
        assert_eq!(asked[0].0, vec!["fake".to_string(), "setup-token".to_string()], "the token command goes through the terminal seam");
        assert_eq!(asked[0].1, vec![("FAKE_API_KEY".to_string(), String::new())], "a subscription token never sees the API key");
        assert_eq!(st.url, "https://claude.ai/oauth?x=1");
        assert_eq!(hc.token("fake").as_deref(), Some(TOKEN), "the token went to the vault");
        // The token is masked everywhere it could show: rows, state, message.
        let shown = format!("{:?}{:?}", hc.known(), hc.state("harness.fake.token"));
        assert!(!shown.contains("AbCdEfGh"), "{shown}");
        assert_eq!(hc.known()[1].signed_in, "true");
    }

    #[test]
    fn a_token_sign_in_that_prints_nothing_fails_plainly_and_a_pasted_token_stays_the_fallback() {
        let (spawn, _) = terminal("");
        let (_d, hc) = with_terminal_creds(spawn);
        hc.login("harness.fake.token", "start", "").unwrap();
        assert!(wait_for(&hc, "harness.fake.token", LOGIN_FAILED).message.contains("Paste the token"));
        hc.login("harness.fake.token", "input", TOKEN).unwrap();
        assert_eq!(hc.token("fake").as_deref(), Some(TOKEN));
    }

    /// A fake CLI that prints a login URL, waits for the code on stdin and writes its credentials file (the real `auth login` shape).
    #[test]
    fn the_login_prints_a_link_waits_for_the_code_and_signs_in() {
        struct Cli(std::path::PathBuf);
        impl Harness for Cli {
            fn id(&self) -> &str {
                "cli"
            }
            fn capabilities(&self) -> Capabilities {
                Fake(None).capabilities()
            }
            fn models(&self, _: &dyn Probe) -> Vec<ModelInfo> {
                Vec::new()
            }
            fn sign_in_problem(&self, _: &dyn Probe) -> Option<String> {
                (!self.0.exists()).then(|| "not signed in".into())
            }
            fn launch(&self, _: &LaunchRequest) -> Result<LaunchPlan, LaunchError> {
                Err(LaunchError("no".into()))
            }
            fn credential(&self) -> Option<Credential> {
                let creds = self.0.to_string_lossy().replace(std::path::MAIN_SEPARATOR, "/");
                let (program, args) = if cfg!(windows) {
                    ("powershell", vec!["-NoProfile".to_string(), "-Command".into(), format!("[Console]::Out.WriteLine('Open https://login.example/x?c=1'); $c = [Console]::In.ReadLine(); if ($c -eq 'good') {{ Set-Content -Path '{creds}' -Value ok; exit 0 }} else {{ exit 1 }}")])
                } else {
                    ("sh", vec!["-c".to_string(), format!("echo 'Open https://login.example/x?c=1'; read c; [ \"$c\" = good ] && echo ok > '{creds}'")])
                };
                Some(Credential {
                    env: "CLI_API_KEY".into(),
                    label: "Cli".into(),
                    help: String::new(),
                    auth_modes: vec![AUTH_API_KEY.into(), AUTH_SUBSCRIPTION.into()],
                    login_argv: std::iter::once(program.to_string()).chain(args).collect(),
                    ..Default::default()
                })
            }
        }
        let dir = tempfile::tempdir().unwrap();
        let ks = Arc::new(Keystore::with(dir.path(), Arc::new(|_| None), None, infantry_vault::limits::Limits::default()));
        let hc = HarnessCredentials::new(vec![Arc::new(Cli(dir.path().join("creds")))], ks, Arc::new(|_| None));
        hc.login("harness.cli.key", "start", "").unwrap();
        let st = wait_for_url(&hc, "cli");
        assert_eq!(st.url, "https://login.example/x?c=1", "the link the CLI printed is the row's login url");
        assert_eq!(st.state, LOGIN_RUNNING);
        hc.login("harness.cli.key", "input", "good").unwrap();
        let done = wait_for(&hc, "cli", LOGIN_DONE);
        assert_eq!(done.message, "Signed in.");
        assert_eq!(hc.known()[0].signed_in, "true");
    }

    fn wait_for_url(hc: &HarnessCredentials, name: &str) -> LoginState {
        for _ in 0..600 {
            let s = hc.state(name);
            if !s.url.is_empty() {
                return s;
            }
            std::thread::sleep(std::time::Duration::from_millis(50));
        }
        panic!("no login link: {:?}", hc.state(name));
    }

    #[test]
    fn a_code_needs_a_running_sign_in() {
        let (_d, hc) = creds(None);
        assert!(hc.login("harness.fake.key", "input", "abc").is_err());
        assert!(hc.login("plugin.x", "start", "").is_err());
    }
}
