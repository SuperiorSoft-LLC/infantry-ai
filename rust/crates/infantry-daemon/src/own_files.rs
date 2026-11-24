// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
//! Where this program's own files are. Under BattalionOS the daemon is a library run by the OS's worker, so
//! `current_exe()` is the OS's program, not ours: nothing may use it for its own files. Every such place goes through
//! here: the package folder the OS gave (`BATTALION_APP_DIR`), else the folder of the running program.

use std::path::{Path, PathBuf};
use std::sync::OnceLock;

/// The OS gives the package folder of the running version here (contract v1).
pub const APP_DIR_ENV: &str = "BATTALION_APP_DIR";

static LIBRARY: OnceLock<PathBuf> = OnceLock::new();

/// Under the OS: the library file this daemon runs from (set once by the library entry).
pub fn set_library(path: PathBuf) {
    let _ = LIBRARY.set(path);
}

/// The package folder given by the OS, else the folder beside the running program. Plugins, the bundled sources and
/// the CLI are looked up from it.
pub fn package_dir() -> Option<PathBuf> {
    package_dir_from(std::env::var_os(APP_DIR_ENV), std::env::current_exe().ok().as_deref())
}

/// Where the program files (the CLI next to the daemon) are: `<package>/bin` under the OS, else beside the running program.
pub fn bin_dir() -> Option<PathBuf> {
    if let Some(d) = os_dir(std::env::var_os(APP_DIR_ENV)) {
        return Some(d.join("bin"));
    }
    std::env::current_exe().ok()?.parent().map(Path::to_path_buf)
}

/// The file this daemon is: the library under the OS, else the running program. Its name and bytes identify the build.
pub fn own_binary() -> Option<PathBuf> {
    if os_dir(std::env::var_os(APP_DIR_ENV)).is_some() {
        if let Some(l) = LIBRARY.get() {
            return Some(l.clone());
        }
    }
    std::env::current_exe().ok()
}

fn os_dir(v: Option<std::ffi::OsString>) -> Option<PathBuf> {
    v.filter(|v| !v.is_empty()).map(PathBuf::from)
}

fn package_dir_from(app_dir: Option<std::ffi::OsString>, exe: Option<&Path>) -> Option<PathBuf> {
    os_dir(app_dir).or_else(|| exe?.parent().map(Path::to_path_buf))
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn the_os_package_folder_wins_else_the_program_folder() {
        let exe = PathBuf::from("C:/x/bin/infantry-daemon.exe");
        assert_eq!(package_dir_from(Some("C:/pkg/1.0.0".into()), Some(&exe)), Some(PathBuf::from("C:/pkg/1.0.0")));
        assert_eq!(package_dir_from(Some("".into()), Some(&exe)), Some(PathBuf::from("C:/x/bin")));
        assert_eq!(package_dir_from(None, Some(&exe)), Some(PathBuf::from("C:/x/bin")));
        assert_eq!(package_dir_from(None, None), None);
    }
}
