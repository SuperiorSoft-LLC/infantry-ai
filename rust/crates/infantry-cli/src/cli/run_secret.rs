// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
//! SECRETS-3: `infantry run --secret <name> [--secret <name>]... [--project <id>] -- <cmd...>`.
//!
//! The daemon spawns the command (an inline `run_start`, the same run path and frame unit as the Run tab, so the same
//! permission), injecting the named secrets' fields as environment. The CLI never receives a secret value: it gets the
//! run's `run_bytes` (printed as they come), sends its stdin as `run_input`, turns Ctrl+C into a `^C` for the run
//! (a second Ctrl+C stops it) and exits with the run's exit code.

use super::conn::{Envelope, FrameStream};
use super::{Getenv, Io};
use base64::Engine;
use serde_json::{json, Value};
use std::io::{Read, Write};
use std::sync::atomic::{AtomicUsize, Ordering};
use std::sync::mpsc;

const B64: base64::engine::GeneralPurpose = base64::engine::general_purpose::STANDARD;
/// How long the CLI waits for the daemon to answer the start.
const START_TIMEOUT_MS: u64 = 30_000;
/// One poll of the stream between stdin and Ctrl+C checks.
const POLL_MS: u64 = 100;
/// One `run_input` frame carries at most this many bytes (the daemon's per-frame cap is larger).
const INPUT_CHUNK: usize = 1024;

/// The parsed words of `run --secret ...`.
#[derive(Debug, PartialEq, Default)]
pub struct SecretRun {
    pub secrets: Vec<String>,
    pub project: Option<String>,
    pub command: Vec<String>,
}

/// True when `infantry run` asks for a secret (then the daemon runs it, not this process).
pub fn wants_secret(args: &[String]) -> bool {
    let end = args.iter().position(|a| a == "--").unwrap_or(args.len());
    args[..end].iter().any(|a| a == "--secret" || a.starts_with("--secret="))
}

/// Parses the words after `run`.
pub fn parse(args: &[String]) -> Result<SecretRun, String> {
    let Some(split) = args.iter().position(|a| a == "--") else {
        return Err("expects a command after --, e.g. infantry run --secret <name> -- <cmd>".into());
    };
    let mut r = SecretRun { command: args[split + 1..].to_vec(), ..Default::default() };
    let opts = &args[..split];
    let mut i = 0;
    while i < opts.len() {
        let (key, inline) = match opts[i].split_once('=') {
            Some((k, v)) if k.starts_with("--") => (k, Some(v.to_string())),
            _ => (opts[i].as_str(), None),
        };
        let mut value = || -> Result<String, String> {
            match inline.clone() {
                Some(v) => Ok(v),
                None => {
                    i += 1;
                    opts.get(i).cloned().ok_or_else(|| format!("{key} expects a value"))
                }
            }
        };
        match key {
            "--secret" => {
                let v = value()?;
                if v.trim().is_empty() {
                    return Err("--secret expects a secret name".into());
                }
                r.secrets.push(v.trim().to_string());
            }
            "--project" => r.project = Some(value()?.trim().to_string()).filter(|p| !p.is_empty()),
            o => {
                let short: String = o.chars().take(40).collect();
                return Err(format!("unknown option '{short}' with --secret (supported: --secret, --project)"));
            }
        }
        i += 1;
    }
    if r.command.is_empty() {
        return Err("expects a command after --".into());
    }
    Ok(r)
}

/// The inline `run_start` body. It names secrets, never values.
pub fn start_body(r: &SecretRun, cwd: &str) -> Value {
    json!({"argv": r.command, "cwd": cwd, "secrets": r.secrets, "project": r.project})
}

static CTRL_C: AtomicUsize = AtomicUsize::new(0);

#[cfg(windows)]
fn trap_ctrl_c() {
    unsafe extern "system" fn handler(kind: u32) -> windows_sys::core::BOOL {
        if kind == windows_sys::Win32::System::Console::CTRL_C_EVENT {
            CTRL_C.fetch_add(1, Ordering::SeqCst);
            return 1;
        }
        0
    }
    unsafe {
        windows_sys::Win32::System::Console::SetConsoleCtrlHandler(Some(handler), 1);
    }
}

#[cfg(unix)]
fn trap_ctrl_c() {
    extern "C" fn handler(_: libc::c_int) {
        CTRL_C.fetch_add(1, Ordering::SeqCst);
    }
    unsafe {
        libc::signal(libc::SIGINT, handler as *const () as libc::sighandler_t);
    }
}

#[cfg(not(any(windows, unix)))]
fn trap_ctrl_c() {}

/// `infantry run --secret ...`: the exit code (the run's, or 1/2 when it could not start).
pub fn run(args: &[String], io: &mut dyn Io, getenv: Getenv) -> i32 {
    let r = match parse(&args[1..]) {
        Ok(r) => r,
        Err(e) => {
            io.err(&format!("error: run: {e}"));
            return 2;
        }
    };
    let ep = match super::resolve_endpoint(&super::args::Parsed::default(), getenv, false) {
        Ok(ep) => ep,
        Err(e) => {
            io.err(&format!("error: run --secret needs the daemon: {e}"));
            return 1;
        }
    };
    let mut stream = match FrameStream::open(&ep, Box::new(|_: &str| {})) {
        Ok(s) => s,
        Err(e) => {
            io.err(&format!("error: run --secret needs the daemon: {}", e.message()));
            return 1;
        }
    };
    let cwd = std::env::current_dir().map(|d| d.display().to_string()).unwrap_or_default();
    let start = Envelope::new("run_start", start_body(&r, &cwd).to_string());
    let rec = match stream.request_within(&start, "run", "run_start", false, START_TIMEOUT_MS) {
        Ok(a) => a,
        Err(e) => {
            io.err(&format!("error: run: {}", e.message()));
            return 1;
        }
    };
    let run_id = serde_json::from_str::<Value>(&rec.data).ok().and_then(|v| v["runId"].as_str().map(String::from)).unwrap_or_default();
    if run_id.is_empty() {
        io.err("error: run: the daemon answered no runId");
        return 1;
    }
    trap_ctrl_c();
    let (tx, rx) = mpsc::channel::<Vec<u8>>();
    std::thread::spawn(move || {
        let mut buf = [0u8; INPUT_CHUNK];
        let mut stdin = std::io::stdin();
        while let Ok(n) = stdin.read(&mut buf) {
            if n == 0 || tx.send(buf[..n].to_vec()).is_err() {
                break;
            }
        }
    });
    let send_input = |s: &mut FrameStream, bytes: &[u8]| {
        let _ = s.send(&Envelope::new("run_input", json!({"runId": run_id, "data": B64.encode(bytes)}).to_string()));
    };
    let mut seen_ctrl_c = 0;
    let mut out = std::io::stdout();
    loop {
        let c = CTRL_C.load(Ordering::SeqCst);
        if c > seen_ctrl_c {
            if seen_ctrl_c == 0 && c == 1 {
                send_input(&mut stream, b"\x03");
            } else {
                let _ = stream.send(&Envelope::new("run_stop", run_id.clone()));
            }
            seen_ctrl_c = c;
        }
        while let Ok(chunk) = rx.try_recv() {
            send_input(&mut stream, &chunk);
        }
        let f = match stream.next(POLL_MS) {
            Ok(Some(f)) => f,
            Ok(None) => continue,
            Err(e) => {
                io.err(&format!("error: run: lost the daemon ({}); run {run_id} may still be running", e.message()));
                return 1;
            }
        };
        let v: Value = serde_json::from_str(&f.data).unwrap_or(Value::Null);
        if v["runId"].as_str() != Some(run_id.as_str()) {
            continue;
        }
        match f.kind.as_str() {
            "run_bytes" => {
                if let Ok(b) = B64.decode(v["data"].as_str().unwrap_or("")) {
                    let _ = out.write_all(&b).and_then(|_| out.flush());
                }
            }
            "run_finished" => return v["exitCode"].as_i64().map(|c| c as i32).unwrap_or(1),
            _ => {}
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn v(a: &[&str]) -> Vec<String> {
        a.iter().map(|s| s.to_string()).collect()
    }

    #[test]
    fn secret_words_route_to_the_daemon_only_before_the_separator() {
        assert!(wants_secret(&v(&["run", "--secret", "db", "--", "x"])));
        assert!(wants_secret(&v(&["run", "--secret=db", "--", "x"])));
        assert!(!wants_secret(&v(&["run", "--label", "t", "--", "x", "--secret", "db"])));
    }

    #[test]
    fn parse_collects_names_and_the_command() {
        let r = parse(&v(&["--secret", "db", "--secret=api", "--project", "p1", "--", "npm", "test"])).unwrap();
        assert_eq!(r, SecretRun { secrets: v(&["db", "api"]), project: Some("p1".into()), command: v(&["npm", "test"]) });
        assert!(parse(&v(&["--secret", "db"])).is_err());
        assert!(parse(&v(&["--secret", "--", "a"])).is_err());
        assert!(parse(&v(&["--secret", "db", "--"])).is_err());
        assert!(parse(&v(&["--label", "x", "--secret", "db", "--", "a"])).is_err());
    }

    #[test]
    fn the_start_body_names_secrets_and_never_values() {
        let r = SecretRun { secrets: v(&["db"]), project: None, command: v(&["echo", "hi"]) };
        assert_eq!(start_body(&r, "C:/w"), json!({"argv": ["echo", "hi"], "cwd": "C:/w", "secrets": ["db"], "project": null}));
    }
}
