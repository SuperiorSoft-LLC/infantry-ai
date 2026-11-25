// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
//! DAEMON-LOCK-2: one Infantry AI per data folder, whichever way it is started. The program (`infantry-daemon`, a child
//! process) and the library entry (`run_checked`, in this process, as BattalionOS's worker runs it) share one start path
//! that takes the lock before any file of the folder is opened. Temp state dirs, port 0, every INFANTRY_*, AGENTHQ_*,
//! BATTALION_* variable removed (from this process and from the children); children are stopped by pid.

use std::path::{Path, PathBuf};
use std::process::{Child, Command, Stdio};
use std::sync::Mutex;
use std::time::{Duration, Instant};

/// The library entry uses one process-wide stop signal, so these tests run one at a time.
static ONE_AT_A_TIME: Mutex<()> = Mutex::new(());

fn scrub_env() {
    for (k, _) in std::env::vars() {
        let u = k.to_ascii_uppercase();
        if u.starts_with("INFANTRY_") || u.starts_with("AGENTHQ_") || u.starts_with("BATTALION_") {
            std::env::remove_var(k);
        }
    }
}

fn args(state: &Path) -> Vec<String> {
    ["--port", "0", "--lock-wait-secs", "0", "--state-dir"].iter().map(|s| s.to_string()).chain([state.to_string_lossy().into_owned()]).collect()
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

fn port_file(state: &Path) -> PathBuf {
    state.join("daemon.port")
}

fn program(state: &Path, log: &Path) -> Child {
    let mut cmd = Command::new(env!("CARGO_BIN_EXE_infantry-daemon"));
    cmd.args(args(state)).stdin(Stdio::null()).stdout(Stdio::from(std::fs::File::create(log).unwrap())).stderr(Stdio::from(std::fs::File::create(log.with_extension("err")).unwrap()));
    for (k, _) in std::env::vars() {
        let u = k.to_ascii_uppercase();
        if u.starts_with("INFANTRY_") || u.starts_with("AGENTHQ_") || u.starts_with("BATTALION_") {
            cmd.env_remove(k);
        }
    }
    cmd.spawn().expect("start the program")
}

fn program_refused(state: &Path, log: &Path) -> String {
    let mut c = program(state, log);
    let status = wait_for(60, || c.try_wait().unwrap());
    assert_eq!(status.code(), Some(2), "a refused program exits with a clear code, not 0");
    std::fs::read_to_string(log.with_extension("err")).unwrap()
}

fn library(state: &Path) -> std::thread::JoinHandle<Result<(), String>> {
    let a = args(state);
    std::thread::spawn(move || infantry_distribution::run_checked(a))
}

fn stop_library(h: std::thread::JoinHandle<Result<(), String>>) {
    infantry_distribution::STOP.notify_one();
    let end = Instant::now() + Duration::from_secs(60);
    while !h.is_finished() {
        assert!(Instant::now() < end, "the library did not stop in 60 s");
        std::thread::sleep(Duration::from_millis(100));
    }
    h.join().unwrap().expect("a clean stop");
}

const WORDS: &str = "Infantry AI is already running with this data (process ";

#[test]
fn a_program_holds_the_folder_a_second_program_and_the_library_are_refused() {
    let _g = ONE_AT_A_TIME.lock().unwrap_or_else(|e| e.into_inner());
    scrub_env();
    let d = tempfile::tempdir().unwrap();
    let state = d.path().join("state");
    let mut first = program(&state, &d.path().join("first.log"));
    wait_for(60, || port_file(&state).is_file().then_some(()));

    let err = program_refused(&state, &d.path().join("second.log"));
    assert!(err.contains(WORDS) && err.contains(&format!("process {})", first.id())) && err.contains("Close it first."), "{err}");

    let lib = library(&state).join().unwrap().unwrap_err();
    assert!(lib.starts_with(WORDS) && lib.contains(&first.id().to_string()) && lib.ends_with("Close it first."), "{lib}");
    // The refused starts did not take the first one's files.
    assert!(port_file(&state).is_file());
    first.kill().unwrap();
    first.wait().unwrap();
}

#[test]
fn the_library_holds_the_folder_the_program_is_refused_and_stop_then_start_again_works_in_one_process() {
    let _g = ONE_AT_A_TIME.lock().unwrap_or_else(|e| e.into_inner());
    scrub_env();
    let d = tempfile::tempdir().unwrap();
    let state = d.path().join("state");

    let lib = library(&state);
    wait_for(60, || port_file(&state).is_file().then_some(()));
    let err = program_refused(&state, &d.path().join("p.log"));
    assert!(err.contains(WORDS) && err.contains(&format!("process {})", std::process::id())), "{err}");
    stop_library(lib);
    assert!(!port_file(&state).exists(), "a stopped library leaves no port file");

    // Stop, then start again in this same process: the lock was released when the library stopped.
    let again = library(&state);
    wait_for(60, || port_file(&state).is_file().then_some(()));
    stop_library(again);

    // And the program can take the folder the library gave back.
    let mut p = program(&state, &d.path().join("after.log"));
    wait_for(60, || port_file(&state).is_file().then_some(()));
    p.kill().unwrap();
    p.wait().unwrap();
}
