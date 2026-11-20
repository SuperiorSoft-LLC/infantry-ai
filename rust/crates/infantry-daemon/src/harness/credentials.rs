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
use infantry_harness::{Credential, Harness, LocalProbe, AUTH_API_KEY, AUTH_SUBSCRIPTION};
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

/// The harness id of a key name (`harness.claude.key` -> `claude`).
pub fn harness_of(secret: &str) -> Option<&str> {
    secret.strip_prefix("harness.")?.strip_suffix(".key")
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
}

impl Run {
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

    pub fn state(&self, id: &str) -> LoginState {
        self.runs.lock().unwrap_or_else(|e| e.into_inner()).get(id).map(|r| r.state.lock().unwrap_or_else(|e| e.into_inner()).clone()).unwrap_or_default()
    }

    /// The key rows the vault shows ([`infantry_vault::handler::HandlerDeps::extra_known`]).
    pub fn known(&self) -> Vec<KnownSecret> {
        self.declared()
            .map(|(h, c)| {
                let id = h.id().to_string();
                let login = self.state(&id);
                let mut k = KnownSecret::new(&key_name(&id), &c.label, Some(&c.env));
                k.help = c.help.clone();
                k.auth_mode = self.mode(&id, &c);
                k.auth_modes = c.auth_modes.clone();
                k.login_command = c.login_argv.join(" ");
                k.can_login = !c.login_argv.is_empty();
                k.signed_in = if c.login_argv.is_empty() { "unknown".into() } else { h.sign_in_problem(&LocalProbe).is_none().to_string() };
                k.login_state = login.state;
                k.login_url = login.url;
                k.login_message = login.message;
                k
            })
            .collect()
    }

    /// The env a session of [provider] gets: the key in api-key mode, the variable blanked in subscription mode.
    pub fn session_env(&self, provider: &str) -> Vec<(String, String)> {
        let Some((_, c)) = self.declared().find(|(h, _)| h.id() == provider) else { return Vec::new() };
        if self.mode(provider, &c) == AUTH_SUBSCRIPTION {
            return vec![(c.env.clone(), String::new())];
        }
        self.key(provider).map(|k| vec![(c.env.clone(), k)]).unwrap_or_default()
    }

    /// start / input / cancel the sign-in of the harness whose key is [secret] ([`infantry_vault::handler::LoginFn`]).
    pub fn login(self: &Arc<Self>, secret: &str, action: &str, text: &str) -> Result<(), String> {
        let id = harness_of(secret).ok_or_else(|| format!("'{secret}' is not a harness key"))?.to_string();
        match action {
            "start" => self.start(&id),
            "input" => self.input(&id, text),
            "cancel" => {
                if let Some(r) = self.runs.lock().unwrap_or_else(|e| e.into_inner()).remove(&id) {
                    r.kill();
                }
                Ok(())
            }
            other => Err(format!("unknown sign-in action '{other}'")),
        }
    }

    fn start(self: &Arc<Self>, id: &str) -> Result<(), String> {
        let (h, c) = self.declared().find(|(h, _)| h.id() == id).map(|(h, c)| (h.clone(), c)).ok_or_else(|| format!("unknown harness '{id}'"))?;
        if c.login_argv.is_empty() {
            return Err(format!("{} has no in-app sign-in; run its login command in a terminal", c.label));
        }
        if let Some(old) = self.runs.lock().unwrap_or_else(|e| e.into_inner()).remove(id) {
            old.kill();
        }
        let run = Arc::new(Run {
            state: Mutex::new(LoginState { state: LOGIN_RUNNING.into(), ..Default::default() }),
            child: Mutex::default(),
            stdin: Mutex::default(),
            cancelled: Default::default(),
        });
        self.runs.lock().unwrap_or_else(|e| e.into_inner()).insert(id.to_string(), run.clone());
        // The CLI the guided setup installed lives in the app's folder: start what its npm shim runs (no cmd.exe between).
        let mut argv = infantry_harness::shim::launcher(&LocalProbe, &c.login_argv[0], "node");
        argv.extend(c.login_argv[1..].iter().cloned());
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
        let readers: Vec<_> = [child.stdout.take().map(|s| Box::new(s) as Box<dyn Read + Send>), child.stderr.take().map(|s| Box::new(s) as Box<dyn Read + Send>)]
            .into_iter()
            .flatten()
            .map(|stream| {
                let run = run.clone();
                std::thread::spawn(move || {
                    for line in BufReader::new(stream).lines().map_while(Result::ok) {
                        seen(&run, &line);
                    }
                })
            })
            .collect();
        *run.stdin.lock().unwrap_or_else(|e| e.into_inner()) = child.stdin.take();
        *run.child.lock().unwrap_or_else(|e| e.into_inner()) = Some(child);
        let (me, id) = (self.clone(), id.to_string());
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
            let current = me.runs.lock().unwrap_or_else(|e| e.into_inner()).get(&id).is_some_and(|r| Arc::ptr_eq(r, &run));
            if run.cancelled.load(std::sync::atomic::Ordering::SeqCst) || !current {
                return;
            }
            // The probe after the exit is what counts: exit 0 and credentials on disk = signed in.
            let signed_in = h.sign_in_problem(&LocalProbe).is_none();
            run.set(|s| {
                if status && signed_in {
                    s.state = LOGIN_DONE.into();
                    s.message = "Signed in.".into();
                } else {
                    s.state = LOGIN_FAILED.into();
                    if s.message.is_empty() {
                        s.message = "The sign-in did not finish. Start it again.".into();
                    }
                }
            });
        });
        Ok(())
    }

    fn input(&self, id: &str, text: &str) -> Result<(), String> {
        let run = self.runs.lock().unwrap_or_else(|e| e.into_inner()).get(id).cloned();
        let run = run.filter(|r| r.state.lock().unwrap_or_else(|e| e.into_inner()).state == LOGIN_RUNNING).ok_or("no sign-in is running; start it again")?;
        let line = text.trim().replace(['\r', '\n'], "");
        if line.is_empty() {
            return Err("paste the code the sign-in page shows".into());
        }
        let mut stdin = run.stdin.lock().unwrap_or_else(|e| e.into_inner());
        let w = stdin.as_mut().ok_or("the sign-in no longer takes input; start it again")?;
        w.write_all(format!("{line}\n").as_bytes()).and_then(|_| w.flush()).map_err(|e| format!("could not send the code: {e}"))?;
        run.set(|s| s.message = "Checking the code...".into());
        Ok(())
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
            Capabilities::default()
        }
        fn models(&self, _: &dyn Probe) -> Vec<ModelInfo> {
            Vec::new()
        }
        fn sign_in_problem(&self, _: &dyn Probe) -> Option<String> {
            Some("not signed in".into())
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
        let ks = Arc::new(Keystore::open(dir.path()).unwrap());
        let c = Credential {
            env: "FAKE_API_KEY".into(),
            label: "Fake".into(),
            help: "h".into(),
            auth_modes: vec![AUTH_API_KEY.into(), AUTH_SUBSCRIPTION.into()],
            login_argv: vec!["fake".into(), "auth".into(), "login".into()],
        };
        let hc = HarnessCredentials::new(vec![Arc::new(Fake(Some(c))), Arc::new(Fake(None))], ks, Arc::new(move |_| setting.map(String::from)));
        (dir, hc)
    }

    #[test]
    fn a_declared_harness_gets_a_key_row_with_its_sign_in() {
        let (_d, hc) = creds(None);
        let k = hc.known();
        assert_eq!(k.len(), 1, "a harness with no credential gets no row");
        assert_eq!((k[0].name.as_str(), k[0].env_alias.as_deref()), ("harness.fake.key", Some("FAKE_API_KEY")));
        assert!(k[0].can_login);
        assert_eq!(k[0].login_command, "fake auth login");
        assert_eq!(k[0].signed_in, "false");
        assert_eq!(k[0].auth_modes, vec![AUTH_API_KEY.to_string(), AUTH_SUBSCRIPTION.to_string()]);
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
    fn a_code_needs_a_running_sign_in() {
        let (_d, hc) = creds(None);
        assert!(hc.login("harness.fake.key", "input", "abc").is_err());
        assert!(hc.login("plugin.x", "start", "").is_err());
    }
}
