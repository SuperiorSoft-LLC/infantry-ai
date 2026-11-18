// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
//! Finding the real program behind a CLI installed with npm, through a [`Probe`] (no process started).
//!
//! On Windows npm installs `<name>.cmd` shims. Spawning the shim puts `cmd.exe` between the host and the CLI (argument
//! quoting rules, one more process in the tree), so a harness prefers what the shim runs: a native `.exe`
//! (`opencode-ai/bin/opencode.exe`) or `node <cli.js>` (pi). The shim's own last line names it as `"%dp0%\..."`.

use crate::Probe;
use std::path::{Path, PathBuf};

/// Where [name] is on the PATH the probe sees: the first existing `<dir>/<name><ext>` (Windows: `.cmd`, `.exe`, none).
pub fn find_on_path(probe: &dyn Probe, name: &str) -> Option<PathBuf> {
    let path = probe.env("PATH")?;
    let exts: &[&str] = if cfg!(windows) { &[".cmd", ".exe", ""] } else { &[""] };
    std::env::split_paths(&path)
        .flat_map(|d| exts.iter().map(move |e| d.join(format!("{name}{e}"))))
        .find(|p| probe.exists(p))
}

/// Whether [program] (a name or a path) can be found.
pub fn installed(probe: &dyn Probe, program: &str) -> bool {
    probe.exists(Path::new(program)) || find_on_path(probe, program).is_some()
}

/// The file an npm `.cmd` shim runs (`"%dp0%\node_modules\...\cli.js"`), resolved against the shim's folder.
pub fn shim_target(shim: &Path, text: &str) -> Option<PathBuf> {
    let dir = shim.parent()?;
    text.lines().rev().find_map(|line| {
        let start = line.find("\"%dp0%\\")?;
        let rest = &line[start + 7..];
        let end = rest.find('"')?;
        let rel = &rest[..end];
        let lower = rel.to_ascii_lowercase();
        (lower.ends_with(".js") || lower.ends_with(".exe") || lower.ends_with(".mjs") || lower.ends_with(".cjs"))
            .then(|| rel.split('\\').fold(dir.to_path_buf(), |p, seg| p.join(seg)))
    })
}

/// The argv prefix that starts CLI [name]: the shim's target (`[exe]` or `[node, script]`) when it is an npm shim whose
/// target exists, else `[name]` resolved by the runner. [node] is the node program for a script target.
pub fn launcher(probe: &dyn Probe, name: &str, node: &str) -> Vec<String> {
    let Some(found) = find_on_path(probe, name) else { return vec![name.to_string()] };
    let is_cmd = found.extension().is_some_and(|e| e.eq_ignore_ascii_case("cmd"));
    if is_cmd {
        if let Some(target) = probe.read_to_string(&found).and_then(|t| shim_target(&found, &t)).filter(|t| probe.exists(t)) {
            let t = target.to_string_lossy().into_owned();
            let script = target.extension().is_some_and(|e| !e.eq_ignore_ascii_case("exe"));
            return if script { vec![node.to_string(), t] } else { vec![t] };
        }
    }
    vec![found.to_string_lossy().into_owned()]
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn reads_npm_shims() {
        let shim = Path::new("C:/npm/pi.cmd");
        let pi = "endLocal & goto #_undefined_# 2>NUL || title %COMSPEC% & \"%_prog%\"  \"%dp0%\\node_modules\\@earendil-works\\pi-coding-agent\\dist\\bundle\\cli.js\" %*";
        assert_eq!(
            shim_target(shim, pi).unwrap(),
            Path::new("C:/npm").join("node_modules").join("@earendil-works").join("pi-coding-agent").join("dist").join("bundle").join("cli.js")
        );
        let oc = "CALL :find_dp0\n\"%dp0%\\node_modules\\opencode-ai\\bin\\opencode.exe\"   %*";
        assert!(shim_target(Path::new("C:/npm/opencode.cmd"), oc).unwrap().ends_with("opencode.exe"));
        assert!(shim_target(shim, "echo nothing").is_none());
    }
}
