// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
//! `hook-channel`: how a harness CLI's hooks reach the runner. Two generic halves, no vendor names:
//! - an **events file**: a command hook appends one event word per line ([`event_command`]); [`EventsFile`] tails it and reads each
//!   word whatever shell wrote it (PowerShell 5.1 writes UTF-16LE with a BOM, cmd keeps a trailing space, Windows ends lines with CR);
//! - a **loopback HTTP endpoint** ([`HookServer`]): a blocking hook (a pre-tool or permission call) POSTs its JSON to
//!   `http://127.0.0.1:<port>/hook/<token>` and gets the registered handler's JSON answer back.

use crate::tail::AppendedLines;
use std::collections::HashMap;
use std::io::{BufRead, BufReader, Read, Write};
use std::net::{SocketAddr, TcpListener, TcpStream};
use std::path::{Path, PathBuf};
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::{Arc, Mutex};
use std::time::Duration;

/// The shell command of a command hook that appends [event] to [file]: `echo <event> >> "<file>"`. The spaces around `>>` matter
/// (PowerShell reads `echo stop>>"f"` as one word and writes nothing); forward slashes so no shell reads a backslash as an escape.
/// It means the same in bash, sh, PowerShell 5.1, pwsh and cmd.
pub fn event_command(event: &str, file: &Path) -> String {
    format!("echo {} >> \"{}\"", event, file.to_string_lossy().replace('\\', "/"))
}

/// The event word of one events-file line: ASCII letters only, lower-cased (NULs of UTF-16, a BOM, spaces and CR go).
pub fn event_of(line: &str) -> String {
    line.chars().filter(|c| c.is_ascii_alphabetic()).map(|c| c.to_ascii_lowercase()).collect()
}

/// A hook events file being tailed.
pub struct EventsFile {
    pub path: PathBuf,
    tail: AppendedLines,
}

impl EventsFile {
    /// A fresh events file at [path] (an old one is removed so a past session's events are never read).
    pub fn fresh(path: PathBuf) -> Self {
        let _ = std::fs::remove_file(&path);
        EventsFile { path, tail: AppendedLines::default() }
    }

    pub fn exists(&self) -> bool {
        self.path.is_file()
    }

    /// The events appended since the last read.
    pub fn read(&mut self) -> Vec<String> {
        self.tail.read(&self.path).iter().map(|l| event_of(l)).filter(|e| !e.is_empty()).collect()
    }
}

/// A blocking hook call's handler: request body in, response body (JSON) out. Runs on the connection's thread.
pub type HookHandler = Arc<dyn Fn(&str) -> String + Send + Sync>;

/// Largest hook request body accepted, in bytes.
pub const MAX_BODY_BYTES: usize = 4 * 1024 * 1024;
/// How long a connection may take to send its request.
pub const READ_TIMEOUT: Duration = Duration::from_secs(30);

/// The loopback endpoint for blocking hook calls. One per runner; sessions register a token each.
pub struct HookServer {
    addr: SocketAddr,
    handlers: Arc<Mutex<HashMap<String, HookHandler>>>,
    stop: Arc<AtomicBool>,
}

impl HookServer {
    /// Binds 127.0.0.1 on a free port and serves until dropped.
    pub fn start() -> std::io::Result<Arc<HookServer>> {
        let listener = TcpListener::bind("127.0.0.1:0")?;
        let addr = listener.local_addr()?;
        let handlers: Arc<Mutex<HashMap<String, HookHandler>>> = Arc::default();
        let stop = Arc::new(AtomicBool::new(false));
        let (h, s) = (handlers.clone(), stop.clone());
        std::thread::Builder::new().name("infantry-hooks".into()).spawn(move || {
            for conn in listener.incoming() {
                if s.load(Ordering::SeqCst) {
                    break;
                }
                let Ok(conn) = conn else { continue };
                let h = h.clone();
                let _ = std::thread::Builder::new().name("infantry-hook-call".into()).spawn(move || serve(conn, &h));
            }
        })?;
        Ok(Arc::new(HookServer { addr, handlers, stop }))
    }

    /// The URL a hook of [token] calls.
    pub fn url(&self, token: &str) -> String {
        format!("http://127.0.0.1:{}/hook/{}", self.addr.port(), token)
    }

    pub fn register(&self, token: &str, handler: HookHandler) {
        self.handlers.lock().unwrap().insert(token.to_string(), handler);
    }

    pub fn unregister(&self, token: &str) {
        self.handlers.lock().unwrap().remove(token);
    }
}

impl Drop for HookServer {
    fn drop(&mut self) {
        self.stop.store(true, Ordering::SeqCst);
        let _ = TcpStream::connect_timeout(&self.addr, Duration::from_millis(200));
    }
}

fn serve(mut conn: TcpStream, handlers: &Mutex<HashMap<String, HookHandler>>) {
    let _ = conn.set_read_timeout(Some(READ_TIMEOUT));
    let (status, body) = match read_request(&conn) {
        Some((path, body)) => {
            let token = path.strip_prefix("/hook/").unwrap_or("").to_string();
            let handler = handlers.lock().unwrap().get(&token).cloned();
            match handler {
                Some(h) => ("200 OK", h(&body)),
                None => ("404 Not Found", "{}".to_string()),
            }
        }
        None => ("400 Bad Request", "{}".to_string()),
    };
    let _ = write!(
        conn,
        "HTTP/1.1 {status}\r\nContent-Type: application/json\r\nContent-Length: {}\r\nConnection: close\r\n\r\n{body}",
        body.len()
    );
    let _ = conn.flush();
}

/// (path, body) of one HTTP/1.1 POST; None when it is not one.
fn read_request(conn: &TcpStream) -> Option<(String, String)> {
    let mut r = BufReader::new(conn);
    let mut line = String::new();
    r.read_line(&mut line).ok()?;
    let mut parts = line.split_whitespace();
    let (_method, path) = (parts.next()?, parts.next()?.to_string());
    let mut length = 0usize;
    loop {
        let mut h = String::new();
        if r.read_line(&mut h).ok()? == 0 {
            break;
        }
        let h = h.trim_end();
        if h.is_empty() {
            break;
        }
        if let Some((k, v)) = h.split_once(':') {
            if k.trim().eq_ignore_ascii_case("content-length") {
                length = v.trim().parse().ok()?;
            }
        }
    }
    if length > MAX_BODY_BYTES {
        return None;
    }
    let mut body = vec![0u8; length];
    r.read_exact(&mut body).ok()?;
    Some((path, String::from_utf8_lossy(&body).into_owned()))
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn the_event_command_spaces_its_redirect_and_uses_forward_slashes() {
        assert_eq!(event_command("stop", Path::new("C:\\s\\x.events")), "echo stop >> \"C:/s/x.events\"");
    }

    #[test]
    fn events_written_by_powershell_cmd_and_sh_all_read() {
        let dir = std::env::temp_dir().join(format!("infantry-ev-{}", uuid::Uuid::new_v4()));
        std::fs::create_dir_all(&dir).unwrap();
        let mut ev = EventsFile::fresh(dir.join("s.events"));
        let mut bytes = vec![0xFF, 0xFE]; // PowerShell 5.1: UTF-16LE with a BOM
        for c in "start\r\n".encode_utf16() {
            bytes.extend_from_slice(&c.to_le_bytes());
        }
        bytes.extend_from_slice(b"submit \r\n"); // cmd: trailing space
        bytes.extend_from_slice(b"stop\n"); // sh
        std::fs::write(&ev.path, bytes).unwrap();
        assert_eq!(ev.read(), vec!["start", "submit", "stop"]);
        let _ = std::fs::remove_dir_all(dir);
    }

    #[test]
    fn a_hook_call_gets_its_handler_answer_and_an_unknown_token_404() {
        let server = HookServer::start().unwrap();
        server.register("t1", Arc::new(|body: &str| format!("{{\"echo\":{}}}", body.len())));
        let post = |url: &str, body: &str| {
            let rest = url.strip_prefix("http://").unwrap();
            let (host, path) = rest.split_at(rest.find('/').unwrap());
            let mut s = TcpStream::connect(host).unwrap();
            write!(s, "POST {path} HTTP/1.1\r\nHost: {host}\r\nContent-Length: {}\r\n\r\n{body}", body.len()).unwrap();
            let mut out = String::new();
            s.read_to_string(&mut out).unwrap();
            out
        };
        let ok = post(&server.url("t1"), "{\"a\":1}");
        assert!(ok.starts_with("HTTP/1.1 200"), "{ok}");
        assert!(ok.ends_with("{\"echo\":7}"), "{ok}");
        assert!(post(&server.url("nope"), "{}").starts_with("HTTP/1.1 404"));
        server.unregister("t1");
        assert!(post(&server.url("t1"), "{}").starts_with("HTTP/1.1 404"));
    }
}
