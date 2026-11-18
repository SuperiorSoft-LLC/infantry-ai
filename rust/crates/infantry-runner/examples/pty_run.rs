// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
//! Debug tool: runs a command in the runner's PTY for some seconds, prints what the terminal receives (escaped) and the screen.
//! Cursor queries are answered. `cargo run --example pty_run -- <seconds> <program> [args...]`

use infantry_runner::pty;
use infantry_runner::screen::{queries, Screen};
use std::io::{Read, Write};
use std::sync::{Arc, Mutex};
use std::time::{Duration, Instant};

fn main() {
    let a: Vec<String> = std::env::args().skip(1).collect();
    let secs: u64 = a[0].parse().expect("seconds");
    let cmd = pty::Command {
        program: a[1].clone(),
        args: a[2..].to_vec(),
        cwd: std::env::temp_dir(),
        env: vec![],
        env_remove: ["CLAUDECODE", "CLAUDE_CODE_*", "INFANTRY_*", "AGENTHQ_*"].map(String::from).to_vec(),
    };
    let t0 = Instant::now();
    let mut c = pty::spawn(&cmd, 40, 160).expect("spawn");
    println!("pid {}", c.pid);
    let writer = Arc::new(Mutex::new(c.writer));
    let screen = Arc::new(Mutex::new(Screen::new(40, 160)));
    let mut reader = c.reader.take().unwrap();
    let (w, s) = (writer.clone(), screen.clone());
    std::thread::spawn(move || {
        let mut buf = vec![0u8; 16384];
        while let Ok(n) = reader.read(&mut buf) {
            if n == 0 {
                break;
            }
            let chunk = &buf[..n];
            let text: String = String::from_utf8_lossy(chunk).escape_debug().to_string().chars().take(200).collect();
            println!("[{} ms] {n}: {text}", t0.elapsed().as_millis());
            let mut sc = s.lock().unwrap();
            sc.feed(chunk, t0.elapsed().as_millis() as u64);
            for _ in queries(chunk) {
                let (r, col) = sc.cursor();
                let mut w = w.lock().unwrap();
                let _ = write!(w, "\x1b[{};{}R", r + 1, col + 1);
                let _ = w.flush();
            }
        }
        println!("[{} ms] EOF", t0.elapsed().as_millis());
    });
    std::thread::sleep(Duration::from_secs(secs));
    println!("exited: {:?}", c.process.try_wait());
    println!("--- screen ---\n{}", screen.lock().unwrap().contents().trim_end());
    c.process.kill();
    std::process::exit(0);
}
