// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
//! Memory figures for the processes report: one process's resident set and the machine's free and total RAM, in MB.
//! None when the OS does not answer (a process that just exited, a platform without the figure).

const MB: u64 = 1024 * 1024;

#[cfg(windows)]
pub fn process_mb(pid: u32) -> Option<u64> {
    use windows_sys::Win32::Foundation::CloseHandle;
    use windows_sys::Win32::System::ProcessStatus::{K32GetProcessMemoryInfo, PROCESS_MEMORY_COUNTERS};
    use windows_sys::Win32::System::Threading::{OpenProcess, PROCESS_QUERY_LIMITED_INFORMATION};
    unsafe {
        let h = OpenProcess(PROCESS_QUERY_LIMITED_INFORMATION, 0, pid);
        if h.is_null() {
            return None;
        }
        let mut c: PROCESS_MEMORY_COUNTERS = std::mem::zeroed();
        c.cb = std::mem::size_of::<PROCESS_MEMORY_COUNTERS>() as u32;
        let ok = K32GetProcessMemoryInfo(h, &mut c, c.cb) != 0;
        CloseHandle(h);
        ok.then(|| c.WorkingSetSize as u64 / MB)
    }
}

#[cfg(not(windows))]
pub fn process_mb(pid: u32) -> Option<u64> {
    let t = std::fs::read_to_string(format!("/proc/{pid}/status")).ok()?;
    let l = t.lines().find(|l| l.starts_with("VmRSS:"))?;
    l.split_whitespace().nth(1)?.parse::<u64>().ok().map(|kb| kb / 1024)
}

/// (free, total) physical memory.
#[cfg(windows)]
pub fn machine_mb() -> (Option<u64>, Option<u64>) {
    use windows_sys::Win32::System::SystemInformation::{GlobalMemoryStatusEx, MEMORYSTATUSEX};
    let mut m: MEMORYSTATUSEX = unsafe { std::mem::zeroed() };
    m.dwLength = std::mem::size_of::<MEMORYSTATUSEX>() as u32;
    if unsafe { GlobalMemoryStatusEx(&mut m) } == 0 {
        return (None, None);
    }
    (Some(m.ullAvailPhys / MB), Some(m.ullTotalPhys / MB))
}

#[cfg(not(windows))]
pub fn machine_mb() -> (Option<u64>, Option<u64>) {
    let Ok(t) = std::fs::read_to_string("/proc/meminfo") else { return (None, None) };
    let kb = |key: &str| t.lines().find(|l| l.starts_with(key)).and_then(|l| l.split_whitespace().nth(1)?.parse::<u64>().ok()).map(|k| k / 1024);
    (kb("MemAvailable:"), kb("MemTotal:"))
}

#[cfg(test)]
mod tests {
    #[test]
    fn this_process_has_a_resident_set_and_the_machine_has_ram() {
        assert!(super::process_mb(std::process::id()).is_some());
        let (free, total) = super::machine_mb();
        assert!(total.unwrap() >= free.unwrap());
    }
}
