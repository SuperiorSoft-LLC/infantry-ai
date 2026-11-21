// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
//! Embeds the database schema (the SQLDelight `*.sq` sources, the one schema both daemons share) so a daemon on a
//! FRESH state dir can create every table, and the version a fresh database is stamped with (newest `N.sqm` + 1).
use std::fmt::Write as _;
use std::path::Path;

fn main() {
    // Read at RUN time: `env!` would bake the checkout that compiled this script into the cached build-script binary in
    // the shared target dir, and break the next build from another worktree once the first one is removed.
    let manifest_dir = std::env::var("CARGO_MANIFEST_DIR").expect("cargo sets CARGO_MANIFEST_DIR");
    let dir = Path::new(&manifest_dir)
        .join("../../../superiorSoftLibrary/infantryCore/src/main/sqldelight/com/superiorsoftllc/infantry/db");
    println!("cargo:rerun-if-changed={}", dir.display());
    let mut files: Vec<_> = std::fs::read_dir(&dir)
        .unwrap_or_else(|e| panic!("schema folder {}: {e}", dir.display()))
        .filter_map(|e| e.ok().map(|e| e.path()))
        .collect();
    files.sort();
    let mut version = 0i64;
    let mut out = String::from("/// (file name, contents) of every schema source.\npub const SCHEMA_SOURCES: &[(&str, &str)] = &[\n");
    for f in &files {
        let name = f.file_name().unwrap().to_string_lossy().to_string();
        if let Some(n) = name.strip_suffix(".sqm").and_then(|n| n.parse::<i64>().ok()) {
            version = version.max(n);
        }
        if f.extension().is_some_and(|x| x == "sq") {
            println!("cargo:rerun-if-changed={}", f.display());
            let abs = std::fs::canonicalize(f).unwrap();
            let _ = writeln!(out, "    ({name:?}, include_str!({:?})),", abs.to_string_lossy().trim_start_matches(r"\\?\"));
        }
    }
    out.push_str("];\n");
    let _ = writeln!(out, "/// The version a fresh database is stamped with.\npub const SCHEMA_VERSION: i64 = {};", version + 1);
    let dest = Path::new(&std::env::var("OUT_DIR").unwrap()).join("schema_sources.rs");
    std::fs::write(dest, out).unwrap();
}
