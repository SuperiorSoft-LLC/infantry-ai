// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
//! The conformance suite every [`Runner`] passes (local, remote link): `suite(&runner, scratch_dir).await` panics on the
//! first failed check. [scratch_dir] is an existing, writable directory on the RUNNER's host, without spaces (the checks
//! put its paths in shell command lines). Commands are harmless and picked by the runner's OS ([`Pong::os`]): the host
//! shell's `echo`/`exit`, `findstr`/`cat`, a loopback `ping`/`sleep`, and `curl` when the host has it.

use crate::hooks::event_of;
use crate::*;
use std::time::Duration;
use tokio::time::{sleep, timeout, Instant};

const WAIT: Duration = Duration::from_secs(15);

/// What a process printed, and how it ended.
#[derive(Debug, Default)]
pub struct Collected {
    pub stdout: String,
    pub stderr: String,
    pub code: Option<i32>,
}

/// Reads [rx] until the channel closes (after [`ProcEvent::Exited`]) or [within] passes.
pub async fn collect(rx: &mut mpsc::UnboundedReceiver<ProcEvent>, within: Duration) -> Collected {
    let mut c = Collected::default();
    let _ = timeout(within, async {
        while let Some(e) = rx.recv().await {
            match e {
                ProcEvent::Output(b) => c.stdout.push_str(&String::from_utf8_lossy(&b)),
                ProcEvent::Stderr(b) => c.stderr.push_str(&String::from_utf8_lossy(&b)),
                ProcEvent::Exited(code) => c.code = Some(code),
            }
        }
    })
    .await;
    c
}

async fn run(runner: &dyn Runner, spec: ProcessSpec) -> Collected {
    let what = format!("{} {:?}", spec.program, spec.args);
    let h = runner.spawn(spec).await.unwrap_or_else(|e| panic!("spawn {what}: {e}"));
    let mut rx = runner.events(&h.id).await.expect("events");
    let c = collect(&mut rx, WAIT).await;
    let _ = runner.release(&h.id).await;
    c
}

/// Polls the screen of [id] until a row satisfies [want].
async fn screen_until(runner: &dyn Runner, id: &ProcId, what: &str, want: impl Fn(&str) -> bool) -> ScreenSnapshot {
    let deadline = Instant::now() + WAIT;
    loop {
        let s = runner.screen(id).await.expect("screen");
        if s.rows.iter().any(|r| want(r)) {
            return s;
        }
        assert!(Instant::now() < deadline, "screen never showed {what}; rows: {:#?}", s.rows);
        sleep(Duration::from_millis(100)).await;
    }
}

/// Receives tail lines until one satisfies [want].
async fn line_until(rx: &mut mpsc::UnboundedReceiver<String>, what: &str, want: impl Fn(&str) -> bool) {
    let r = timeout(WAIT, async {
        while let Some(l) = rx.recv().await {
            if want(&l) {
                return true;
            }
        }
        false
    })
    .await;
    assert!(matches!(r, Ok(true)), "tail never delivered {what}");
}

struct Os {
    windows: bool,
}

impl Os {
    fn sh(&self, line: &str) -> ProcessSpec {
        if self.windows {
            ProcessSpec::new("cmd", ["/c", line])
        } else {
            ProcessSpec::new("sh", ["-c", line])
        }
    }

    fn join(&self, dir: &str, name: &str) -> String {
        let sep = if self.windows { '\\' } else { '/' };
        format!("{}{}{}", dir.trim_end_matches(['/', '\\']), sep, name)
    }
}

/// Runs every check against [runner].
pub async fn suite(runner: &dyn Runner, scratch_dir: &str) {
    assert!(!scratch_dir.contains(' '), "suite: scratch_dir must have no spaces");
    let tag = format!("{:x}", std::time::SystemTime::now().duration_since(std::time::UNIX_EPOCH).unwrap().as_nanos());

    // ping
    let pong = runner.ping().await.expect("ping");
    assert!(!pong.version.is_empty() && !pong.os.is_empty(), "ping: {pong:?}");
    let os = Os { windows: pong.os == "windows" };

    // spawn piped: stdout and a clean exit
    let c = run(runner, os.sh("echo suite-piped-out")).await;
    assert!(c.stdout.contains("suite-piped-out"), "piped stdout: {c:?}");
    assert_eq!(c.code, Some(0), "piped exit: {c:?}");

    // stderr is its own stream
    let c = run(runner, os.sh("echo suite-piped-err 1>&2")).await;
    assert!(c.stderr.contains("suite-piped-err") && !c.stdout.contains("suite-piped-err"), "piped stderr: {c:?}");

    // exit code
    let c = run(runner, os.sh("exit 7")).await;
    assert_eq!(c.code, Some(7), "exit code: {c:?}");

    // write + close_input (a reader to EOF)
    let cat = if os.windows { ProcessSpec::new("findstr", ["/r", "^"]) } else { ProcessSpec::new("cat", Vec::<String>::new()) };
    let h = runner.spawn(cat).await.expect("spawn reader");
    let mut rx = runner.events(&h.id).await.expect("events");
    runner.write(&h.id, b"suite-stdin-line\n").await.expect("write");
    runner.close_input(&h.id).await.expect("close_input");
    let c = collect(&mut rx, WAIT).await;
    assert!(c.stdout.contains("suite-stdin-line") && c.code == Some(0), "write/close_input: {c:?}");
    assert!(runner.write(&h.id, b"x").await.is_err(), "write after exit must fail");
    runner.release(&h.id).await.expect("release");
    assert!(matches!(runner.events(&h.id).await, Err(RunnerError::NotFound(_))), "released process still known");
    assert!(matches!(runner.screen(&ProcId("no-such".into())).await, Err(RunnerError::NotFound(_))));

    // spawn PTY, write, screen, screen_text_since, resize, exit code
    let shell = if os.windows { ProcessSpec::new("cmd", Vec::<String>::new()) } else { ProcessSpec::new("sh", Vec::<String>::new()) };
    let h = runner.spawn(shell.pty(Size { rows: 24, cols: 80 })).await.expect("spawn pty");
    let mut rx = runner.events(&h.id).await.expect("events pty");
    let s0 = runner.screen(&h.id).await.expect("screen");
    assert_eq!(s0.size, Size { rows: 24, cols: 80 });
    let (typed, shown) = if os.windows { ("echo pty-%OS%-mark\r", "pty-Windows_NT-mark") } else { ("echo pty-$((6*7))-mark\n", "pty-42-mark") };
    runner.write(&h.id, typed.as_bytes()).await.expect("write pty");
    screen_until(runner, &h.id, shown, |r| r.contains(shown)).await;
    let since = runner.screen_text_since(&h.id, s0.total, 64 * 1024).await.expect("text_since");
    assert!(since.contains(shown), "screen_text_since: {since:?}");
    runner.resize(&h.id, Size { rows: 30, cols: 100 }).await.expect("resize");
    let s1 = runner.screen(&h.id).await.expect("screen after resize");
    assert_eq!(s1.size, Size { rows: 30, cols: 100 }, "screen size after resize");
    if os.windows {
        runner.write(&h.id, b"mode con\r").await.expect("write mode");
        screen_until(runner, &h.id, "Columns: 100", |r| r.contains("Columns:") && r.trim_end().ends_with("100")).await;
    } else {
        runner.write(&h.id, b"stty size\n").await.expect("write stty");
        screen_until(runner, &h.id, "30 100", |r| r.trim() == "30 100").await;
    }
    let eol = if os.windows { "\r" } else { "\n" };
    runner.write(&h.id, format!("exit 5{eol}").as_bytes()).await.expect("write exit");
    let c = collect(&mut rx, WAIT).await;
    assert_eq!(c.code, Some(5), "pty exit code");
    assert!(c.stdout.contains(shown), "pty output stream carries what the screen showed");
    assert!(runner.screen(&h.id).await.is_ok(), "screen readable after exit");
    assert!(matches!(runner.resize(&h.id, Size { rows: 10, cols: 10 }).await, Err(_)), "resize after exit must fail");
    runner.release(&h.id).await.expect("release pty");

    // resize of a piped process is unsupported
    let h = runner.spawn(os.sh("exit 0")).await.expect("spawn");
    assert!(matches!(runner.resize(&h.id, Size { rows: 5, cols: 5 }).await, Err(RunnerError::Unsupported(_)) | Err(RunnerError::Exited(_))));
    let _ = runner.release(&h.id).await;

    // tail: a file that does not exist yet, then two appended lines
    let file = os.join(scratch_dir, &format!("suite-tail-{tag}.txt"));
    let mut lines = runner.tail(TailSpec { path: file.clone(), from: TailFrom::Start }).await.expect("tail");
    let c = run(runner, os.sh(&format!("echo line-one>>{file}&& echo line-two>>{file}"))).await;
    assert_eq!(c.code, Some(0), "tail writer: {c:?}");
    line_until(&mut lines, "line-one", |l| l.trim() == "line-one").await;
    line_until(&mut lines, "line-two", |l| l.trim() == "line-two").await;
    drop(lines);

    // kill tree: a grandchild that appends a tick every second stops when the tree is killed
    let ticks = os.join(scratch_dir, &format!("suite-ticks-{tag}.txt"));
    let inner = if os.windows {
        format!("for /l %i in (1,1,600) do @(echo tick>>{ticks}& ping -n 2 127.0.0.1 >NUL)")
    } else {
        format!("while true; do echo tick >> {ticks}; sleep 1; done")
    };
    let spec = if os.windows { ProcessSpec::new("cmd", ["/c", "cmd", "/c", &inner]) } else { ProcessSpec::new("sh", ["-c", &format!("sh -c '{inner}'")]) };
    let mut lines = runner.tail(TailSpec { path: ticks.clone(), from: TailFrom::Start }).await.expect("tail ticks");
    let h = runner.spawn(spec).await.expect("spawn tree");
    let mut rx = runner.events(&h.id).await.expect("events tree");
    line_until(&mut lines, "a tick", |l| l.trim() == "tick").await;
    runner.kill(&h.id, KillScope::Tree).await.expect("kill tree");
    let c = collect(&mut rx, WAIT).await;
    assert!(c.code.is_some(), "no exit after kill tree");
    sleep(Duration::from_millis(1500)).await;
    while lines.try_recv().is_ok() {}
    sleep(Duration::from_secs(3)).await;
    assert!(lines.try_recv().is_err(), "a grandchild kept writing after kill tree");
    runner.kill(&h.id, KillScope::Tree).await.expect("kill of an exited process is not an error");
    runner.release(&h.id).await.expect("release tree");
    drop(lines);

    // hook channel: a command hook's event, and a blocking call answered by the harness
    let mut ep = runner.hook_channel().await.expect("hook_channel");
    assert!(ep.url.starts_with("http://127.0.0.1:") && ep.url.ends_with(&ep.token), "hook url: {}", ep.url);
    let mut events = runner.tail(TailSpec { path: ep.events_file.clone(), from: TailFrom::Start }).await.expect("tail events");
    // Run the hook line the way a CLI's hook runner does: through a shell that parses its own command line (on Windows,
    // PowerShell 5.1, which writes UTF-16LE: `event_of` reads it).
    let hook = if os.windows {
        ProcessSpec::new("powershell", ["-NoProfile", "-NonInteractive", "-Command", &ep.event_command("stop")])
    } else {
        os.sh(&ep.event_command("stop"))
    };
    let c = run(runner, hook).await;
    assert_eq!(c.code, Some(0), "event command: {c:?}");
    line_until(&mut events, "the stop event", |l| event_of(l) == "stop").await;
    let curl = ProcessSpec::new("curl", ["-s", "-X", "POST", "-H", "Content-Type: application/json", "--data", "{\"tool\":\"suite\"}", &ep.url]);
    if let Ok(h) = runner.spawn(curl).await {
        let mut rx = runner.events(&h.id).await.expect("events curl");
        let call = timeout(WAIT, ep.calls.recv()).await.expect("no hook call arrived").expect("hook channel closed");
        assert!(call.body.contains("\"suite\""), "hook body: {}", call.body);
        call.reply("{\"decision\":\"suite-ok\"}");
        let c = collect(&mut rx, WAIT).await;
        assert!(c.stdout.contains("suite-ok"), "hook reply: {c:?}");
        let _ = runner.release(&h.id).await;
    }
}
