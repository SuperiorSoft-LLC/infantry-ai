// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
//! The axum server: `/ws` (the v1 WebSocket, behind [`AccessPolicy`]) and `/health`.

use crate::auth::{self, AccessPolicy};
use crate::gateway::Gateway;
use crate::sessions::{Conn, Daemon};
use axum::extract::ws::{Message, WebSocket, WebSocketUpgrade};
use axum::extract::State;
use axum::http::{header, HeaderMap, StatusCode, Uri};
use axum::response::{IntoResponse, Response};
use axum::routing::get;
use axum::Router;
use infantry_protocol::Envelope;
use std::sync::Arc;
use std::time::Duration;
use tokio::sync::broadcast::error::RecvError;

/// How often the store is written when something changed.
pub const FLUSH_MS: u64 = 250;

pub const BUILD: &str = concat!("rust-", env!("CARGO_PKG_VERSION"));

#[derive(Clone)]
pub struct App {
    pub daemon: Arc<Daemon>,
    pub policy: Arc<AccessPolicy>,
}

/// The router state: the app, plus the strangler gateway when a legacy backend is configured.
#[derive(Clone)]
struct Shared {
    app: App,
    gateway: Option<Arc<Gateway>>,
}

fn h<'a>(headers: &'a HeaderMap, name: header::HeaderName) -> Option<&'a str> {
    headers.get(name).and_then(|v| v.to_str().ok())
}

fn refuse(r: auth::Refusal) -> Response {
    let mut resp = (StatusCode::from_u16(r.status).unwrap_or(StatusCode::FORBIDDEN), r.reason).into_response();
    if r.challenge_bearer {
        resp.headers_mut().insert(header::WWW_AUTHENTICATE, "Bearer".parse().unwrap());
    }
    resp
}

fn check(app: &App, uri: &Uri, headers: &HeaderMap) -> Option<Response> {
    let protocols = h(headers, header::SEC_WEBSOCKET_PROTOCOL);
    app.policy
        .check(uri.path(), h(headers, header::HOST), h(headers, header::ORIGIN), h(headers, header::AUTHORIZATION), protocols)
        .map(refuse)
}

async fn health(State(s): State<Shared>, uri: Uri, headers: HeaderMap) -> Response {
    let app = &s.app;
    if let Some(r) = check(app, &uri, &headers) {
        return r;
    }
    let mut body = serde_json::json!({
        "ok": true, "provider": app.daemon.harness.name(), "contract": crate::wire::CONTRACT,
        "build": BUILD, "sessions": app.daemon.session_count(),
    });
    if let Some(g) = &s.gateway {
        body["legacy"] = serde_json::json!({"port": g.legacy.port, "ready": g.backend_ready(), "pid": g.legacy.pid()});
    }
    ([(header::CONTENT_TYPE, "application/json")], body.to_string()).into_response()
}

async fn ws(
    State(s): State<Shared>,
    uri: Uri,
    headers: HeaderMap,
    upgrade: Result<WebSocketUpgrade, axum::extract::ws::rejection::WebSocketUpgradeRejection>,
) -> Response {
    let app = s.app;
    // Access first: an unauthenticated caller learns nothing about the route, upgrade or not.
    if let Some(r) = check(&app, &uri, &headers) {
        return r;
    }
    let upgrade = match upgrade {
        Ok(u) => u,
        Err(e) => return e.into_response(),
    };
    // A browser that offered the bearer subprotocol is answered with `infantry` (when it offered that too).
    let offered = h(&headers, header::SEC_WEBSOCKET_PROTOCOL).unwrap_or("");
    let upgrade = if offered.split(',').any(|p| p.trim() == auth::ANSWER_PROTOCOL) {
        upgrade.protocols([auth::ANSWER_PROTOCOL])
    } else {
        upgrade
    };
    match s.gateway {
        Some(g) => {
            let owner_header = h(&headers, header::HeaderName::from_static("x-infantry-owner"))
                .or_else(|| h(&headers, header::HeaderName::from_static("x-agenthq-owner")));
            let owner = g.is_owner(owner_header, h(&headers, header::SEC_WEBSOCKET_PROTOCOL));
            upgrade.on_upgrade(move |socket| gateway_connection(app.daemon, g, owner, socket))
        }
        None => upgrade.on_upgrade(move |socket| connection(app.daemon, socket)),
    }
}

async fn connection(daemon: Arc<Daemon>, mut socket: WebSocket) {
    // Subscribe first so nothing pushed during the burst is lost.
    let mut rx = daemon.subscribe();
    for f in daemon.hello(BUILD) {
        if socket.send(Message::Text(f.into())).await.is_err() {
            return;
        }
    }
    let mut conn = Conn::default();
    loop {
        tokio::select! {
            m = socket.recv() => {
                let text = match m {
                    Some(Ok(Message::Text(t))) => t.to_string(),
                    Some(Ok(Message::Close(_))) | None | Some(Err(_)) => break,
                    Some(Ok(_)) => continue,
                };
                let replies = match serde_json::from_str::<Envelope>(&text) {
                    Ok(env) => daemon.handle(env, &mut conn),
                    Err(e) => vec![serde_json::to_string(&Envelope::new(crate::wire::ERROR, format!("frame refused: not an envelope ({e})"))).unwrap_or_default()],
                };
                for r in replies {
                    if socket.send(Message::Text(r.into())).await.is_err() {
                        return;
                    }
                }
            }
            f = rx.recv() => match f {
                Ok(f) => if socket.send(Message::Text(f.into())).await.is_err() { break },
                Err(RecvError::Lagged(_)) => continue,
                Err(RecvError::Closed) => break,
            },
        }
    }
}

/// A client of the strangler gateway: the frames a Rust unit owns are answered here, the rest go to the legacy backend;
/// the client gets Rust's pushes and the gateway's relays (Kotlin's broadcasts and its answers to this client).
async fn gateway_connection(daemon: Arc<Daemon>, gw: Arc<Gateway>, owner: bool, mut socket: WebSocket) {
    let mut rx = daemon.subscribe();
    let (id, mut relay) = gw.register();
    for f in gw.hello(owner, daemon.hello(BUILD)).await {
        if socket.send(Message::Text(f.into())).await.is_err() {
            gw.unregister(id);
            return;
        }
    }
    let mut conn = Conn::default();
    loop {
        tokio::select! {
            m = socket.recv() => {
                let text = match m {
                    Some(Ok(Message::Text(t))) => t.to_string(),
                    Some(Ok(Message::Close(_))) | None | Some(Err(_)) => break,
                    Some(Ok(_)) => continue,
                };
                let replies = match serde_json::from_str::<Envelope>(&text) {
                    Ok(env) if gw.routes.is_rust(&env.r#type) => daemon.handle(env, &mut conn),
                    Ok(_) => gw.forward(&text, id, owner).into_iter().collect(),
                    Err(e) => vec![serde_json::to_string(&Envelope::new(crate::wire::ERROR, format!("frame refused: not an envelope ({e})"))).unwrap_or_default()],
                };
                let mut gone = false;
                for r in replies {
                    if socket.send(Message::Text(r.into())).await.is_err() {
                        gone = true;
                        break;
                    }
                }
                if gone { break }
            }
            f = rx.recv() => match f {
                Ok(f) => if socket.send(Message::Text(f.into())).await.is_err() { break },
                Err(RecvError::Lagged(_)) => continue,
                Err(RecvError::Closed) => break,
            },
            f = relay.recv() => match f {
                Some(f) => if socket.send(Message::Text(f.into())).await.is_err() { break },
                None => break,
            },
        }
    }
    gw.unregister(id);
}

pub fn router(app: App) -> Router {
    router_with(app, None)
}

fn router_with(app: App, gateway: Option<Arc<Gateway>>) -> Router {
    Router::new().route("/ws", get(ws)).route("/health", get(health)).with_state(Shared { app, gateway })
}

/// Serves until the listener fails or `shutdown` resolves; the store is flushed every [FLUSH_MS] and at the end.
pub async fn serve(listener: tokio::net::TcpListener, app: App, shutdown: impl std::future::Future<Output = ()> + Send + 'static) -> std::io::Result<()> {
    serve_with(listener, app, None, shutdown).await
}

/// [serve], in gateway mode when `gateway` is set: it starts the legacy backend and its upstreams first, and stops the
/// backend when the server ends.
pub async fn serve_with(
    listener: tokio::net::TcpListener,
    app: App,
    gateway: Option<Arc<Gateway>>,
    shutdown: impl std::future::Future<Output = ()> + Send + 'static,
) -> std::io::Result<()> {
    if let Some(g) = &gateway {
        g.start(listener.local_addr().map(|a| a.port()).unwrap_or(0));
    }
    let d = app.daemon.clone();
    let flusher = tokio::spawn(async move {
        let mut t = tokio::time::interval(Duration::from_millis(FLUSH_MS));
        loop {
            t.tick().await;
            let d = d.clone();
            let _ = tokio::task::spawn_blocking(move || d.flush()).await;
        }
    });
    let daemon = app.daemon.clone();
    let r = axum::serve(listener, router_with(app, gateway.clone())).with_graceful_shutdown(shutdown).await;
    if let Some(g) = &gateway {
        g.stop();
    }
    flusher.abort();
    tokio::task::spawn_blocking(move || daemon.shutdown()).await.ok();
    r
}
