// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
//! The axum server: `/ws` (the v1 WebSocket, behind [`AccessPolicy`]) and `/health`.

use crate::auth::{self, AccessPolicy};
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

async fn health(State(app): State<App>, uri: Uri, headers: HeaderMap) -> Response {
    if let Some(r) = check(&app, &uri, &headers) {
        return r;
    }
    let body = serde_json::json!({
        "ok": true, "provider": app.daemon.harness.name(), "contract": crate::wire::CONTRACT,
        "build": BUILD, "sessions": app.daemon.session_count(),
    });
    ([(header::CONTENT_TYPE, "application/json")], body.to_string()).into_response()
}

async fn ws(
    State(app): State<App>,
    uri: Uri,
    headers: HeaderMap,
    upgrade: Result<WebSocketUpgrade, axum::extract::ws::rejection::WebSocketUpgradeRejection>,
) -> Response {
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
    upgrade.on_upgrade(move |socket| connection(app.daemon, socket))
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

pub fn router(app: App) -> Router {
    Router::new().route("/ws", get(ws)).route("/health", get(health)).with_state(app)
}

/// Serves until the listener fails or `shutdown` resolves; the store is flushed every [FLUSH_MS] and at the end.
pub async fn serve(listener: tokio::net::TcpListener, app: App, shutdown: impl std::future::Future<Output = ()> + Send + 'static) -> std::io::Result<()> {
    let d = app.daemon.clone();
    let flusher = tokio::spawn(async move {
        let mut t = tokio::time::interval(Duration::from_millis(FLUSH_MS));
        let every = (crate::held::REFRESH_MS / FLUSH_MS).max(1);
        let mut n = 0u64;
        loop {
            t.tick().await;
            n += 1;
            let d = d.clone();
            let refresh = n % every == 0;
            let _ = tokio::task::spawn_blocking(move || {
                if refresh {
                    d.refresh_held();
                }
                d.flush()
            })
            .await;
        }
    });
    let daemon = app.daemon.clone();
    let r = axum::serve(listener, router(app)).with_graceful_shutdown(shutdown).await;
    flusher.abort();
    tokio::task::spawn_blocking(move || daemon.shutdown()).await.ok();
    r
}
