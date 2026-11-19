// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
//! Starting a process, piped or in a pseudo-terminal, and the threads that pump it: readers publish output to the hub, a
//! waiter publishes the exit once the output drained (or the drain grace ran out).

use crate::hub::Hub;
use crate::{Input, Proc, PtyCtl, Term};
use infantry_runner::job::{kill_pid, Job};
use infantry_runner::pty::{environment, Command};
use infantry_runner_api::{Priority, ProcEvent, ProcessSpec, Size};
use std::io::Read;
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::mpsc as smpsc;
use std::sync::{Arc, Mutex};
use std::time::{Duration, Instant};

/// Bytes per read from a child's pipe.
const READ_BUF: usize = 16 * 1024;

/// On Windows a bare program name is looked up on PATH x PATHEXT (npm installs `.cmd` shims, which `CreateProcess` and std
/// do not find by bare name). A name with a path part or an extension already given is left alone, as is any name not found.
/// A `.cmd` shim that only launches an `.exe` next to it is followed to that `.exe` ([`shim_target`]).
pub(crate) fn resolve_program(program: &str, env: &[(String, String)]) -> String {
    let found = find_program(program, env);
    if found.to_ascii_lowercase().ends_with(".cmd") {
        let p = std::path::Path::new(&found);
        if let (Some(dir), Ok(text)) = (p.parent(), std::fs::read_to_string(p)) {
            if let Some(exe) = shim_target(&text, dir) {
                return exe;
            }
        }
    }
    found
}

/// The `.exe` an npm `.cmd` shim runs (a line `"%dp0%\<rel>.exe" %*`), when it exists. Run through the shim, `cmd.exe`
/// would sit between the runner and the program and own its console (keys typed into a pty) and its exit code.
pub(crate) fn shim_target(shim: &str, dir: &std::path::Path) -> Option<String> {
    shim.lines().find_map(|l| {
        let rest = l.trim().strip_prefix("\"%dp0%\\")?;
        let rel = &rest[..rest.find('"')?];
        if !rel.to_ascii_lowercase().ends_with(".exe") {
            return None;
        }
        let p = dir.join(rel);
        p.is_file().then(|| p.to_string_lossy().into_owned())
    })
}

fn find_program(program: &str, env: &[(String, String)]) -> String {
    if !cfg!(windows) || program.contains(['/', '\\', ':']) {
        return program.to_string();
    }
    let var = |k: &str| {
        env.iter().rev().find(|(n, _)| n.eq_ignore_ascii_case(k)).map(|(_, v)| std::ffi::OsString::from(v)).or_else(|| std::env::var_os(k))
    };
    let Some(path) = var("PATH") else { return program.to_string() };
    let pathext = var("PATHEXT").map(|v| v.to_string_lossy().into_owned()).unwrap_or_else(|| ".COM;.EXE;.BAT;.CMD".into());
    let exts: Vec<&str> = pathext.split(';').filter(|e| !e.is_empty()).collect();
    for dir in std::env::split_paths(&path) {
        for e in exts.iter().copied().chain(std::iter::once("")) {
            let f = dir.join(format!("{program}{e}"));
            if f.is_file() {
                return f.to_string_lossy().into_owned();
            }
        }
    }
    program.to_string()
}

fn command(spec: &ProcessSpec) -> Command {
    Command {
        program: resolve_program(&spec.program, &spec.env),
        args: spec.args.clone(),
        cwd: spec.cwd.clone().map(Into::into).unwrap_or_else(|| std::env::current_dir().unwrap_or_else(|_| ".".into())),
        env: spec.env.clone(),
        env_remove: spec.env_remove.clone(),
    }
}

fn thread(name: &str, f: impl FnOnce() + Send + 'static) -> std::io::Result<()> {
    std::thread::Builder::new().name(name.into()).spawn(f).map(|_| ())
}

/// Reads [r] to EOF, handing each chunk to [on]; signals [done] at the end.
fn pump(mut r: impl Read + Send + 'static, on: impl Fn(&[u8]) + Send + 'static, done: smpsc::Sender<()>) -> std::io::Result<()> {
    thread("infantry-runner-read", move || {
        let mut buf = vec![0u8; READ_BUF];
        loop {
            match r.read(&mut buf) {
                Ok(0) | Err(_) => break,
                Ok(n) => on(&buf[..n]),
            }
        }
        let _ = done.send(());
    })
}

/// Waits for [readers] done signals until [deadline].
fn drain(done: &smpsc::Receiver<()>, readers: usize, grace: Duration) {
    let deadline = Instant::now() + grace;
    for _ in 0..readers {
        let left = deadline.saturating_duration_since(Instant::now());
        if done.recv_timeout(left).is_err() {
            return;
        }
    }
}

#[cfg(windows)]
fn priority_flag(p: Priority) -> u32 {
    use windows_sys::Win32::System::Threading::{BELOW_NORMAL_PRIORITY_CLASS, IDLE_PRIORITY_CLASS};
    match p {
        Priority::BelowNormal => BELOW_NORMAL_PRIORITY_CLASS,
        Priority::Idle => IDLE_PRIORITY_CLASS,
        _ => 0,
    }
}

#[cfg(unix)]
fn nice_of(p: Priority) -> i32 {
    match p {
        Priority::BelowNormal => 10,
        Priority::Idle => 19,
        _ => 0,
    }
}

pub fn start(spec: &ProcessSpec, hub: Arc<Hub>, grace: Duration) -> std::io::Result<Proc> {
    match spec.pty {
        Some(size) => start_pty(spec, size, hub, grace),
        None => start_piped(spec, hub, grace),
    }
}

fn start_piped(spec: &ProcessSpec, hub: Arc<Hub>, grace: Duration) -> std::io::Result<Proc> {
    use std::process::Stdio;
    let cmd = command(spec);
    let mut c = std::process::Command::new(&cmd.program);
    c.args(&cmd.args).current_dir(&cmd.cwd).env_clear().envs(environment(&cmd));
    c.stdin(Stdio::piped()).stdout(Stdio::piped()).stderr(Stdio::piped());
    #[cfg(windows)]
    {
        use std::os::windows::process::CommandExt;
        use windows_sys::Win32::System::Threading::CREATE_NO_WINDOW;
        c.creation_flags(CREATE_NO_WINDOW | priority_flag(spec.priority));
    }
    #[cfg(unix)]
    {
        use std::os::unix::process::CommandExt;
        let nice = nice_of(spec.priority);
        // SAFETY: only async-signal-safe libc calls between fork and exec.
        unsafe {
            c.pre_exec(move || {
                libc::setpgid(0, 0);
                if nice != 0 {
                    libc::setpriority(libc::PRIO_PROCESS, 0, nice);
                }
                Ok(())
            });
        }
    }
    let job = Arc::new(Job::new()?);
    let mut child = c.spawn().map_err(|e| std::io::Error::new(e.kind(), format!("spawn `{}`: {e}", cmd.program)))?;
    let pid = child.id();
    // Std gives no suspended start: the child joins its job a moment after it starts (before a shell has started anything).
    if let Err(e) = job.assign(pid) {
        let _ = child.kill();
        return Err(e);
    }
    let stdin = child.stdin.take().map(|s| Box::new(s) as Box<dyn std::io::Write + Send>);
    let (done_tx, done_rx) = smpsc::channel();
    if let Some(out) = child.stdout.take() {
        let h = hub.clone();
        pump(out, move |b| h.publish(ProcEvent::Output(b.to_vec())), done_tx.clone())?;
    }
    if let Some(err) = child.stderr.take() {
        let h = hub.clone();
        pump(err, move |b| h.publish(ProcEvent::Stderr(b.to_vec())), done_tx.clone())?;
    }
    drop(done_tx);
    let exited = Arc::new(AtomicBool::new(false));
    {
        let (hub, exited) = (hub.clone(), exited.clone());
        thread("infantry-runner-wait", move || {
            let code = match child.wait() {
                Ok(s) => exit_code(s),
                Err(_) => -1,
            };
            exited.store(true, Ordering::SeqCst);
            drain(&done_rx, 2, grace);
            hub.finish(code);
        })?;
    }
    Ok(Proc {
        hub,
        pid,
        input: Arc::new(Mutex::new(stdin)),
        term: None,
        pty: None,
        job,
        kill_one: Box::new(move || {
            if !exited.load(Ordering::SeqCst) {
                kill_pid(pid);
            }
        }),
    })
}

fn exit_code(s: std::process::ExitStatus) -> i32 {
    #[cfg(unix)]
    {
        use std::os::unix::process::ExitStatusExt;
        if let Some(sig) = s.signal() {
            return 128 + sig;
        }
    }
    s.code().unwrap_or(-1)
}

/// The reader side of a pty: feed the screen, answer terminal queries, publish.
fn pty_reader(term: Arc<Mutex<Term>>, input: Input, hub: Arc<Hub>) -> impl Fn(&[u8]) + Send + 'static {
    move |b: &[u8]| {
        let answer = {
            let mut t = term.lock().unwrap();
            t.feed(b);
            t.answers(b)
        };
        if !answer.is_empty() {
            if let Some(w) = input.lock().unwrap().as_mut() {
                let _ = w.write_all(&answer);
                let _ = w.flush();
            }
        }
        hub.publish(ProcEvent::Output(b.to_vec()));
    }
}

/// After the child exited, waits until the terminal output settles (conhost draws its last frame), at most [grace].
fn settle(term: &Mutex<Term>, grace: Duration) {
    let deadline = Instant::now() + grace;
    let mut last = term.lock().unwrap().bytes();
    let mut quiet = 0;
    while quiet < 2 && Instant::now() < deadline {
        std::thread::sleep(Duration::from_millis(50));
        let now = term.lock().unwrap().bytes();
        if now == last {
            quiet += 1;
        } else {
            quiet = 0;
            last = now;
        }
    }
}

use std::io::Write;

#[cfg(windows)]
struct WinPty(Arc<crate::conpty::Pty>);

#[cfg(windows)]
impl PtyCtl for WinPty {
    fn resize(&self, size: Size) -> std::io::Result<()> {
        self.0.resize(size.rows, size.cols)
    }
}

#[cfg(windows)]
fn start_pty(spec: &ProcessSpec, size: Size, hub: Arc<Hub>, grace: Duration) -> std::io::Result<Proc> {
    let cmd = command(spec);
    let job = Arc::new(Job::new()?);
    let j = job.clone();
    let s = crate::conpty::spawn(&cmd, size.rows, size.cols, priority_flag(spec.priority), move |pid| j.assign(pid))?;
    let pid = s.pid;
    let pty = Arc::new(s.pty);
    let process = Arc::new(s.process);
    let term = Arc::new(Mutex::new(Term::new(size)));
    let input: Input = Arc::new(Mutex::new(Some(Box::new(s.writer))));
    let (done_tx, done_rx) = smpsc::channel();
    pump(s.reader, pty_reader(term.clone(), input.clone(), hub.clone()), done_tx)?;
    {
        let (hub, pty, process, term, input) = (hub.clone(), pty.clone(), process.clone(), term.clone(), input.clone());
        thread("infantry-runner-wait", move || {
            let code = process.wait();
            settle(&term, grace);
            // Closing the pseudo-console ends the reader (EOF); the reader keeps draining meanwhile, so the close never blocks.
            pty.close();
            drain(&done_rx, 1, grace);
            input.lock().unwrap().take();
            hub.finish(code);
        })?;
    }
    Ok(Proc {
        hub,
        pid,
        input,
        term: Some(term),
        pty: Some(Box::new(WinPty(pty))),
        job,
        kill_one: Box::new(move || {
            if !process.exited() {
                process.kill();
            }
        }),
    })
}

#[cfg(unix)]
struct UnixPty(Mutex<Box<dyn portable_pty::MasterPty + Send>>);

#[cfg(unix)]
impl PtyCtl for UnixPty {
    fn resize(&self, size: Size) -> std::io::Result<()> {
        self.0
            .lock()
            .unwrap()
            .resize(portable_pty::PtySize { rows: size.rows, cols: size.cols, pixel_width: 0, pixel_height: 0 })
            .map_err(|e| std::io::Error::other(e.to_string()))
    }
}

#[cfg(unix)]
fn start_pty(spec: &ProcessSpec, size: Size, hub: Arc<Hub>, grace: Duration) -> std::io::Result<Proc> {
    use portable_pty::{native_pty_system, CommandBuilder, PtySize};
    let e = |x: &dyn std::fmt::Display| std::io::Error::other(x.to_string());
    let cmd = command(spec);
    let pair = native_pty_system()
        .openpty(PtySize { rows: size.rows, cols: size.cols, pixel_width: 0, pixel_height: 0 })
        .map_err(|x| e(&x))?;
    let nice = nice_of(spec.priority);
    let mut b = if nice != 0 {
        let mut b = CommandBuilder::new("nice");
        b.args(["-n", &nice.to_string(), &cmd.program]);
        b
    } else {
        CommandBuilder::new(&cmd.program)
    };
    b.args(&cmd.args);
    b.cwd(&cmd.cwd);
    b.env_clear();
    for (k, v) in environment(&cmd) {
        b.env(k, v);
    }
    let mut child = pair.slave.spawn_command(b).map_err(|x| e(&x))?;
    drop(pair.slave);
    let pid = child.process_id().unwrap_or(0);
    let job = Arc::new(Job::new()?);
    job.assign(pid)?; // the pty child is a session leader: its process group is the job
    let reader = pair.master.try_clone_reader().map_err(|x| e(&x))?;
    let writer = pair.master.take_writer().map_err(|x| e(&x))?;
    let killer = Mutex::new(child.clone_killer());
    let term = Arc::new(Mutex::new(Term::new(size)));
    let input: Input = Arc::new(Mutex::new(Some(writer)));
    let (done_tx, done_rx) = smpsc::channel();
    pump(reader, pty_reader(term.clone(), input.clone(), hub.clone()), done_tx)?;
    let exited = Arc::new(AtomicBool::new(false));
    {
        let (hub, term, input, exited) = (hub.clone(), term.clone(), input.clone(), exited.clone());
        thread("infantry-runner-wait", move || {
            let code = child.wait().map(|s| s.exit_code() as i32).unwrap_or(-1);
            exited.store(true, Ordering::SeqCst);
            settle(&term, grace);
            drain(&done_rx, 1, grace);
            input.lock().unwrap().take();
            hub.finish(code);
        })?;
    }
    Ok(Proc {
        hub,
        pid,
        input,
        term: Some(term),
        pty: Some(Box::new(UnixPty(Mutex::new(pair.master)))),
        job,
        kill_one: Box::new(move || {
            if !exited.load(Ordering::SeqCst) {
                let _ = killer.lock().unwrap().kill();
            }
        }),
    })
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn an_npm_cmd_shim_is_followed_to_the_exe_it_runs() {
        let dir = std::env::temp_dir().join(format!("infantry-shim-{}", uuid::Uuid::new_v4().simple()));
        let bin = dir.join("node_modules").join("tool").join("bin");
        std::fs::create_dir_all(&bin).unwrap();
        std::fs::write(bin.join("tool.exe"), b"MZ").unwrap();
        let shim = "@ECHO off\r\nSETLOCAL\r\nCALL :find_dp0\r\n\"%dp0%\\node_modules\\tool\\bin\\tool.exe\"   %*\r\n";
        assert_eq!(shim_target(shim, &dir).as_deref(), Some(bin.join("tool.exe").to_string_lossy().as_ref()));
        // A shim that runs node (a script, not an exe) or an exe that is not there stays a shim.
        assert_eq!(shim_target("\"%dp0%\\node.exe\" \"%dp0%\\x.js\" %*", &dir), None);
        assert_eq!(shim_target("\"%_prog%\"  \"%dp0%\\cli.js\" %*", &dir), None);
        if cfg!(windows) {
            std::fs::write(dir.join("tool.cmd"), shim).unwrap();
            let env = vec![("PATH".to_string(), dir.to_string_lossy().into_owned())];
            assert_eq!(resolve_program("tool", &env), bin.join("tool.exe").to_string_lossy());
        }
        let _ = std::fs::remove_dir_all(&dir);
    }
}
