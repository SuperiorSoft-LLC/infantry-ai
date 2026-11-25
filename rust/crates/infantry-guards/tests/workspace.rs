// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
//! The guards run against the real workspace.
use std::path::{Path, PathBuf};

use infantry_guards::check::{cargo_metadata, check_edges, check_placement, workspace_crates};
use infantry_guards::{features, licences};

fn rust_dir() -> PathBuf {
    // crates/infantry-guards -> rust/
    Path::new(env!("CARGO_MANIFEST_DIR")).join("../..").canonicalize().expect("rust workspace dir")
}

fn crates() -> Vec<infantry_guards::check::Crate> {
    let meta = cargo_metadata(&rust_dir()).expect("cargo metadata");
    workspace_crates(&meta).expect("parse metadata")
}

#[test]
fn every_workspace_crate_respects_the_layer_map() {
    let v = check_edges(&crates());
    assert!(v.is_empty(), "layer violations (docs/rust-port/LAYERS.md):\n  {}", v.join("\n  "));
}

#[test]
fn harness_code_lives_in_l3() {
    let v = check_placement(&crates());
    assert!(v.is_empty(), "misplaced harness code:\n  {}", v.join("\n  "));
}

/// Core names no plugin id (`Infantry_<Name>`): it holds generic mechanisms only.
#[test]
fn core_names_no_plugin_id() {
    let core = rust_dir().join("crates/infantry-core");
    let mut hits = Vec::new();
    walk(&core, &mut |p| {
        let ext = p.extension().and_then(|e| e.to_str()).unwrap_or_default();
        if !matches!(ext, "rs" | "toml" | "md" | "json" | "sql") {
            return;
        }
        let text = std::fs::read_to_string(p).unwrap_or_default();
        for (i, line) in text.lines().enumerate() {
            if line.contains("Infantry_") {
                hits.push(format!("{}:{}: {}", p.display(), i + 1, line.trim()));
            }
        }
    });
    assert!(core.join("src/lib.rs").exists(), "infantry-core not found at {}", core.display());
    assert!(hits.is_empty(), "infantry-core names a plugin id:\n  {}", hits.join("\n  "));
}

/// The licence line (licences.rs): MPL core links only the Apache API crates, an API crate links no MPL crate, every
/// crate is MPL-2.0 or Apache-2.0. Today's crossings are on the shrinking list in licences.rs.
#[test]
fn licence_line_holds() {
    let v = licences::check_crossings(&crates(), licences::CROSSINGS);
    assert!(v.is_empty(), "licence line (docs/LICENSE-MAP.md section 1, docs/rust-port/DECOUPLE-PLAN.md section 7):\n  {}", v.join("\n  "));
}

/// Every `.rs` file carries the SPDX header of its crate's licence (scripts/license-headers.sh covers no `.rs` yet).
#[test]
fn every_rust_file_carries_its_crates_spdx_header() {
    let v = licences::check_headers(&rust_dir(), &crates(), licences::HEADERLESS);
    assert!(v.is_empty(), "SPDX headers (the crate's Cargo.toml `license` is the source of truth):\n  {}", v.join("\n  "));
}

/// No new feature enters core (DECOUPLE-1 #45): core names no feature beyond today's baseline in features.rs, which
/// only shrinks. The baseline is per (file, term): a new file or a new term in a listed file fails. A move that clears a
/// file or an edge deletes its entry in the same change; a move that splits a file may carry its entry over (same terms
/// or fewer).
#[test]
fn core_names_no_new_feature() {
    let cs = crates();
    let v = features::check_ratchet(&features::feature_edges(&cs), &features::scan(&rust_dir(), &cs), features::BASELINE, features::EDGE_BASELINE);
    assert!(v.is_empty(), "core and the features (docs/rust-port/DECOUPLE-PLAN.md section 3; AGENTS.md rule 1):\n  {}", v.join("\n  "));
}

/// DECOUPLE-1 (#45): the core files that still name a feature, by file and line. RED until the moves in
/// docs/rust-port/DECOUPLE-PLAN.md land; the move that clears the last line removes the `#[ignore]`.
#[test]
#[ignore = "RED (DECOUPLE-1 #45): core still names the features that leave it; see docs/rust-port/DECOUPLE-PLAN.md section 3"]
fn core_names_no_feature() {
    let cs = crates();
    let edges: Vec<String> = features::feature_edges(&cs).iter().map(|(f, t)| format!("`{f}` depends on `{t}`")).collect();
    let hits = features::scan(&rust_dir(), &cs);
    let files = features::report(&hits);
    assert!(
        edges.is_empty() && hits.is_empty(),
        "core names a feature: {} crate edge(s), {} line(s) in {} file(s)\n  {}\n  {}",
        edges.len(),
        hits.len(),
        files.len(),
        edges.join("\n  "),
        files.join("\n  ")
    );
}

fn walk(dir: &Path, f: &mut dyn FnMut(&Path)) {
    let Ok(rd) = std::fs::read_dir(dir) else { return };
    for e in rd.flatten() {
        let p = e.path();
        if p.is_dir() {
            if p.file_name().is_some_and(|n| n == "target") {
                continue;
            }
            walk(&p, f);
        } else {
            f(&p);
        }
    }
}

/// NoSpawnWindowGuard: a child process on Windows gets its own visible console window unless it is created with
/// CREATE_NO_WINDOW. Every `Command::new` in non-test source must go through `infantry_proc::command` / `NoWindow`.
/// Allow-listed: this tooling crate (runs `cargo metadata` at test time, never in a user session), the infantry-proc seam
/// itself, and the conformance/mcp_conformance test binaries.
#[test]
fn no_spawn_bypasses_the_windowless_helper() {
    fn walk(dir: &Path, out: &mut Vec<PathBuf>) {
        let Ok(rd) = std::fs::read_dir(dir) else { return };
        for e in rd.flatten() {
            let p = e.path();
            let name = e.file_name().to_string_lossy().to_string();
            if p.is_dir() {
                if !matches!(name.as_str(), "target" | "tests" | "node_modules" | ".git") {
                    walk(&p, out);
                }
            } else if name.ends_with(".rs") {
                out.push(p);
            }
        }
    }
    const ALLOW: &[&str] = &["crates/infantry-guards/", "crates/infantry-proc/", "crates/conformance/", "crates/infantry-cli/src/bin/mcp_conformance.rs"];
    let root = rust_dir();
    let mut files = Vec::new();
    walk(&root, &mut files);
    let mut bad = Vec::new();
    for f in files {
        let rel = f.strip_prefix(&root).unwrap().to_string_lossy().replace('\\', "/");
        if ALLOW.iter().any(|a| rel.starts_with(a) || rel == *a) {
            continue;
        }
        let src = std::fs::read_to_string(&f).unwrap_or_default();
        // Skip the in-file `#[cfg(test)]` module: test code may spawn plain helpers.
        let body = src.split("#[cfg(test)]").next().and_then(|s| s.split("#[cfg(all(test").next()).unwrap_or("");
        for (i, line) in body.lines().enumerate() {
            let t = line.trim_start();
            if t.starts_with("//") {
                continue;
            }
            if line.contains("Command::new(") && !line.contains("HarnessCommand::new(") && !line.contains("infantry_proc") {
                bad.push(format!("{rel}:{}: {}", i + 1, t.chars().take(100).collect::<String>()));
            }
        }
    }
    assert!(bad.is_empty(), "spawn bypasses infantry_proc::command (console window pops up on Windows):\n  {}", bad.join("\n  "));
}

/// SRC-CLEAN-1: a raw NUL byte in a tracked text source makes git treat the file as binary (its diff and secret scan
/// are hidden). Write it as an escape instead. Binary files (by extension) are not looked at.
#[test]
fn no_nul_byte_in_tracked_source() {
    let repo = rust_dir().join("..");
    let out = std::process::Command::new("git").args(["ls-files", "-z"]).current_dir(&repo).output().expect("git ls-files");
    assert!(out.status.success(), "git ls-files failed");
    const EXT: &[&str] = &["rs", "ts", "tsx", "js", "mjs", "css", "json", "toml", "ps1", "py", "md", "yml"];
    let mut bad = Vec::new();
    for rel in out.stdout.split(|b| *b == 0).filter(|s| !s.is_empty()) {
        let rel = String::from_utf8_lossy(rel);
        let ext = rel.rsplit_once('.').map(|(_, e)| e).unwrap_or_default();
        if !EXT.contains(&ext) {
            continue;
        }
        if let Ok(bytes) = std::fs::read(repo.join(rel.as_ref())) {
            if bytes.contains(&0) {
                bad.push(rel.to_string());
            }
        }
    }
    assert!(bad.is_empty(), "raw NUL byte in tracked source (write it as an escape):\n  {}", bad.join("\n  "));
}
