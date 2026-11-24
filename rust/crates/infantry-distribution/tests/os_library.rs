// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
//! BOS-18: Infantry AI as a library loaded once by BattalionOS's own worker. The worker is the real one (the OS's
//! `bos-fixture-os` program from the BattalionOS repo, named by `BOS_WORKER_EXE`); this test plays the OS side of the
//! handshake exactly as the supervisor does (nonce in the environment and on stdin line 1, the context JSON on line 2,
//! stdin held open; end of file = stop). Temp state dir, port 0, every INFANTRY_*, AGENTHQ_*, BATTALION_* variable of this
//! process removed first. Without `BOS_WORKER_EXE` the test says so and does nothing (it needs the OS's worker).

use std::io::{Read, Write};
use std::path::{Path, PathBuf};
use std::process::{Command, Stdio};
use std::time::{Duration, Instant};

fn library() -> PathBuf {
    let name = if cfg!(windows) {
        "infantry_distribution.dll"
    } else if cfg!(target_os = "macos") {
        "libinfantry_distribution.dylib"
    } else {
        "libinfantry_distribution.so"
    };
    std::env::current_exe()
        .unwrap()
        .ancestors()
        .skip(1)
        .take(8)
        .map(|d| d.join(name))
        .find(|p| p.is_file())
        .unwrap_or_else(|| panic!("{name} not found beside the test binary: build the library first (cargo build -p infantry-distribution --lib, same profile)"))
}

fn get(url: &str, path: &str) -> std::io::Result<(u16, String)> {
    let host = url.trim_start_matches("http://");
    let mut s = std::net::TcpStream::connect(host)?;
    s.set_read_timeout(Some(Duration::from_secs(5)))?;
    write!(s, "GET {path} HTTP/1.1\r\nHost: {host}\r\nConnection: close\r\n\r\n")?;
    let mut buf = String::new();
    s.read_to_string(&mut buf)?;
    let code = buf.split_whitespace().nth(1).and_then(|c| c.parse().ok()).unwrap_or(0);
    Ok((code, buf.split_once("\r\n\r\n").map(|(_, b)| b.to_string()).unwrap_or_default()))
}

fn wait_for<T>(secs: u64, mut f: impl FnMut() -> Option<T>) -> T {
    let end = Instant::now() + Duration::from_secs(secs);
    loop {
        if let Some(v) = f() {
            return v;
        }
        assert!(Instant::now() < end, "timed out");
        std::thread::sleep(Duration::from_millis(100));
    }
}

#[test]
fn the_worker_loads_the_daemon_library_health_answers_and_stop_ends_it_cleanly() {
    let Some(worker) = std::env::var_os("BOS_WORKER_EXE").map(PathBuf::from) else {
        eprintln!("SKIPPED: BOS_WORKER_EXE (the BattalionOS worker program) is not set");
        return;
    };
    let lib = library();
    let root = std::env::temp_dir().join(format!("bos18-daemon-{}", std::process::id()));
    let _ = std::fs::remove_dir_all(&root);
    let (app_dir, state, run) = (root.join("app"), root.join("state"), root.join("run"));
    for d in [&app_dir.join("lib"), &state, &run] {
        std::fs::create_dir_all(d).unwrap();
    }
    // The package folder the OS gives: the library lives in `lib/` (the daemon reads its own build id from it).
    let in_package = app_dir.join("lib").join(lib.file_name().unwrap());
    std::fs::copy(&lib, &in_package).unwrap();
    let (ready, token) = (run.join("ready.json"), run.join("token"));
    std::fs::write(&token, "test-secret-for-the-stop-call").unwrap();

    let nonce = "bos18-test-nonce";
    let mut cmd = Command::new(&worker);
    cmd.arg("--bos-app-worker").arg(&in_package).current_dir(&app_dir).stdin(Stdio::piped()).stdout(Stdio::piped()).stderr(Stdio::piped());
    for (k, _) in std::env::vars() {
        let u = k.to_ascii_uppercase();
        if u.starts_with("INFANTRY_") || u.starts_with("AGENTHQ_") || u.starts_with("BATTALION_") {
            cmd.env_remove(k);
        }
    }
    cmd.env("BATTALION_CONTRACT", "1")
        .env("BATTALION_APP_ID", "infantry-ai")
        .env("BATTALION_APP_VERSION", "0.0.0")
        .env("BATTALION_APP_DIR", &app_dir)
        .env("BATTALION_STATE_DIR", &state)
        .env("BATTALION_READY_FILE", &ready)
        .env("BATTALION_TOKEN_FILE", &token)
        .env("BATTALION_WORKER_NONCE", nonce);
    let mut child = cmd.spawn().expect("start the worker");
    let mut stdin = child.stdin.take().unwrap();
    // Port 0: a free port (the manifest's `args` of the package say the same).
    let ctx = serde_json::json!({
        "contract": 1, "abi": 1, "appId": "infantry-ai", "appVersion": "0.0.0",
        "appDir": app_dir, "stateDir": state, "readyFile": ready, "tokenFile": token,
        "args": ["--port", "0"]
    });
    writeln!(stdin, "{nonce}").unwrap();
    writeln!(stdin, "{ctx}").unwrap();
    stdin.flush().unwrap();

    // Ready file: written by the daemon once it listens.
    let rf: serde_json::Value = wait_for(60, || {
        if let Ok(Some(st)) = child.try_wait() {
            let mut err = String::new();
            let _ = child.stderr.take().map(|mut e| e.read_to_string(&mut err));
            panic!("the worker ended early ({st}): {err}");
        }
        std::fs::read_to_string(&ready).ok().and_then(|t| serde_json::from_str(&t).ok())
    });
    let url = rf["url"].as_str().unwrap().to_string();
    assert!(url.starts_with("http://127.0.0.1:"), "{url}");
    assert_eq!(rf["pid"].as_u64().unwrap() as u32, child.id(), "the ready file names the worker process (the library runs inside it)");

    // /health answers.
    let (code, body) = wait_for(30, || get(&url, "/health").ok().filter(|(c, _)| *c == 200));
    assert_eq!(code, 200, "{body}");
    assert!(!body.trim().is_empty());

    // stop(): the OS closes the worker's stdin; the library stops gracefully and the worker ends with run's code 0.
    drop(stdin);
    let end = Instant::now() + Duration::from_secs(30);
    let status = loop {
        if let Some(s) = child.try_wait().unwrap() {
            break s;
        }
        assert!(Instant::now() < end, "stop() did not end the daemon in 30 s");
        std::thread::sleep(Duration::from_millis(100));
    };
    assert!(status.success(), "clean end expected, got {status}");
    assert!(!ready.exists(), "the ready file is cleared at a graceful stop");
    assert!(get(&url, "/health").is_err(), "the port is closed");
    let _ = std::fs::remove_dir_all(&root);
    let _: &Path = &root;
}
