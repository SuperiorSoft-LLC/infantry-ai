// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
//! The keystore frames (SECRETS-1/2/2b, Kotlin `KeystoreHandler`): `secret_status` / `secret_set` / `secret_clear` /
//! `secret_import` for the built-in single-value secrets, `secret_field_set` / `secret_field_delete` / `secret_delete` /
//! `secret_move` for the user's key/value secrets, `secret_login` and `plugin_secret_get`. Each change is answered by one
//! `secret_state` (names, masked tails, never a value). Under a restricted policy every change is refused.
//! A refusal never echoes the frame's data: a decode error says only "malformed request".
use std::sync::Arc;

use serde::de::DeserializeOwned;
use serde::{Deserialize, Serialize};

use crate::keystore::{self, EnvFn, Keystore, UPDATE_TOKEN, VALUE_FIELD};
use crate::template::RestrictedFn;
use crate::VaultError;

/// A secret the app knows how to use, as it is right now (harness keys come and go; their state is read per call).
#[derive(Debug, Clone, Default, PartialEq, Eq)]
pub struct KnownSecret {
    pub name: String,
    pub label: String,
    /// The older environment variable that still overrides it.
    pub env_alias: Option<String>,
    pub help: String,
    pub auth_modes: Vec<String>,
    pub auth_mode: String,
    pub login_command: String,
    /// "true" / "false" / "unknown".
    pub signed_in: String,
    pub last_probe: i64,
    pub uses_secret: String,
    pub for_mode: String,
    pub can_login: bool,
    pub login_state: String,
    pub login_url: String,
    pub login_message: String,
    pub login_code_urls: Vec<String>,
}

impl KnownSecret {
    pub fn new(name: &str, label: &str, env_alias: Option<&str>) -> Self {
        KnownSecret { name: name.into(), label: label.into(), env_alias: env_alias.map(Into::into), signed_in: "unknown".into(), ..Default::default() }
    }
}

/// The built-ins every daemon has: the update token.
pub fn default_known() -> Vec<KnownSecret> {
    vec![KnownSecret::new(UPDATE_TOKEN, "Update token", Some("INFANTRY_UPDATE_TOKEN"))]
}

/// The keystore name holding a plugin module's endpoint credentials.
pub fn plugin_secret_name(module: &str) -> String {
    format!("plugin.{}", module.to_lowercase())
}

pub const LOGIN_ACTIONS: &[&str] = &["start", "input", "cancel"];

// ---- wire ----

#[derive(Serialize, Deserialize, Debug, Clone, PartialEq, Eq)]
#[serde(rename_all = "camelCase")]
pub struct SecretRow {
    pub name: String,
    pub label: String,
    pub set: bool,
    pub tail: String,
    pub importable: bool,
    pub overridden_by_env: bool,
    pub help: String,
    pub auth_modes: Vec<String>,
    pub auth_mode: String,
    pub login_command: String,
    pub signed_in: String,
    pub last_probe: i64,
    pub uses_secret: String,
    pub for_mode: String,
    pub can_login: bool,
    pub login_state: String,
    pub login_url: String,
    pub login_message: String,
    /// Address prefixes of the page that shows the login code (a harness declaration); empty = paste only.
    #[serde(default)]
    pub login_code_urls: Vec<String>,
}

#[derive(Serialize, Deserialize, Debug, Clone, PartialEq, Eq)]
pub struct SecretFieldRow {
    pub name: String,
    pub tail: String,
}

#[derive(Serialize, Deserialize, Debug, Clone, PartialEq, Eq)]
pub struct SecretEntryRow {
    pub name: String,
    pub fields: Vec<SecretFieldRow>,
    /// The owning project; the app scope is left off the wire.
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub project: Option<String>,
}

#[derive(Serialize, Deserialize, Debug, Clone, PartialEq, Eq)]
pub struct SecretState {
    pub protection: String,
    pub secrets: Vec<SecretRow>,
    pub entries: Vec<SecretEntryRow>,
    pub locked: bool,
}

#[derive(Deserialize)]
#[serde(deny_unknown_fields)]
struct SecretSet {
    name: String,
    value: String,
}

#[derive(Deserialize)]
#[serde(deny_unknown_fields)]
struct SecretName {
    name: String,
    #[serde(default)]
    project: Option<String>,
}

#[derive(Deserialize)]
#[serde(deny_unknown_fields)]
struct SecretFieldSet {
    name: String,
    field: String,
    value: String,
    #[serde(default)]
    project: Option<String>,
}

#[derive(Deserialize)]
#[serde(deny_unknown_fields)]
struct SecretFieldName {
    name: String,
    field: String,
    #[serde(default)]
    project: Option<String>,
}

#[derive(Deserialize)]
#[serde(deny_unknown_fields)]
struct SecretMove {
    name: String,
    #[serde(default)]
    from: Option<String>,
    #[serde(default)]
    to: Option<String>,
}

#[derive(Deserialize)]
#[serde(deny_unknown_fields)]
struct SecretLogin {
    name: String,
    action: String,
    #[serde(default)]
    text: String,
}

#[derive(Deserialize)]
#[serde(deny_unknown_fields)]
struct PluginSecretGet {
    module: String,
    field: String,
}

/// The direct answer to `plugin_secret_get`; never pushed, never logged.
#[derive(Serialize, Deserialize, Clone, PartialEq, Eq)]
pub struct PluginSecret {
    pub module: String,
    pub field: String,
    pub value: Option<String>,
}

impl std::fmt::Debug for PluginSecret {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        write!(f, "PluginSecret(module={}, field={})", self.module, self.field)
    }
}

pub fn decode<T: DeserializeOwned>(data: &str) -> Result<T, VaultError> {
    serde_json::from_str(data).map_err(|_| VaultError::invalid("malformed request"))
}

pub type KnownFn = Arc<dyn Fn() -> Vec<KnownSecret> + Send + Sync>;
pub type AuthCheckFn = Arc<dyn Fn(&str) + Send + Sync>;
pub type LoginFn = Arc<dyn Fn(&str, &str, &str) -> Result<(), String> + Send + Sync>;
/// (action, secret, field, project): an audit note for a change. Names only.
pub type AuditFn = Arc<dyn Fn(&str, &str, &str, Option<&str>) + Send + Sync>;

/// What the handler needs from the daemon. Every hook has a safe default.
#[derive(Clone)]
pub struct HandlerDeps {
    pub known_base: Vec<KnownSecret>,
    /// Secrets that appear and vanish at run time (the harness API keys), read per call.
    pub extra_known: KnownFn,
    pub env: EnvFn,
    pub restricted: RestrictedFn,
    /// Re-probes the sign-in of the harness whose key secret is named (the Check button).
    pub auth_check: AuthCheckFn,
    /// start / input / cancel the in-app sign-in of the harness whose key secret is named.
    pub login: LoginFn,
    pub audit: Option<AuditFn>,
}

impl Default for HandlerDeps {
    fn default() -> Self {
        HandlerDeps {
            known_base: default_known(),
            extra_known: Arc::new(Vec::new),
            env: Arc::new(|k| std::env::var(k).ok()),
            restricted: Arc::new(|| false),
            auth_check: Arc::new(|_| {}),
            login: Arc::new(|_, _, _| Err("no in-app sign-in here".into())),
            audit: None,
        }
    }
}

pub struct KeystoreHandler {
    keystore: Arc<Keystore>,
    deps: HandlerDeps,
}

/// What one frame came to: the answer, and whether it is a change every client must see.
pub struct Answer {
    pub reply: infantry_protocol::envelope::Envelope,
    pub push: bool,
}

impl KeystoreHandler {
    pub fn new(keystore: Arc<Keystore>, deps: HandlerDeps) -> Self {
        KeystoreHandler { keystore, deps }
    }

    pub fn keystore(&self) -> &Arc<Keystore> {
        &self.keystore
    }

    pub fn known(&self) -> Vec<KnownSecret> {
        let mut k = self.deps.known_base.clone();
        k.extend((self.deps.extra_known)());
        k
    }

    /// A run or a render may use `name`: never a built-in nor a harness key.
    pub fn is_run_secret(&self, name: &str) -> bool {
        !self.known().iter().any(|k| k.name == name) && !name.starts_with("harness.")
    }

    pub fn restricted(&self) -> bool {
        (self.deps.restricted)()
    }

    fn known_one(&self, name: &str) -> Result<KnownSecret, VaultError> {
        self.known().into_iter().find(|k| k.name == name).ok_or_else(|| VaultError::invalid(format!("unknown secret '{}'", name.chars().take(64).collect::<String>())))
    }

    /// A user secret's name: valid, and not a built-in's.
    fn user(&self, name: &str) -> Result<String, VaultError> {
        keystore::check_name(name)?;
        if self.known().iter().any(|k| k.name == name) {
            return Err(VaultError::invalid(format!("'{name}' is a built-in secret")));
        }
        Ok(name.to_string())
    }

    fn audit(&self, action: &str, name: &str, field: &str, project: Option<&str>) {
        if let Some(a) = &self.deps.audit {
            a(action, name, field, project);
        }
    }

    pub fn state(&self) -> Result<SecretState, VaultError> {
        let known = self.known();
        let names: Vec<String> = known.iter().map(|k| k.name.clone()).collect();
        let infos = self.keystore.status(&names)?;
        let secrets = known
            .iter()
            .zip(infos)
            .map(|(k, info)| {
                let alias_set = k.env_alias.as_deref().and_then(|a| (self.deps.env)(a)).is_some_and(|v| !v.trim().is_empty());
                SecretRow {
                    name: k.name.clone(),
                    label: k.label.clone(),
                    set: info.set,
                    tail: info.tail,
                    importable: alias_set && !info.set,
                    overridden_by_env: alias_set && info.set,
                    help: k.help.clone(),
                    auth_modes: k.auth_modes.clone(),
                    auth_mode: k.auth_mode.clone(),
                    login_command: k.login_command.clone(),
                    signed_in: if k.signed_in.is_empty() { "unknown".into() } else { k.signed_in.clone() },
                    last_probe: k.last_probe,
                    uses_secret: k.uses_secret.clone(),
                    for_mode: k.for_mode.clone(),
                    can_login: k.can_login,
                    login_state: k.login_state.clone(),
                    login_url: k.login_url.clone(),
                    login_message: k.login_message.clone(),
                    login_code_urls: k.login_code_urls.clone(),
                }
            })
            .collect();
        let row = |name: String, project: Option<String>| -> Result<SecretEntryRow, VaultError> {
            let fields = self.keystore.describe(&name, project.as_deref())?.into_iter().map(|f| SecretFieldRow { name: f.name, tail: f.tail }).collect();
            Ok(SecretEntryRow { name, fields, project })
        };
        let mut entries = Vec::new();
        for n in self.keystore.names(None)? {
            if !names.contains(&n) {
                entries.push(row(n, None)?);
            }
        }
        for p in self.keystore.projects() {
            for n in self.keystore.names(Some(&p))? {
                entries.push(row(n, Some(p.clone()))?);
            }
        }
        Ok(SecretState { protection: self.keystore.protection()?.wire().into(), secrets, entries, locked: self.restricted() })
    }

    pub fn state_envelope(&self) -> Result<infantry_protocol::envelope::Envelope, VaultError> {
        let s = serde_json::to_string(&self.state()?).map_err(|_| VaultError::failed("the state could not be encoded"))?;
        Ok(infantry_protocol::envelope::Envelope::new(infantry_protocol::frames::SECRET_STATE, s))
    }

    /// A change: refused under the lock, else `block` then the new state.
    fn change(&self, block: impl FnOnce() -> Result<(), VaultError>) -> Result<Answer, VaultError> {
        if self.restricted() {
            return Err(VaultError::invalid("secrets are locked under a restricted policy"));
        }
        block()?;
        Ok(Answer { reply: self.state_envelope()?, push: true })
    }

    /// Answers one keystore frame; None when `frame` is not one of them.
    pub fn answer(&self, frame: &str, data: &str) -> Option<Result<Answer, VaultError>> {
        use infantry_protocol::frames as f;
        let ks = &self.keystore;
        Some(match frame {
            f::SECRET_STATUS => (|| {
                let d = data.trim();
                if !d.is_empty() && d != "{}" {
                    let req: SecretName = decode(d)?;
                    (self.deps.auth_check)(&self.known_one(&req.name)?.name);
                }
                Ok(Answer { reply: self.state_envelope()?, push: false })
            })(),
            f::SECRET_SET => self.change(|| {
                let req: SecretSet = decode(data)?;
                let k = self.known_one(&req.name)?;
                ks.set(&k.name, &req.value)?;
                self.audit("set", &k.name, VALUE_FIELD, None);
                Ok(())
            }),
            f::SECRET_CLEAR => self.change(|| {
                let req: SecretName = decode(data)?;
                let k = self.known_one(&req.name)?;
                ks.clear(&k.name, None)?;
                self.audit("clear", &k.name, "", None);
                Ok(())
            }),
            f::SECRET_IMPORT => self.change(|| {
                let req: SecretName = decode(data)?;
                let k = self.known_one(&req.name)?;
                let v = k.env_alias.as_deref().and_then(|a| (self.deps.env)(a)).filter(|v| !v.trim().is_empty());
                let v = v.ok_or_else(|| VaultError::invalid(format!("nothing to import for '{}'", k.name)))?;
                ks.set(&k.name, &v)?;
                self.audit("import-env", &k.name, VALUE_FIELD, None);
                Ok(())
            }),
            f::SECRET_FIELD_SET => self.change(|| {
                let req: SecretFieldSet = decode(data)?;
                let name = self.user(&req.name)?;
                let p = keystore::scope(req.project.as_deref())?;
                ks.set_field(&name, &req.field, &req.value, p.as_deref())?;
                self.audit("set-field", &name, &req.field, p.as_deref());
                Ok(())
            }),
            f::SECRET_FIELD_DELETE => self.change(|| {
                let req: SecretFieldName = decode(data)?;
                let name = self.user(&req.name)?;
                let p = keystore::scope(req.project.as_deref())?;
                if !ks.delete_field(&name, &req.field, p.as_deref())? {
                    return Err(VaultError::invalid("no such field"));
                }
                self.audit("delete-field", &name, &req.field, p.as_deref());
                Ok(())
            }),
            f::SECRET_DELETE => self.change(|| {
                let req: SecretName = decode(data)?;
                let name = self.user(&req.name)?;
                let p = keystore::scope(req.project.as_deref())?;
                if !ks.clear(&name, p.as_deref())? {
                    return Err(VaultError::invalid("no such secret"));
                }
                self.audit("delete", &name, "", p.as_deref());
                Ok(())
            }),
            f::SECRET_MOVE => self.change(|| {
                let req: SecretMove = decode(data)?;
                let name = self.user(&req.name)?;
                let (from, to) = (keystore::scope(req.from.as_deref())?, keystore::scope(req.to.as_deref())?);
                ks.move_secret(&name, from.as_deref(), to.as_deref())?;
                self.audit("move", &name, "", to.as_deref());
                Ok(())
            }),
            f::SECRET_LOGIN => self.change(|| {
                let req: SecretLogin = decode(data)?;
                let k = self.known_one(&req.name)?;
                if !k.can_login {
                    return Err(VaultError::invalid(format!("{} has no in-app sign-in", k.label)));
                }
                if !LOGIN_ACTIONS.contains(&req.action.as_str()) {
                    return Err(VaultError::invalid(format!("action must be one of {}", LOGIN_ACTIONS.join(", "))));
                }
                (self.deps.login)(&k.name, &req.action, &req.text).map_err(VaultError::Invalid)
            }),
            f::PLUGIN_SECRET_GET => (|| {
                if self.restricted() {
                    return Err(VaultError::invalid("secrets are locked under a restricted policy"));
                }
                let req: PluginSecretGet = decode(data)?;
                keystore::check_field(&req.field)?;
                if req.field.to_uppercase().starts_with("INFANTRY_") {
                    return Err(VaultError::invalid("INFANTRY_* fields are never handed to a plugin"));
                }
                let name = plugin_secret_name(&req.module);
                keystore::check_name(&name)?;
                let value = ks.field(&name, &req.field, None)?;
                let body = serde_json::to_string(&PluginSecret { module: req.module, field: req.field, value }).map_err(|_| VaultError::failed("encode"))?;
                Ok(Answer { reply: infantry_protocol::envelope::Envelope::new(f::PLUGIN_SECRET, body), push: false })
            })(),
            _ => return None,
        })
    }
}
