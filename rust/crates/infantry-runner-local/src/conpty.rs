// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
//! A child in a Windows pseudo-console that can be RESIZED. `infantry_runner::pty` keeps its HPCON private (no resize), so
//! this is the same ConPTY call sequence (flags 0: no cursor-query handshake; the child never inherits the runner's stdio)
//! with three additions: [`Pty::resize`], a priority class, and a start suspended until the child is in its Job Object, so
//! no grandchild can escape the tree kill. Follow-up: give `infantry_runner::pty` a resize and drop this copy.

use infantry_runner::pty::{command_line, environment, Command};
use std::fs::File;
use std::os::windows::io::{FromRawHandle, RawHandle};
use std::sync::Mutex;
use windows_sys::Win32::Foundation::{CloseHandle, HANDLE, INVALID_HANDLE_VALUE, WAIT_OBJECT_0};
use windows_sys::Win32::System::Console::{ClosePseudoConsole, CreatePseudoConsole, ResizePseudoConsole, COORD, HPCON};
use windows_sys::Win32::System::Pipes::CreatePipe;
use windows_sys::Win32::System::Threading::{
    CreateProcessW, DeleteProcThreadAttributeList, GetExitCodeProcess, InitializeProcThreadAttributeList, ResumeThread,
    TerminateProcess, UpdateProcThreadAttribute, WaitForSingleObject, CREATE_SUSPENDED, CREATE_UNICODE_ENVIRONMENT,
    EXTENDED_STARTUPINFO_PRESENT, INFINITE, LPPROC_THREAD_ATTRIBUTE_LIST, PROCESS_INFORMATION, PROC_THREAD_ATTRIBUTE_PSEUDOCONSOLE,
    STARTF_USESTDHANDLES, STARTUPINFOEXW,
};

/// The pseudo-console. [`Pty::close`] (or drop) closes it: the reader then sees EOF.
pub struct Pty {
    hpc: Mutex<Option<HPCON>>,
}

// SAFETY: an HPCON is a kernel handle; ConPTY calls on it are thread-safe; the Mutex orders resize against close.
unsafe impl Send for Pty {}
unsafe impl Sync for Pty {}

impl Pty {
    pub fn resize(&self, rows: u16, cols: u16) -> std::io::Result<()> {
        let g = self.hpc.lock().unwrap();
        let Some(h) = *g else { return Err(std::io::Error::other("the pseudo-console is closed")) };
        let hr = unsafe { ResizePseudoConsole(h, COORD { X: cols as i16, Y: rows as i16 }) };
        if hr != 0 {
            return Err(std::io::Error::other(format!("ResizePseudoConsole failed: HRESULT {hr:#x}")));
        }
        Ok(())
    }

    pub fn close(&self) {
        if let Some(h) = self.hpc.lock().unwrap().take() {
            unsafe { ClosePseudoConsole(h) };
        }
    }
}

impl Drop for Pty {
    fn drop(&mut self) {
        self.close();
    }
}

/// The child process handle.
pub struct Proc {
    handle: HANDLE,
}

// SAFETY: a process handle is usable from any thread.
unsafe impl Send for Proc {}
unsafe impl Sync for Proc {}

impl Proc {
    /// Blocks until the process ends; its exit code.
    pub fn wait(&self) -> i32 {
        unsafe {
            WaitForSingleObject(self.handle, INFINITE);
            let mut code = 0u32;
            GetExitCodeProcess(self.handle, &mut code);
            code as i32
        }
    }

    pub fn exited(&self) -> bool {
        unsafe { WaitForSingleObject(self.handle, 0) == WAIT_OBJECT_0 }
    }

    pub fn kill(&self) {
        unsafe { TerminateProcess(self.handle, 1) };
    }
}

impl Drop for Proc {
    fn drop(&mut self) {
        unsafe { CloseHandle(self.handle) };
    }
}

pub struct Spawned {
    pub pty: Pty,
    pub process: Proc,
    pub pid: u32,
    pub writer: File,
    pub reader: File,
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

/// Starts [cmd] in a pseudo-console of [rows] x [cols] with creation flags [extra] (a priority class). The child is put into
/// a job by [before_resume] while still suspended.
pub fn spawn(cmd: &Command, rows: u16, cols: u16, extra: u32, before_resume: impl FnOnce(u32) -> std::io::Result<()>) -> std::io::Result<Spawned> {
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
        let writer = File::from_raw_handle(in_w as RawHandle);
        let reader = File::from_raw_handle(out_r as RawHandle);
        let mut hpc: HPCON = 0;
        let hr = CreatePseudoConsole(COORD { X: cols as i16, Y: rows as i16 }, in_r, out_w, 0, &mut hpc);
        CloseHandle(in_r);
        CloseHandle(out_w);
        if hr != 0 {
            return Err(std::io::Error::other(format!("CreatePseudoConsole failed: HRESULT {hr:#x}")));
        }
        let pty = Pty { hpc: Mutex::new(Some(hpc)) };

        let mut size = 0usize;
        InitializeProcThreadAttributeList(std::ptr::null_mut(), 1, 0, &mut size);
        let mut attrs = vec![0u8; size];
        let list = attrs.as_mut_ptr() as LPPROC_THREAD_ATTRIBUTE_LIST;
        if InitializeProcThreadAttributeList(list, 1, 0, &mut size) == 0 {
            return Err(last_error("InitializeProcThreadAttributeList"));
        }
        if UpdateProcThreadAttribute(
            list,
            0,
            PROC_THREAD_ATTRIBUTE_PSEUDOCONSOLE as usize,
            hpc as *const _,
            std::mem::size_of::<HPCON>(),
            std::ptr::null_mut(),
            std::ptr::null(),
        ) == 0
        {
            DeleteProcThreadAttributeList(list);
            return Err(last_error("UpdateProcThreadAttribute"));
        }
        let mut si: STARTUPINFOEXW = std::mem::zeroed();
        si.StartupInfo.cb = std::mem::size_of::<STARTUPINFOEXW>() as u32;
        si.lpAttributeList = list;
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
            EXTENDED_STARTUPINFO_PRESENT | CREATE_UNICODE_ENVIRONMENT | CREATE_SUSPENDED | extra,
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
        let process = Proc { handle: pi.hProcess };
        if let Err(e) = before_resume(pi.dwProcessId) {
            process.kill();
            CloseHandle(pi.hThread);
            return Err(e);
        }
        ResumeThread(pi.hThread);
        CloseHandle(pi.hThread);
        Ok(Spawned { pty, process, pid: pi.dwProcessId, writer, reader })
    }
}
