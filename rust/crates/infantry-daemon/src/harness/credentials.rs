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
use infantry_harness::{Credential, Harness, LocalProbe, Probe, AUTH_API_KEY, AUTH_SUBSCRIPTION};
use infantry_vault::handler::KnownSecret;
use infantry_vault::keystore::{Keystore, VALUE_FIELD};
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
}

impl Run {
    fn new(state: LoginState) -> Arc<Run> {
        Arc::new(Run { state: Mutex::new(state), child: Mutex::default(), stdin: Mutex::default(), cancelled: Default::default(), captured: Default::default() })
    }
    /// Stops the process but keeps the outcome (the token arrived by paste; the CLI no longer matters).
    fn kill_process(&self) {
        if let Some(c) = self.child.lock().unwrap_or_else(|e| e.into_inner()).as_mut() {
            let _ = c.kill();
        }
    }
    fn kill(&self) {
        self.cancelled.store(true, std::sync::atomic::Ordering::SeqCst);
        if let Some(c) = self.child.lock().unwrap_or_else(|e| e.into_inner()).as_mut() {
            let _ = c.kill();
        }
    }
    fn set(&self, f: impl FnOnce(&mut LoginState)) {
        f(&mut self.state.lock().unwrap_or_else(|e| e.into_inner()));
    }
}

pub type SettingFn = Arc<dyn Fn(&str) -> Option<String> + Send + Sync>;

pub struct HarnessCredentials {
    harnesses: Vec<Arc<dyn Harness>>,
    keystore: Arc<Keystore>,
    setting: SettingFn,
    runs: Mutex<HashMap<String, Arc<Run>>>,
}

impl HarnessCredentials {
    pub fn new(harnesses: Vec<Arc<dyn Harness>>, keystore: Arc<Keystore>, setting: SettingFn) -> Arc<Self> {
        Arc::new(HarnessCredentials { harnesses, keystore, setting, runs: Mutex::default() })
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
                    t.signed_in = h.sign_in_problem(&probe).is_none().to_string();
                    let login = self.state(&token_name(&id));
                    (t.login_state, t.login_url, t.login_message) = (login.state, login.url, login.message);
                    rows.push(t);
                }
                let login = self.state(&id);
                let mut k = KnownSecret::new(&key_name(&id), &c.label, Some(&c.env));
                k.help = c.help.clone();
                k.auth_mode = self.mode(&id, &c);
                k.auth_modes = c.auth_modes.clone();
                k.login_command = c.login_argv.join(" ");
                k.can_login = !c.login_argv.is_empty();
                k.signed_in = if c.login_argv.is_empty() { "unknown".into() } else { h.sign_in_problem(&probe).is_none().to_string() };
                k.login_state = login.state;
                k.login_url = login.url;
                k.login_message = login.message;
                rows.push(k);
                rows
            })
            .collect()
    }

    /// The env a session of [provider] gets: the key in api-key mode, the variable blanked in subscription mode.
    pub fn session_env(&self, provider: &str) -> Vec<(String, String)> {
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
            other => Err(format!("unknown sign-in action '{other}'")),
        }
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
            let signed_in = h.sign_in_problem(&me.probe()).is_none();
            run.set(|s| {
                if status && signed_in {
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
        let run = run.filter(|r| r.state.lock().unwrap_or_else(|e| e.into_inner()).state == LOGIN_RUNNING).ok_or("no sign-in is running; start it again")?;
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
            token_env: "FAKE_OAUTH".into(),
            token_argv: vec!["fake".into(), "setup-token".into()],
            token_prefix: "sk-fake-".into(),
        };
        let hc = HarnessCredentials::new(vec![Arc::new(Fake(Some(c))), Arc::new(Fake(None))], ks, Arc::new(move |_| setting.map(String::from)));
        (dir, hc)
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
        assert_eq!(k[1].auth_modes, vec![AUTH_API_KEY.to_string(), AUTH_SUBSCRIPTION.to_string()]);
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
        // Something that is not a token needs a running sign-in to receive it.
        assert!(hc.login("harness.fake.token", "input", "a-code").is_err());
    }

    #[test]
    fn the_printed_token_is_found_in_the_cli_output() {
        assert_eq!(find_token("Your token: sk-fake-abcdef1234567890. Keep it safe", "sk-fake-").as_deref(), Some("sk-fake-abcdef1234567890"));
        assert_eq!(find_token("sk-fake-", "sk-fake-"), None, "the prefix alone is not a token");
        assert_eq!(find_token("sk-fake-abcdef1234567890", ""), None, "no prefix declared: nothing is taken from output");
        assert_eq!(harness_of("harness.claude.token"), Some("claude"));
    }

    #[test]
    fn a_code_needs_a_running_sign_in() {
        let (_d, hc) = creds(None);
        assert!(hc.login("harness.fake.key", "input", "abc").is_err());
        assert!(hc.login("plugin.x", "start", "").is_err());
    }
}
