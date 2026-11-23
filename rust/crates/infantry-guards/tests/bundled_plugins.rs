// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
//! SVC-8: the plugins that ship in the app but are built in the infantry-plugins repo (rust/plugins/bundled-external.json),
//! and the environment the release path stages them for.
use std::path::{Path, PathBuf};
use std::process::Command;

fn repo_root() -> PathBuf {
    Path::new(env!("CARGO_MANIFEST_DIR")).join("../../..").canonicalize().expect("repo root")
}

fn list() -> serde_json::Value {
    serde_json::from_str(&std::fs::read_to_string(repo_root().join("rust/plugins/bundled-external.json")).expect("bundled-external.json")).expect("valid json")
}

#[test]
fn the_bundled_external_list_names_the_hosting_plugin_with_a_safe_path() {
    let l = list();
    let mods = l["modules"].as_array().expect("modules");
    assert!(mods.iter().any(|m| m["name"] == "Infantry_Sites"), "the hosting plugin is in the bundled list");
    for m in mods {
        let (name, path) = (m["name"].as_str().unwrap(), m["path"].as_str().unwrap());
        assert!(!path.contains("..") && !path.starts_with('/'), "{name}: path {path} leaves the plugins repo");
        assert_eq!(path.rsplit('/').next().unwrap(), name, "{name}: the folder name is the module name (stage.sh skips by it)");
    }
}

#[test]
fn stage_sh_skips_a_listed_module_and_stages_the_external_ones() {
    let s = std::fs::read_to_string(repo_root().join("rust/plugins/stage.sh")).unwrap();
    assert!(s.contains("bundled-external.json") && s.contains("stage-external.sh"), "stage.sh reads the list and calls stage-external.sh");
}

#[test]
fn every_release_job_that_stages_the_bundle_is_prod_and_checks_out_the_plugins_repo() {
    let w = std::fs::read_to_string(repo_root().join(".github/workflows/rust-release.yml")).unwrap();
    let stages = w.matches("bash src-tauri/scripts/stage-bundle.sh").count();
    assert!(stages >= 3, "windows, linux and macos stage the bundle");
    assert_eq!(w.matches("INFANTRY_BUILD_ENV: prod").count(), stages, "each job that stages the bundle builds for prod");
    assert_eq!(w.matches("repository: SuperiorSoft-LLC/infantry-plugins").count(), stages, "each of them checks out the plugins repo");
    assert_eq!(w.matches("INFANTRY_PLUGINS_REPO=").count(), stages, "and points INFANTRY_PLUGINS_REPO at it");
}

/// A fake plugins repo whose module stage.sh records its arguments, so the env the release path passes is visible.
fn fake_repo(dir: &Path) {
    let m = dir.join("official/Infantry_Sites");
    std::fs::create_dir_all(&m).unwrap();
    std::fs::write(m.join("stage.sh"), "#!/usr/bin/env bash\nmkdir -p \"$1\"; echo \"$*\" > \"$1/args.txt\"\n").unwrap();
}

fn run(build_env: Option<&str>, profile: &str) -> (bool, String, String) {
    let dir = std::env::temp_dir().join(format!("infantry-svc8-guard-{}-{}", std::process::id(), profile));
    let _ = std::fs::remove_dir_all(&dir);
    let (repo, out) = (dir.join("repo"), dir.join("out"));
    fake_repo(&repo);
    // Git's bash when it is installed: the bash first on a Windows PATH can be WSL's, which drops environment variables.
    let git_bash = Path::new("C:/Program Files/Git/bin/bash.exe");
    let mut c = Command::new(if git_bash.is_file() { git_bash } else { Path::new("bash") });
    c.current_dir(repo_root().join("rust/plugins")).arg("stage-external.sh").arg(&out).arg(profile).env("INFANTRY_PLUGINS_REPO", &repo).env_remove("INFANTRY_BUILD_ENV");
    if let Some(e) = build_env {
        c.env("INFANTRY_BUILD_ENV", e);
    }
    let o = c.output().expect("bash");
    let args = std::fs::read_to_string(out.join("args.txt")).unwrap_or_default();
    let _ = std::fs::remove_dir_all(&dir);
    (o.status.success(), args.trim().to_string(), String::from_utf8_lossy(&o.stderr).to_string())
}

#[test]
fn a_prod_release_stages_the_bundled_plugin_for_prod() {
    let (ok, args, err) = run(Some("prod"), "release");
    assert!(ok, "stage-external failed: {err}");
    assert!(args.ends_with("--env prod --release"), "args were: {args}");
}

#[test]
fn a_local_build_stages_for_staging_and_a_bad_env_is_refused() {
    let (ok, args, _) = run(None, "debug");
    assert!(ok && args.ends_with("--env staging"), "args were: {args}");
    let (ok, _, err) = run(Some("live"), "release");
    assert!(!ok && err.contains("not prod, staging or dev"), "a bad env is refused: {err}");
}
