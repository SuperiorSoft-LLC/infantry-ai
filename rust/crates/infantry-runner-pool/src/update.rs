// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
//! RR-11: Update runner. A runner in a Docker container on this machine is recreated on the app's image tag with the SAME
//! name, volumes, published ports and env (so the fingerprint and bearer survive: same volume, no re-pair). The old
//! container is renamed aside and put back when the new one does not answer in time.
use serde_json::Value;
use std::time::{Duration, Instant};

/// `limits.runners.updateWaitSec`: how long the new container has to answer hello before the old one is restored.
pub const UPDATE_WAIT_KEY: &str = "limits.runners.updateWaitSec";
pub const DEFAULT_UPDATE_WAIT_SEC: u64 = 60;

/// What a recreate needs, read from `docker inspect` of the old container.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct RecreatePlan {
    pub name: String,
    /// Image reference of the new container: the old one's repository at the app's tag.
    pub image: String,
    /// `-v` values: `source:target[:ro]` (a named volume's source is its name).
    pub volumes: Vec<String>,
    /// `-p` values: `[ip:]host:container/proto`.
    pub ports: Vec<String>,
    /// `NAME=value` lines, passed through `--env-file` (never argv: they may hold secrets).
    pub env: Vec<String>,
    pub restart: Option<String>,
    /// The command arguments after the image (the old `Config.Cmd`).
    pub cmd: Vec<String>,
}

/// The repository part of an image reference (`host:5000/a/b:tag@sha` -> `host:5000/a/b`).
pub fn image_repo(image: &str) -> &str {
    let image = image.split('@').next().unwrap_or(image);
    match image.rfind(':') {
        Some(i) if !image[i..].contains('/') => &image[..i],
        _ => image,
    }
}

fn strs(v: &Value) -> Vec<String> {
    v.as_array().map(|a| a.iter().filter_map(|s| s.as_str().map(str::to_string)).collect()).unwrap_or_default()
}

/// Builds the plan from one `docker inspect` object.
pub fn plan_from_inspect(inspect: &Value, tag: &str) -> Result<RecreatePlan, String> {
    let name = inspect["Name"].as_str().unwrap_or("").trim_start_matches('/').to_string();
    if name.is_empty() {
        return Err("docker inspect has no container name".into());
    }
    let image = inspect["Config"]["Image"].as_str().filter(|s| !s.is_empty()).ok_or("docker inspect has no image")?;
    let mut volumes = Vec::new();
    for m in inspect["Mounts"].as_array().map(Vec::as_slice).unwrap_or(&[]) {
        let (kind, dest) = (m["Type"].as_str().unwrap_or(""), m["Destination"].as_str().unwrap_or(""));
        let source = if kind == "volume" { m["Name"].as_str() } else { m["Source"].as_str() }.unwrap_or("");
        if source.is_empty() || dest.is_empty() {
            continue;
        }
        let ro = if m["RW"].as_bool() == Some(false) { ":ro" } else { "" };
        volumes.push(format!("{source}:{dest}{ro}"));
    }
    let mut ports = Vec::new();
    if let Some(map) = inspect["HostConfig"]["PortBindings"].as_object() {
        for (container, binds) in map {
            for b in binds.as_array().map(Vec::as_slice).unwrap_or(&[]) {
                let (ip, host) = (b["HostIp"].as_str().unwrap_or(""), b["HostPort"].as_str().unwrap_or(""));
                ports.push(if ip.is_empty() { format!("{host}:{container}") } else { format!("{ip}:{host}:{container}") });
            }
        }
    }
    // PATH, HOME and HOSTNAME come from the image and the engine; the new image brings its own.
    let env = strs(&inspect["Config"]["Env"]).into_iter().filter(|e| !e.starts_with("PATH=") && !e.starts_with("HOSTNAME=") && !e.starts_with("HOME=")).collect();
    let restart = inspect["HostConfig"]["RestartPolicy"]["Name"].as_str().filter(|r| !r.is_empty() && *r != "no").map(str::to_string);
    Ok(RecreatePlan { name, image: format!("{}:{tag}", image_repo(image)), volumes, ports, env, restart, cmd: strs(&inspect["Config"]["Cmd"]) })
}

impl RecreatePlan {
    /// The `docker run` arguments (after `docker`), env through `env_file`.
    pub fn run_args(&self, env_file: Option<&str>) -> Vec<String> {
        let mut a: Vec<String> = vec!["run".into(), "-d".into(), "--name".into(), self.name.clone()];
        if let Some(r) = &self.restart {
            a.extend(["--restart".into(), r.clone()]);
        }
        for v in &self.volumes {
            a.extend(["-v".into(), v.clone()]);
        }
        for p in &self.ports {
            a.extend(["-p".into(), p.clone()]);
        }
        if let Some(f) = env_file {
            a.extend(["--env-file".into(), f.into()]);
        }
        a.push(self.image.clone());
        a.extend(self.cmd.iter().cloned());
        a
    }
}

/// The docker engine as the update uses it: one call = `docker <args>`; Ok(stdout) or Err(stderr).
pub trait Docker: Send + Sync {
    fn run(&self, args: &[String]) -> Result<String, String>;
}

/// The real `docker` (through infantry-proc: windowless).
pub struct CliDocker;

impl Docker for CliDocker {
    fn run(&self, args: &[String]) -> Result<String, String> {
        let out = infantry_proc::command("docker").args(args).output().map_err(|e| format!("docker did not start: {e}"))?;
        if out.status.success() {
            Ok(String::from_utf8_lossy(&out.stdout).trim().to_string())
        } else {
            Err(String::from_utf8_lossy(&out.stderr).trim().to_string())
        }
    }
}

fn d(args: &[&str]) -> Vec<String> {
    args.iter().map(|s| s.to_string()).collect()
}

/// The container that publishes host `port` on this machine, as its inspect object.
pub fn find_container(docker: &dyn Docker, port: u16) -> Result<Option<Value>, String> {
    let ids = docker.run(&d(&["ps", "-aq"]))?;
    let ids: Vec<String> = ids.split_whitespace().map(str::to_string).collect();
    if ids.is_empty() {
        return Ok(None);
    }
    let mut args = d(&["inspect"]);
    args.extend(ids);
    let all: Value = serde_json::from_str(&docker.run(&args)?).map_err(|e| format!("docker inspect unreadable: {e}"))?;
    let want = port.to_string();
    Ok(all.as_array().and_then(|a| {
        a.iter().find(|c| c["HostConfig"]["PortBindings"].as_object().is_some_and(|m| m.values().any(|b| b.as_array().is_some_and(|b| b.iter().any(|x| x["HostPort"].as_str() == Some(want.as_str())))))).cloned()
    }))
}

/// Recreates the container; `answers` says whether the new one answers hello (polled until `wait`). Steps are reported to
/// `progress`. Returns the new image on success. On any failure the new container is removed and the old one restored.
pub fn recreate(docker: &dyn Docker, plan: &RecreatePlan, env_file: Option<&str>, wait: Duration, answers: &dyn Fn() -> bool, progress: &dyn Fn(&str)) -> Result<String, String> {
    let aside = format!("{}-old-{}", plan.name, std::process::id());
    progress(&format!("pulling {}", plan.image));
    // A local-only tag has nothing to pull: a failed pull is fine when the image is already here.
    if let Err(e) = docker.run(&d(&["pull", &plan.image])) {
        if docker.run(&d(&["image", "inspect", &plan.image])).is_err() {
            return Err(format!("image {} is not here and could not be pulled: {e}", plan.image));
        }
    }
    progress("stopping the old container");
    docker.run(&d(&["stop", &plan.name]))?;
    docker.run(&d(&["rename", &plan.name, &aside]))?;
    let restore = |why: String| -> String {
        let _ = docker.run(&d(&["rm", "-f", &plan.name]));
        let back = docker.run(&d(&["rename", &aside, &plan.name])).and_then(|_| docker.run(&d(&["start", &plan.name])));
        match back {
            Ok(_) => format!("{why}; the old runner was restored"),
            Err(e) => format!("{why}; restoring the old runner failed too: {e} (it is the container '{aside}')"),
        }
    };
    progress("starting the new container");
    if let Err(e) = docker.run(&plan.run_args(env_file)) {
        return Err(restore(format!("the new container did not start: {e}")));
    }
    progress("waiting for the new runner to answer");
    let end = Instant::now() + wait;
    loop {
        if answers() {
            break;
        }
        if Instant::now() >= end {
            return Err(restore(format!("the new runner did not answer in {} s", wait.as_secs())));
        }
        std::thread::sleep(Duration::from_millis(500));
    }
    progress("removing the old container");
    let _ = docker.run(&d(&["rm", "-f", &aside]));
    Ok(plan.image.clone())
}

#[cfg(test)]
mod tests {
    use super::*;
    use serde_json::json;
    use std::sync::Mutex;

    fn fixture() -> Value {
        json!({"Name": "/infantry-test-runner", "Config": {"Image": "infantry-runner:0.4.6", "Cmd": ["--mode", "runner"], "Env": ["PATH=/usr/bin", "INFANTRY_STATE=/data", "HOSTNAME=abc"]},
          "Mounts": [{"Type": "volume", "Name": "runner-data", "Source": "/var/lib/docker/volumes/runner-data/_data", "Destination": "/data", "RW": true},
                     {"Type": "bind", "Source": "/srv/work", "Destination": "/work", "RW": false}],
          "HostConfig": {"PortBindings": {"18950/tcp": [{"HostIp": "", "HostPort": "18951"}]}, "RestartPolicy": {"Name": "unless-stopped"}}})
    }

    #[test]
    fn the_plan_keeps_name_volumes_ports_env_and_moves_the_tag() {
        let p = plan_from_inspect(&fixture(), "0.4.7").unwrap();
        assert_eq!((p.name.as_str(), p.image.as_str()), ("infantry-test-runner", "infantry-runner:0.4.7"));
        assert_eq!(p.volumes, ["runner-data:/data", "/srv/work:/work:ro"]);
        assert_eq!(p.ports, ["18951:18950/tcp"]);
        assert_eq!(p.env, ["INFANTRY_STATE=/data"]);
        let a = p.run_args(Some("e.env"));
        assert!(a.join(" ").contains("--name infantry-test-runner --restart unless-stopped -v runner-data:/data -v /srv/work:/work:ro -p 18951:18950/tcp --env-file e.env infantry-runner:0.4.7 --mode runner"), "{a:?}");
        assert!(!a.join(" ").contains("INFANTRY_STATE"), "env never goes on argv");
        assert_eq!(image_repo("host:5000/a/b:1.2"), "host:5000/a/b");
        assert_eq!(image_repo("host:5000/a/b"), "host:5000/a/b");
    }

    struct Fake(Mutex<Vec<String>>, &'static str);
    impl Docker for Fake {
        fn run(&self, args: &[String]) -> Result<String, String> {
            let line = args.join(" ");
            self.0.lock().unwrap().push(line.clone());
            if self.1 == "start-fails" && line.starts_with("run -d") {
                return Err("boom".into());
            }
            Ok(String::new())
        }
    }

    #[test]
    fn a_new_container_that_never_answers_rolls_back() {
        let plan = plan_from_inspect(&fixture(), "0.4.7").unwrap();
        let fake = Fake(Mutex::new(vec![]), "ok");
        let err = recreate(&fake, &plan, None, Duration::from_millis(600), &|| false, &|_| {}).unwrap_err();
        assert!(err.contains("did not answer") && err.contains("restored"), "{err}");
        let log = fake.0.lock().unwrap().join("\n");
        assert!(log.contains("rm -f infantry-test-runner") && log.contains(&format!("rename infantry-test-runner-old-{} infantry-test-runner", std::process::id())) && log.trim_end().ends_with("start infantry-test-runner"), "{log}");
    }

    #[test]
    fn a_new_container_that_does_not_start_rolls_back_and_one_that_answers_removes_the_old() {
        let plan = plan_from_inspect(&fixture(), "0.4.7").unwrap();
        let fake = Fake(Mutex::new(vec![]), "start-fails");
        assert!(recreate(&fake, &plan, None, Duration::from_secs(1), &|| true, &|_| {}).unwrap_err().contains("restored"));
        let fake = Fake(Mutex::new(vec![]), "ok");
        assert_eq!(recreate(&fake, &plan, None, Duration::from_secs(1), &|| true, &|_| {}).unwrap(), "infantry-runner:0.4.7");
        assert!(fake.0.lock().unwrap().last().unwrap().starts_with("rm -f infantry-test-runner-old-"));
    }
}
