// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
//! Embeds the user-facing wiki pages (docs/wiki/*.md, top level) into the component, as the Kotlin jar bundled them.
use std::{env, fs, path::Path};

fn main() {
    let root = Path::new(&env::var("CARGO_MANIFEST_DIR").unwrap()).join("../../../../../docs/wiki");
    println!("cargo:rerun-if-changed={}", root.display());
    let mut names: Vec<String> = fs::read_dir(&root)
        .map(|d| d.flatten().filter(|e| e.path().is_file()).filter_map(|e| e.file_name().to_str().and_then(|n| n.strip_suffix(".md")).map(String::from)).collect())
        .unwrap_or_default();
    names.sort();
    let mut out = String::from("pub static PAGES: &[(&str, &str)] = &[\n");
    for n in &names {
        let p = root.join(format!("{n}.md")).canonicalize().unwrap();
        let p = p.display().to_string().trim_start_matches(r"\\?\").replace('\\', "/");
        out.push_str(&format!("    ({n:?}, include_str!({p:?})),\n"));
    }
    out.push_str("];\n");
    fs::write(Path::new(&env::var("OUT_DIR").unwrap()).join("pages.rs"), out).unwrap();
}
