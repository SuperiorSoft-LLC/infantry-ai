// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
//! Where the daemon is and which token to present: a port of `Cli.resolveTarget` (CliMain.kt) with the same order and the
//! same refusals. Nothing here contacts the daemon, and no token is ever written anywhere.

use crate::env::{self, Getenv};
use std::path::{Path, PathBuf};

const DEFAULT_HOST: &str = "127.0.0.1";
const DEFAULT_PORT: i32 = 8787;
const INSTALLED_DEFAULT_PORT: i32 = 8797;
const MIN_TOKEN_LENGTH: usize = 32;

/// The connection options of the command line.
#[derive(Default, Clone)]
pub struct Connection {
    pub host: Option<String>,
    pub port: Option<i32>,
    pub url: Option<String>,
    pub endpoint: Option<String>,
    pub endpoints_file: Option<String>,
    pub token_file: Option<String>,
}

impl Connection {
    fn address_given(&self) -> bool {
        self.host.is_some() || self.port.is_some()
    }
}

#[derive(Clone)]
pub struct Target {
    pub url: String,
    pub token: Option<String>,
    pub token_source: String,
    pub answer_timeout_ms: i64,
    pub session_id: Option<String>,
}

/// The user's home (`user.home`).
pub fn home() -> PathBuf {
    env::raw("USERPROFILE")
        .or_else(|| env::raw("HOME"))
        .filter(|h| !h.is_empty())
        .map(PathBuf::from)
        .unwrap_or_else(|| PathBuf::from("."))
}

/// `stateDir`: `INFANTRY_STATE_DIR`, else `~/.infantry` (read only: this bridge never creates or migrates it).
pub fn state_dir(getenv: Getenv) -> PathBuf {
    match getenv("INFANTRY_STATE_DIR").filter(|v| !v.trim().is_empty()) {
        Some(d) => PathBuf::from(d),
        None => home().join(".infantry"),
    }
}

fn absolute(p: &Path) -> PathBuf {
    if p.is_absolute() {
        p.to_path_buf()
    } else {
        std::env::current_dir().map(|c| c.join(p)).unwrap_or_else(|_| p.to_path_buf())
    }
}

/// `AccessPolicy.readTokenFile`: UTF-8, an optional BOM, trimmed. Errors name the file, never its contents.
pub fn read_token_file(file: &Path) -> Result<String, String> {
    let bytes = std::fs::read(file).map_err(|e| {
        let kind = if e.kind() == std::io::ErrorKind::NotFound { "FileNotFoundException" } else { "IOException" };
        format!("cannot read the token file {} ({kind})", file.display())
    })?;
    let text = String::from_utf8_lossy(&bytes);
    let text = text.strip_prefix('\u{feff}').unwrap_or(&text).trim().to_string();
    if text.is_empty() {
        return Err(format!("the token file {} is empty", file.display()));
    }
    Ok(text)
}

/// `AccessPolicy.tokenFromEnvironment`: `INFANTRY_TOKEN_FILE` (its contents), else `INFANTRY_TOKEN`; both is an error.
pub fn token_from_environment(getenv: Getenv) -> Result<Option<String>, String> {
    let file = getenv("INFANTRY_TOKEN_FILE").filter(|v| !v.trim().is_empty());
    let direct = getenv("INFANTRY_TOKEN").filter(|v| !v.trim().is_empty());
    match (file, direct) {
        (Some(_), Some(_)) => Err("set INFANTRY_TOKEN_FILE or INFANTRY_TOKEN, not both".into()),
        (Some(f), None) => read_token_file(Path::new(&f)).map(Some),
        (None, d) => Ok(d.map(|d| d.trim().to_string())),
    }
}

/// A `java.util.Properties` file, the simple `key=value` / `key: value` subset the daemon writes.
fn properties(file: &Path) -> Vec<(String, String)> {
    let Ok(text) = std::fs::read_to_string(file) else { return Vec::new() };
    text.lines()
        .map(str::trim_start)
        .filter(|l| !l.is_empty() && !l.starts_with('#') && !l.starts_with('!'))
        .filter_map(|l| {
            let at = l.find(['=', ':'])?;
            Some((l[..at].trim().to_string(), l[at + 1..].trim().to_string()))
        })
        .collect()
}

fn property(file: &Path, key: &str) -> Option<String> {
    properties(file).into_iter().find(|(k, _)| k == key).map(|(_, v)| v)
}

/// `RemoteAccess.load(dir).remote`.
fn remote_on(dir: &Path) -> bool {
    property(&dir.join("network.properties"), "remote").is_some_and(|v| v.trim().eq_ignore_ascii_case("true"))
}

/// `RemoteAccess.filePort`.
fn file_port(dir: &Path) -> Option<i32> {
    property(&dir.join("network.properties"), "port")?.trim().parse::<i32>().ok().filter(|p| (0..=65535).contains(p))
}

/// `RemoteAccess.storedToken`.
fn stored_token(dir: &Path) -> Option<String> {
    let f = dir.join("remote.token");
    if !f.is_file() {
        return None;
    }
    read_token_file(&f).ok().filter(|t| t.len() >= MIN_TOKEN_LENGTH)
}

/// `Cli.installedStateDir`.
pub fn installed_state_dir(getenv: Getenv) -> Option<PathBuf> {
    let dir = getenv("INFANTRY_INSTALL_STATE_DIR").filter(|v| !v.trim().is_empty());
    let port = getenv("INFANTRY_INSTALL_PORT").filter(|v| !v.trim().is_empty());
    if dir.is_none() && port.is_none() {
        return None;
    }
    if let Some(d) = dir {
        return Some(absolute(Path::new(&d)));
    }
    let base = getenv("LOCALAPPDATA")
        .filter(|v| !v.trim().is_empty())
        .map(PathBuf::from)
        .unwrap_or_else(|| home().join("AppData/Local"));
    let vendor = base.join("SuperiorSoft");
    let new = vendor.join("InfantryAI");
    let old = vendor.join("AgentHQ");
    Some(absolute(if new.is_dir() || !old.is_dir() { &new } else { &old }))
}

/// `Cli.envTargetUrl`: `INFANTRY_PORT`, else the port `INFANTRY_STATE_DIR` records. A named target never falls back.
pub fn env_target_url(getenv: Getenv) -> Result<Option<String>, String> {
    if let Some(raw_port) = env::non_blank(getenv, "INFANTRY_PORT") {
        let port = raw_port.parse::<i32>().ok().filter(|p| (1..=65535).contains(p)).ok_or_else(|| {
            format!("INFANTRY_PORT='{raw_port}' is not a port (1-65535); not falling back to the default daemon")
        })?;
        return Ok(Some(format!("ws://127.0.0.1:{port}/ws")));
    }
    let Some(raw_dir) = env::non_blank(getenv, "INFANTRY_STATE_DIR") else { return Ok(None) };
    let dir = absolute(Path::new(&raw_dir));
    let port = std::fs::read_to_string(dir.join("daemon.port"))
        .ok()
        .and_then(|t| t.trim().parse::<i32>().ok())
        .or_else(|| file_port(&dir))
        .ok_or_else(|| {
            format!(
                "INFANTRY_STATE_DIR={} names no running daemon (no daemon.port in it); start that daemon or set INFANTRY_PORT. Not falling back to the default daemon.",
                dir.display()
            )
        })?;
    Ok(Some(format!("ws://127.0.0.1:{port}/ws")))
}

/// `Cli.installedUrl`.
fn installed_url(getenv: Getenv) -> Option<String> {
    installed_state_dir(getenv)?;
    let port = getenv("INFANTRY_INSTALL_PORT")
        .and_then(|v| v.trim().parse::<i32>().ok())
        .filter(|p| (1..=65535).contains(p))
        .unwrap_or(INSTALLED_DEFAULT_PORT);
    Some(format!("ws://127.0.0.1:{port}/ws"))
}

struct SavedLogin {
    url: String,
    token_file: Option<String>,
}

/// `LoginStore.load`: `<stateDir>/login.json`, or None.
fn saved_login(getenv: Getenv) -> Option<SavedLogin> {
    let text = std::fs::read_to_string(state_dir(getenv).join("login.json")).ok()?;
    let v: serde_json::Value = serde_json::from_str(&text).ok()?;
    Some(SavedLogin {
        url: v.get("url")?.as_str()?.to_string(),
        token_file: v.get("tokenFile").and_then(|t| t.as_str()).map(str::to_string),
    })
}

fn login_token(login: &SavedLogin) -> Option<String> {
    let f = PathBuf::from(login.token_file.as_ref()?);
    std::fs::read_to_string(f).ok().map(|t| t.trim().to_string()).filter(|t| !t.is_empty())
}

struct Endpoint {
    name: String,
    url: String,
    token_file: Option<String>,
}

/// `Endpoints.load`.
fn load_endpoints(file: &Path) -> Result<Vec<Endpoint>, String> {
    if !file.exists() {
        return Ok(Vec::new());
    }
    let bad = || format!("cannot read endpoints file {}: not valid endpoint JSON", file.display());
    let text = std::fs::read_to_string(file).map_err(|_| bad())?;
    let v: serde_json::Value = serde_json::from_str(text.strip_prefix('\u{feff}').unwrap_or(&text)).map_err(|_| bad())?;
    let list = match v.get("endpoints") {
        None => Vec::new(),
        Some(serde_json::Value::Array(a)) => a.clone(),
        Some(_) => return Err(bad()),
    };
    let mut out = Vec::new();
    for e in list {
        let name = e.get("name").and_then(|x| x.as_str()).ok_or_else(bad)?.to_string();
        let url = e.get("url").and_then(|x| x.as_str()).ok_or_else(bad)?.to_string();
        let token_file = match e.get("tokenFile") {
            None | Some(serde_json::Value::Null) => None,
            Some(serde_json::Value::String(s)) => Some(s.clone()),
            Some(_) => return Err(bad()),
        };
        out.push(Endpoint { name, url, token_file });
    }
    if out.iter().any(|e| e.name.trim().is_empty()) {
        return Err(format!("endpoints file {} has an endpoint with no name", file.display()));
    }
    let mut names: Vec<&str> = out.iter().map(|e| e.name.as_str()).collect();
    names.sort();
    names.dedup();
    if names.len() != out.len() {
        return Err(format!("endpoints file {} names two endpoints the same", file.display()));
    }
    if let Some(e) = out.iter().find(|e| !e.url.starts_with("ws://") && !e.url.starts_with("wss://")) {
        return Err(format!("endpoint '{}' in {} must be a ws:// or wss:// URL", e.name, file.display()));
    }
    Ok(out)
}

/// The host of a `ws://` / `wss://` URL, or None when it has no authority.
pub fn url_host(url: &str) -> Option<String> {
    let rest = url.split_once("://")?.1;
    let authority = rest.split(['/', '?', '#']).next().unwrap_or("");
    let authority = authority.rsplit_once('@').map_or(authority, |(_, h)| h);
    let host = if let Some(v6) = authority.strip_prefix('[') {
        format!("[{}]", v6.split(']').next().unwrap_or(""))
    } else {
        authority.split(':').next().unwrap_or("").to_string()
    };
    Some(host).filter(|h| !h.is_empty())
}

fn url_path(url: &str) -> &str {
    let rest = url.split_once("://").map_or("", |(_, r)| r);
    let after = rest.find(['/', '?', '#']).map_or("", |i| &rest[i..]);
    if after.starts_with('/') {
        after.split(['?', '#']).next().unwrap_or("")
    } else {
        ""
    }
}

/// `Cli.normaliseUrl`: a ws:// or wss:// URL with a host; `/ws` added when the path is omitted.
pub fn normalise_url(raw: &str) -> Result<String, String> {
    if !raw.starts_with("ws://") && !raw.starts_with("wss://") {
        return Err(format!("the daemon URL must start with ws:// or wss://, got '{raw}'"));
    }
    if raw.contains(' ') {
        return Err(format!("not a valid daemon URL: '{raw}'"));
    }
    if url_host(raw).is_none() {
        return Err(format!("the daemon URL has no host: '{raw}'"));
    }
    let path = url_path(raw);
    Ok(if path.is_empty() || path == "/" { format!("{}/ws", raw.trim_end_matches('/')) } else { raw.to_string() })
}

/// `AccessPolicy.isLoopbackHost`.
pub fn is_loopback_host(host: &str) -> bool {
    let bare = host.trim().trim_start_matches('[').trim_end_matches(']');
    if bare.is_empty() {
        return false;
    }
    if bare.eq_ignore_ascii_case("localhost") {
        return true;
    }
    if let Ok(ip) = bare.parse::<std::net::IpAddr>() {
        return ip.is_loopback();
    }
    use std::net::ToSocketAddrs;
    (bare, 0).to_socket_addrs().ok().and_then(|mut a| a.next()).is_some_and(|a| a.ip().is_loopback())
}

/// `Cli.resolveTarget`. Err = a usage error (the CLI prints `error: <message>` and exits 2).
pub fn resolve(conn: &Connection, getenv: Getenv) -> Result<Target, String> {
    let endpoint = match &conn.endpoint {
        Some(name) => {
            let file = conn
                .endpoints_file
                .as_ref()
                .map(PathBuf::from)
                .or_else(|| getenv("INFANTRY_ENDPOINTS").filter(|v| !v.trim().is_empty()).map(PathBuf::from))
                .unwrap_or_else(|| state_dir(getenv).join("endpoints.json"));
            let list = load_endpoints(&file)?;
            Some(
                list.into_iter()
                    .find(|e| &e.name == name)
                    .ok_or_else(|| format!("no endpoint named '{name}' in {}", file.display()))?,
            )
        }
        None => None,
    };
    let typed = format!(
        "ws://{}:{}/ws",
        conn.host.as_deref().unwrap_or(DEFAULT_HOST),
        conn.port.unwrap_or(DEFAULT_PORT)
    );
    let login = saved_login(getenv);
    let raw_url = if let Some(u) = &conn.url {
        u.clone()
    } else if let Some(e) = &endpoint {
        e.url.clone()
    } else if conn.address_given() {
        typed.clone()
    } else if let Some(u) = getenv("INFANTRY_URL").filter(|v| !v.trim().is_empty()) {
        u
    } else if let Some(u) = env_target_url(getenv)? {
        u
    } else if let Some(l) = &login {
        l.url.clone()
    } else if let Some(u) = installed_url(getenv) {
        u
    } else {
        typed
    };
    let url = normalise_url(&raw_url)?;
    let (token, source) = if let Some(tf) = &conn.token_file {
        (Some(read_token_file(Path::new(tf))?), "--token-file".to_string())
    } else if let Some(e) = &endpoint {
        match &e.token_file {
            Some(tf) => (Some(read_token_file(Path::new(tf))?), format!("endpoint '{}'", e.name)),
            None => (None, format!("endpoint '{}', which names no token file", e.name)),
        }
    } else {
        let (t, s) = environment_token(getenv)?;
        match (&t, &login) {
            (None, Some(l)) if raw_url == l.url => match login_token(l) {
                Some(lt) => (Some(lt), "saved login".to_string()),
                None => (t, s),
            },
            _ => (t, s),
        }
    };
    Ok(Target {
        url,
        token,
        token_source: source,
        answer_timeout_ms: env::answer_timeout_ms(getenv),
        session_id: env::non_blank(getenv, "INFANTRY_SESSION_ID"),
    })
}

/// `Cli.environmentToken`.
fn environment_token(getenv: Getenv) -> Result<(Option<String>, String), String> {
    let env_token = token_from_environment(getenv)?;
    let install_dir = installed_state_dir(getenv);
    let isolated = getenv("INFANTRY_ISOLATED_CREDENTIALS").is_some_and(|v| v.trim().eq_ignore_ascii_case("true"));
    let token = env_token
        .clone()
        .or_else(|| install_dir.as_ref().and_then(|d| if remote_on(d) { stored_token(d) } else { None }))
        .or_else(|| {
            // RemoteAccess.clientToken: the env token (unless isolated), else this machine's stored remote token.
            let from_env = if isolated { None } else { token_from_environment(getenv).ok().flatten() };
            from_env.or_else(|| {
                let dir = state_dir(getenv);
                if remote_on(&dir) {
                    stored_token(&dir)
                } else {
                    None
                }
            })
        });
    let source = if getenv("INFANTRY_TOKEN_FILE").is_some_and(|v| !v.trim().is_empty()) {
        "INFANTRY_TOKEN_FILE"
    } else if env_token.is_some() {
        "INFANTRY_TOKEN"
    } else if token.is_some() {
        "stored remote-access token"
    } else {
        "no token configured"
    };
    Ok((token, source.to_string()))
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn normalise_adds_ws_path() {
        assert_eq!(normalise_url("ws://127.0.0.1:18901").unwrap(), "ws://127.0.0.1:18901/ws");
        assert_eq!(normalise_url("ws://127.0.0.1:18901/").unwrap(), "ws://127.0.0.1:18901/ws");
        assert_eq!(normalise_url("wss://h/x").unwrap(), "wss://h/x");
        assert!(normalise_url("http://h").unwrap_err().contains("must start with ws://"));
        assert!(normalise_url("ws:///ws").unwrap_err().contains("has no host"));
    }

    #[test]
    fn port_env_wins_and_never_falls_back() {
        let env = |n: &str| (n == "INFANTRY_PORT").then(|| "18901".to_string());
        assert_eq!(env_target_url(&env).unwrap().as_deref(), Some("ws://127.0.0.1:18901/ws"));
        let bad = |n: &str| (n == "INFANTRY_PORT").then(|| "x".to_string());
        assert!(env_target_url(&bad).unwrap_err().contains("not falling back"));
    }

    #[test]
    fn both_token_vars_refused() {
        let env = |n: &str| matches!(n, "INFANTRY_TOKEN" | "INFANTRY_TOKEN_FILE").then(|| "x".to_string());
        assert_eq!(token_from_environment(&env).unwrap_err(), "set INFANTRY_TOKEN_FILE or INFANTRY_TOKEN, not both");
    }

    #[test]
    fn loopback() {
        assert!(is_loopback_host("localhost"));
        assert!(is_loopback_host("127.0.0.1"));
        assert!(is_loopback_host("[::1]"));
        assert!(!is_loopback_host(""));
        assert!(!is_loopback_host("10.0.0.1"));
    }
}
