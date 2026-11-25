// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
//! DAEMON-LOCK-3/4: the daemon's listener. A port that any program already LISTENS on, on any address, is never shared:
//! the listening table of the OS is asked first (Windows lets `127.0.0.1:P` bind beside another program's `0.0.0.0:P` when
//! that program did not bind exclusively, as 0.7.2 and 0.8.3 do not), then the socket is bound with `SO_EXCLUSIVEADDRUSE`
//! (so a later program cannot slip in beside this one). A bind that fails, or a holder that is still listening, is retried for
//! a bounded time (`DEFAULT_BIND_WAIT`): a daemon that is ending, or connections of the previous socket still closing, must
//! not turn a restart into a refusal. A port still held at the end is an error; the caller refuses or picks a free port.
use std::net::{SocketAddr, ToSocketAddrs};
use std::time::{Duration, Instant};

/// How long a bind waits for the previous holder of the port to go (the restart, toggle and update paths).
pub const DEFAULT_BIND_WAIT: Duration = Duration::from_secs(5);
const POLL: Duration = Duration::from_millis(50);

/// Binds `host:port` exclusively, waiting up to [`DEFAULT_BIND_WAIT`]. Must be called inside a tokio runtime.
pub fn bind_exclusive(host: &str, port: u16) -> std::io::Result<tokio::net::TcpListener> {
    bind_exclusive_wait(host, port, DEFAULT_BIND_WAIT)
}

/// Binds `host:port` exclusively, waiting up to `wait` for a holder that is closing. Must be called inside a tokio runtime.
pub fn bind_exclusive_wait(host: &str, port: u16, wait: Duration) -> std::io::Result<tokio::net::TcpListener> {
    let addr: SocketAddr = (host, port).to_socket_addrs()?.next().ok_or_else(|| std::io::Error::new(std::io::ErrorKind::InvalidInput, format!("no address for {host}")))?;
    let start = Instant::now();
    loop {
        let attempt = match if port == 0 { None } else { listener_pid(port) } {
            Some(pid) => Err(std::io::Error::new(std::io::ErrorKind::AddrInUse, format!("port {port} is held by another program (process {pid})"))),
            None => try_bind(addr),
        };
        match attempt {
            Ok(l) => return Ok(l),
            Err(e) if retryable(&e) && start.elapsed() < wait => std::thread::sleep(POLL),
            Err(e) => return Err(e),
        }
    }
}

fn retryable(e: &std::io::Error) -> bool {
    matches!(e.kind(), std::io::ErrorKind::AddrInUse | std::io::ErrorKind::PermissionDenied)
}

fn try_bind(addr: SocketAddr) -> std::io::Result<tokio::net::TcpListener> {
    let sock = socket2::Socket::new(socket2::Domain::for_address(addr), socket2::Type::STREAM, Some(socket2::Protocol::TCP))?;
    exclusive(&sock)?;
    sock.bind(&addr.into())?;
    sock.listen(1024)?;
    sock.set_nonblocking(true)?;
    tokio::net::TcpListener::from_std(sock.into())
}

#[cfg(windows)]
fn exclusive(sock: &socket2::Socket) -> std::io::Result<()> {
    use std::os::windows::io::AsRawSocket;
    use windows_sys::Win32::Networking::WinSock::{setsockopt, SOL_SOCKET, SO_EXCLUSIVEADDRUSE};
    let on: u32 = 1;
    // SAFETY: a valid socket handle owned by `sock`, and a 4-byte option value that lives for the call.
    let rc = unsafe { setsockopt(sock.as_raw_socket() as usize, SOL_SOCKET, SO_EXCLUSIVEADDRUSE, (&on as *const u32).cast(), 4) };
    if rc == 0 { Ok(()) } else { Err(std::io::Error::last_os_error()) }
}

#[cfg(not(windows))]
fn exclusive(sock: &socket2::Socket) -> std::io::Result<()> {
    sock.set_reuse_address(false)
}

/// The pid of a process LISTENING on `port` on ANY local address (IPv4 or IPv6), asked of the OS; None when none, or when it
/// cannot be told. Connections that are only established or closing are not listeners, so a daemon that just ended is not one.
#[cfg(windows)]
pub fn listener_pid(port: u16) -> Option<u32> {
    use windows_sys::Win32::Networking::WinSock::{AF_INET, AF_INET6};
    listener_pid_in(AF_INET as u32, port).or_else(|| listener_pid_in(AF_INET6 as u32, port))
}

#[cfg(windows)]
fn listener_pid_in(family: u32, port: u16) -> Option<u32> {
    use windows_sys::Win32::NetworkManagement::IpHelper::{GetExtendedTcpTable, MIB_TCP6ROW_OWNER_PID, MIB_TCPROW_OWNER_PID, TCP_TABLE_OWNER_PID_LISTENER};
    let v6 = family == windows_sys::Win32::Networking::WinSock::AF_INET6 as u32;
    let row = if v6 { std::mem::size_of::<MIB_TCP6ROW_OWNER_PID>() } else { std::mem::size_of::<MIB_TCPROW_OWNER_PID>() };
    let mut size = 0u32;
    // SAFETY: the first call only asks for the size; the next fills a buffer of at least that size, rows read unaligned.
    unsafe {
        GetExtendedTcpTable(std::ptr::null_mut(), &mut size, 0, family, TCP_TABLE_OWNER_PID_LISTENER, 0);
        for _ in 0..3 {
            let mut buf = vec![0u8; size as usize + 1024];
            let mut len = buf.len() as u32;
            if GetExtendedTcpTable(buf.as_mut_ptr().cast(), &mut len, 0, family, TCP_TABLE_OWNER_PID_LISTENER, 0) == 0 {
                let n = u32::from_le_bytes(buf[0..4].try_into().ok()?) as usize;
                for i in 0..n {
                    let p = buf.as_ptr().add(4 + i * row);
                    let (local_port, pid) = if v6 {
                        let r = std::ptr::read_unaligned(p as *const MIB_TCP6ROW_OWNER_PID);
                        (r.dwLocalPort, r.dwOwningPid)
                    } else {
                        let r = std::ptr::read_unaligned(p as *const MIB_TCPROW_OWNER_PID);
                        (r.dwLocalPort, r.dwOwningPid)
                    };
                    if u16::from_be(local_port as u16) == port {
                        return Some(pid);
                    }
                }
                return None;
            }
            size = len;
        }
    }
    None
}

/// Off Windows: `/proc/net/tcp` and `/proc/net/tcp6` (listening socket inode) and `/proc/<pid>/fd`.
#[cfg(not(windows))]
pub fn listener_pid(port: u16) -> Option<u32> {
    let inode = ["/proc/net/tcp", "/proc/net/tcp6"].iter().find_map(|f| {
        let table = std::fs::read_to_string(f).ok()?;
        table.lines().skip(1).find_map(|l| {
            let c: Vec<&str> = l.split_whitespace().collect();
            let local_port = u16::from_str_radix(c.get(1)?.rsplit(':').next()?, 16).ok()?;
            if c.get(3) == Some(&"0A") && local_port == port { c.get(9).map(|s| s.to_string()) } else { None }
        })
    })?;
    let want = format!("socket:[{inode}]");
    std::fs::read_dir("/proc").ok()?.flatten().find_map(|e| {
        let pid: u32 = e.file_name().to_str()?.parse().ok()?;
        std::fs::read_dir(e.path().join("fd")).ok()?.flatten().any(|f| std::fs::read_link(f.path()).map(|t| t.to_string_lossy() == want).unwrap_or(false)).then_some(pid)
    })
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::io::{BufRead, Read, Write};

    // NOTE (owner 2026-11-25): a test that LISTENS on 0.0.0.0 raises a Windows Firewall prompt. Tests that run by default listen on
    // 127.0.0.1 only, or BIND 0.0.0.0 WITHOUT listen(). The ones that must listen on 0.0.0.0 are #[ignore]d and run in a Windows Sandbox.

    /// A plain (non-exclusive, like 0.7.2 and 0.8.3) socket bound to `addr`, NOT listening: no connection can reach it, no prompt.
    fn plain_bind(addr: &str) -> std::net::TcpListener {
        let a: SocketAddr = addr.parse().unwrap();
        let s = socket2::Socket::new(socket2::Domain::for_address(a), socket2::Type::STREAM, Some(socket2::Protocol::TCP)).unwrap();
        s.bind(&a.into()).unwrap();
        s.into()
    }

    /// Point 0, measured (never listens): a plain 0.0.0.0 bind (what 0.8.3 does) does not stop an exclusive 127.0.0.1 bind on Windows.
    /// The result is printed, and asserted per platform; this is WHY `bind_exclusive` asks the listening table first.
    #[test]
    fn a_plain_wide_bind_without_listen_lets_an_exclusive_loopback_bind_in_on_windows() {
        let wide = plain_bind("0.0.0.0:0");
        let port = wide.local_addr().unwrap().port();
        let second = std::thread::spawn(move || {
            let rt = tokio::runtime::Builder::new_current_thread().enable_all().build().unwrap();
            let _g = rt.enter();
            let r = try_bind(format!("127.0.0.1:{port}").parse().unwrap());
            r.is_ok()
        })
        .join()
        .unwrap();
        println!("plain 0.0.0.0:{port} (no listen) then exclusive 127.0.0.1:{port}: shared={second}");
        assert_eq!(second, cfg!(windows), "the observed behaviour of this OS changed");
    }

    #[tokio::test]
    async fn a_port_held_on_loopback_refuses_every_second_bind() {
        let local = bind_exclusive("127.0.0.1", 0).unwrap();
        let port = local.local_addr().unwrap().port();
        assert_eq!(listener_pid(port), Some(std::process::id()));
        assert!(bind_exclusive_wait("127.0.0.1", port, Duration::from_millis(150)).is_err());
        assert!(bind_exclusive_wait("0.0.0.0", port, Duration::from_millis(150)).is_err());
        drop(local);
        assert_eq!(listener_pid(port), None);
        bind_exclusive("127.0.0.1", port).expect("free again after the holder ended");
    }

    /// A holder that goes while we wait is not a refusal (the restart path); one that stays is.
    #[tokio::test]
    async fn a_bind_waits_for_a_holder_that_is_closing() {
        let local = bind_exclusive("127.0.0.1", 0).unwrap();
        let port = local.local_addr().unwrap().port();
        let t = std::thread::spawn(move || {
            std::thread::sleep(Duration::from_millis(300));
            drop(local);
        });
        let started = Instant::now();
        let l = bind_exclusive_wait("127.0.0.1", port, Duration::from_secs(5)).expect("bound once the holder ended");
        assert!(started.elapsed() >= Duration::from_millis(250));
        drop(l);
        t.join().unwrap();
    }

    /// The legacy holder case on loopback: a plain (non-exclusive) listener, as 0.7.2 opens it, is refused by the table check.
    #[tokio::test]
    async fn a_plain_loopback_listener_of_an_older_daemon_is_never_shared() {
        let old = std::net::TcpListener::bind("127.0.0.1:0").unwrap();
        let port = old.local_addr().unwrap().port();
        assert!(bind_exclusive_wait("127.0.0.1", port, Duration::from_millis(150)).is_err());
        assert!(bind_exclusive_wait("0.0.0.0", port, Duration::from_millis(150)).is_err(), "a wide bind is only asked for; the refusal comes from the table");
    }

    /// Point 0, both orders, with the holder LISTENING on 0.0.0.0. Raises a firewall prompt: only in the Windows Sandbox.
    #[test]
    #[ignore = "listens on 0.0.0.0: run in a Windows Sandbox only"]
    fn wide_holder_is_never_shared_in_both_orders() {
        let rt = tokio::runtime::Builder::new_current_thread().enable_all().build().unwrap();
        let _g = rt.enter();
        // Older style holder (plain, wide, listening), then new style.
        let old = std::net::TcpListener::bind("0.0.0.0:0").unwrap();
        let port = old.local_addr().unwrap().port();
        assert_eq!(listener_pid(port), Some(std::process::id()));
        assert!(bind_exclusive_wait("127.0.0.1", port, Duration::from_millis(200)).is_err(), "127.0.0.1 beside an older 0.0.0.0 holder");
        assert!(bind_exclusive_wait("0.0.0.0", port, Duration::from_millis(200)).is_err());
        drop(old);
        // New style holder first (exclusive, wide), then a new daemon and an older plain bind.
        let new = bind_exclusive("0.0.0.0", 0).unwrap();
        let port = new.local_addr().unwrap().port();
        assert!(bind_exclusive_wait("127.0.0.1", port, Duration::from_millis(200)).is_err());
        assert!(std::net::TcpListener::bind(("127.0.0.1", port)).is_err(), "an older plain bind beside a new holder");
        assert!(std::net::TcpListener::bind(("0.0.0.0", port)).is_err());
    }

    /// A daemon process holds the port with REAL established clients, ends (graceful stop or killed by its pid) with the clients
    /// still open, and the next daemon binds at once, on the same or the other address.
    fn restart_cases(cases: &[(&str, &str)], rounds: usize) {
        if let (Ok(host), Ok(port)) = (std::env::var("LISTEN_CHILD_HOST"), std::env::var("LISTEN_CHILD_PORT")) {
            let rt = tokio::runtime::Runtime::new().unwrap();
            rt.block_on(async move {
                let l = bind_exclusive(&host, port.parse().unwrap()).unwrap();
                println!("READY");
                std::thread::spawn(|| {
                    let mut line = String::new();
                    let _ = std::io::stdin().lock().read_line(&mut line);
                    std::process::exit(0); // graceful: the process ends, the OS closes its sockets
                });
                let mut held = Vec::new();
                loop {
                    let (mut s, _) = l.accept().await.unwrap();
                    use tokio::io::AsyncWriteExt;
                    let _ = s.write_all(b"hello").await;
                    held.push(s);
                }
            });
            return;
        }
        let lan: String = (|| {
            let u = std::net::UdpSocket::bind("0.0.0.0:0").ok()?;
            u.connect("192.0.2.1:9").ok()?;
            Some(u.local_addr().ok()?.ip().to_string())
        })()
        .unwrap_or_else(|| "127.0.0.1".into());
        let rt = tokio::runtime::Builder::new_current_thread().enable_all().build().unwrap();
        let _g = rt.enter();
        let free = || {
            let l = std::net::TcpListener::bind("127.0.0.1:0").unwrap();
            l.local_addr().unwrap().port()
        };
        let test_name = if cases.iter().any(|(a, b)| *a == "0.0.0.0" || *b == "0.0.0.0") { "listen::tests::restart_with_clients_connected_wide" } else { "listen::tests::restart_with_clients_connected_loopback" };
        let mut failures = Vec::new();
        let mut worst = Duration::ZERO;
        for (first, next) in cases {
            for graceful in [true, false] {
                for round in 0..rounds {
                    let port = free();
                    let mut child = std::process::Command::new(std::env::current_exe().unwrap())
                        .args([test_name, "--exact", "--nocapture", "--test-threads=1", "--include-ignored"])
                        .env("LISTEN_CHILD_HOST", first)
                        .env("LISTEN_CHILD_PORT", port.to_string())
                        .stdin(std::process::Stdio::piped())
                        .stdout(std::process::Stdio::piped())
                        .spawn()
                        .unwrap();
                    let mut out = std::io::BufReader::new(child.stdout.take().unwrap());
                    let mut line = String::new();
                    loop {
                        line.clear();
                        assert!(out.read_line(&mut line).unwrap() > 0, "child exited before listening");
                        if line.contains("READY") {
                            break;
                        }
                    }
                    let target = if *first == "0.0.0.0" { lan.as_str() } else { "127.0.0.1" };
                    let mut clients: Vec<std::net::TcpStream> = (0..3).map(|_| std::net::TcpStream::connect((target, port)).unwrap()).collect();
                    for c in clients.iter_mut() {
                        let mut b = [0u8; 5];
                        c.read_exact(&mut b).unwrap();
                        c.write_all(b"ping").unwrap();
                    }
                    if graceful {
                        let _ = writeln!(child.stdin.take().unwrap(), "stop");
                    } else {
                        child.kill().unwrap();
                    }
                    child.wait().unwrap();
                    let t = Instant::now();
                    // At once: no wait at all, so a failure here is a real refusal, not a slow start.
                    match bind_exclusive_wait(next, port, Duration::ZERO) {
                        Ok(l) => drop(l),
                        Err(e) => failures.push(format!("{first}->{next} graceful={graceful} round={round}: {e}")),
                    }
                    worst = worst.max(t.elapsed());
                    drop(clients);
                }
            }
        }
        println!("{} restarts, worst bind {:?}, {} failed at once", cases.len() * 2 * rounds, worst, failures.len());
        assert!(failures.is_empty(), "{} restarts failed to bind at once: {:?}", failures.len(), failures);
    }

    #[test]
    fn restart_with_clients_connected_loopback() {
        restart_cases(&[("127.0.0.1", "127.0.0.1")], 10);
    }

    /// The same on 0.0.0.0 (the LAN address of the machine as the client) and the toggle both ways. Sandbox only.
    #[test]
    #[ignore = "listens on 0.0.0.0: run in a Windows Sandbox only"]
    fn restart_with_clients_connected_wide() {
        restart_cases(&[("0.0.0.0", "0.0.0.0"), ("127.0.0.1", "0.0.0.0"), ("0.0.0.0", "127.0.0.1")], 10);
    }
}
