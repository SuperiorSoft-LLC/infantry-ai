// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
//! The ONE spawn seam. A console program started from a GUI/daemon process on Windows gets its own visible console window
//! unless `CREATE_NO_WINDOW` is set. Build every `std::process::Command` with [`command`] (or call [`NoWindow::no_window`]
//! on one built elsewhere); `NoSpawnWindowGuard` in this crate's tests fails when a spawn bypasses it. Stdio pipes are
//! untouched (this is not `DETACHED_PROCESS`).

pub mod user_path;

use std::ffi::OsStr;
use std::process::Command;

/// `CREATE_NO_WINDOW` (winbase.h).
pub const CREATE_NO_WINDOW: u32 = 0x0800_0000;

/// Marks a command windowless on Windows; a no-op elsewhere.
pub trait NoWindow {
    fn no_window(&mut self) -> &mut Self;
}

impl NoWindow for Command {
    fn no_window(&mut self) -> &mut Self {
        #[cfg(windows)]
        {
            use std::os::windows::process::CommandExt;
            self.creation_flags(CREATE_NO_WINDOW);
        }
        self
    }
}

/// A new windowless command.
pub fn command<S: AsRef<OsStr>>(program: S) -> Command {
    let mut c = Command::new(program);
    c.no_window();
    c
}

/// A child that is killed (by its own handle, never by image name) and reaped when this guard drops: a test helper that
/// starts a daemon holds one so the daemon cannot outlive its test, even when the test panics.
pub struct KillOnDrop(pub std::process::Child);

impl Drop for KillOnDrop {
    fn drop(&mut self) {
        let _ = self.0.kill();
        let _ = self.0.wait();
    }
}

/// Stops the process tree of `pid` by pid (Windows `taskkill /PID n /T /F`, else `kill -9`). Never by image name.
pub fn kill_pid(pid: u32) -> bool {
    #[cfg(windows)]
    let mut c = command("taskkill");
    #[cfg(windows)]
    c.args(["/PID", &pid.to_string(), "/T", "/F"]);
    #[cfg(not(windows))]
    let mut c = command("kill");
    #[cfg(not(windows))]
    c.args(["-9", &pid.to_string()]);
    c.stdout(std::process::Stdio::null()).stderr(std::process::Stdio::null()).status().map(|s| s.success()).unwrap_or(false)
}

/// Creation flags for a spawn that also needs extra flags (a priority class, a new process group): OR them with the no-window bit.
pub const fn with_no_window(extra: u32) -> u32 {
    extra | CREATE_NO_WINDOW
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn kill_on_drop_ends_the_child() {
        #[cfg(windows)]
        let c = command("ping").args(["-n", "60", "127.0.0.1"]).stdout(std::process::Stdio::null()).spawn().unwrap();
        #[cfg(not(windows))]
        let c = command("sleep").arg("60").spawn().unwrap();
        let g = KillOnDrop(c);
        drop(g);
    }

    #[test]
    fn flag_value_is_create_no_window() {
        assert_eq!(CREATE_NO_WINDOW, 0x0800_0000);
        assert_eq!(with_no_window(0x200), 0x0800_0200);
    }

    #[test]
    fn piped_output_still_works() {
        let out = if cfg!(windows) { command("cmd").args(["/C", "echo hi"]).output() } else { command("sh").args(["-c", "echo hi"]).output() }.unwrap();
        assert!(String::from_utf8_lossy(&out.stdout).contains("hi"));
    }
}
