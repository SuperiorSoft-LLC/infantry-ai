// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
//! SVC-8: the hosting plugin, staged the way the release stages it (rust/plugins/stage-external.sh, prod), loads in a
//! daemon that starts from a clean state dir and answers its Sites frame with no manual install, and a later stage (an update of an
//! existing install) leaves the state dir, where settings and the device key
//! live, alone.
//!
//! Its own test binary: it points `INFANTRY_PLUGINS_DIR` at a temp folder, a process-wide setting. It needs a checkout of the
//! infantry-plugins repo (`INFANTRY_PLUGINS_REPO`, else the sibling folder) and skips, saying so, when there is none.
use infantry_daemon::harness::stub::Stub;
use infantry_daemon::sessions::{Config, Daemon};
use infantry_protocol::Envelope;
use std::path::{Path, PathBuf};
use std::process::Command;
use std::sync::Arc;

fn plugins_repo() -> Option<PathBuf> {
    let root = Path::new(env!("CARGO_MANIFEST_DIR")).join("../../..");
    let p = std::env::var("INFANTRY_PLUGINS_REPO").map(PathBuf::from).unwrap_or_else(|_| root.join("../infantry-plugins"));
    p.join("official/Infantry_Sites/stage.sh").is_file().then_some(p)
}

fn stage(out: &Path, repo: &Path) {
    let git_bash = Path::new("C:/Program Files/Git/bin/bash.exe");
    let root = Path::new(env!("CARGO_MANIFEST_DIR")).join("../../plugins");
    let o = Command::new(if git_bash.is_file() { git_bash } else { Path::new("bash") })
        .current_dir(&root)
        .arg("stage-external.sh")
        .arg(out)
        .env("INFANTRY_PLUGINS_REPO", repo)
        .env("INFANTRY_BUILD_ENV", "prod")
        .output()
        .expect("bash");
    assert!(o.status.success(), "stage-external failed: {}", String::from_utf8_lossy(&o.stderr));
}

#[tokio::test(flavor = "multi_thread")]
async fn the_hosting_plugin_loads_from_a_clean_state_dir_and_survives_a_restage() {
    let Some(repo) = plugins_repo() else {
        eprintln!("SKIP: no infantry-plugins checkout (set INFANTRY_PLUGINS_REPO)");
        return;
    };
    let dir = std::env::temp_dir().join(format!("infantry-svc8-{}", uuid::Uuid::new_v4()));
    let (state, plugins) = (dir.join("state"), dir.join("plugins"));
    std::fs::create_dir_all(&state).unwrap();
    stage(&plugins, &repo);
    let manifest = std::fs::read_to_string(plugins.join("official/Infantry_Sites/module.json")).unwrap();
    assert!(manifest.contains("https://superiorsoftllc.tech/api/superiorsoft/auth/oauth/device/code"), "the prod endpoints are rendered");
    assert!(!manifest.contains("127.0.0.1:18953") || manifest.contains("\"dev\""), "only the endpoints table names dev");
    assert!(plugins.join("official/Infantry_Sites/bin").read_dir().unwrap().next().is_some(), "the sidecar binary is staged");

    std::env::set_var("INFANTRY_PLUGINS_DIR", &plugins);
    let daemon = Daemon::new(Arc::new(Stub::default()), Config::new(state.clone(), dir.clone())).unwrap();
    let c = infantry_daemon::compose::build(&daemon, &state).unwrap();
    let r = c.request(&Envelope::new("sites_list", "{}"), true).expect("sites_list is answered by the staged plugin");
    assert_eq!(r[0].r#type, "sites_list", "answer: {} {}", r[0].r#type, r[0].data);
    assert!(r[0].data.contains("\"state\""), "the panel answer carries a state: {}", r[0].data);

    // An update stages over the same plugins folder; the state dir (settings, the device key) is not part of it.
    std::fs::write(state.join("owner-state.marker"), "kept").unwrap();
    // (Windows will not replace a running sidecar's exe: the installer closes the app first, so the test stages a copy.)
    let again = dir.join("plugins-update");
    stage(&again, &repo);
    assert_eq!(std::fs::read_to_string(state.join("owner-state.marker")).unwrap(), "kept");
    assert!(again.join("official/Infantry_Sites/module.json").is_file());
    drop(c);
    let _ = std::fs::remove_dir_all(&dir);
}
