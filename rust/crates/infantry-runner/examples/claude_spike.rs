// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
//! RUST-SESS-1 live proof: real interactive `claude` sessions in ConPTY, driven by the runner. Throwaway sessions only: each run
//! makes its own temp folder (`%TEMP%\infantry-rust-spike-<uuid>`, state + an untrusted work folder) and resumes only a session
//! it created itself. The child environment drops this agent's identity (`INFANTRY_*`, `AGENTHQ_*`) and the nested-session
//! guard (`CLAUDECODE`, `CLAUDE_CODE_*`), and points `INFANTRY_STATE_DIR` at the temp state.
//!
//! ```text
//! cargo run --release --example claude_spike -- smoke          # trust, turn, AskUserQuestion hook, permission hook, interrupt, close, resume
//! cargo run --release --example claude_spike -- load 5         # N concurrent sessions: runner RSS, CPU while streaming, time to first output
//! ```

use infantry_runner::claude::Claude;
use infantry_runner::harness::*;
use infantry_runner::hooks::HookServer;
use infantry_runner::job::{alive, self_usage};
use infantry_runner::session::Session;
use infantry_runner::stream::StreamEvent;
use std::path::PathBuf;
use std::sync::atomic::{AtomicUsize, Ordering};
use std::sync::Arc;
use std::time::{Duration, Instant};

const MODEL: &str = "haiku";
const TURN_TIMEOUT: Duration = Duration::from_secs(180);
const EXIT_TIMEOUT: Duration = Duration::from_secs(30);

/// Answers every question with its recommended (else first) option and approves every tool; counts both.
#[derive(Default)]
struct AutoOwner {
    asked: AtomicUsize,
    approved: AtomicUsize,
}

impl Owner for AutoOwner {
    fn ask(&self, session: &str, q: &Question) -> Answer {
        self.asked.fetch_add(1, Ordering::SeqCst);
        let pick = q.recommended.clone().or_else(|| q.options.first().cloned()).unwrap_or_else(|| "yes".into());
        println!("  [owner] {}: asked {:?} -> {pick}", &session[..8], q.text.lines().next().unwrap_or(""));
        Answer { picked: vec![pick], other: None }
    }
    fn approve(&self, session: &str, tool: &str, input: &str) -> bool {
        self.approved.fetch_add(1, Ordering::SeqCst);
        println!("  [owner] {}: approve {tool} {}", &session[..8], input.chars().take(80).collect::<String>());
        true
    }
}

struct Rig {
    root: PathBuf,
    work: PathBuf,
    host: Host,
    owner: Arc<AutoOwner>,
}

impl Rig {
    fn new() -> Rig {
        let root = std::env::temp_dir().join(format!("infantry-rust-spike-{}", uuid::Uuid::new_v4()));
        let work = root.join("work");
        std::fs::create_dir_all(&work).unwrap();
        let owner = Arc::new(AutoOwner::default());
        let host = Host { state_dir: root.join("state"), hooks: HookServer::start().unwrap(), owner: owner.clone() };
        println!("temp root: {}", root.display());
        Rig { root, work, host, owner }
    }

    fn request(&self, approval: ToolApproval, resume: Option<String>) -> LaunchRequest {
        let mut r = LaunchRequest::new(self.work.clone());
        r.model = Some(MODEL.into());
        r.approval = approval;
        r.resume = resume;
        r.env_remove = ["CLAUDECODE", "CLAUDE_CODE_*", "INFANTRY_*", "AGENTHQ_*"].map(String::from).to_vec();
        r.env = vec![("INFANTRY_STATE_DIR".into(), self.root.join("state").to_string_lossy().into_owned())];
        r
    }
}

/// What one turn produced.
#[derive(Default, Debug)]
struct TurnOut {
    end: Option<TurnEnd>,
    reason: Option<String>,
    text: String,
    tools: Vec<String>,
    details: Vec<String>,
    first_output_ms: Option<u64>,
    first_record_at: Option<Instant>,
}

/// Drains [s]'s events until a turn ends (or [stop] says so, or the timeout).
fn pump(s: &Session, out: &mut TurnOut, timeout: Duration, mut stop: impl FnMut(&TurnOut) -> bool) {
    let deadline = Instant::now() + timeout;
    while Instant::now() < deadline {
        match s.events.recv_timeout(Duration::from_millis(100)) {
            Ok(e) => {
                absorb(e, out);
                if out.end.is_some() || stop(out) {
                    return;
                }
            }
            Err(std::sync::mpsc::RecvTimeoutError::Timeout) => {
                if stop(out) {
                    return;
                }
            }
            Err(_) => return,
        }
    }
    out.reason.get_or_insert_with(|| format!("timed out after {} s; screen:\n{}", timeout.as_secs(), s.screen_text()));
}

fn absorb(e: Event, out: &mut TurnOut) {
    match e {
        Event::FirstOutput { ms } => out.first_output_ms = Some(ms),
        Event::Output { records, .. } => {
            for r in records {
                match r {
                    StreamEvent::Init { .. } => {}
                    StreamEvent::AssistantText { text, .. } => {
                        out.first_record_at.get_or_insert_with(Instant::now);
                        out.text.push_str(&text);
                    }
                    StreamEvent::ToolCall { name, .. } => {
                        out.first_record_at.get_or_insert_with(Instant::now);
                        out.tools.push(name);
                    }
                    StreamEvent::Thinking { .. } | StreamEvent::ToolResult { .. } => {
                        out.first_record_at.get_or_insert_with(Instant::now);
                    }
                    _ => {}
                }
            }
        }
        Event::TurnEnded { end, reason } => {
            out.end = Some(end);
            out.reason = reason;
        }
        Event::Detail(d) => {
            println!("  [detail] {d}");
            out.details.push(d);
        }
        Event::TurnStarted { reason } => println!("  [turn started by the cli] {reason:?}"),
        Event::Exited { code } => println!("  [exited] {code}"),
        Event::ProviderSession(_) => {}
    }
}

fn turn(s: &Session, prompt: &str) -> TurnOut {
    let mut out = TurnOut::default();
    s.send_turn(prompt);
    pump(s, &mut out, TURN_TIMEOUT, |_| false);
    out
}

fn wait_exit(s: &Session) -> Option<i32> {
    let deadline = Instant::now() + EXIT_TIMEOUT;
    while Instant::now() < deadline {
        if let Some(c) = s.exited() {
            return Some(c);
        }
        while let Ok(e) = s.events.try_recv() {
            absorb(e, &mut TurnOut::default());
        }
        std::thread::sleep(Duration::from_millis(100));
    }
    None
}

fn check(name: &str, ok: bool, detail: impl std::fmt::Display) -> bool {
    println!("{} {name}: {detail}", if ok { "PASS" } else { "FAIL" });
    ok
}

fn mb(b: u64) -> String {
    format!("{:.1} MB", b as f64 / 1_048_576.0)
}

fn smoke() -> bool {
    let rig = Rig::new();
    let claude = Claude::default();
    let mut all = true;
    let spawned = Instant::now();
    let s = Session::spawn(&claude, &rig.request(ToolApproval::Ask, None), &rig.host).expect("spawn claude");
    let id = s.session_id().to_string();
    let pid = s.pid();
    println!("session {id} pid {pid}");

    // 1. spawn + trust + first turn
    let t = turn(&s, "Reply with exactly: pong");
    let to_record = t.first_record_at.map(|at| at.duration_since(spawned).as_millis());
    all &= check(
        "first turn (spawn, trust, paste, Stop hook, transcript)",
        t.end == Some(TurnEnd::Completed) && t.text.to_lowercase().contains("pong"),
        format!("{:?} {:?} text={:?}", t.end, t.reason, t.text),
    );
    all &= check("folder trust answered", t.details.iter().any(|d| d.contains("folder trust")), "trust detail seen");
    println!("METRIC time to first PTY output: {:?} ms; to first transcript record: {:?} ms", t.first_output_ms, to_record);

    // 2. AskUserQuestion through the PreToolUse HTTP hook
    let t = turn(
        &s,
        "Use the AskUserQuestion tool to ask me which colour I prefer, with exactly two options: Red and Blue. After I answer, reply with only the colour I picked.",
    );
    let asked = rig.owner.asked.load(Ordering::SeqCst);
    all &= check(
        "AskUserQuestion answered through the hook",
        t.end == Some(TurnEnd::Completed) && asked >= 1 && t.text.to_lowercase().contains("red"),
        format!("{:?} asked={asked} tools={:?} text={:?} reason={:?}", t.end, t.tools, t.text, t.reason),
    );

    // 3. a tool that needs approval through the PermissionRequest hook (Ask mode)
    let t = turn(&s, "Use the Bash tool to run exactly this command: echo spike-ok > spike.txt . Then reply with only the word: written");
    let approved = rig.owner.approved.load(Ordering::SeqCst);
    let file = rig.work.join("spike.txt").is_file();
    all &= check(
        "permission request approved through the hook",
        t.end == Some(TurnEnd::Completed) && approved >= 1 && file,
        format!("{:?} approved={approved} file={file} tools={:?} reason={:?}", t.end, t.tools, t.reason),
    );

    // 4. interrupt a streaming turn (CPU measured while it streams)
    let before = self_usage();
    let t0 = Instant::now();
    s.send_turn("Write a 1500-word essay about the history of sailing ships. Do not use any tools.");
    let mut t = TurnOut::default();
    pump(&s, &mut t, Duration::from_secs(8), |_| false);
    let mid = self_usage();
    let wall = t0.elapsed().as_millis() as u64;
    println!(
        "METRIC runner CPU while one session streams: {} ms CPU over {wall} ms wall ({:.2}% of one core); rss {}",
        mid.cpu_ms - before.cpu_ms,
        (mid.cpu_ms - before.cpu_ms) as f64 * 100.0 / wall as f64,
        mb(mid.rss)
    );
    let interrupted = s.interrupt();
    pump(&s, &mut t, Duration::from_secs(30), |_| false);
    all &= check("interrupt", interrupted && t.end == Some(TurnEnd::Interrupted), format!("sent={interrupted} {:?} {:?}", t.end, t.reason));

    // 5. the session still takes turns after the interrupt
    let t = turn(&s, "Reply with exactly: after");
    all &= check("turn after interrupt", t.end == Some(TurnEnd::Completed) && t.text.to_lowercase().contains("after"), format!("{:?} {:?}", t.text, t.reason));

    // 6. close: Esc, /exit, Enter
    s.close();
    let code = wait_exit(&s);
    all &= check("close (/exit)", code.is_some(), format!("exit code {code:?}"));
    drop(s);
    std::thread::sleep(Duration::from_millis(300));
    all &= check("child gone after close", !alive(pid), format!("pid {pid} alive={}", alive(pid)));

    // 7. resume the throwaway session this run created
    let s = Session::spawn(&claude, &rig.request(ToolApproval::Ask, Some(id.clone())), &rig.host).expect("resume claude");
    let t = turn(&s, "What exact word did you reply with in your very first answer of this conversation? Reply with only that word.");
    all &= check(
        "resume (--resume <own throwaway id>)",
        s.session_id() == id && t.end == Some(TurnEnd::Completed) && t.text.to_lowercase().contains("pong"),
        format!("{:?} text={:?} reason={:?}", t.end, t.text, t.reason),
    );

    // 8. process control: dropping a running session kills its tree through the Job Object
    let rpid = s.pid();
    drop(s);
    std::thread::sleep(Duration::from_millis(500));
    all &= check("job object kills the child on drop", !alive(rpid), format!("pid {rpid} alive={}", alive(rpid)));

    let _ = std::fs::remove_dir_all(&rig.root);
    println!("{}", if all { "SMOKE OK" } else { "SMOKE FAILED" });
    all
}

fn load(n: usize) -> bool {
    let rig = Rig::new();
    let claude = Claude::default();
    let base = self_usage();
    println!("runner baseline: rss {} private {}", mb(base.rss), mb(base.private));
    let t0 = Instant::now();
    let sessions: Vec<Session> =
        (0..n).map(|_| Session::spawn(&claude, &rig.request(ToolApproval::AllowAll, None), &rig.host).expect("spawn claude")).collect();
    let pids: Vec<u32> = sessions.iter().map(Session::pid).collect();
    let mut outs: Vec<TurnOut> = (0..n).map(|_| TurnOut::default()).collect();
    for s in &sessions {
        s.send_turn("Reply with exactly: pong");
    }
    drain_all(&sessions, &mut outs, TURN_TIMEOUT);
    let up = self_usage();
    let ok_up = outs.iter().filter(|o| o.end == Some(TurnEnd::Completed)).count();
    for (i, o) in outs.iter().enumerate().filter(|(_, o)| o.end != Some(TurnEnd::Completed)) {
        let screen = sessions[i].screen_text();
        let lines: Vec<&str> = screen.lines().filter(|l| !l.trim().is_empty()).collect();
        println!("  first turn of session {i} ({}): {:?} {:?}; screen:\n{}", sessions[i].session_id(), o.end, o.reason, lines.join("\n"));
    }
    let mut ttfo: Vec<u64> = outs.iter().filter_map(|o| o.first_output_ms).collect();
    ttfo.sort_unstable();
    let mut ttr: Vec<u128> = outs.iter().filter_map(|o| o.first_record_at.map(|a| a.duration_since(t0).as_millis())).collect();
    ttr.sort_unstable();
    println!("METRIC n={n} first turns completed: {ok_up}/{n} in {} ms", t0.elapsed().as_millis());
    println!("METRIC n={n} time to first PTY output ms (min/median/max): {:?}", spread(&ttfo));
    println!("METRIC n={n} time to first transcript record ms (min/median/max): {:?}", spread(&ttr));
    println!("METRIC n={n} runner rss {} (peak {}), private {}", mb(up.rss), mb(up.peak_rss), mb(up.private));

    // streaming phase: every session writes at once
    let mut outs: Vec<TurnOut> = (0..n).map(|_| TurnOut::default()).collect();
    let (c0, w0) = (self_usage(), Instant::now());
    for s in &sessions {
        s.send_turn("Write a 400-word paragraph about rivers. Do not use any tools.");
    }
    drain_all(&sessions, &mut outs, TURN_TIMEOUT);
    let (c1, wall) = (self_usage(), w0.elapsed().as_millis() as u64);
    let ok = outs.iter().filter(|o| o.end == Some(TurnEnd::Completed)).count();
    println!(
        "METRIC n={n} streaming: {ok}/{n} completed; runner CPU {} ms over {wall} ms wall ({:.2}% of one core); rss {} (peak {})",
        c1.cpu_ms - c0.cpu_ms,
        (c1.cpu_ms - c0.cpu_ms) as f64 * 100.0 / wall as f64,
        mb(c1.rss),
        mb(c1.peak_rss)
    );
    for (i, o) in outs.iter().enumerate().filter(|(_, o)| o.end != Some(TurnEnd::Completed)) {
        println!("  session {i}: {:?} {:?}", o.end, o.reason);
    }
    drop(sessions); // Job Objects kill every tree
    std::thread::sleep(Duration::from_millis(800));
    let left = pids.iter().filter(|p| alive(**p)).count();
    println!("METRIC n={n} children alive after drop: {left}");
    let _ = std::fs::remove_dir_all(&rig.root);
    ok_up == n && ok == n && left == 0
}

fn drain_all(sessions: &[Session], outs: &mut [TurnOut], timeout: Duration) {
    let deadline = Instant::now() + timeout;
    while Instant::now() < deadline && outs.iter().any(|o| o.end.is_none()) {
        let mut any = false;
        for (s, o) in sessions.iter().zip(outs.iter_mut()) {
            while let Ok(e) = s.events.try_recv() {
                any = true;
                absorb(e, o);
            }
        }
        if !any {
            std::thread::sleep(Duration::from_millis(50));
        }
    }
}

fn spread<T: Copy>(v: &[T]) -> Option<(T, T, T)> {
    if v.is_empty() {
        None
    } else {
        Some((v[0], v[v.len() / 2], v[v.len() - 1]))
    }
}

fn main() {
    let args: Vec<String> = std::env::args().skip(1).collect();
    let ok = match args.first().map(String::as_str) {
        Some("smoke") => smoke(),
        Some("load") => load(args.get(1).and_then(|n| n.parse().ok()).unwrap_or(1)),
        _ => {
            eprintln!("usage: claude_spike smoke | load <n>");
            false
        }
    };
    std::process::exit(if ok { 0 } else { 1 });
}
