// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
//! One running harness session: the PTY child in its own [`Job`], the [`Screen`] its output feeds, and the harness [`Driver`]
//! stepped every [`TICK_MS`]. Two threads per session: the PTY reader (blocking reads) and the ticker.

use crate::harness::{Driver, Event, Harness, Host, Io, LaunchRequest};
use crate::job::Job;
use crate::pty::{self, PtyChild};
use crate::screen::{self, Screen, TerminalQuery};
use std::io::{Read, Write};
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::mpsc::{channel, Receiver, Sender};
use std::sync::{Arc, Mutex};
use std::time::{Duration, Instant};

/// How often the driver is stepped.
pub const TICK_MS: u64 = 150;
/// How long a clean close may take before the session's process tree is killed.
pub const CLOSE_GRACE_MS: u64 = 10_000;
/// Bytes per PTY read.
pub const READ_BUF: usize = 16 * 1024;

struct Inner {
    driver: Box<dyn Driver>,
    screen: Screen,
    writer: Box<dyn Write + Send>,
    process: pty::Process,
    console: Option<pty::Console>,
    exited: Option<i32>,
    closing_at: Option<u64>,
    first_output: bool,
}

pub struct Session {
    inner: Arc<Mutex<Inner>>,
    tx: Sender<Event>,
    /// Everything the session reports, in order.
    pub events: Receiver<Event>,
    started: Instant,
    pid: u32,
    session_id: String,
    job: Arc<Job>,
    running: Arc<AtomicBool>,
}

impl Session {
    pub fn spawn(harness: &dyn Harness, req: &LaunchRequest, host: &Host) -> std::io::Result<Session> {
        let spec = harness.launch(req, host)?;
        let cmd = pty::Command {
            program: spec.program,
            args: spec.args,
            cwd: spec.cwd,
            env: req.env.iter().cloned().chain(spec.env).collect(),
            env_remove: req.env_remove.clone(),
        };
        let started = Instant::now();
        let PtyChild { process, console, writer, reader, pid } = pty::spawn(&cmd, pty::DEFAULT_ROWS, pty::DEFAULT_COLS)?;
        let (tx, events) = channel();
        let job = Arc::new(Job::new()?);
        if let Err(e) = job.assign(pid) {
            let _ = tx.send(Event::Detail(format!("could not put pid {pid} in the session's job: {e}")));
        }
        let inner = Arc::new(Mutex::new(Inner {
            driver: spec.driver,
            screen: Screen::new(pty::DEFAULT_ROWS, pty::DEFAULT_COLS),
            writer,
            process,
            console,
            exited: None,
            closing_at: None,
            first_output: false,
        }));
        let running = Arc::new(AtomicBool::new(true));
        let s = Session { inner, tx, events, started, pid, session_id: spec.session_id, job, running };
        s.start_reader(reader.expect("a fresh pty has its reader"));
        s.start_ticker();
        Ok(s)
    }

    pub fn pid(&self) -> u32 {
        self.pid
    }

    /// The provider's session id (what a later launch resumes).
    pub fn session_id(&self) -> &str {
        &self.session_id
    }

    pub fn exited(&self) -> Option<i32> {
        self.inner.lock().unwrap().exited
    }

    /// The visible screen (debugging).
    pub fn screen_text(&self) -> String {
        self.inner.lock().unwrap().screen.contents()
    }

    /// Bytes the terminal printed so far.
    pub fn screen_bytes(&self) -> u64 {
        self.inner.lock().unwrap().screen.bytes()
    }

    pub fn busy(&self) -> bool {
        self.inner.lock().unwrap().driver.busy()
    }

    pub fn send_turn(&self, prompt: &str) {
        let p = prompt.to_string();
        self.step(move |d, io| d.send_turn(p, io));
    }

    pub fn interrupt(&self) -> bool {
        let mut r = false;
        self.step(|d, io| r = d.abort_turn(io));
        r
    }

    /// The CLI's own exit; the process tree is killed if it has not exited [CLOSE_GRACE_MS] later.
    pub fn close(&self) {
        let now = self.now();
        {
            let mut g = self.inner.lock().unwrap();
            if g.exited.is_some() {
                return;
            }
            g.closing_at.get_or_insert(now);
        }
        self.step(|d, io| d.close(io));
    }

    /// Kills the session's whole process tree now.
    pub fn kill(&self) {
        self.job.kill();
    }

    fn now(&self) -> u64 {
        self.started.elapsed().as_millis() as u64
    }

    /// Runs [f] on the driver under the lock with a fresh [Io], then types what it wrote.
    fn step(&self, f: impl FnOnce(&mut dyn Driver, &mut Io<'_>)) {
        let now = self.now();
        let mut g = self.inner.lock().unwrap();
        let g = &mut *g;
        let mut input = Vec::new();
        {
            let mut io = Io::new(now, &g.screen, &mut input, &self.tx);
            f(g.driver.as_mut(), &mut io);
        }
        if !input.is_empty() && g.exited.is_none() {
            let _ = g.writer.write_all(&input);
            let _ = g.writer.flush();
        }
    }

    fn start_reader(&self, mut reader: Box<dyn Read + Send>) {
        let (inner, tx, started) = (self.inner.clone(), self.tx.clone(), self.started);
        std::thread::Builder::new()
            .name("infantry-pty-read".into())
            .spawn(move || {
                let mut buf = vec![0u8; READ_BUF];
                loop {
                    let n = match reader.read(&mut buf) {
                        Ok(0) | Err(_) => break,
                        Ok(n) => n,
                    };
                    let now = started.elapsed().as_millis() as u64;
                    let mut g = inner.lock().unwrap();
                    g.screen.feed(&buf[..n], now);
                    if !g.first_output {
                        g.first_output = true;
                        let _ = tx.send(Event::FirstOutput { ms: now });
                    }
                    for q in screen::queries(&buf[..n]) {
                        match q {
                            TerminalQuery::CursorPosition => {
                                let (r, c) = g.screen.cursor();
                                let _ = write!(g.writer, "\x1b[{};{}R", r + 1, c + 1);
                                let _ = g.writer.flush();
                            }
                        }
                    }
                }
            })
            .expect("spawn the pty reader");
    }

    fn start_ticker(&self) {
        let (inner, tx, started, running, job) =
            (self.inner.clone(), self.tx.clone(), self.started, self.running.clone(), self.job.clone());
        std::thread::Builder::new()
            .name("infantry-session-tick".into())
            .spawn(move || {
                while running.load(Ordering::SeqCst) {
                    std::thread::sleep(Duration::from_millis(TICK_MS));
                    let now = started.elapsed().as_millis() as u64;
                    let mut close_master = None;
                    {
                        let mut g = inner.lock().unwrap();
                        let g = &mut *g;
                        let mut input = Vec::new();
                        if let Some(code) = g.process.try_wait() {
                            g.exited = Some(code);
                            let mut io = Io::new(now, &g.screen, &mut input, &tx);
                            g.driver.on_exit(code, &mut io);
                            let _ = tx.send(Event::Exited { code });
                            close_master = g.console.take();
                            running.store(false, Ordering::SeqCst);
                        } else {
                            {
                                let mut io = Io::new(now, &g.screen, &mut input, &tx);
                                g.driver.tick(&mut io);
                            }
                            if !input.is_empty() {
                                let _ = g.writer.write_all(&input);
                                let _ = g.writer.flush();
                            }
                            if g.closing_at.is_some_and(|at| now.saturating_sub(at) >= CLOSE_GRACE_MS) {
                                g.closing_at = None;
                                let _ = tx.send(Event::Detail("the session did not exit after close; killed".into()));
                                job.kill();
                            }
                        }
                    }
                    // Outside the lock: closing the pseudo-console waits for the reader to drain, and the reader takes the lock.
                    drop(close_master);
                }
            })
            .expect("spawn the session ticker");
    }
}

impl Drop for Session {
    fn drop(&mut self) {
        self.running.store(false, Ordering::SeqCst);
        if self.exited().is_none() {
            self.job.kill();
        }
        // The ticker has stopped: close the pseudo-console here so the reader sees EOF and ends.
        let console = self.inner.lock().unwrap().console.take();
        drop(console);
    }
}
