// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
//! Embeds the whole user-facing wiki tree (docs/wiki/**/*.md) into the component. A page's id is its path under
//! docs/wiki without `.md` (e.g. `plugins/overview`). Order and section come from the `nav:` of docs/mkdocs.yml;
//! pages the nav does not name follow, sorted by path, with their folder as section.
use std::{env, fs, path::Path};

fn walk(dir: &Path, rel: &str, out: &mut Vec<String>) {
    let Ok(rd) = fs::read_dir(dir) else { return };
    for e in rd.flatten() {
        let name = e.file_name().to_string_lossy().to_string();
        let path = e.path();
        let id = if rel.is_empty() { name.clone() } else { format!("{rel}/{name}") };
        if path.is_dir() {
            walk(&path, &id, out);
        } else if let Some(stem) = id.strip_suffix(".md") {
            out.push(stem.to_string());
        }
    }
}

/// (page id, section) in nav order, read from the simple two-level `nav:` list of mkdocs.yml.
fn nav(yml: &str) -> Vec<(String, String)> {
    let mut out = Vec::new();
    let (mut in_nav, mut section) = (false, String::new());
    for line in yml.lines() {
        if line.starts_with("nav:") {
            in_nav = true;
            continue;
        }
        if !in_nav {
            continue;
        }
        if !line.is_empty() && !line.starts_with(' ') {
            break;
        }
        let t = line.trim_start();
        let indent = line.len() - t.len();
        let Some(item) = t.strip_prefix("- ") else { continue };
        let (label, target) = item.split_once(':').map(|(l, r)| (l.trim(), r.trim())).unwrap_or((item.trim(), ""));
        if target.is_empty() {
            section = label.to_string();
        } else if let Some(stem) = target.strip_suffix(".md") {
            let sec = if indent <= 2 { String::new() } else { section.clone() };
            if indent <= 2 {
                section.clear();
            }
            out.push((stem.to_string(), sec));
        }
    }
    out
}

fn main() {
    let docs = Path::new(&env::var("CARGO_MANIFEST_DIR").unwrap()).join("../../../../../docs");
    let root = docs.join("wiki");
    println!("cargo:rerun-if-changed={}", root.display());
    println!("cargo:rerun-if-changed={}", docs.join("mkdocs.yml").display());
    let mut found = Vec::new();
    walk(&root, "", &mut found);
    found.sort();
    let mut ordered: Vec<(String, String)> =
        nav(&fs::read_to_string(docs.join("mkdocs.yml")).unwrap_or_default()).into_iter().filter(|(p, _)| found.contains(p)).collect();
    for p in &found {
        if !ordered.iter().any(|(o, _)| o == p) {
            let sec = p.rsplit_once('/').map(|(d, _)| d.to_string()).unwrap_or_default();
            ordered.push((p.clone(), sec));
        }
    }
    let mut out = String::from("pub static PAGES: &[(&str, &str, &str)] = &[\n");
    for (n, sec) in &ordered {
        let p = root.join(format!("{n}.md")).canonicalize().unwrap();
        let p = p.display().to_string().trim_start_matches(r"\\?\").replace('\\', "/");
        out.push_str(&format!("    ({n:?}, {sec:?}, include_str!({p:?})),\n"));
    }
    out.push_str("];\n");
    fs::write(Path::new(&env::var("OUT_DIR").unwrap()).join("pages.rs"), out).unwrap();
}
