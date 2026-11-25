// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
//! The axum server: `/ws` (the v1 WebSocket, behind [`AccessPolicy`]) and `/health`.

use crate::auth::{self, AccessPolicy};
use crate::gateway::Gateway;
use crate::sessions::{Conn, Daemon};
use axum::extract::ws::{Message, WebSocket, WebSocketUpgrade};
use axum::extract::{ConnectInfo, State};
use axum::http::{header, HeaderMap, StatusCode, Uri};
use axum::response::{IntoResponse, Response};
use axum::routing::{get, post};
use axum::Router;
use infantry_protocol::Envelope;
use futures_util::StreamExt;
use std::sync::Arc;
use std::time::Duration;
use tokio::sync::broadcast::error::RecvError;

/// How often the store is written when something changed.
pub const FLUSH_MS: u64 = 250;
/// The largest body a plugin route is handed (a plugin may cap lower itself).
pub const ROUTE_BODY_MAX_BYTES: usize = 1_048_576;

pub const BUILD: &str = concat!("rust-", env!("CARGO_PKG_VERSION"));

/// RUST-BUG: what tells two builds of this daemon apart (the package version is the same for all of them): the size and
/// an FNV-1a hash of this executable's bytes. The app asks the bundled binary for the same id (`--build-id`) and
/// replaces a running daemon whose `/health` `buildId` differs. Computed once; "unknown" when the exe cannot be read.
pub fn build_id() -> &'static str {
    static ID: std::sync::OnceLock<String> = std::sync::OnceLock::new();
    ID.get_or_init(|| {
        let Some(bytes) = crate::own_files::own_binary().and_then(|e| std::fs::read(e).ok()) else { return "unknown".into() };
        let mut h: u64 = 0xcbf2_9ce4_8422_2325;
        for b in &bytes {
            h = (h ^ u64::from(*b)).wrapping_mul(0x0000_0100_0000_01b3);
        }
        format!("{:x}-{h:016x}", bytes.len())
    })
}

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
    /// BOS-7: set only when the OS started this daemon as an app (`BATTALION_CONTRACT`).
    os: Option<Arc<crate::os_app::OsRuntime>>,
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
    check_from(app, false, uri, headers)
}

/// [check] knowing whether the peer is on this computer (BOS-33: the OS's own page skips the phone token only then).
fn check_from(app: &App, peer_loopback: bool, uri: &Uri, headers: &HeaderMap) -> Option<Response> {
    let protocols = h(headers, header::SEC_WEBSOCKET_PROTOCOL);
    app.policy
        .check_from(peer_loopback, uri.path(), h(headers, header::HOST), h(headers, header::ORIGIN), h(headers, header::AUTHORIZATION), protocols)
        .map(refuse)
}

fn peer_is_loopback(peer: Option<&ConnectInfo<std::net::SocketAddr>>) -> bool {
    peer.is_some_and(|ConnectInfo(a)| a.ip().is_loopback())
}

/// BOS-33: `GET|POST /os/phone`, the Settings > Daemon phone-access rows of an app of BattalionOS. Served to the OS's own page
/// only (its origin, a loopback Host, a loopback peer): never to a paired phone or another origin, even one holding the token.
/// `GET ?reveal=1` adds the token (only when on); `POST` `{"enabled":bool}` writes launch.json and, when it changed,
/// asks the OS to restart the app (the daemon stops; the OS's normal restart brings it back with the new bind).
async fn os_phone(State(s): State<Shared>, peer: Option<axum::Extension<ConnectInfo<std::net::SocketAddr>>>, method: axum::http::Method, uri: Uri, headers: HeaderMap, body: axum::body::Bytes) -> Response {
    let Some(os) = &s.os else { return (StatusCode::NOT_FOUND, "not found").into_response() };
    let loopback = peer_is_loopback(peer.as_ref().map(|axum::Extension(c)| c));
    let origin = h(&headers, header::ORIGIN);
    let cors = origin.filter(|_| s.app.policy.is_os_page(loopback, h(&headers, header::HOST), origin));
    let Some(allowed) = cors else { return (StatusCode::FORBIDDEN, "phone access is set from the app's own page only").into_response() };
    let Some(state) = os.env.state_dir.clone() else { return (StatusCode::CONFLICT, "this app has no state folder to keep the phone settings in").into_response() };
    let mut resp = if method == axum::http::Method::OPTIONS {
        StatusCode::NO_CONTENT.into_response()
    } else if method == axum::http::Method::POST {
        let enabled = serde_json::from_slice::<serde_json::Value>(&body).ok().and_then(|v| v.get("enabled").and_then(|e| e.as_bool()));
        let Some(on) = enabled else { return (StatusCode::BAD_REQUEST, r#"send {"enabled": true|false}"#).into_response() };
        let changed = crate::phone::enabled(&state) != on;
        let port = os.bound_port();
        match tokio::task::spawn_blocking(move || crate::phone::set(&state, on, port)).await {
            Ok(Ok(())) => {}
            Ok(Err(e)) => return (StatusCode::INTERNAL_SERVER_ERROR, e).into_response(),
            Err(_) => return StatusCode::INTERNAL_SERVER_ERROR.into_response(),
        }
        if changed {
            os.request_stop();
        }
        ([(header::CONTENT_TYPE, "application/json")], serde_json::json!({"ok": true, "restart": changed}).to_string()).into_response()
    } else {
        let reveal = uri.query().is_some_and(|q| q.split('&').any(|p| p == "reveal=1" || p == "reveal=true"));
        let ifaces: Vec<(String, std::net::IpAddr)> = if_addrs::get_if_addrs().map(|v| v.into_iter().map(|i| (i.name.clone(), i.ip())).collect()).unwrap_or_default();
        let port = os.bound_port();
        let v = crate::phone::get(&state, port, &ifaces, reveal);
        ([(header::CONTENT_TYPE, "application/json"), (header::CACHE_CONTROL, "no-store")], v.to_string()).into_response()
    };
    let hd = resp.headers_mut();
    if let Ok(v) = allowed.parse() {
        hd.insert(header::ACCESS_CONTROL_ALLOW_ORIGIN, v);
    }
    hd.insert(header::VARY, "Origin".parse().unwrap());
    hd.insert(header::ACCESS_CONTROL_ALLOW_METHODS, "GET, POST, OPTIONS".parse().unwrap());
    hd.insert(header::ACCESS_CONTROL_ALLOW_HEADERS, "content-type".parse().unwrap());
    resp
}

async fn health(State(s): State<Shared>, uri: Uri, headers: HeaderMap) -> Response {
    let app = &s.app;
    if let Some(r) = check(app, &uri, &headers) {
        return r;
    }
    let mut body = serde_json::json!({
        "ok": true, "provider": app.daemon.harness.name(), "contract": crate::wire::CONTRACT,
        "build": BUILD, "buildId": build_id(), "sessions": app.daemon.session_count(),
    });
    // BOS-7: agent turns in flight, so the OS can ask before an update (only for an app daemon).
    if s.os.is_some() {
        use infantry_protocol::session::SessionState;
        body["busy"] = serde_json::json!(app.daemon.records().iter().filter(|r| matches!(r.state, SessionState::Busy | SessionState::Starting)).count());
    }
    if let Some(g) = &s.gateway {
        body["legacy"] = serde_json::json!({"port": g.legacy.port, "ready": g.backend_ready(), "pid": g.legacy.pid()});
    }
    ([(header::CONTENT_TYPE, "application/json")], body.to_string()).into_response()
}

/// BOS-7: `POST /stop` with the OS's per-start secret as bearer. The graceful path of [serve_with] does the rest.
async fn stop(State(s): State<Shared>, headers: HeaderMap) -> Response {
    let Some(os) = &s.os else { return (StatusCode::NOT_FOUND, "not found").into_response() };
    if !os.stop_authorized(h(&headers, header::AUTHORIZATION)) {
        let mut r = (StatusCode::UNAUTHORIZED, "the stop call needs the app's stop secret as a bearer token").into_response();
        r.headers_mut().insert(header::WWW_AUTHENTICATE, "Bearer".parse().unwrap());
        return r;
    }
    os.request_stop();
    ([(header::CONTENT_TYPE, "application/json")], r#"{"ok":true,"stopping":true}"#).into_response()
}

async fn ws(
    State(s): State<Shared>,
    peer: Option<axum::Extension<ConnectInfo<std::net::SocketAddr>>>,
    uri: Uri,
    headers: HeaderMap,
    upgrade: Result<WebSocketUpgrade, axum::extract::ws::rejection::WebSocketUpgradeRejection>,
) -> Response {
    let app = s.app;
    // Access first: an unauthenticated caller learns nothing about the route, upgrade or not.
    if let Some(r) = check_from(&app, peer_is_loopback(peer.as_ref().map(|axum::Extension(c)| c)), &uri, &headers) {
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

/// The connection burst: the session manager's frames, then the other units'.
async fn hello(daemon: &Arc<Daemon>) -> Vec<String> {
    let mut out = daemon.hello(BUILD);
    if let Some(u) = daemon.units() {
        out.extend(tokio::task::spawn_blocking(move || u.hello()).await.unwrap_or_default());
    }
    out
}

/// One frame this daemon answers: the session manager's own, else the unit that owns it (on the blocking pool).
/// Hop timings (debug, off by default): `INFANTRY_HOP_TIMINGS=1` makes the daemon log one line per client frame to stderr:
/// the frame type, what it took to answer, and the bytes written back. A slow line is a handler holding up the frames
/// behind it on that connection (the loop answers one frame at a time).
fn hop_timings() -> bool {
    static ON: std::sync::OnceLock<bool> = std::sync::OnceLock::new();
    *ON.get_or_init(|| std::env::var("INFANTRY_HOP_TIMINGS").is_ok_and(|v| !v.is_empty() && v != "0"))
}

/// Unit frames that outlived `Config::frame_detach_ms`: their replies are sent when they finish, not before the next frame.
type Detached = futures_util::stream::FuturesUnordered<tokio::task::JoinHandle<Vec<String>>>;

/// One frame. Session frames and the plugin-bridge note run in order on the connection (they are quick and own `conn`).
/// A unit frame runs on the blocking pool; one that is still running after `frame_detach_ms` is detached, so a slow
/// handler (a CLI probe, a git status) no longer holds up every frame behind it, the board's included.
async fn answer(daemon: &Arc<Daemon>, env: Envelope, conn: &mut Conn, owner: bool, detached: &mut Detached) -> Vec<String> {
    // A tool call carrying a session id is that session's MCP bridge alive on this connection.
    if env.r#type == crate::compose::PLUGIN_TOOL_CALL {
        if let Some(sid) = serde_json::from_str::<serde_json::Value>(&env.data).ok().and_then(|v| v["client"]["sessionId"].as_str().map(String::from)) {
            daemon.health.bridges.seen(&sid, &mut conn.bridges, &daemon.health.journal);
        }
    }
    let timings = hop_timings();
    if !crate::sessions::owns(&env.r#type) {
        if let Some(u) = daemon.units().filter(|u| u.owns(&env.r#type)) {
            let (ty, bytes_in) = (env.r#type.clone(), env.data.len());
            let mut job = tokio::task::spawn_blocking(move || {
                let t = std::time::Instant::now();
                let replies = u.handle(env, owner);
                if timings {
                    let out: usize = replies.iter().map(String::len).sum();
                    eprintln!("[hop] frame {ty} in={bytes_in}B answered in {:.1}ms out={out}B replies={}", t.elapsed().as_secs_f64() * 1000.0, replies.len());
                }
                replies
            });
            return match tokio::time::timeout(Duration::from_millis(daemon.cfg.frame_detach_ms), &mut job).await {
                Ok(done) => done.unwrap_or_default(),
                Err(_) => {
                    detached.push(job);
                    Vec::new()
                }
            };
        }
    }
    let (ty, bytes_in, t) = (env.r#type.clone(), env.data.len(), std::time::Instant::now());
    let replies = daemon.handle(env, conn);
    if timings {
        let out: usize = replies.iter().map(String::len).sum();
        eprintln!("[hop] frame {ty} in={bytes_in}B answered in {:.1}ms out={out}B replies={}", t.elapsed().as_secs_f64() * 1000.0, replies.len());
    }
    replies
}

/// The replies of a detached frame that has just finished (empty when it panicked).
fn finished(done: Option<Result<Vec<String>, tokio::task::JoinError>>) -> Vec<String> {
    done.and_then(|r| r.ok()).unwrap_or_default()
}

async fn connection(daemon: Arc<Daemon>, mut socket: WebSocket) {
    // Subscribe first so nothing pushed during the burst is lost.
    let mut rx = daemon.subscribe();
    for f in hello(&daemon).await {
        if socket.send(Message::Text(f.into())).await.is_err() {
            return;
        }
    }
    let mut conn = Conn::default();
    let mut detached = Detached::new();
    let mut cause = "connection lost";
    loop {
        tokio::select! {
            m = socket.recv() => {
                let text = match m {
                    Some(Ok(Message::Text(t))) => t.to_string(),
                    Some(Ok(Message::Close(_))) => { cause = crate::health::CLEAN_CLOSE; break }
                    None | Some(Err(_)) => break,
                    Some(Ok(_)) => continue,
                };
                // A client that passed the access policy without the gateway is the owner (local, or the token holder).
                let replies = match serde_json::from_str::<Envelope>(&text) {
                    Ok(env) => answer(&daemon, env, &mut conn, true, &mut detached).await,
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
            done = detached.next(), if !detached.is_empty() => {
                let mut gone = false;
                for r in finished(done) {
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
        }
    }
    daemon.health.bridges.closed(&conn.bridges, Some(cause), &daemon.health.journal);
}

/// A client of the strangler gateway: the frames a Rust unit owns are answered here, the rest go to the legacy backend;
/// the client gets Rust's pushes and the gateway's relays (Kotlin's broadcasts and its answers to this client).
async fn gateway_connection(daemon: Arc<Daemon>, gw: Arc<Gateway>, owner: bool, mut socket: WebSocket) {
    let mut rx = daemon.subscribe();
    let (id, mut relay) = gw.register();
    for f in gw.hello(owner, hello(&daemon).await).await {
        if socket.send(Message::Text(f.into())).await.is_err() {
            gw.unregister(id);
            return;
        }
    }
    let mut conn = Conn::default();
    let mut detached = Detached::new();
    let mut cause = "connection lost";
    loop {
        tokio::select! {
            m = socket.recv() => {
                let text = match m {
                    Some(Ok(Message::Text(t))) => t.to_string(),
                    Some(Ok(Message::Close(_))) => { cause = crate::health::CLEAN_CLOSE; break }
                    None | Some(Err(_)) => break,
                    Some(Ok(_)) => continue,
                };
                let replies = match serde_json::from_str::<Envelope>(&text) {
                    Ok(env) if gw.routes.is_rust(&env.r#type) => answer(&daemon, env, &mut conn, owner, &mut detached).await,
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
            done = detached.next(), if !detached.is_empty() => {
                let mut gone = false;
                for r in finished(done) {
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
    daemon.health.bridges.closed(&conn.bridges, Some(cause), &daemon.health.journal);
    gw.unregister(id);
}

/// RR-6b: the tunnel listener's router: `/ws` only, and every request needs a session token (a loopback Host or no token is
/// never enough). A request with an Origin header, or any other path, is refused.
pub fn tunnel_router(app: App) -> Router {
    Router::new().route("/ws", get(tunnel_ws)).with_state(app)
}

async fn tunnel_ws(
    State(app): State<App>,
    headers: HeaderMap,
    upgrade: Result<WebSocketUpgrade, axum::extract::ws::rejection::WebSocketUpgradeRejection>,
) -> Response {
    if h(&headers, header::ORIGIN).is_some() {
        return refuse(auth::Refusal { status: 403, reason: "requests carrying an Origin header are refused on the tunnel", challenge_bearer: false });
    }
    let Some(sid) = crate::tunnel::bearer(h(&headers, header::AUTHORIZATION)).and_then(|t| app.daemon.tunnel.resolve(t)) else {
        return refuse(auth::Refusal { status: 401, reason: "a session token is required", challenge_bearer: true });
    };
    match upgrade {
        Ok(u) => u.on_upgrade(move |socket| tunnel_connection(app.daemon, sid, socket)),
        Err(e) => e.into_response(),
    }
}

/// One tunnel connection, bound to [sid]: only that session's MCP frames are answered, no broadcast is forwarded, and the
/// hello carries no session list.
async fn tunnel_connection(daemon: Arc<Daemon>, sid: String, mut socket: WebSocket) {
    let hello = [
        crate::sessions::encode_frame(crate::wire::READY, crate::wire::ready(daemon.harness.name(), &daemon.instance, BUILD)),
        crate::sessions::encode_frame(crate::wire::POLICY, crate::wire::policy()),
    ];
    for f in hello {
        if socket.send(Message::Text(f.into())).await.is_err() {
            return;
        }
    }
    let mut conn = Conn::default();
    let mut detached = Detached::new();
    loop {
        tokio::select! {
            m = socket.recv() => {
                let text = match m {
                    Some(Ok(Message::Text(t))) => t.to_string(),
                    Some(Ok(Message::Close(_))) | None | Some(Err(_)) => break,
                    Some(Ok(_)) => continue,
                };
                // The token may have ended with its session while the connection stayed open.
                if daemon.tunnel.resolve_session(&sid).is_none() {
                    break;
                }
                let replies = match serde_json::from_str::<Envelope>(&text) {
                    Ok(env) => match crate::tunnel::scope_refusal(&sid, &env.r#type, env.session_id.as_deref(), &env.data) {
                        Some(why) => {
                            let mut e = Envelope::new(crate::wire::ERROR, format!("403 forbidden: {why}"));
                            e.request_id = env.request_id.clone();
                            vec![serde_json::to_string(&e).unwrap_or_default()]
                        }
                        None => answer(&daemon, env, &mut conn, true, &mut detached).await,
                    },
                    Err(e) => vec![serde_json::to_string(&Envelope::new(crate::wire::ERROR, format!("frame refused: not an envelope ({e})"))).unwrap_or_default()],
                };
                for r in replies {
                    if socket.send(Message::Text(r.into())).await.is_err() {
                        return;
                    }
                }
            }
            done = detached.next(), if !detached.is_empty() => {
                for r in finished(done) {
                    if socket.send(Message::Text(r.into())).await.is_err() {
                        return;
                    }
                }
            }
        }
    }
    daemon.health.bridges.closed(&conn.bridges, Some("tunnel connection closed"), &daemon.health.journal);
}

pub fn router(app: App) -> Router {
    router_with(app, None, None)
}

/// The units' HTTP routes (`/mail/*`) pass the same access policy as `/ws`.
async fn guarded(State(s): State<Shared>, req: axum::extract::Request, next: axum::middleware::Next) -> Response {
    if let Some(r) = check(&s.app, req.uri(), req.headers()) {
        return r;
    }
    next.run(req).await
}

/// Any other path: a route a loaded plugin declares, behind the access policy unless it is a public route (whose plugin
/// authenticates it, e.g. a webhook's signature).
async fn plugin_route(State(s): State<Shared>, req: axum::extract::Request) -> Response {
    let (method, path) = (req.method().as_str().to_string(), req.uri().path().to_string());
    let Some(units) = s.app.daemon.units() else { return (StatusCode::NOT_FOUND, "not found").into_response() };
    let Some(public) = units.route(&method, &path) else { return (StatusCode::NOT_FOUND, "not found").into_response() };
    if !public {
        if let Some(r) = check(&s.app, req.uri(), req.headers()) {
            return r;
        }
    }
    let query = req.uri().query().unwrap_or("").to_string();
    let headers: Vec<(String, String)> = req.headers().iter().filter_map(|(k, v)| Some((k.to_string(), v.to_str().ok()?.to_string()))).collect();
    let body = match axum::body::to_bytes(req.into_body(), ROUTE_BODY_MAX_BYTES).await {
        Ok(b) => b.to_vec(),
        Err(_) => return (StatusCode::PAYLOAD_TOO_LARGE, format!("body is larger than {ROUTE_BODY_MAX_BYTES} bytes")).into_response(),
    };
    let call = crate::sessions::RouteCall { method, path, query, headers, body };
    let (status, hdrs, body) = tokio::task::spawn_blocking(move || units.serve_route(call)).await.unwrap_or((500, Vec::new(), b"the route failed".to_vec()));
    let mut resp = Response::builder().status(StatusCode::from_u16(status).unwrap_or(StatusCode::INTERNAL_SERVER_ERROR));
    for (k, v) in hdrs {
        resp = resp.header(k, v);
    }
    resp.body(axum::body::Body::from(body)).unwrap_or_else(|_| StatusCode::INTERNAL_SERVER_ERROR.into_response())
}

fn router_with(app: App, gateway: Option<Arc<Gateway>>, os: Option<Arc<crate::os_app::OsRuntime>>) -> Router {
    let shared = Shared { app: app.clone(), gateway, os: os.clone() };
    let mut r = Router::new().route("/ws", get(ws)).route("/health", get(health));
    if os.is_some() {
        r = r.route(crate::os_app::STOP_PATH, post(stop)).route("/os/phone", axum::routing::any(os_phone));
    }
    let mut r = r.fallback(plugin_route).with_state(shared.clone());
    if let Some(extra) = app.daemon.units().and_then(|u| u.http()) {
        r = r.merge(extra.layer(axum::middleware::from_fn_with_state(shared, guarded)));
    }
    r
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
    serve_os(listener, app, gateway, None, shutdown).await
}

/// [serve_with], as an app of BattalionOS when `os` is set: it adds `POST /stop` and `busy` in `/health`. The stop route
/// only asks; the caller's `shutdown` (which waits on [crate::os_app::OsRuntime::stopped]) ends the server gracefully.
pub async fn serve_os(
    listener: tokio::net::TcpListener,
    app: App,
    gateway: Option<Arc<Gateway>>,
    os: Option<Arc<crate::os_app::OsRuntime>>,
    shutdown: impl std::future::Future<Output = ()> + Send + 'static,
) -> std::io::Result<()> {
    if let Some(g) = &gateway {
        g.start(listener.local_addr().map(|a| a.port()).unwrap_or(0));
    }
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
                    d.health.journal.heartbeat();
                }
                d.flush()
            })
            .await;
        }
    });
    let daemon = app.daemon.clone();
    // RR-6b: the listener a remote runner's loopback forward targets (token-only; the main port trusts loopback).
    let tunnel = tokio::net::TcpListener::bind(("127.0.0.1", 0)).await.ok();
    if let Some(t) = &tunnel {
        daemon.tunnel.set_port(t.local_addr().map(|a| a.port()).unwrap_or(0));
    }
    let tunnel_app = app.clone();
    let tunnel_task = tunnel.map(|t| tokio::spawn(async move { let _ = axum::serve(t, tunnel_router(tunnel_app)).await; }));
    let r = axum::serve(listener, router_with(app, gateway.clone(), os).into_make_service_with_connect_info::<std::net::SocketAddr>()).with_graceful_shutdown(shutdown).await;
    if let Some(g) = &gateway {
        g.stop();
    }
    flusher.abort();
    if let Some(t) = tunnel_task {
        t.abort();
    }
    daemon.health.journal.end(if r.is_ok() { "clean stop" } else { "server error" });
    tokio::task::spawn_blocking(move || daemon.shutdown()).await.ok();
    r
}
