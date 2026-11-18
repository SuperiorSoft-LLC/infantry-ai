// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
//! `loopback-http` on this machine: a small HTTP/1.1 client over a loopback TCP socket (no TLS: the server is a process on
//! this host). One connection per request (`Connection: close`); the body is streamed as it arrives, de-chunked.

use infantry_runner_api::http::is_loopback_url;
use infantry_runner_api::{HttpEvent, HttpRequest, RunnerError};
use std::time::Duration;
use tokio::io::{AsyncReadExt, AsyncWriteExt};
use tokio::net::TcpStream;
use tokio::sync::mpsc;

/// The connect + response-head timeout when the request names none.
pub const DEFAULT_TIMEOUT: Duration = Duration::from_secs(30);

/// Starts [req]; its events arrive on the returned receiver.
pub fn start(req: HttpRequest) -> Result<mpsc::UnboundedReceiver<HttpEvent>, RunnerError> {
    if !is_loopback_url(&req.url) {
        return Err(RunnerError::Unsupported(format!("loopback-http refuses {}", req.url)));
    }
    let (tx, rx) = mpsc::unbounded_channel();
    tokio::spawn(async move {
        if let Err(e) = run(req, &tx).await {
            let _ = tx.send(HttpEvent::Failed(e));
        }
    });
    Ok(rx)
}

/// (authority, path) of an `http://` URL.
fn split_url(url: &str) -> (String, String) {
    let rest = url.strip_prefix("http://").unwrap_or(url);
    match rest.find('/') {
        Some(i) => (rest[..i].to_string(), rest[i..].to_string()),
        None => (rest.to_string(), "/".to_string()),
    }
}

async fn run(req: HttpRequest, tx: &mpsc::UnboundedSender<HttpEvent>) -> Result<(), String> {
    let timeout = req.timeout_ms.map(Duration::from_millis).unwrap_or(DEFAULT_TIMEOUT);
    let (authority, path) = split_url(&req.url);
    let (host, port) = match authority.rsplit_once(':') {
        Some((h, p)) if !p.contains(']') => (h.to_string(), p.parse::<u16>().map_err(|_| format!("bad port in {authority}"))?),
        _ => (authority.clone(), 80),
    };
    let host = host.trim_start_matches('[').trim_end_matches(']');
    let host = if host.eq_ignore_ascii_case("localhost") { "127.0.0.1" } else { host };
    let mut stream = tokio::time::timeout(timeout, TcpStream::connect((host, port)))
        .await
        .map_err(|_| format!("connect to {authority} timed out"))?
        .map_err(|e| format!("connect to {authority}: {e}"))?;
    let mut head = format!("{} {} HTTP/1.1\r\nHost: {}\r\nConnection: close\r\n", req.method, path, authority);
    let mut has_length = false;
    for (k, v) in &req.headers {
        has_length |= k.eq_ignore_ascii_case("content-length");
        head.push_str(&format!("{k}: {v}\r\n"));
    }
    if !has_length && (!req.body.is_empty() || !matches!(req.method.as_str(), "GET" | "HEAD")) {
        head.push_str(&format!("Content-Length: {}\r\n", req.body.len()));
    }
    head.push_str("\r\n");
    stream.write_all(head.as_bytes()).await.map_err(|e| e.to_string())?;
    if !req.body.is_empty() {
        stream.write_all(&req.body).await.map_err(|e| e.to_string())?;
    }
    stream.flush().await.map_err(|e| e.to_string())?;

    // The response head.
    let mut buf: Vec<u8> = Vec::new();
    let mut chunk = vec![0u8; 16 * 1024];
    let head_end = tokio::time::timeout(timeout, async {
        loop {
            if let Some(i) = find(&buf, b"\r\n\r\n") {
                return Ok(i);
            }
            let n = stream.read(&mut chunk).await.map_err(|e| e.to_string())?;
            if n == 0 {
                return Err("the server closed the connection before answering".to_string());
            }
            buf.extend_from_slice(&chunk[..n]);
        }
    })
    .await
    .map_err(|_| "no response head in time".to_string())??;
    let head_text = String::from_utf8_lossy(&buf[..head_end]).to_string();
    let rest: Vec<u8> = buf[head_end + 4..].to_vec();
    let mut lines = head_text.split("\r\n");
    let status_line = lines.next().unwrap_or("");
    let status: u16 = status_line
        .split_whitespace()
        .nth(1)
        .and_then(|s| s.parse().ok())
        .ok_or_else(|| format!("bad status line: {status_line}"))?;
    let headers: Vec<(String, String)> = lines
        .filter_map(|l| l.split_once(':').map(|(k, v)| (k.trim().to_string(), v.trim().to_string())))
        .collect();
    let header = |name: &str| headers.iter().find(|(k, _)| k.eq_ignore_ascii_case(name)).map(|(_, v)| v.clone());
    let chunked = header("transfer-encoding").is_some_and(|v| v.to_ascii_lowercase().contains("chunked"));
    let length: Option<usize> = header("content-length").and_then(|v| v.parse().ok());
    if tx.send(HttpEvent::Head { status, headers: headers.clone() }).is_err() {
        return Ok(());
    }

    // The body.
    if chunked {
        let mut decoder = Chunked::default();
        let mut data = rest;
        loop {
            let (out, done) = decoder.push(&data)?;
            if !out.is_empty() && tx.send(HttpEvent::Body(out)).is_err() {
                return Ok(());
            }
            if done {
                break;
            }
            let n = stream.read(&mut chunk).await.map_err(|e| e.to_string())?;
            if n == 0 {
                return Err("the connection closed inside a chunked body".into());
            }
            data = chunk[..n].to_vec();
        }
    } else {
        let mut left = length;
        let mut data = rest;
        loop {
            if let Some(l) = left.as_mut() {
                data.truncate(*l);
                *l -= data.len();
            }
            if !data.is_empty() && tx.send(HttpEvent::Body(std::mem::take(&mut data))).is_err() {
                return Ok(());
            }
            if left == Some(0) || status == 204 || status == 304 || req.method == "HEAD" {
                break;
            }
            let n = stream.read(&mut chunk).await.map_err(|e| e.to_string())?;
            if n == 0 {
                if left.is_some() {
                    return Err("the connection closed before the whole body arrived".into());
                }
                break;
            }
            data = chunk[..n].to_vec();
        }
    }
    let _ = tx.send(HttpEvent::End);
    Ok(())
}

fn find(hay: &[u8], needle: &[u8]) -> Option<usize> {
    hay.windows(needle.len()).position(|w| w == needle)
}

/// An incremental `Transfer-Encoding: chunked` decoder.
#[derive(Default)]
struct Chunked {
    pending: Vec<u8>,
    /// Bytes still to read of the current chunk (+2 for its CRLF), or None when a size line is next.
    left: Option<usize>,
}

impl Chunked {
    /// Feeds bytes; returns the decoded data and whether the last chunk was seen.
    fn push(&mut self, bytes: &[u8]) -> Result<(Vec<u8>, bool), String> {
        self.pending.extend_from_slice(bytes);
        let mut out = Vec::new();
        loop {
            match self.left {
                None => {
                    let Some(i) = find(&self.pending, b"\r\n") else { return Ok((out, false)) };
                    let line = String::from_utf8_lossy(&self.pending[..i]).to_string();
                    let size = usize::from_str_radix(line.split(';').next().unwrap_or("").trim(), 16)
                        .map_err(|_| format!("bad chunk size line: {line}"))?;
                    self.pending.drain(..i + 2);
                    if size == 0 {
                        return Ok((out, true));
                    }
                    self.left = Some(size + 2);
                }
                Some(n) => {
                    if self.pending.is_empty() {
                        return Ok((out, false));
                    }
                    let take = n.min(self.pending.len());
                    let data: Vec<u8> = self.pending.drain(..take).collect();
                    let data_part = if n > 2 { &data[..data.len().min(n - 2)] } else { &data[..0] };
                    out.extend_from_slice(data_part);
                    let rest = n - take;
                    self.left = if rest == 0 { None } else { Some(rest) };
                }
            }
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[tokio::test]
    async fn streams_a_chunked_answer_from_a_loopback_server() {
        let listener = tokio::net::TcpListener::bind("127.0.0.1:0").await.unwrap();
        let port = listener.local_addr().unwrap().port();
        tokio::spawn(async move {
            let (mut sock, _) = listener.accept().await.unwrap();
            let mut buf = vec![0u8; 4096];
            let n = sock.read(&mut buf).await.unwrap();
            let req = String::from_utf8_lossy(&buf[..n]).to_string();
            assert!(req.starts_with("POST /x HTTP/1.1\r\n") && req.contains("Authorization: Basic a") && req.ends_with("{}"));
            sock.write_all(b"HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\n\r\n6\r\ndata: \r\n").await.unwrap();
            sock.write_all(b"3\r\n{}\n\r\n0\r\n\r\n").await.unwrap();
        });
        let req = HttpRequest::new("POST", format!("http://127.0.0.1:{port}/x")).header("Authorization", "Basic a").body("{}");
        let mut rx = start(req).unwrap();
        assert!(matches!(rx.recv().await, Some(HttpEvent::Head { status: 200, .. })));
        let mut body = Vec::new();
        loop {
            match rx.recv().await.unwrap() {
                HttpEvent::Body(b) => body.extend(b),
                HttpEvent::End => break,
                other => panic!("{other:?}"),
            }
        }
        assert_eq!(body, b"data: {}\n");
        assert!(start(HttpRequest::new("GET", "http://example.com/")).is_err());
    }

    #[test]
    fn chunked_split_anywhere() {
        let body = b"5\r\nhello\r\n7\r\n, world\r\n0\r\n\r\n";
        for cut in 0..body.len() {
            let mut d = Chunked::default();
            let (mut a, done_a) = d.push(&body[..cut]).unwrap();
            let (b, done_b) = if done_a { (Vec::new(), true) } else { d.push(&body[cut..]).unwrap() };
            a.extend(b);
            assert!(done_b, "cut {cut}");
            assert_eq!(a, b"hello, world", "cut {cut}");
        }
    }
}
