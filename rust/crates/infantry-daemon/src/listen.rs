// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
//! DAEMON-LOCK-3: the daemon's listener is bound EXCLUSIVELY. Windows lets `127.0.0.1:P` bind beside another program's
//! `0.0.0.0:P` (a second daemon on the same port); `SO_EXCLUSIVEADDRUSE` refuses that, so a port held on any address is a
//! failed bind (the caller then refuses or picks a free port), never a second listener beside it. Elsewhere the OS already refuses.
use std::net::{SocketAddr, ToSocketAddrs};

/// Binds `host:port` exclusively and returns a tokio listener. Must be called inside a tokio runtime.
pub fn bind_exclusive(host: &str, port: u16) -> std::io::Result<tokio::net::TcpListener> {
    let addr: SocketAddr = (host, port).to_socket_addrs()?.next().ok_or_else(|| std::io::Error::new(std::io::ErrorKind::InvalidInput, format!("no address for {host}")))?;
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

#[cfg(test)]
mod tests {
    use super::*;

    #[tokio::test]
    async fn a_port_held_on_all_addresses_refuses_a_loopback_bind() {
        let wide = bind_exclusive("0.0.0.0", 0).unwrap();
        let port = wide.local_addr().unwrap().port();
        assert!(bind_exclusive("127.0.0.1", port).is_err(), "127.0.0.1:{port} bound beside 0.0.0.0:{port}");
        assert!(bind_exclusive("0.0.0.0", port).is_err());
        drop(wide);
        bind_exclusive("127.0.0.1", port).expect("free again after the holder ended");
    }

    #[tokio::test]
    async fn a_port_held_on_loopback_refuses_an_all_addresses_bind() {
        let local = bind_exclusive("127.0.0.1", 0).unwrap();
        let port = local.local_addr().unwrap().port();
        assert!(bind_exclusive("0.0.0.0", port).is_err());
    }
}
