// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
//! A child process in a pseudo-terminal: ConPTY on Windows (called directly), openpty elsewhere (portable-pty).
//!
//! RUST-SESS-1 findings (Windows 11 build 26300):
//! - portable-pty 0.9 passes `PSEUDOCONSOLE_INHERIT_CURSOR`: conhost then opens with a cursor query (`ESC[6n`) and waits for
//!   the reply. Through portable-pty even `cmd /c echo` never got past it; called directly, `cmd` did, but `claude.exe` never
//!   did (no output, no exit, `--version` included). Without the flag there is no handshake and both start at once.
//! - So the Windows side calls ConPTY itself (flags 0, `STARTF_USESTDHANDLES` with invalid handles so the child never inherits
//!   the runner's redirected stdio). A later cursor query from the CLI itself is still answered by the session from vt100.

use std::io::{Read, Write};
use std::path::PathBuf;

/// The terminal size a session starts with. Wide, so a CLI's lines rarely wrap.
pub const DEFAULT_ROWS: u16 = 40;
pub const DEFAULT_COLS: u16 = 160;

/// What to run.
#[derive(Debug, Clone)]
pub struct Command {
    pub program: String,
    pub args: Vec<String>,
    pub cwd: PathBuf,
    /// Applied after [env_remove].
    pub env: Vec<(String, String)>,
    /// Exact names, or prefixes ending in `*`, removed from the inherited environment.
    pub env_remove: Vec<String>,
}

pub struct PtyChild {
    pub process: Process,
    /// Dropping it closes the pseudo-console (the reader then sees EOF).
    pub console: Option<Console>,
    pub writer: Box<dyn Write + Send>,
    pub reader: Option<Box<dyn Read + Send>>,
    pub pid: u32,
}

/// Whether environment variable [name] is removed by [rules].
pub fn removed(name: &str, rules: &[String]) -> bool {
    rules.iter().any(|r| match r.strip_suffix('*') {
        Some(prefix) => name.to_ascii_uppercase().starts_with(&prefix.to_ascii_uppercase()),
        None => name.eq_ignore_ascii_case(r),
    })
}

/// The child's environment: the runner's own, minus [Command::env_remove], plus [Command::env].
pub fn environment(cmd: &Command) -> Vec<(String, String)> {
    let mut out: Vec<(String, String)> = std::env::vars_os()
        .filter_map(|(k, v)| Some((k.into_string().ok()?, v.into_string().ok()?)))
        .filter(|(k, _)| !removed(k, &cmd.env_remove))
        .filter(|(k, _)| !cmd.env.iter().any(|(n, _)| n.eq_ignore_ascii_case(k)))
        .collect();
    out.extend(cmd.env.iter().cloned());
    out
}

/// One argument quoted the way the MSVC runtime (and `CommandLineToArgvW`) splits it back.
pub fn quote_arg(arg: &str) -> String {
    if !arg.is_empty() && !arg.contains([' ', '\t', '\n', '\u{b}', '"']) {
        return arg.into();
    }
    let mut out = String::from("\"");
    let mut slashes = 0usize;
    for c in arg.chars() {
        match c {
            '\\' => slashes += 1,
            '"' => {
                out.push_str(&"\\".repeat(slashes * 2 + 1));
                out.push('"');
                slashes = 0;
            }
            _ => {
                out.push_str(&"\\".repeat(slashes));
                out.push(c);
                slashes = 0;
            }
        }
    }
    out.push_str(&"\\".repeat(slashes * 2));
    out.push('"');
    out
}

pub fn command_line(cmd: &Command) -> String {
    std::iter::once(cmd.program.as_str()).chain(cmd.args.iter().map(String::as_str)).map(quote_arg).collect::<Vec<_>>().join(" ")
}

#[cfg(windows)]
pub use win::{spawn, Console, Process};

#[cfg(windows)]
mod win {
    use super::{command_line, environment, Command, PtyChild};
    use std::fs::File;
    use std::os::windows::io::{FromRawHandle, RawHandle};
    use windows_sys::Win32::Foundation::{CloseHandle, HANDLE, INVALID_HANDLE_VALUE, WAIT_OBJECT_0};
    use windows_sys::Win32::System::Console::{ClosePseudoConsole, CreatePseudoConsole, COORD, HPCON};
    use windows_sys::Win32::System::Pipes::CreatePipe;
    use windows_sys::Win32::System::Threading::{
        CreateProcessW, DeleteProcThreadAttributeList, GetExitCodeProcess, InitializeProcThreadAttributeList, TerminateProcess,
        UpdateProcThreadAttribute, WaitForSingleObject, CREATE_UNICODE_ENVIRONMENT, EXTENDED_STARTUPINFO_PRESENT,
        LPPROC_THREAD_ATTRIBUTE_LIST, PROCESS_INFORMATION, PROC_THREAD_ATTRIBUTE_PSEUDOCONSOLE, STARTF_USESTDHANDLES, STARTUPINFOEXW,
    };

    /// The pseudo-console; dropping it closes it (the child's console goes away, the reader sees EOF).
    pub struct Console(HPCON);
    // SAFETY: an HPCON is a kernel handle; ConPTY calls on it are thread-safe.
    unsafe impl Send for Console {}

    impl Drop for Console {
        fn drop(&mut self) {
            unsafe { ClosePseudoConsole(self.0) };
        }
    }

    pub struct Process {
        handle: HANDLE,
    }
    // SAFETY: a process handle is usable from any thread.
    unsafe impl Send for Process {}
    unsafe impl Sync for Process {}

    impl Process {
        /// The exit code once the process ended.
        pub fn try_wait(&self) -> Option<i32> {
            unsafe {
                if WaitForSingleObject(self.handle, 0) != WAIT_OBJECT_0 {
                    return None;
                }
                let mut code = 0u32;
                GetExitCodeProcess(self.handle, &mut code);
                Some(code as i32)
            }
        }

        pub fn kill(&self) {
            unsafe { TerminateProcess(self.handle, 1) };
        }
    }

    impl Drop for Process {
        fn drop(&mut self) {
            unsafe { CloseHandle(self.handle) };
        }
    }

    fn wide(s: &str) -> Vec<u16> {
        s.encode_utf16().chain([0]).collect()
    }

    fn last_error(what: &str) -> std::io::Error {
        let e = std::io::Error::last_os_error();
        std::io::Error::new(e.kind(), format!("{what}: {e}"))
    }

    /// `name=value\0...\0\0`, sorted by name without case as CreateProcess wants it.
    fn env_block(cmd: &Command) -> Vec<u16> {
        let mut vars = environment(cmd);
        vars.sort_by_key(|(k, _)| k.to_uppercase());
        let mut block: Vec<u16> = Vec::new();
        for (k, v) in vars {
            block.extend(format!("{k}={v}").encode_utf16());
            block.push(0);
        }
        block.push(0);
        block
    }

    pub fn spawn(cmd: &Command, rows: u16, cols: u16) -> std::io::Result<PtyChild> {
        unsafe {
            let (mut in_r, mut in_w, mut out_r, mut out_w): (HANDLE, HANDLE, HANDLE, HANDLE) =
                (std::ptr::null_mut(), std::ptr::null_mut(), std::ptr::null_mut(), std::ptr::null_mut());
            if CreatePipe(&mut in_r, &mut in_w, std::ptr::null(), 0) == 0 {
                return Err(last_error("CreatePipe"));
            }
            if CreatePipe(&mut out_r, &mut out_w, std::ptr::null(), 0) == 0 {
                CloseHandle(in_r);
                CloseHandle(in_w);
                return Err(last_error("CreatePipe"));
            }
            // The runner's ends become Files (closed on drop); conhost owns its ends once the console exists.
            let writer = File::from_raw_handle(in_w as RawHandle);
            let reader = File::from_raw_handle(out_r as RawHandle);
            let mut hpc: HPCON = 0;
            let hr = CreatePseudoConsole(COORD { X: cols as i16, Y: rows as i16 }, in_r, out_w, 0, &mut hpc);
            CloseHandle(in_r);
            CloseHandle(out_w);
            if hr != 0 {
                return Err(std::io::Error::other(format!("CreatePseudoConsole failed: HRESULT {hr:#x}")));
            }
            let console = Console(hpc);

            let mut size = 0usize;
            InitializeProcThreadAttributeList(std::ptr::null_mut(), 1, 0, &mut size);
            let mut attrs = vec![0u8; size];
            let list = attrs.as_mut_ptr() as LPPROC_THREAD_ATTRIBUTE_LIST;
            if InitializeProcThreadAttributeList(list, 1, 0, &mut size) == 0 {
                return Err(last_error("InitializeProcThreadAttributeList"));
            }
            let set = UpdateProcThreadAttribute(
                list,
                0,
                PROC_THREAD_ATTRIBUTE_PSEUDOCONSOLE as usize,
                hpc as *const _,
                std::mem::size_of::<HPCON>(),
                std::ptr::null_mut(),
                std::ptr::null(),
            );
            if set == 0 {
                DeleteProcThreadAttributeList(list);
                return Err(last_error("UpdateProcThreadAttribute"));
            }
            let mut si: STARTUPINFOEXW = std::mem::zeroed();
            si.StartupInfo.cb = std::mem::size_of::<STARTUPINFOEXW>() as u32;
            si.lpAttributeList = list;
            // The child must not inherit the runner's own (possibly redirected) stdio: it binds to the pseudo-console.
            si.StartupInfo.dwFlags = STARTF_USESTDHANDLES;
            si.StartupInfo.hStdInput = INVALID_HANDLE_VALUE;
            si.StartupInfo.hStdOutput = INVALID_HANDLE_VALUE;
            si.StartupInfo.hStdError = INVALID_HANDLE_VALUE;
            let mut line = wide(&command_line(cmd));
            let mut env = env_block(cmd);
            let cwd = wide(&cmd.cwd.to_string_lossy());
            let mut pi: PROCESS_INFORMATION = std::mem::zeroed();
            let ok = CreateProcessW(
                std::ptr::null(),
                line.as_mut_ptr(),
                std::ptr::null(),
                std::ptr::null(),
                0,
                EXTENDED_STARTUPINFO_PRESENT | CREATE_UNICODE_ENVIRONMENT,
                env.as_mut_ptr() as *const _,
                cwd.as_ptr(),
                &si.StartupInfo,
                &mut pi,
            );
            let err = (ok == 0).then(|| last_error(&format!("CreateProcessW `{}`", cmd.program)));
            DeleteProcThreadAttributeList(list);
            if let Some(e) = err {
                return Err(e);
            }
            CloseHandle(pi.hThread);
            Ok(PtyChild {
                process: Process { handle: pi.hProcess },
                console: Some(console),
                writer: Box::new(writer),
                reader: Some(Box::new(reader)),
                pid: pi.dwProcessId,
            })
        }
    }
}

#[cfg(not(windows))]
pub use unix::{spawn, Console, Process};

#[cfg(not(windows))]
mod unix {
    use super::{environment, Command, PtyChild};
    use portable_pty::{native_pty_system, Child, CommandBuilder, MasterPty, PtySize};
    use std::sync::Mutex;

    pub struct Console(#[allow(dead_code)] Box<dyn MasterPty + Send>);

    pub struct Process(Mutex<Box<dyn Child + Send + Sync>>);

    impl Process {
        pub fn try_wait(&self) -> Option<i32> {
            self.0.lock().unwrap().try_wait().ok().flatten().map(|s| s.exit_code() as i32)
        }

        pub fn kill(&self) {
            let _ = self.0.lock().unwrap().kill();
        }
    }

    pub fn spawn(cmd: &Command, rows: u16, cols: u16) -> std::io::Result<PtyChild> {
        let e = |e: anyhow_like::E| std::io::Error::other(e.0);
        let pair = native_pty_system().openpty(PtySize { rows, cols, pixel_width: 0, pixel_height: 0 }).map_err(|x| e(x.into()))?;
        let mut b = CommandBuilder::new(&cmd.program);
        b.args(&cmd.args);
        b.cwd(&cmd.cwd);
        b.env_clear();
        for (k, v) in environment(cmd) {
            b.env(k, v);
        }
        let child = pair.slave.spawn_command(b).map_err(|x| e(x.into()))?;
        drop(pair.slave);
        let pid = child.process_id().unwrap_or(0);
        let reader = pair.master.try_clone_reader().map_err(|x| e(x.into()))?;
        let writer = pair.master.take_writer().map_err(|x| e(x.into()))?;
        Ok(PtyChild { process: Process(Mutex::new(child)), console: Some(Console(pair.master)), writer, reader: Some(reader), pid })
    }

    mod anyhow_like {
        pub struct E(pub String);
        impl<T: std::fmt::Display> From<T> for E {
            fn from(t: T) -> Self {
                E(t.to_string())
            }
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn env_rules_match_names_and_prefixes() {
        let rules = vec!["CLAUDECODE".to_string(), "INFANTRY_*".to_string()];
        assert!(removed("CLAUDECODE", &rules));
        assert!(removed("infantry_session_id", &rules));
        assert!(!removed("CLAUDECODE_X", &rules));
        assert!(!removed("PATH", &rules));
    }

    #[test]
    fn arguments_are_quoted_as_the_msvc_runtime_splits_them() {
        assert_eq!(quote_arg("plain"), "plain");
        assert_eq!(quote_arg(""), "\"\"");
        assert_eq!(quote_arg("a b"), "\"a b\"");
        assert_eq!(quote_arg(r#"Bash(infantry:*),mcp__x"#), r#"Bash(infantry:*),mcp__x"#);
        assert_eq!(quote_arg(r#"say "hi""#), r#""say \"hi\"""#);
        assert_eq!(quote_arg(r"C:\dir with space\"), r#""C:\dir with space\\""#);
        assert_eq!(quote_arg(r"C:\x\y"), r"C:\x\y");
    }

    #[test]
    fn the_environment_drops_removed_names_and_overrides_by_name() {
        let cmd = Command {
            program: "x".into(),
            args: vec![],
            cwd: ".".into(),
            env: vec![("PATH_EXTRA_TEST".into(), "1".into())],
            env_remove: vec!["*".into()],
        };
        assert_eq!(environment(&cmd), vec![("PATH_EXTRA_TEST".to_string(), "1".to_string())]);
    }

    #[cfg(windows)]
    #[test]
    fn a_child_in_conpty_prints_through_the_pseudo_console() {
        let cmd = Command {
            program: r"C:\Windows\System32\cmd.exe".into(),
            args: vec!["/c".into(), "echo".into(), "pty-ok".into()],
            cwd: std::env::temp_dir(),
            env: vec![],
            env_remove: vec![],
        };
        let mut c = spawn(&cmd, 24, 80).unwrap();
        let mut reader = c.reader.take().unwrap();
        let mut writer = c.writer;
        let (tx, rx) = std::sync::mpsc::channel();
        std::thread::spawn(move || {
            let mut buf = [0u8; 4096];
            let mut all = Vec::new();
            while let Ok(n) = reader.read(&mut buf) {
                if n == 0 {
                    break;
                }
                all.extend_from_slice(&buf[..n]);
                if all.windows(4).any(|w| w == b"\x1b[6n") {
                    all.clear();
                    let _ = tx.send(None);
                }
                if String::from_utf8_lossy(&all).contains("pty-ok") {
                    let _ = tx.send(Some(()));
                    return;
                }
            }
        });
        let deadline = std::time::Duration::from_secs(10);
        loop {
            match rx.recv_timeout(deadline).expect("the child printed") {
                None => {
                    writer.write_all(b"\x1b[1;1R").unwrap();
                    writer.flush().unwrap();
                }
                Some(()) => break,
            }
        }
        let start = std::time::Instant::now();
        while c.process.try_wait().is_none() && start.elapsed() < deadline {
            std::thread::sleep(std::time::Duration::from_millis(20));
        }
        assert_eq!(c.process.try_wait(), Some(0));
        drop(c.console.take());
    }
}
