// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
//! The checker: `cargo metadata` JSON in, violations out.
use std::path::{Path, PathBuf};
use std::process::Command;

use serde_json::Value;

use crate::layers::{classify, Layer, ALLOWLIST, HARNESS_MODULE_NAMES, PLUGIN_DEPS, TOOLING_DEPS};

/// One workspace crate as the checker sees it.
#[derive(Debug, Clone)]
pub struct Crate {
    pub name: String,
    pub manifest_path: String,
    /// The `license` of its Cargo.toml (an SPDX id; empty when it has none).
    pub license: String,
    /// Workspace crates it depends on (normal and build dependencies; dev-dependencies are
    /// test-only and not checked).
    pub deps: Vec<String>,
}

/// Runs `cargo metadata --format-version 1 --no-deps` in `workspace_dir`.
pub fn cargo_metadata(workspace_dir: &Path) -> Result<Value, String> {
    let cargo = std::env::var("CARGO").unwrap_or_else(|_| "cargo".into());
    let out = Command::new(cargo)
        .args(["metadata", "--format-version", "1", "--no-deps", "--offline"])
        .current_dir(workspace_dir)
        .output()
        .map_err(|e| format!("cannot run cargo metadata: {e}"))?;
    if !out.status.success() {
        return Err(format!("cargo metadata failed: {}", String::from_utf8_lossy(&out.stderr)));
    }
    serde_json::from_slice(&out.stdout).map_err(|e| format!("cargo metadata output is not JSON: {e}"))
}

/// The workspace crates and their intra-workspace edges.
pub fn workspace_crates(meta: &Value) -> Result<Vec<Crate>, String> {
    let members: Vec<&str> = meta["workspace_members"]
        .as_array()
        .ok_or("metadata has no workspace_members")?
        .iter()
        .filter_map(Value::as_str)
        .collect();
    let packages = meta["packages"].as_array().ok_or("metadata has no packages")?;
    let ws: Vec<&Value> = packages.iter().filter(|p| p["id"].as_str().is_some_and(|id| members.contains(&id))).collect();
    let names: Vec<&str> = ws.iter().filter_map(|p| p["name"].as_str()).collect();
    let mut crates = Vec::new();
    for p in ws {
        let name = p["name"].as_str().ok_or("package without name")?.to_string();
        let manifest_path = p["manifest_path"].as_str().unwrap_or_default().to_string();
        let license = p["license"].as_str().unwrap_or_default().to_string();
        let mut deps: Vec<String> = p["dependencies"]
            .as_array()
            .map(|a| a.as_slice())
            .unwrap_or_default()
            .iter()
            .filter(|d| d["kind"].as_str() != Some("dev"))
            .filter_map(|d| d["name"].as_str())
            .filter(|d| names.contains(d))
            .map(str::to_string)
            .collect();
        deps.sort();
        deps.dedup();
        crates.push(Crate { name, manifest_path, license, deps });
    }
    Ok(crates)
}

/// Every dependency-rule violation, as human-readable lines (empty = clean).
pub fn check_edges(crates: &[Crate]) -> Vec<String> {
    let mut out = Vec::new();
    let class = |c: &Crate| classify(&c.name, &c.manifest_path);
    for c in crates {
        let Some((from, from_tier)) = class(c) else {
            out.push(format!(
                "unknown workspace crate `{}` ({}): add it to infantry-guards/src/layers.rs and docs/rust-port/LAYERS.md",
                c.name, c.manifest_path
            ));
            continue;
        };
        for dep in &c.deps {
            let Some(d) = crates.iter().find(|x| &x.name == dep) else { continue };
            let Some((to, to_tier)) = class(d) else { continue }; // reported as unknown above
            if let Some(v) = edge_violation(&c.name, from, from_tier, dep, to, to_tier) {
                out.push(v);
            }
        }
    }
    out
}

fn edge_violation(from_name: &str, from: Layer, from_tier: u8, to_name: &str, to: Layer, to_tier: u8) -> Option<String> {
    let edge = format!("`{from_name}` ({}) -> `{to_name}` ({})", from.label(), to.label());
    match from {
        Layer::Top => return None,
        Layer::Tooling => {
            let allowed = TOOLING_DEPS.iter().find(|(n, _)| *n == from_name).map(|(_, a)| *a).unwrap_or(&[]);
            return (!allowed.contains(&to_name)).then(|| format!("{edge}: this tooling crate may depend only on {allowed:?}"));
        }
        Layer::Plugin => {
            return (!PLUGIN_DEPS.contains(&to_name)).then(|| format!("{edge}: a plugin depends only on {PLUGIN_DEPS:?}"));
        }
        _ => {}
    }
    let (Some(fr), Some(tr)) = (from.rank(), to.rank()) else {
        return Some(format!("{edge}: only a top crate may depend on a plugin, top or tooling crate"));
    };
    if from == Layer::Harness && to == Layer::Orchestration {
        return Some(format!("{edge}: L3 never depends on L4 (agents work with no orchestrator)"));
    }
    if tr > fr {
        return Some(format!("{edge}: depends on a higher layer"));
    }
    if tr == fr && to_tier >= from_tier {
        return Some(format!("{edge}: depends on its own layer (tier {to_tier} >= {from_tier}; see the tiers in layers.rs)"));
    }
    None
}

/// Harness driver modules (`src/<harness>` or `src/<harness>.rs`) inside crates below L3,
/// minus the allowlist.
pub fn check_placement(crates: &[Crate]) -> Vec<String> {
    let mut out = Vec::new();
    for c in crates {
        let Some((layer, _)) = classify(&c.name, &c.manifest_path) else { continue };
        if !layer.rank().is_some_and(|r| r < 3) {
            continue;
        }
        let dir = crate_dir(c);
        for h in HARNESS_MODULE_NAMES {
            for rel in [format!("src/{h}"), format!("src/{h}.rs")] {
                if !dir.join(&rel).exists() {
                    continue;
                }
                let allowed = ALLOWLIST.iter().any(|(n, p, _)| *n == c.name && *p == rel);
                if !allowed {
                    out.push(format!("`{}` ({}) holds harness code at {rel}: harness drivers live in L3", c.name, layer.label()));
                }
            }
        }
    }
    out
}

pub fn crate_dir(c: &Crate) -> PathBuf {
    Path::new(&c.manifest_path).parent().map(Path::to_path_buf).unwrap_or_default()
}

/// `file` relative to `root`, with `/`. Tolerates the `\\?\` prefix that `canonicalize` adds on Windows while cargo
/// metadata paths have none; a file outside `root` comes back whole.
pub fn rel_path(root: &Path, file: &Path) -> String {
    fn norm(p: &Path) -> String {
        let s = p.to_string_lossy().replace('\\', "/");
        s.strip_prefix("//?/").map(str::to_string).unwrap_or(s)
    }
    let (root, file) = (norm(root), norm(file));
    file.strip_prefix(&format!("{}/", root.trim_end_matches('/'))).map(str::to_string).unwrap_or(file)
}

#[cfg(test)]
mod tests {
    use super::*;
    use serde_json::json;

    fn pkg(name: &str, path: &str, deps: &[(&str, Option<&str>)]) -> Value {
        json!({
            "id": format!("path+file:///ws/{name}#0.1.0"),
            "name": name,
            "manifest_path": format!("/ws/{path}/Cargo.toml"),
            "dependencies": deps.iter().map(|(d, kind)| json!({"name": d, "kind": kind, "path": format!("/ws/x/{d}")}))
                .chain(std::iter::once(json!({"name": "serde", "kind": null})))
                .collect::<Vec<_>>(),
        })
    }

    fn meta(pkgs: Vec<Value>) -> Value {
        let ids: Vec<Value> = pkgs.iter().map(|p| p["id"].clone()).collect();
        json!({"packages": pkgs, "workspace_members": ids})
    }

    fn violations(pkgs: Vec<Value>) -> Vec<String> {
        check_edges(&workspace_crates(&meta(pkgs)).unwrap())
    }

    fn good() -> Vec<Value> {
        vec![
            pkg("infantry-protocol", "crates/infantry-protocol", &[]),
            pkg("infantry-core", "crates/infantry-core", &[("infantry-protocol", None)]),
            pkg("infantry-plugin-wit", "crates/infantry-plugin-wit", &[]),
            pkg("infantry-plugin-sdk", "crates/infantry-plugin-sdk", &[("infantry-plugin-wit", None)]),
            pkg("infantry-runner-api", "crates/infantry-runner-api", &[("infantry-core", None)]),
            pkg("infantry-runner-local", "crates/infantry-runner-local", &[("infantry-runner-api", None)]),
            pkg("infantry-harness", "crates/infantry-harness", &[("infantry-runner-api", None)]),
            pkg("infantry-harness-codex", "crates/infantry-harness-codex", &[("infantry-harness", None)]),
            pkg("infantry-orchestrator-api", "crates/infantry-orchestrator-api", &[("infantry-harness", None)]),
            pkg("infantry-board-plugin", "plugins/official/Infantry_Board/daemon", &[("infantry-plugin-sdk", None)]),
            pkg("infantry-daemon", "crates/infantry-daemon", &[("infantry-orchestrator-api", None), ("infantry-board-plugin", None)]),
            pkg("infantry-conformance", "crates/conformance", &[("infantry-protocol", None), ("infantry-daemon", Some("dev"))]),
        ]
    }

    #[test]
    fn clean_graph_passes() {
        assert_eq!(violations(good()), Vec::<String>::new());
    }

    #[test]
    fn planted_upward_edge_fails() {
        let mut g = good();
        g[1] = pkg("infantry-core", "crates/infantry-core", &[("infantry-protocol", None), ("infantry-runner-api", None)]);
        let v = violations(g);
        assert_eq!(v.len(), 1, "{v:?}");
        assert!(v[0].contains("`infantry-core` (L1 core) -> `infantry-runner-api` (L2 runner): depends on a higher layer"), "{v:?}");
    }

    #[test]
    fn planted_bad_edges_each_fail() {
        let cases: Vec<(Value, &str)> = vec![
            (pkg("infantry-harness", "crates/infantry-harness", &[("infantry-orchestrator-api", None)]), "L3 never depends on L4"),
            (pkg("infantry-runner-api", "crates/infantry-runner-api", &[("infantry-runner-local", None)]), "its own layer"),
            (pkg("infantry-board-plugin", "plugins/official/Infantry_Board/daemon", &[("infantry-core", None)]), "a plugin depends only on"),
            (pkg("infantry-core", "crates/infantry-core", &[("infantry-daemon", None)]), "only a top crate may depend"),
            (pkg("infantry-conformance", "crates/conformance", &[("infantry-core", None)]), "tooling crate may depend only on"),
        ];
        for (bad, want) in cases {
            let name = bad["name"].as_str().unwrap().to_string();
            let g: Vec<Value> = good().into_iter().map(|p| if p["name"] == name.as_str() { bad.clone() } else { p }).collect();
            let v = violations(g);
            assert!(v.iter().any(|l| l.contains(want)), "planted edge in {name} should fail with `{want}`, got {v:?}");
        }
    }

    #[test]
    fn unknown_crate_fails_but_plugins_map_automatically() {
        let mut g = good();
        g.push(pkg("infantry-mystery", "crates/infantry-mystery", &[]));
        g.push(pkg("weather-daemon", "plugins/official/Infantry_Weather/daemon", &[("infantry-plugin-wit", None)]));
        let v = violations(g);
        assert_eq!(v.len(), 1, "{v:?}");
        assert!(v[0].starts_with("unknown workspace crate `infantry-mystery`"), "{v:?}");
    }

    #[test]
    fn dev_dependencies_are_not_checked() {
        let mut g = good();
        g[1] = pkg("infantry-core", "crates/infantry-core", &[("infantry-protocol", None), ("infantry-daemon", Some("dev"))]);
        assert!(violations(g).is_empty());
    }
}
