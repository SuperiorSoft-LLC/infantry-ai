// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
//! The `/mail/*` HTTP routes the CLI and scripts use. Bodies and status codes match the Kotlin `MailHttp`:
//! 200 + the stored message, 400 + `MailRefused` (or a malformed-payload refusal), 404 for an unknown read.
//! The daemon merges [`routes`] into its axum router behind its own auth layer.
use std::collections::HashMap;
use std::sync::Arc;

use axum::body::Bytes;
use axum::extract::{Path, Query, State};
use axum::http::StatusCode;
use axum::response::{IntoResponse, Response};
use axum::routing::{get, post};
use axum::{Json, Router};
use serde_json::json;

use super::mailbox::{FanOutResult, MailResult};
use super::message::{MailFanOutRequest, MailReadRequest, MailRefused, MailSendRequest};
use super::router::MailRouter;

pub fn routes(router: Arc<MailRouter>) -> Router {
    Router::new()
        .route("/mail/send", post(send))
        .route("/mail/fanout", post(fan_out))
        .route("/mail/read", post(read))
        .route("/mail/inbox", get(inbox))
        .route("/mail/outbox", get(outbox))
        .route("/mail/thread/{thread_id}", get(thread))
        .route("/mail/threads", get(threads))
        .route("/mail/waiting", get(waiting))
        .with_state(router)
}

fn refused(from: &str, to: &str, thread: Option<String>, reason: impl Into<String>) -> Response {
    (StatusCode::BAD_REQUEST, Json(MailRefused { from: from.into(), to: to.into(), thread_id: thread, reason: reason.into() })).into_response()
}

async fn send(State(r): State<Arc<MailRouter>>, body: Bytes) -> Response {
    let Ok(req) = serde_json::from_slice::<MailSendRequest>(&body) else {
        return refused("", "", None, "malformed mail_send payload");
    };
    match r.send(&req, false) {
        MailResult::Sent(m) => Json(r.mailbox().message(&m.id).unwrap_or(m)).into_response(),
        MailResult::Refused(reason) => refused(&req.from, &req.to, req.thread_id.clone(), reason),
    }
}

async fn fan_out(State(r): State<Arc<MailRouter>>, body: Bytes) -> Response {
    let Ok(req) = serde_json::from_slice::<MailFanOutRequest>(&body) else {
        return refused("", "", None, "malformed mail_fanout payload");
    };
    match r.fan_out(&req, false) {
        FanOutResult::Sent(ms) => Json(ms).into_response(),
        FanOutResult::Refused(reason) => refused(&req.from, &req.recipients.join(","), req.thread_id.clone(), reason),
    }
}

async fn read(State(r): State<Arc<MailRouter>>, body: Bytes) -> Response {
    let Ok(req) = serde_json::from_slice::<MailReadRequest>(&body) else {
        return (StatusCode::BAD_REQUEST, Json(json!({ "error": "malformed mail_read payload" }))).into_response();
    };
    let found = r.mailbox().find(&req.message_id).ok().flatten();
    match found.and_then(|m| r.mailbox().mark_read(&m.id)) {
        Some(m) => Json(m).into_response(),
        None => (StatusCode::NOT_FOUND, Json(json!({ "error": format!("unknown or unreadable message {}", req.message_id) }))).into_response(),
    }
}

async fn inbox(State(r): State<Arc<MailRouter>>, Query(q): Query<HashMap<String, String>>) -> Response {
    Json(r.mailbox().inbox(q.get("session").map(String::as_str).unwrap_or(""))).into_response()
}

async fn outbox(State(r): State<Arc<MailRouter>>, Query(q): Query<HashMap<String, String>>) -> Response {
    Json(r.mailbox().outbox(q.get("session").map(String::as_str).unwrap_or(""))).into_response()
}

async fn thread(State(r): State<Arc<MailRouter>>, Path(thread_id): Path<String>) -> Response {
    Json(r.mailbox().thread(&thread_id)).into_response()
}

async fn threads(State(r): State<Arc<MailRouter>>) -> Response {
    Json(r.mailbox().threads()).into_response()
}

async fn waiting(State(r): State<Arc<MailRouter>>) -> Response {
    Json(r.waiting()).into_response()
}
