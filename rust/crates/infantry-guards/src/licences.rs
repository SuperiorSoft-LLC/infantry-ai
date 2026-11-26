// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
//! The licence line (docs/LICENSE-MAP.md section 1; docs/archive/rust-port/DECOUPLE-PLAN.md section 7): the Rust port of the
//! Kotlin `DaemonCoreGuardTest`, `HarnessCoreGuardTest` and `LicenseMapGuardTest`.
//!
//! Core is MPL-2.0; the SDK, the APIs, the features and the plugins are Apache-2.0. On normal and build dependencies
//! (dev-dependencies never ship):
//! 1. an MPL crate depends on no Apache crate except the API crates ([`API_CRATES`]): core never holds a feature, a
//!    concrete harness or plugin code;
//! 2. an API crate depends on no MPL crate: whoever builds against the SDK is never pulled onto MPL terms.
//!
//! An Apache feature or tool crate may use MPL core: MPL is file-level (LICENSE-MAP section 1). Every crate is MPL-2.0
//! or Apache-2.0, and every `.rs` file carries the SPDX header of its crate's licence.
//!
//! Today's exceptions are listed below, each with the move that removes it. The lists only shrink: a new crossing or a
//! new header-less file fails, and so does an entry that no longer applies (the move that fixes it deletes the entry).
use std::path::Path;

use crate::check::{crate_dir, rel_path, Crate};

pub const MPL: &str = "MPL-2.0";
pub const APACHE: &str = "Apache-2.0";

/// The Apache crates an MPL crate may depend on: the contracts that plugins, harnesses and orchestrators build against.
pub const API_CRATES: &[&str] = &["infantry-plugin-wit", "infantry-plugin-sdk", "infantry-harness", "infantry-orchestrator-api", "infantry-runner-api", "infantry-runner-source"];

/// Tolerated crossings, `(from, to, the move that removes it)`. The LIC-N moves are in DECOUPLE-PLAN.md section 7a.
/// Shrinks to zero.
pub const CROSSINGS: &[(&str, &str, &str)] = &[
    // Rule 1: core links a feature or a concrete harness.
    ("infantry-daemon", "infantry-board", "LIC-6 to LIC-10: the board wiring becomes a module in its own Apache crate, registered by the distribution"),
];

/// `.rs` files with no SPDX header, `(path from rust/, the move that adds it)`.
pub const HEADERLESS: &[(&str, &str)] = &[];

/// Every licence-line violation as a human-readable line (empty = clean), against `allowed` crossings.
pub fn check_crossings(crates: &[Crate], allowed: &[(&str, &str, &str)]) -> Vec<String> {
    let mut out = Vec::new();
    let licence = |name: &str| crates.iter().find(|c| c.name == name).map(|c| c.license.as_str()).unwrap_or_default();
    for c in crates {
        if c.license != MPL && c.license != APACHE {
            out.push(format!("`{}` has licence `{}`: a crate is {MPL} (core) or {APACHE} (everything else)", c.name, c.license));
        }
    }
    let mut seen = Vec::new();
    for c in crates {
        for dep in &c.deps {
            let rule = match (c.license.as_str(), licence(dep)) {
                (MPL, APACHE) if !API_CRATES.contains(&dep.as_str()) => "an MPL crate depends on an Apache crate that is not an API crate",
                (APACHE, MPL) if API_CRATES.contains(&c.name.as_str()) => "an Apache API crate depends on an MPL crate",
                _ => continue,
            };
            seen.push((c.name.as_str(), dep.as_str()));
            if !allowed.iter().any(|(f, t, _)| *f == c.name && t == dep) {
                out.push(format!("`{}` ({}) -> `{dep}` ({}): {rule}", c.name, c.license, licence(dep)));
            }
        }
    }
    for (f, t, why) in allowed {
        if !seen.contains(&(*f, *t)) {
            out.push(format!("stale crossing `{f}` -> `{t}` ({why}): the edge is gone, delete the entry in licences.rs"));
        }
    }
    out
}

/// The SPDX id of a source text's header (in its first lines), if any.
pub fn spdx_of(text: &str) -> Option<&str> {
    text.lines().take(5).find_map(|l| l.split("SPDX-License-Identifier:").nth(1)).map(str::trim)
}

/// Every `.rs` file whose SPDX header is missing or differs from its crate's licence, minus `headerless`; plus stale
/// `headerless` entries. Paths are reported from `rust_dir`, with `/`.
pub fn check_headers(rust_dir: &Path, crates: &[Crate], headerless: &[(&str, &str)]) -> Vec<String> {
    let mut out = Vec::new();
    let mut missing = Vec::new();
    for c in crates {
        let root = crate_dir(c);
        let mut files = Vec::new();
        rs_files(&root, &root, &mut files);
        for f in files {
            let rel = rel_path(rust_dir, &f);
            let text = std::fs::read_to_string(&f).unwrap_or_default();
            match spdx_of(&text) {
                Some(id) if id == c.license => {}
                Some(id) => out.push(format!("{rel}: SPDX `{id}`, but crate `{}` is `{}`", c.name, c.license)),
                None => {
                    missing.push(rel.clone());
                    if !headerless.iter().any(|(p, _)| *p == rel) {
                        out.push(format!("{rel}: no SPDX header (crate `{}` is `{}`)", c.name, c.license));
                    }
                }
            }
        }
    }
    for (p, why) in headerless {
        if !missing.iter().any(|m| m == p) {
            out.push(format!("stale header-less entry `{p}` ({why}): the file has a header now or is gone, delete the entry"));
        }
    }
    out
}

/// The `.rs` files under `dir`, skipping `target` and any nested crate (a sub-folder with its own Cargo.toml).
fn rs_files(root: &Path, dir: &Path, out: &mut Vec<std::path::PathBuf>) {
    let Ok(rd) = std::fs::read_dir(dir) else { return };
    for e in rd.flatten() {
        let p = e.path();
        if p.is_dir() {
            let nested = p != root && p.join("Cargo.toml").exists();
            if !nested && !matches!(e.file_name().to_str(), Some("target" | "node_modules" | ".git")) {
                rs_files(root, &p, out);
            }
        } else if p.extension().is_some_and(|x| x == "rs") {
            out.push(p);
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn krate(name: &str, license: &str, deps: &[&str]) -> Crate {
        Crate { name: name.into(), manifest_path: format!("/ws/{name}/Cargo.toml"), license: license.into(), deps: deps.iter().map(|d| d.to_string()).collect() }
    }

    fn clean() -> Vec<Crate> {
        vec![
            krate("infantry-protocol", MPL, &[]),
            krate("infantry-core", MPL, &["infantry-protocol"]),
            krate("infantry-plugin-wit", APACHE, &[]),
            krate("infantry-plugin-sdk", APACHE, &["infantry-plugin-wit"]),
            krate("infantry-daemon", MPL, &["infantry-core", "infantry-plugin-wit"]),
            // An Apache feature may use MPL core.
            krate("infantry-board", APACHE, &["infantry-core", "infantry-protocol"]),
        ]
    }

    #[test]
    fn clean_line_passes() {
        assert_eq!(check_crossings(&clean(), &[]), Vec::<String>::new());
    }

    #[test]
    fn core_linking_a_feature_fails() {
        let mut g = clean();
        g[4].deps.push("infantry-board".into());
        let v = check_crossings(&g, &[]);
        assert_eq!(v.len(), 1, "{v:?}");
        assert!(v[0].contains("`infantry-daemon` (MPL-2.0) -> `infantry-board` (Apache-2.0)"), "{v:?}");
    }

    #[test]
    fn an_api_crate_using_mpl_fails() {
        let mut g = clean();
        g[3].deps.push("infantry-core".into());
        let v = check_crossings(&g, &[]);
        assert!(v.len() == 1 && v[0].contains("an Apache API crate depends on an MPL crate"), "{v:?}");
    }

    #[test]
    fn an_allowed_crossing_passes_and_a_stale_one_fails() {
        let mut g = clean();
        g[4].deps.push("infantry-board".into());
        assert_eq!(check_crossings(&g, &[("infantry-daemon", "infantry-board", "a move")]), Vec::<String>::new());
        let v = check_crossings(&clean(), &[("infantry-daemon", "infantry-board", "a move")]);
        assert!(v.len() == 1 && v[0].starts_with("stale crossing"), "{v:?}");
    }

    #[test]
    fn an_unknown_licence_fails() {
        let mut g = clean();
        g[5].license = "MIT".into();
        let v = check_crossings(&g, &[]);
        assert!(v.iter().any(|l| l.contains("has licence `MIT`")), "{v:?}");
    }

    #[test]
    fn spdx_header_is_read_from_the_first_lines() {
        assert_eq!(spdx_of("// SPDX-License-Identifier: MPL-2.0\n// Copyright"), Some("MPL-2.0"));
        assert_eq!(spdx_of("#![allow(x)]\nfn main() {}"), None);
    }
}
