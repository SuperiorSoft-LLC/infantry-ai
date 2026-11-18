// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
//! ```text
//! infantry-migrate snapshot [--from DIR] [--to DIR] [--label TEXT] [--force]
//! infantry-migrate verify   [--to DIR] [--from DIR]
//! infantry-migrate status   [--to DIR]
//! ```
//! `--from` defaults to `%LOCALAPPDATA%\SuperiorSoft\InfantryAI` (the Kotlin app's state dir; only ever read),
//! `--to` to `%LOCALAPPDATA%\SuperiorSoft\InfantryAI-Rust`. See docs/rust-port/MIGRATION.md.
use infantry_migrate::{default_dirs, manifest, snapshot, status, verify};
use std::path::PathBuf;

const USAGE: &str = "infantry-migrate snapshot [--from DIR] [--to DIR] [--label TEXT] [--force]
infantry-migrate verify   [--to DIR] [--from DIR]
infantry-migrate status   [--to DIR]

snapshot  copy the state dir (read-only on the source) into --to; the previous copy is kept as <to>.prev
verify    check the copy: integrity, every table and session readable, access lists, drift since the snapshot
status    when the snapshot was taken and what it holds";

fn opt(v: Option<i64>) -> String {
    v.map(|v| v.to_string()).unwrap_or_else(|| "-".into())
}

fn main() {
    let a: Vec<String> = std::env::args().skip(1).collect();
    let arg = |k: &str| a.iter().position(|x| x == k).and_then(|i| a.get(i + 1)).cloned();
    let (def_from, def_to) = default_dirs();
    let to = arg("--to").map(PathBuf::from).unwrap_or(def_to);
    let code = match a.first().map(String::as_str) {
        Some("snapshot") => {
            let o = snapshot::Options {
                from: arg("--from").map(PathBuf::from).unwrap_or(def_from),
                to,
                label: arg("--label").unwrap_or_else(|| manifest::DEFAULT_SOURCE_LABEL.into()),
                force: a.iter().any(|x| x == "--force"),
            };
            match snapshot::run(&o, &mut |s| eprintln!("[migrate] {s}")) {
                Ok(m) => {
                    println!(
                        "snapshot done in {} ms: {} sessions, {} projects, {} agents, {} roles, {} files + {} dbs ({:.1} MB), {} runtime files skipped",
                        m.took_ms,
                        m.counts.sessions,
                        opt(m.counts.projects),
                        opt(m.counts.agents),
                        m.counts.roles,
                        m.files.len(),
                        m.databases.len(),
                        m.counts.bytes as f64 / 1_048_576.0,
                        m.skipped.len()
                    );
                    for d in &m.databases {
                        println!("  {}: integrity {}, {} tables, read {}", d.path, d.integrity, d.tables.len(), d.mode);
                    }
                    println!("next: infantry-migrate verify --to \"{}\"", o.to.display());
                    0
                }
                Err(e) => {
                    eprintln!("snapshot failed: {e}");
                    1
                }
            }
        }
        Some("verify") => match verify::run(&to, arg("--from").map(PathBuf::from).as_deref()) {
            Ok(r) => {
                r.lines.iter().for_each(|l| println!("{l}"));
                r.failures.iter().for_each(|f| println!("FAIL      {f}"));
                println!("result    {}", if r.ok() { "OK" } else { "FAILED" });
                if r.ok() {
                    0
                } else {
                    1
                }
            }
            Err(e) => {
                eprintln!("verify failed: {e}");
                1
            }
        },
        Some("status") => match status::run(&to) {
            Ok(lines) => {
                lines.iter().for_each(|l| println!("{l}"));
                0
            }
            Err(e) => {
                eprintln!("{e}");
                1
            }
        },
        Some("--help") | Some("-h") | None => {
            println!("{USAGE}");
            0
        }
        Some(other) => {
            eprintln!("unknown command `{other}`\n{USAGE}");
            2
        }
    };
    std::process::exit(code);
}
