// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
//! The vault frames on the [`FrameUnit`] contract.
//!
//! - keystore frames ([`crate::handler`]): answered by `secret_state`; a change is also published on the bus so every
//!   client sees it (the requester gets its direct answer only when it sent a `request_id`).
//! - `secret_reveal`, `secret_backup_export`, `secret_backup_import` ([`crate::vault`]): owner only, answered with the
//!   request's own type, never pushed. `secret_reveal` BLOCKS on the Windows Hello prompt and an export / import on the
//!   key derivation: the daemon dispatches them on a blocking thread.
//! - `secret_render` / `secret_find` / `run_secrets`: names, placeholders and paths only (plain-text answers).
//! - `secret_audit_get` (owner): the last audit lines; `secret_verify_check`: what a reveal would use now, no prompt.
use std::path::PathBuf;
use std::sync::Arc;

use infantry_core::frames::{FrameCtx, FrameUnit, Refusal};
use infantry_protocol::envelope::Envelope;
use infantry_protocol::frames as f;
use serde::Deserialize;
use serde_json::json;

use crate::handler::{decode, HandlerDeps, KeystoreHandler};
use crate::keystore::Keystore;
use crate::limits::Limits;
use crate::scrub::SecretMask;
use crate::template::{self, RunSecrets, SecretRenderer};
use crate::vault::{ExportRequest, ImportRequest, RevealRequest, SecretVault};
use crate::VaultError;

pub const SECRET_RENDER: &str = "secret_render";
pub const SECRET_FIND: &str = "secret_find";
pub const RUN_SECRETS: &str = "run_secrets";
pub const SECRET_AUDIT_GET: &str = "secret_audit_get";
pub const SECRET_AUDIT: &str = "secret_audit";
pub const SECRET_VERIFY_CHECK: &str = "secret_verify_check";

pub const FRAMES: &[&str] = &[
    f::SECRET_STATUS,
    f::SECRET_SET,
    f::SECRET_CLEAR,
    f::SECRET_IMPORT,
    f::SECRET_FIELD_SET,
    f::SECRET_FIELD_DELETE,
    f::SECRET_DELETE,
    f::SECRET_MOVE,
    f::SECRET_LOGIN,
    f::PLUGIN_SECRET_GET,
    f::PLUGIN_SECRET_SET,
    f::PLUGIN_SECRET_CLEAR,
    f::SECRET_REVEAL,
    f::SECRET_BACKUP_EXPORT,
    f::SECRET_BACKUP_IMPORT,
    SECRET_RENDER,
    SECRET_FIND,
    RUN_SECRETS,
    SECRET_AUDIT_GET,
    SECRET_VERIFY_CHECK,
];

/// Frames that only the owner connection may send (the rest read names only).
const OWNER_ONLY: &[&str] = &[
    f::SECRET_SET,
    f::SECRET_CLEAR,
    f::SECRET_IMPORT,
    f::SECRET_FIELD_SET,
    f::SECRET_FIELD_DELETE,
    f::SECRET_DELETE,
    f::SECRET_MOVE,
    f::SECRET_LOGIN,
    f::PLUGIN_SECRET_GET,
    f::PLUGIN_SECRET_SET,
    f::PLUGIN_SECRET_CLEAR,
    f::SECRET_REVEAL,
    f::SECRET_BACKUP_EXPORT,
    f::SECRET_BACKUP_IMPORT,
    SECRET_RENDER,
    SECRET_AUDIT_GET,
];

pub type RootFn = Arc<dyn Fn(Option<&str>) -> Option<PathBuf> + Send + Sync>;

/// Everything the vault unit is made of. Build with [`VaultUnit::open`] (the daemon's state dir) or field by field.
pub struct VaultUnit {
    handler: Arc<KeystoreHandler>,
    vault: Arc<SecretVault>,
    renderer: SecretRenderer,
    run: RunSecrets,
    root_of: RootFn,
}

#[derive(Deserialize)]
struct RenderReq {
    template: Option<String>,
    #[serde(default)]
    out: Option<String>,
    #[serde(default)]
    project: Option<String>,
    #[serde(default, rename = "allowMissing")]
    allow_missing: bool,
    #[serde(default)]
    check: bool,
}

#[derive(Deserialize, Default)]
struct FindReq {
    #[serde(default)]
    q: Option<String>,
    #[serde(default)]
    project: Option<String>,
    #[serde(default)]
    scope: Option<String>,
}

fn blank(s: Option<String>) -> Option<String> {
    s.filter(|v| !v.trim().is_empty())
}

impl VaultUnit {
    /// The vault of state dir `state_dir`: the keystore (platform OS vault), the platform verifier, the audit log beside
    /// it. `root_of` maps a project id to its folder (relative render paths).
    pub fn open(state_dir: impl Into<PathBuf>, limits: Limits, mut deps: HandlerDeps, root_of: RootFn) -> Self {
        let keystore = Arc::new(Keystore::open(state_dir, limits.clone()));
        let vault = Arc::new(SecretVault::new(keystore.clone(), limits.clone()));
        if deps.audit.is_none() {
            let v = vault.clone();
            deps.audit = Some(Arc::new(move |action, name, field, project| v.append_audit(action, name, field, project, "", "ok", "")));
        }
        Self::with(keystore, vault, limits, deps, root_of)
    }

    pub fn with(keystore: Arc<Keystore>, vault: Arc<SecretVault>, limits: Limits, deps: HandlerDeps, root_of: RootFn) -> Self {
        let restricted = deps.restricted.clone();
        let handler = Arc::new(KeystoreHandler::new(keystore.clone(), deps));
        let h = handler.clone();
        let run = RunSecrets::new(keystore, Arc::new(move |n| !h.is_run_secret(n)));
        let renderer = SecretRenderer::new(run.clone(), restricted, limits);
        VaultUnit { handler, vault, renderer, run, root_of }
    }

    pub fn keystore(&self) -> &Arc<Keystore> {
        self.handler.keystore()
    }

    pub fn handler(&self) -> &Arc<KeystoreHandler> {
        &self.handler
    }

    pub fn vault(&self) -> &Arc<SecretVault> {
        &self.vault
    }

    /// The run-secret view (Run plugin, MCP tools, Lua): names, fields for a run, placeholders.
    pub fn run_secrets(&self) -> &RunSecrets {
        &self.run
    }

    pub fn renderer(&self) -> &SecretRenderer {
        &self.renderer
    }

    /// The output scrubber over every keystore value: the daemon applies it to every session and tool stream.
    pub fn mask(&self) -> SecretMask {
        SecretMask::of(self.keystore().clone())
    }

    fn run(&self, env: &Envelope, ctx: &FrameCtx) -> Result<Vec<Envelope>, VaultError> {
        let t = env.r#type.as_str();
        if OWNER_ONLY.contains(&t) && !ctx.owner {
            return Err(VaultError::invalid("owner only"));
        }
        // SVC-SEC-1: a plugin's secret is read only by the plugin host, in process (conn 0, the module pinned by
        // `own_secret_request`). A WebSocket client (the app, the CLI, `infantry mcp serve`, an agent) never reads it,
        // even holding the owner token; the owner sets and clears it, never sees it.
        if t == f::PLUGIN_SECRET_GET && ctx.conn_id != 0 {
            return Err(VaultError::invalid(template::PLUGIN_SECRET_REFUSAL));
        }
        if let Some(a) = self.handler.answer(t, &env.data) {
            let a = a?;
            if a.push {
                ctx.bus.publish(a.reply.clone());
                if env.request_id.is_none() {
                    return Ok(Vec::new());
                }
            }
            return Ok(vec![a.reply]);
        }
        let restricted = || -> Result<(), VaultError> {
            if self.handler.restricted() {
                Err(VaultError::invalid("secrets are locked under a restricted policy"))
            } else {
                Ok(())
            }
        };
        match t {
            f::SECRET_REVEAL => {
                restricted()?;
                let r = self.vault.reveal(&decode::<RevealRequest>(&env.data)?)?;
                Ok(vec![Envelope::new(t, to_json(&r)?)])
            }
            f::SECRET_BACKUP_EXPORT => {
                restricted()?;
                let r = self.vault.export(&decode::<ExportRequest>(&env.data)?)?;
                Ok(vec![Envelope::new(t, to_json(&r)?)])
            }
            f::SECRET_BACKUP_IMPORT => {
                restricted()?;
                let r = self.vault.import(&decode::<ImportRequest>(&env.data)?)?;
                if r.applied && (!r.added.is_empty() || !r.replaced.is_empty()) {
                    if let Ok(s) = self.handler.state_envelope() {
                        ctx.bus.publish(s);
                    }
                }
                Ok(vec![Envelope::new(t, to_json(&r)?)])
            }
            SECRET_RENDER => {
                let req: RenderReq = decode(&env.data)?;
                let template = blank(req.template).ok_or_else(|| VaultError::invalid("a template file is required"))?;
                let project = blank(req.project);
                let root = (self.root_of)(project.as_deref());
                let res = self.renderer.render_in(root.as_deref(), &template, blank(req.out).as_deref(), project.as_deref(), req.allow_missing, req.check);
                if res.ok() {
                    Ok(vec![Envelope::new(t, res.summary(req.check))])
                } else {
                    // The summary already reads "render refused: ...".
                    Err(VaultError::Invalid(res.summary(req.check).trim_start_matches("render refused: ").to_string()))
                }
            }
            SECRET_FIND => {
                let req: FindReq = if env.data.trim().is_empty() { FindReq::default() } else { decode(&env.data)? };
                let q = blank(req.q).ok_or_else(|| VaultError::invalid("q is required"))?;
                Ok(vec![Envelope::new(t, template::secret_find(&self.run, &q, blank(req.project).as_deref(), req.scope.as_deref())?)])
            }
            RUN_SECRETS => {
                let req: FindReq = if env.data.trim().is_empty() { FindReq::default() } else { decode(&env.data)? };
                Ok(vec![Envelope::new(t, template::run_secrets(&self.run, blank(req.project).as_deref(), req.scope.as_deref())?)])
            }
            SECRET_AUDIT_GET => {
                let limit = serde_json::from_str::<serde_json::Value>(&env.data).ok().and_then(|v| v.get("limit").and_then(|l| l.as_u64())).unwrap_or(200).min(2_000);
                Ok(vec![Envelope::new(SECRET_AUDIT, json!({ "entries": self.vault.audit_tail(limit as usize) }).to_string())])
            }
            SECRET_VERIFY_CHECK => {
                let c = self.vault.check();
                let b = json!({ "label": self.vault.verifier_label(), "available": c.is_verified(), "method": c.method, "detail": c.detail });
                Ok(vec![Envelope::new(t, b.to_string())])
            }
            other => Err(VaultError::invalid(format!("{other} is not a vault frame"))),
        }
    }
}

fn to_json<T: serde::Serialize>(v: &T) -> Result<String, VaultError> {
    serde_json::to_string(v).map_err(|_| VaultError::failed("the answer could not be encoded"))
}

impl FrameUnit for VaultUnit {
    fn name(&self) -> &'static str {
        "vault"
    }

    fn frames(&self) -> &'static [&'static str] {
        FRAMES
    }

    fn handle(&self, env: &Envelope, ctx: &FrameCtx) -> Result<Vec<Envelope>, Refusal> {
        match self.run(env, ctx) {
            Ok(mut out) => {
                for e in &mut out {
                    e.request_id = env.request_id.clone();
                }
                Ok(out)
            }
            Err(e) => Err(Refusal(e.wire(&env.r#type))),
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::verify::{Fixed, Verification};
    use infantry_core::bus::EventBus;

    #[test]
    fn frames_change_list_and_refuse_without_values() {
        let d = std::env::temp_dir().join(format!("infantry-vault-unit-{}", std::process::id()));
        let _ = std::fs::remove_dir_all(&d);
        let ks = Arc::new(Keystore::with(&d, Arc::new(|_| None), None, Limits::default()));
        let vault = Arc::new(SecretVault::with(ks.clone(), Box::new(Fixed(Verification::unavailable("no Hello"))), d.join("a.jsonl"), Limits::default(), Arc::new(|| 0)));
        let unit = VaultUnit::with(ks, vault, Limits::default(), HandlerDeps::default(), Arc::new(|_| None));
        let owner = FrameCtx { conn_id: 1, owner: true, bus: EventBus::new(8) };
        let guest = FrameCtx { conn_id: 2, owner: false, bus: EventBus::new(8) };
        let mut set = Envelope::new(f::SECRET_FIELD_SET, r#"{"name":"db","field":"PASS","value":"fake-pass-0123456789"}"#);
        set.request_id = Some("r1".into());
        let out = unit.handle(&set, &owner).unwrap();
        assert_eq!((out[0].r#type.as_str(), out[0].request_id.as_deref()), ("secret_state", Some("r1")));
        assert!(out[0].data.contains(r#""entries":[{"name":"db","fields":[{"name":"PASS","tail":"••••6789"}]}]"#), "{}", out[0].data);
        assert!(!out[0].data.contains("fake-pass"));
        assert_eq!(unit.handle(&set, &guest).unwrap_err().0, "secret_field_set refused: owner only");
        let bad = Envelope::new(f::SECRET_FIELD_SET, r#"{"name":"db","field":"PASS","value":"fake","extra":1}"#);
        assert_eq!(unit.handle(&bad, &owner).unwrap_err().0, "secret_field_set refused: malformed request");
        let builtin = Envelope::new(f::SECRET_FIELD_SET, r#"{"name":"update-token","field":"value","value":"fake"}"#);
        assert_eq!(unit.handle(&builtin, &owner).unwrap_err().0, "secret_field_set refused: 'update-token' is a built-in secret");
        let rev = Envelope::new(f::SECRET_REVEAL, r#"{"name":"db","field":"PASS"}"#);
        assert_eq!(unit.handle(&rev, &owner).unwrap_err().0, "secret_reveal refused: no Hello");
        let list = unit.handle(&Envelope::new(RUN_SECRETS, ""), &guest).unwrap();
        assert_eq!(list[0].data, "db  (app)  {{secret:db}}");
        let find = unit.handle(&Envelope::new(SECRET_FIND, r#"{"q":"pass"}"#), &guest).unwrap();
        assert!(find[0].data.starts_with("no secret or field matches 'pass'"));
        assert_eq!(unit.mask().mask("x fake-pass-0123456789 y"), "x •••• y");
        let _ = std::fs::remove_dir_all(&d);
    }

    /// SVC-SEC-1: a plugin's secret is refused to every render, find and list, and to `plugin_secret_get` from any
    /// WebSocket client (even an owner token: the CLI, `infantry mcp serve`, an agent). Only the in-process plugin host
    /// (conn 0) reads it; a user's secret still renders.
    #[test]
    fn plugin_secrets_reach_only_their_plugin() {
        let d = std::env::temp_dir().join(format!("infantry-vault-svcsec1-{}", std::process::id()));
        let _ = std::fs::remove_dir_all(&d);
        std::fs::create_dir_all(&d).unwrap();
        let ks = Arc::new(Keystore::with(d.join("ks"), Arc::new(|_| None), None, Limits::default()));
        ks.set_field("plugin.acme_x", "KEY", "fake-plugin-key-42", None).unwrap();
        ks.set_field("db", "PASS", "fake-user-pass-7", None).unwrap();
        let vault = Arc::new(SecretVault::with(ks.clone(), Box::new(Fixed(Verification::unavailable("no Hello"))), d.join("a.jsonl"), Limits::default(), Arc::new(|| 0)));
        let root = d.clone();
        let unit = VaultUnit::with(ks, vault, Limits::default(), HandlerDeps::default(), Arc::new(move |_| Some(root.clone())));
        let client = FrameCtx { conn_id: 7, owner: true, bus: EventBus::new(8) };
        let host = FrameCtx { conn_id: 0, owner: true, bus: EventBus::new(8) };
        std::fs::write(d.join("t.env"), "K={{secret:plugin.acme_x#KEY}}
").unwrap();
        let r = Envelope::new(SECRET_RENDER, r#"{"template":"t.env","out":"o.env"}"#);
        let e = unit.handle(&r, &client).unwrap_err().0;
        assert!(e.contains("plugin secrets are only for their plugin"), "{e}");
        assert!(!d.join("o.env").exists() && !e.contains("fake-plugin"));
        std::fs::write(d.join("t.env"), "K={{secret:plugin.acme_x}}
").unwrap();
        assert!(unit.handle(&r, &client).unwrap_err().0.contains("only for their plugin"));
        for q in ["acme", "plugin", "KEY"] {
            let out = unit.handle(&Envelope::new(SECRET_FIND, format!(r#"{{"q":"{q}"}}"#)), &client).unwrap();
            assert!(!out[0].data.contains("plugin.acme_x"), "{}", out[0].data);
        }
        assert!(!unit.handle(&Envelope::new(RUN_SECRETS, ""), &client).unwrap()[0].data.contains("plugin."));
        let get = Envelope::new(f::PLUGIN_SECRET_GET, r#"{"module":"acme_x","field":"KEY"}"#);
        assert_eq!(unit.handle(&get, &client).unwrap_err().0, "plugin_secret_get refused: plugin secrets are only for their plugin");
        assert!(unit.handle(&get, &host).unwrap()[0].data.contains("fake-plugin-key-42"));
        std::fs::write(d.join("t.env"), "P={{secret:db#PASS}}
").unwrap();
        unit.handle(&r, &client).unwrap();
        assert_eq!(std::fs::read_to_string(d.join("o.env")).unwrap(), "P=fake-user-pass-7
");
        let _ = std::fs::remove_dir_all(&d);
    }

}
