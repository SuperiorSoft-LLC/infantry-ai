// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
//! The sidecar runtime over in-memory pipes: a long call must not hold up another, imports are routed to the call that
//! made them, a cap of 1 keeps the old one-at-a-time behaviour, and a closed host wakes a waiting import.
use super::*;
use serde_json::json;
use std::io::Read;
use std::sync::mpsc::{Receiver, RecvTimeoutError, Sender};
use std::time::{Duration, Instant};

/// The sidecar keeps one hub per process, and the test plugins keep statics: one test at a time.
static ONE_AT_A_TIME: Mutex<()> = Mutex::new(());
const WAIT: Duration = Duration::from_secs(10);

struct PipeReader(Receiver<Vec<u8>>, Vec<u8>);
impl Read for PipeReader {
    fn read(&mut self, buf: &mut [u8]) -> std::io::Result<usize> {
        if self.1.is_empty() {
            match self.0.recv() {
                Ok(b) => self.1 = b,
                Err(_) => return Ok(0),
            }
        }
        let n = buf.len().min(self.1.len());
        buf[..n].copy_from_slice(&self.1[..n]);
        self.1.drain(..n);
        Ok(n)
    }
}

struct PipeWriter(Sender<Vec<u8>>);
impl Write for PipeWriter {
    fn write(&mut self, buf: &[u8]) -> std::io::Result<usize> {
        let _ = self.0.send(buf.to_vec());
        Ok(buf.len())
    }
    fn flush(&mut self) -> std::io::Result<()> {
        Ok(())
    }
}

/// The host's end of the pipes.
struct Host {
    to_plugin: Option<Sender<Vec<u8>>>,
    from_plugin: Receiver<Vec<u8>>,
    buf: Vec<u8>,
    serving: Option<std::thread::JoinHandle<()>>,
}

impl Host {
    fn start<P: Plugin>() -> Host {
        let (to_plugin, plugin_in) = mpsc::channel();
        let (plugin_out, from_plugin) = mpsc::channel();
        let serving = std::thread::spawn(move || serve_on::<P>(std::io::BufReader::new(PipeReader(plugin_in, Vec::new())), PipeWriter(plugin_out)));
        let mut h = Host { to_plugin: Some(to_plugin), from_plugin, buf: Vec::new(), serving: Some(serving) };
        assert!(h.line(WAIT).expect("a hello").hello.is_some());
        h
    }
    fn send(&self, line: &Line) {
        let mut t = line.to_text();
        t.push('\n');
        self.to_plugin.as_ref().unwrap().send(t.into_bytes()).unwrap();
    }
    fn hook(&self, id: u64, kind: &str) {
        self.send(&Line::call(id, "on-hook", json!({ "kind": kind, "payload": "" })));
    }
    /// The next line the plugin wrote, or None when it wrote none in `wait`.
    fn line(&mut self, wait: Duration) -> Option<Line> {
        let end = Instant::now() + wait;
        loop {
            if let Some(i) = self.buf.iter().position(|b| *b == b'\n') {
                let raw: Vec<u8> = self.buf.drain(..=i).collect();
                return Some(serde_json::from_slice(&raw).expect("a plugin line"));
            }
            match self.from_plugin.recv_timeout(end.saturating_duration_since(Instant::now())) {
                Ok(b) => self.buf.extend(b),
                Err(RecvTimeoutError::Timeout) | Err(RecvTimeoutError::Disconnected) => return None,
            }
        }
    }
    /// The text answer of call `id`, the next reply.
    fn reply(&mut self, wait: Duration) -> (u64, Result<String, String>) {
        let l = self.line(wait).expect("a reply in time");
        let r = match (l.ok, l.err, l.trap) {
            (Some(v), _, _) => Ok(v.as_str().unwrap_or("").to_string()),
            (_, Some(e), _) => Err(e),
            (_, _, Some(t)) => Err(format!("trap: {t}")),
            _ => Ok(String::new()),
        };
        (l.id.unwrap(), r)
    }
    /// The host goes away; the sidecar must return.
    fn close(mut self) {
        self.to_plugin = None;
        let serving = self.serving.take().unwrap();
        let end = Instant::now() + WAIT;
        while !serving.is_finished() {
            assert!(Instant::now() < end, "the sidecar did not return after the host closed");
            std::thread::sleep(Duration::from_millis(5));
        }
        serving.join().unwrap();
    }
}

fn lock() -> std::sync::MutexGuard<'static, ()> {
    ONE_AT_A_TIME.lock().unwrap_or_else(|e| e.into_inner())
}

// ---- a plugin with a call that waits on a flag ----

static RELEASED: (Mutex<bool>, Condvar) = (Mutex::new(false), Condvar::new());
static IMPORT_ERR: Mutex<Option<String>> = Mutex::new(None);
static ACTIVE: Mutex<(usize, usize)> = Mutex::new((0, 0));

fn release() {
    *RELEASED.0.lock().unwrap() = true;
    RELEASED.1.notify_all();
}

struct Probe<const N: usize>;
impl<const N: usize> Plugin for Probe<N> {
    const MAX_CONCURRENT_CALLS: usize = N;
    fn on_hook(kind: String, _payload: String) -> Result<String, String> {
        match kind.as_str() {
            "slow" => {
                let mut r = RELEASED.0.lock().unwrap();
                while !*r {
                    r = RELEASED.1.wait(r).unwrap();
                }
                Ok("slow".into())
            }
            "fast" => Ok("fast".into()),
            "panic" => panic!("boom"),
            "work" => {
                {
                    let mut a = ACTIVE.lock().unwrap();
                    a.0 += 1;
                    a.1 = a.1.max(a.0);
                }
                std::thread::sleep(Duration::from_millis(120));
                ACTIVE.lock().unwrap().0 -= 1;
                Ok("done".into())
            }
            k if k.starts_with("imp:") => match import("test.echo", json!({ "n": &k[4..] })) {
                Ok(v) => Ok(v["n"].as_str().unwrap_or("?").to_string()),
                Err(e) => {
                    *IMPORT_ERR.lock().unwrap() = Some(e.clone());
                    Err(e)
                }
            },
            other => Err(format!("unknown hook {other}")),
        }
    }
}

fn reset() {
    *RELEASED.0.lock().unwrap() = false;
    *IMPORT_ERR.lock().unwrap() = None;
    *ACTIVE.lock().unwrap() = (0, 0);
}

#[test]
fn a_fast_call_is_answered_while_a_slow_one_is_still_running() {
    let _g = lock();
    reset();
    let mut h = Host::start::<Probe<32>>();
    h.hook(1, "slow");
    h.hook(2, "fast");
    let (id, r) = h.reply(WAIT);
    assert_eq!((id, r), (2, Ok("fast".into())), "the fast call answers first");
    assert!(h.line(Duration::from_millis(300)).is_none(), "the slow call is still running");
    release();
    assert_eq!(h.reply(WAIT), (1, Ok("slow".into())));
    h.close();
}

#[test]
fn an_import_reply_goes_to_the_call_that_asked_even_when_answered_out_of_order() {
    let _g = lock();
    reset();
    let mut h = Host::start::<Probe<32>>();
    h.hook(1, "imp:one");
    h.hook(2, "imp:two");
    let mut asks = Vec::new();
    while asks.len() < 2 {
        let l = h.line(WAIT).expect("an import line");
        assert_eq!(l.import.as_deref(), Some("test.echo"));
        asks.push((l.id.unwrap(), l.params.unwrap()["n"].as_str().unwrap().to_string()));
    }
    // Answer the later ask first.
    for (id, n) in asks.iter().rev() {
        h.send(&Line::ok(*id, json!({ "n": n })));
    }
    let mut got = vec![h.reply(WAIT), h.reply(WAIT)];
    got.sort();
    assert_eq!(got, vec![(1, Ok("one".into())), (2, Ok("two".into()))]);
    h.close();
}

#[test]
fn a_cap_of_one_keeps_calls_one_at_a_time() {
    let _g = lock();
    reset();
    let mut h = Host::start::<Probe<1>>();
    for id in 1..=4 {
        h.hook(id, "work");
    }
    for _ in 0..4 {
        assert_eq!(h.reply(WAIT).1, Ok("done".into()));
    }
    assert_eq!(ACTIVE.lock().unwrap().1, 1, "never two at once");
    h.close();
}

#[test]
fn the_cap_bounds_how_many_run_together_and_the_rest_wait() {
    let _g = lock();
    reset();
    let mut h = Host::start::<Probe<3>>();
    for id in 1..=9 {
        h.hook(id, "work");
    }
    for _ in 0..9 {
        assert_eq!(h.reply(WAIT).1, Ok("done".into()));
    }
    let peak = ACTIVE.lock().unwrap().1;
    assert!((2..=3).contains(&peak), "calls ran in parallel up to the cap, peak {peak}");
    h.close();
}

#[test]
fn a_panic_is_a_trap_for_that_call_only() {
    let _g = lock();
    reset();
    let mut h = Host::start::<Probe<32>>();
    h.hook(1, "panic");
    let (id, r) = h.reply(WAIT);
    assert_eq!(id, 1);
    assert!(r.unwrap_err().contains("panicked: boom"));
    h.hook(2, "fast");
    assert_eq!(h.reply(WAIT), (2, Ok("fast".into())));
    h.close();
}

#[test]
fn a_host_that_closes_wakes_a_waiting_import_and_the_sidecar_returns() {
    let _g = lock();
    reset();
    let mut h = Host::start::<Probe<32>>();
    h.hook(1, "imp:x");
    assert_eq!(h.line(WAIT).expect("an import line").import.as_deref(), Some("test.echo"));
    h.close();
    let end = Instant::now() + WAIT;
    while IMPORT_ERR.lock().unwrap().is_none() {
        assert!(Instant::now() < end, "the import was never woken");
        std::thread::sleep(Duration::from_millis(5));
    }
    assert_eq!(IMPORT_ERR.lock().unwrap().as_deref(), Some("the host closed the plugin channel"));
}

#[test]
fn with_a_cap_of_one_a_fast_call_waits_behind_a_slow_one_the_old_behaviour() {
    let _g = lock();
    reset();
    let mut h = Host::start::<Probe<1>>();
    h.hook(1, "slow");
    h.hook(2, "fast");
    assert!(h.line(Duration::from_millis(400)).is_none(), "the fast call is queued behind the slow one");
    release();
    assert_eq!(h.reply(WAIT), (1, Ok("slow".into())));
    assert_eq!(h.reply(WAIT), (2, Ok("fast".into())));
    h.close();
}
