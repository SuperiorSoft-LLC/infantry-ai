// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
//! The guards run against the real workspace.
use std::path::{Path, PathBuf};

use infantry_guards::check::{cargo_metadata, check_edges, check_placement, workspace_crates};

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
