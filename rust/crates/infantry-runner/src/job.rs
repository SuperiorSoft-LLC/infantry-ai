// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
//! Process control: a Job Object per session on Windows (KILL_ON_JOB_CLOSE), so a session's whole process tree dies when the
//! session is killed or the runner exits for any reason (the kernel closes the handle); kill by pid; the runner's own resource
//! use (for the measurements). Unix keeps the same API with process groups.

/// One session's process group. Dropping it kills every process still in it.
pub struct Job {
    #[cfg(windows)]
    handle: windows_sys::Win32::Foundation::HANDLE,
    #[cfg(unix)]
    pgid: std::sync::atomic::AtomicI32,
}

// SAFETY: a job handle is a kernel handle, usable from any thread.
unsafe impl Send for Job {}
unsafe impl Sync for Job {}

#[cfg(windows)]
impl Job {
    pub fn new() -> std::io::Result<Job> {
        use windows_sys::Win32::System::JobObjects::*;
        unsafe {
            let handle = CreateJobObjectW(std::ptr::null(), std::ptr::null());
            if handle.is_null() {
                return Err(std::io::Error::last_os_error());
            }
            let mut info: JOBOBJECT_EXTENDED_LIMIT_INFORMATION = std::mem::zeroed();
            info.BasicLimitInformation.LimitFlags = JOB_OBJECT_LIMIT_KILL_ON_JOB_CLOSE;
            let ok = SetInformationJobObject(
                handle,
                JobObjectExtendedLimitInformation,
                &info as *const _ as *const core::ffi::c_void,
                std::mem::size_of::<JOBOBJECT_EXTENDED_LIMIT_INFORMATION>() as u32,
            );
            if ok == 0 {
                let e = std::io::Error::last_os_error();
                windows_sys::Win32::Foundation::CloseHandle(handle);
                return Err(e);
            }
            Ok(Job { handle })
        }
    }

    /// Puts process [pid] (and every child it starts from now on) into the job.
    pub fn assign(&self, pid: u32) -> std::io::Result<()> {
        use windows_sys::Win32::Foundation::CloseHandle;
        use windows_sys::Win32::System::JobObjects::AssignProcessToJobObject;
        use windows_sys::Win32::System::Threading::{OpenProcess, PROCESS_SET_QUOTA, PROCESS_TERMINATE};
        unsafe {
            let p = OpenProcess(PROCESS_SET_QUOTA | PROCESS_TERMINATE, 0, pid);
            if p.is_null() {
                return Err(std::io::Error::last_os_error());
            }
            let ok = AssignProcessToJobObject(self.handle, p);
            let e = std::io::Error::last_os_error();
            CloseHandle(p);
            if ok == 0 { Err(e) } else { Ok(()) }
        }
    }

    /// Kills every process in the job now.
    pub fn kill(&self) {
        unsafe {
            windows_sys::Win32::System::JobObjects::TerminateJobObject(self.handle, 1);
        }
    }
}

#[cfg(windows)]
impl Drop for Job {
    fn drop(&mut self) {
        unsafe {
            windows_sys::Win32::Foundation::CloseHandle(self.handle);
        }
    }
}

#[cfg(unix)]
impl Job {
    pub fn new() -> std::io::Result<Job> {
        Ok(Job { pgid: std::sync::atomic::AtomicI32::new(0) })
    }

    /// The PTY child is a session leader already (setsid): its process group is the job.
    pub fn assign(&self, pid: u32) -> std::io::Result<()> {
        self.pgid.store(pid as i32, std::sync::atomic::Ordering::SeqCst);
        Ok(())
    }

    pub fn kill(&self) {
        let g = self.pgid.load(std::sync::atomic::Ordering::SeqCst);
        if g > 0 {
            unsafe {
                libc::killpg(g, libc::SIGKILL);
            }
        }
    }
}

#[cfg(unix)]
impl Drop for Job {
    fn drop(&mut self) {
        self.kill();
    }
}

/// Kills process [pid] (only that process; a session's tree goes with [Job::kill]). False when it could not be opened or killed.
pub fn kill_pid(pid: u32) -> bool {
    #[cfg(windows)]
    unsafe {
        use windows_sys::Win32::Foundation::CloseHandle;
        use windows_sys::Win32::System::Threading::{OpenProcess, TerminateProcess, PROCESS_TERMINATE};
        let p = OpenProcess(PROCESS_TERMINATE, 0, pid);
        if p.is_null() {
            return false;
        }
        let ok = TerminateProcess(p, 1) != 0;
        CloseHandle(p);
        ok
    }
    #[cfg(unix)]
    unsafe {
        libc::kill(pid as i32, libc::SIGKILL) == 0
    }
}

/// Whether process [pid] still runs.
pub fn alive(pid: u32) -> bool {
    #[cfg(windows)]
    unsafe {
        use windows_sys::Win32::Foundation::{CloseHandle, STILL_ACTIVE};
        use windows_sys::Win32::System::Threading::{GetExitCodeProcess, OpenProcess, PROCESS_QUERY_LIMITED_INFORMATION};
        let p = OpenProcess(PROCESS_QUERY_LIMITED_INFORMATION, 0, pid);
        if p.is_null() {
            return false;
        }
        let mut code = 0u32;
        let ok = GetExitCodeProcess(p, &mut code) != 0;
        CloseHandle(p);
        ok && code == STILL_ACTIVE as u32
    }
    #[cfg(unix)]
    unsafe {
        libc::kill(pid as i32, 0) == 0
    }
}

/// This process's memory and CPU, for the runner's own measurements.
#[derive(Debug, Clone, Copy, Default)]
pub struct SelfUsage {
    /// Resident set (working set) bytes now.
    pub rss: u64,
    /// Peak resident set bytes.
    pub peak_rss: u64,
    /// Private (committed) bytes.
    pub private: u64,
    /// User + kernel CPU time so far, in milliseconds.
    pub cpu_ms: u64,
}

pub fn self_usage() -> SelfUsage {
    #[cfg(windows)]
    unsafe {
        use windows_sys::Win32::Foundation::FILETIME;
        use windows_sys::Win32::System::ProcessStatus::{GetProcessMemoryInfo, PROCESS_MEMORY_COUNTERS_EX};
        use windows_sys::Win32::System::Threading::{GetCurrentProcess, GetProcessTimes};
        let me = GetCurrentProcess();
        let mut m: PROCESS_MEMORY_COUNTERS_EX = std::mem::zeroed();
        m.cb = std::mem::size_of::<PROCESS_MEMORY_COUNTERS_EX>() as u32;
        GetProcessMemoryInfo(me, &mut m as *mut _ as *mut _, m.cb);
        let z = FILETIME { dwLowDateTime: 0, dwHighDateTime: 0 };
        let (mut c, mut e, mut k, mut u) = (z, z, z, z);
        GetProcessTimes(me, &mut c, &mut e, &mut k, &mut u);
        let ft = |f: FILETIME| ((f.dwHighDateTime as u64) << 32 | f.dwLowDateTime as u64) / 10_000;
        SelfUsage { rss: m.WorkingSetSize as u64, peak_rss: m.PeakWorkingSetSize as u64, private: m.PrivateUsage as u64, cpu_ms: ft(k) + ft(u) }
    }
    #[cfg(unix)]
    unsafe {
        let mut r: libc::rusage = std::mem::zeroed();
        libc::getrusage(libc::RUSAGE_SELF, &mut r);
        let ms = |t: libc::timeval| t.tv_sec as u64 * 1000 + t.tv_usec as u64 / 1000;
        SelfUsage { rss: r.ru_maxrss as u64 * 1024, peak_rss: r.ru_maxrss as u64 * 1024, private: 0, cpu_ms: ms(r.ru_utime) + ms(r.ru_stime) }
    }
}

#[cfg(all(test, windows))]
mod tests {
    use super::*;

    #[test]
    fn a_process_in_a_job_dies_when_the_job_is_dropped() {
        let child = std::process::Command::new("cmd").args(["/c", "ping -n 30 127.0.0.1 >NUL"]).spawn().unwrap();
        let pid = child.id();
        let job = Job::new().unwrap();
        job.assign(pid).unwrap();
        assert!(alive(pid));
        drop(job);
        let deadline = std::time::Instant::now() + std::time::Duration::from_secs(5);
        while alive(pid) && std::time::Instant::now() < deadline {
            std::thread::sleep(std::time::Duration::from_millis(50));
        }
        assert!(!alive(pid), "the job's process outlived the job");
    }

    #[test]
    fn kill_by_pid() {
        let child = std::process::Command::new("cmd").args(["/c", "ping -n 30 127.0.0.1 >NUL"]).spawn().unwrap();
        let pid = child.id();
        assert!(kill_pid(pid));
        let deadline = std::time::Instant::now() + std::time::Duration::from_secs(5);
        while alive(pid) && std::time::Instant::now() < deadline {
            std::thread::sleep(std::time::Duration::from_millis(50));
        }
        assert!(!alive(pid));
    }

    #[test]
    fn self_usage_reads() {
        let u = self_usage();
        assert!(u.rss > 0 && u.peak_rss >= u.rss);
    }
}
