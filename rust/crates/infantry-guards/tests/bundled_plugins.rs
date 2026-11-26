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

/// SITES-ENV-1: the names of the steps in `workflow` that stage plugins (stage.sh, stage-external.sh or stage-bundle.sh)
/// and run without `INFANTRY_BUILD_ENV: prod`, neither on the step nor on its job (nor workflow-wide).
fn stage_steps_without_prod(workflow: &str) -> Vec<String> {
    const PROD: &str = "INFANTRY_BUILD_ENV: prod";
    let stages = |t: &str| ["stage.sh", "stage-external.sh", "stage-bundle.sh"].iter().any(|s| t.contains(s));
    let lines: Vec<&str> = workflow.lines().collect();
    let jobs_at = lines.iter().position(|l| l.trim_end() == "jobs:").unwrap_or(lines.len());
    let top_prod = lines[..jobs_at].iter().any(|l| l.contains(PROD) && !l.trim_start().starts_with('#'));
    let indent = |l: &str| l.len() - l.trim_start().len();
    let mut bad = Vec::new();
    let mut i = jobs_at + 1;
    while i < lines.len() {
        // A job: a key at indent 2 under jobs:. Its header runs to `steps:`; each step starts at "- " under it.
        let job_end = (i + 1..lines.len()).find(|&j| !lines[j].trim().is_empty() && indent(lines[j]) <= 2).unwrap_or(lines.len());
        let job = &lines[i..job_end];
        let steps_at = job.iter().position(|l| l.trim_end().ends_with("steps:")).unwrap_or(job.len());
        let job_prod = top_prod || job[..steps_at].iter().any(|l| l.contains(PROD) && !l.trim_start().starts_with('#'));
        let starts: Vec<usize> = (steps_at..job.len()).filter(|&k| job[k].trim_start().starts_with("- ") && indent(job[k]) <= 6).collect();
        for (n, &s) in starts.iter().enumerate() {
            let step: Vec<&str> = job[s..*starts.get(n + 1).unwrap_or(&job.len())].iter().map(|l| l.split(" #").next().unwrap_or("")).filter(|l| !l.trim_start().starts_with('#')).collect();
            let text = step.join("\n");
            if stages(&text) && !job_prod && !text.contains(PROD) {
                bad.push(format!("{} / {}", lines[i].trim(), step[0].trim()));
            }
        }
        i = job_end;
    }
    bad
}

#[test]
fn every_release_step_that_stages_plugins_runs_with_prod() {
    for wf in ["os-app-release.yml", "rust-release.yml", "android-release.yml"] {
        let w = std::fs::read_to_string(repo_root().join(".github/workflows").join(wf)).unwrap();
        let bad = stage_steps_without_prod(&w);
        assert!(bad.is_empty(), "{wf}: these steps stage plugins without INFANTRY_BUILD_ENV: prod (they ship staging endpoints): {bad:?}");
    }
}

#[test]
fn the_stage_step_check_catches_a_step_without_prod() {
    // The 0.8.5 shape: prod on the build step only, not on the stage step.
    let broken = "jobs:\n  package:\n    runs-on: x\n    steps:\n      - name: Build\n        env: { INFANTRY_BUILD_ENV: prod }\n        run: cargo build\n      - name: Stage the plugins\n        run: bash rust/plugins/stage.sh out release\n";
    assert_eq!(stage_steps_without_prod(broken).len(), 1, "the 0.8.5 workflow shape must fail");
    let fixed = broken.replace("    steps:\n", "    env: { INFANTRY_BUILD_ENV: prod }\n    steps:\n");
    assert!(stage_steps_without_prod(&fixed).is_empty(), "job-level prod covers the stage step");
    let step_level = "jobs:\n  a:\n    steps:\n      - name: Stage\n        env: { INFANTRY_BUILD_ENV: prod }\n        run: bash src-tauri/scripts/stage-bundle.sh\n";
    assert!(stage_steps_without_prod(step_level).is_empty(), "step-level prod is enough");
}

/// DOCS-ENV-1: every step that stages plugins in a release workflow is followed by check-staged-endpoints.sh, which
/// reads every staged text file. It replaced a grep over module.json only (SITES-ENV-1), which let ui js/css/html and
/// md files through, so the old assertion on `--include=module.json` is gone with it.
#[test]
fn every_release_stage_is_followed_by_the_staged_endpoint_check() {
    const CHECK: &str = "scripts/check-staged-endpoints.sh";
    for (wf, stage) in [("os-app-release.yml", "rust/plugins/stage.sh"), ("rust-release.yml", "bash src-tauri/scripts/stage-bundle.sh")] {
        let w = std::fs::read_to_string(repo_root().join(".github/workflows").join(wf)).unwrap();
        let stages: Vec<usize> = w.match_indices(stage).map(|(i, _)| i).collect();
        assert!(!stages.is_empty(), "{wf} stages plugins");
        for (n, &at) in stages.iter().enumerate() {
            let end = stages.get(n + 1).copied().unwrap_or(w.len());
            assert!(w[at..end].contains(CHECK), "{wf}: the stage at byte {at} is not followed by {CHECK}");
        }
    }
}

fn check_dir(tag: &str, files: &[(&str, &[u8])]) -> bool {
    let dir = std::env::temp_dir().join(format!("docs-env-1-{}-{tag}", std::process::id()));
    let _ = std::fs::remove_dir_all(&dir);
    for (rel, body) in files {
        let p = dir.join(rel);
        std::fs::create_dir_all(p.parent().unwrap()).unwrap();
        std::fs::write(p, body).unwrap();
    }
    let git_bash = Path::new("C:/Program Files/Git/bin/bash.exe"); // not WSL's bash (see run)
    let ok = Command::new(if git_bash.is_file() { git_bash } else { Path::new("bash") }).current_dir(repo_root()).arg("scripts/check-staged-endpoints.sh").arg(&dir).output().expect("bash").status.success();
    let _ = std::fs::remove_dir_all(&dir);
    ok
}

#[test]
fn the_staged_endpoint_check_reads_every_text_file_and_skips_binaries() {
    assert!(check_dir("clean", &[("official/A/module.json", br#"{"daemon":{"endpoints":{"api":"https://api.example.com"}}}"#)]), "a prod staging passes");
    assert!(
        !check_dir("js", &[("official/A/module.json", b"{}"), ("official/A/ui/index.js", b"fetch('http://localhost:5173/x')")]),
        "a ui js with localhost fails (the module.json-only grep let it through)"
    );
    assert!(!check_dir("md", &[("official/A/README.md", b"see http://127.0.0.1:18953/v1")]), "any text file is read");
    assert!(!check_dir("staging", &[("official/A/module.json", br#"{"title":"Sites (staging)"}"#)]), "a staging label fails");
    assert!(
        check_dir("bin", &[("official/Infantry_Docs/daemon.component", b"\0asm\x01\0\0\0 curl http://127.0.0.1:18990/health \0\xff")]),
        "a binary is skipped (the Docs component embeds wiki prose that documents local addresses)"
    );
}
