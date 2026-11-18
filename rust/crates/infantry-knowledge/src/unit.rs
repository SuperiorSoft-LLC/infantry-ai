// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
//! The frame units: [`RolesUnit`] (roles, model pins, role traits, avatars) and [`LibraryUnit`] (prompts, skills,
//! traits, layered skills). A write is answered by a push of the stored row to EVERY client (`ctx.bus.publish`),
//! like the Kotlin daemon; a refusal is the `<frame> refused: <reason>` text.
use std::collections::HashSet;
use std::path::{Path, PathBuf};
use std::sync::Arc;

use base64::Engine;
use infantry_core::db::Db;
use infantry_core::frames::{Envelope, FrameCtx, FrameUnit, Refusal};
use infantry_protocol::frames as f;
use serde::Deserialize;

use crate::avatars::{self, Avatars};
use crate::library::{LayerSkill, LibError, Library, LibraryPorts, SkillScope};
use crate::roles::{strict, Role, RoleSave, RoleService};

/// RUST-KNOW-1 avatar frames (new: Kotlin resolved avatars in the client).
pub const AVATAR_GET: &str = "avatar_get";
pub const AVATAR_SET: &str = "avatar_set";
pub const AVATAR_CLEAR: &str = "avatar_clear";
pub const AVATAR_MANIFEST: &str = "avatar_manifest";
pub const AVATAR: &str = "avatar";
pub const AVATARS: &str = "avatars";

/// What the units ask of the daemon. Every method has a neutral default.
pub trait DaemonPorts: Send + Sync {
    fn provider_ids(&self) -> HashSet<String> {
        HashSet::new()
    }
    fn effort_levels(&self) -> HashSet<String> {
        HashSet::new()
    }
    fn check_model(&self, _model: &str) -> Result<(), String> {
        Ok(())
    }
    /// Normalised role ids of the sessions that are not ended (they block a role delete).
    fn live_role_ids(&self) -> HashSet<String> {
        HashSet::new()
    }
    /// The agents whose profile adds `trait_id`.
    fn agents_binding_trait(&self, _trait_id: &str) -> Vec<String> {
        vec![]
    }
}

/// No daemon wired (tests, a bare host).
pub struct NoPorts;
impl DaemonPorts for NoPorts {}

struct Ports {
    roles: Arc<RoleService>,
    daemon: Arc<dyn DaemonPorts>,
}

impl LibraryPorts for Ports {
    fn role_exists(&self, r: &str) -> bool {
        self.roles.exists(r)
    }
    fn provider_ids(&self) -> HashSet<String> {
        self.daemon.provider_ids()
    }
    fn effort_levels(&self) -> HashSet<String> {
        self.daemon.effort_levels()
    }
    fn check_model(&self, m: &str) -> Result<(), String> {
        self.daemon.check_model(m)
    }
    fn agents_binding_trait(&self, t: &str) -> Vec<String> {
        self.daemon.agents_binding_trait(t)
    }
}

/// Everything this crate serves, wired once by the composition root.
pub struct Knowledge {
    pub roles: Arc<RoleService>,
    pub library: Arc<Library>,
    pub avatars: Arc<Avatars>,
    pub daemon: Arc<dyn DaemonPorts>,
}

/// The env var that moves the custom roles folder (`RoleService.ENV`).
pub const ROLES_DIR_ENV: &str = "INFANTRY_ROLES_DIR";

/// `$INFANTRY_ROLES_DIR`, else `<state>/roles`.
pub fn roles_dir(state_dir: &Path) -> PathBuf {
    std::env::var(ROLES_DIR_ENV).ok().filter(|v| !v.trim().is_empty()).map(PathBuf::from).unwrap_or_else(|| state_dir.join("roles"))
}

impl Knowledge {
    /// Roles in `roles_dir`, avatar overrides in `avatars_dir`, the libraries in `db`.
    pub fn new(db: Arc<Db>, roles_dir: PathBuf, avatars_dir: PathBuf, daemon: Arc<dyn DaemonPorts>) -> Knowledge {
        let roles = Arc::new(RoleService::new(roles_dir));
        let library = Arc::new(Library::new(db, Arc::new(Ports { roles: roles.clone(), daemon: daemon.clone() })));
        Knowledge { roles, library, avatars: Arc::new(Avatars::new(avatars_dir)), daemon }
    }

    /// The standard layout under the daemon's state dir: `<state>/roles` (or `$INFANTRY_ROLES_DIR`), `<state>/avatars`.
    pub fn open(db: Arc<Db>, state_dir: &Path, daemon: Arc<dyn DaemonPorts>) -> Knowledge {
        Self::new(db, roles_dir(state_dir), state_dir.join("avatars"), daemon)
    }

    /// One role with its avatar reference filled in.
    pub fn with_avatar(&self, mut r: Role) -> Role {
        r.avatar = Some(self.avatars.resolve(&r.id, None));
        r
    }

    pub fn roles_with_avatars(&self) -> Vec<Role> {
        self.roles.all().into_iter().map(|r| self.with_avatar(r)).collect()
    }

    pub fn units(self: &Arc<Self>) -> Vec<Arc<dyn FrameUnit>> {
        vec![Arc::new(RolesUnit(self.clone())), Arc::new(LibraryUnit(self.clone()))]
    }
}

fn json<T: serde::Serialize>(v: &T) -> String {
    serde_json::to_string(v).unwrap_or_else(|_| "null".into())
}

fn reply(env: &Envelope, frame: &str, data: String) -> Envelope {
    let mut out = Envelope::new(frame, data);
    out.request_id = env.request_id.clone();
    out.session_id = env.session_id.clone();
    out
}

/// A loopback / plugin caller has no client to see the push, so it also gets the row as a direct answer.
fn direct(env: &Envelope) -> bool {
    env.request_id.as_deref().is_some_and(|r| r.starts_with("plugin:") || r.starts_with("loopback:"))
}

fn push(ctx: &FrameCtx, env: &Envelope, frame: &str, data: String) -> Vec<Envelope> {
    ctx.bus.publish(Envelope::new(frame, data.clone()));
    if direct(env) {
        vec![reply(env, frame, data)]
    } else {
        vec![]
    }
}

fn refuse(t: &str, e: LibError) -> Refusal {
    Refusal(e.render(t))
}

fn refused(t: &str, m: impl std::fmt::Display) -> Refusal {
    Refusal(format!("{t} refused: {m}"))
}

// ---- roles -------------------------------------------------------------------------------------------------------

pub struct RolesUnit(pub Arc<Knowledge>);

const ROLE_FRAMES: &[&str] = &[
    f::ROLE_LIST,
    f::ROLE_SAVE,
    f::ROLE_DELETE,
    f::ROLE_BINDING_LIST,
    f::ROLE_BINDING_GET,
    f::ROLE_BINDING_SET,
    f::ROLE_TRAITS_LIST,
    f::ROLE_TRAITS_GET,
    f::ROLE_TRAITS_SET,
    AVATAR_GET,
    AVATAR_SET,
    AVATAR_CLEAR,
    AVATAR_MANIFEST,
];

#[derive(Deserialize, Default)]
#[serde(rename_all = "camelCase", default)]
struct AvatarReq {
    role_id: Option<String>,
    agent_id: Option<String>,
    /// avatar_set: the PNG / WEBP bytes, base64.
    data: Option<String>,
}

impl AvatarReq {
    fn parse(t: &str, text: &str) -> Result<AvatarReq, Refusal> {
        let s = text.trim();
        if s.starts_with('{') {
            serde_json::from_str(s).map_err(|e| refused(t, format!("malformed request: {e}")))
        } else {
            Ok(AvatarReq { role_id: Some(s.to_string()).filter(|s| !s.is_empty()), ..Default::default() })
        }
    }
    /// The id an override is stored under: the agent's when given, else the role's.
    fn target(&self, t: &str) -> Result<String, Refusal> {
        let id = self.agent_id.as_deref().or(self.role_id.as_deref()).map(str::trim).unwrap_or("");
        if !crate::ids::is_valid(id) {
            return Err(refused(t, format!("'{}' is not a role or agent id", crate::roles::take(id, 40))));
        }
        Ok(id.to_string())
    }
}

impl FrameUnit for RolesUnit {
    fn name(&self) -> &'static str {
        "roles"
    }
    fn frames(&self) -> &'static [&'static str] {
        ROLE_FRAMES
    }
    fn handle(&self, env: &Envelope, ctx: &FrameCtx) -> Result<Vec<Envelope>, Refusal> {
        let k = &self.0;
        let t = env.r#type.as_str();
        let data = env.data.trim();
        match t {
            f::ROLE_LIST => Ok(vec![reply(env, f::ROLES, json(&k.roles_with_avatars()))]),
            f::ROLE_SAVE => {
                let req: RoleSave = strict(&env.data, "role_save payload").map_err(|m| refused(t, m))?;
                let saved = k.roles.save(&req).map_err(|m| refused(t, m))?;
                Ok(push(ctx, env, f::ROLE, json(&k.with_avatar(saved))))
            }
            f::ROLE_DELETE => match k.roles.delete(data, &k.daemon.live_role_ids()).map_err(|m| refused(t, m))? {
                Some(r) => Ok(push(ctx, env, f::ROLE, json(&k.with_avatar(r)))),
                None => Ok(push(ctx, env, f::ROLE_REMOVED, data.to_string())),
            },
            f::ROLE_BINDING_LIST => Ok(vec![reply(env, f::ROLE_BINDINGS, json(&k.library.role_binding_list().map_err(|e| refuse(t, e))?))]),
            f::ROLE_BINDING_GET => Ok(vec![reply(env, f::ROLE_BINDING, json(&k.library.role_binding_get(data).map_err(|e| refuse(t, e))?))]),
            f::ROLE_BINDING_SET => {
                let req = strict(&env.data, "role_binding_set payload").map_err(|m| refused(t, m))?;
                let stored = k.library.role_binding_set(&req).map_err(|e| refuse(t, e))?;
                Ok(push(ctx, env, f::ROLE_BINDING, json(&stored)))
            }
            f::ROLE_TRAITS_LIST => Ok(vec![reply(env, f::ROLE_TRAITS_ALL, json(&k.library.role_traits_list().map_err(|e| refuse(t, e))?))]),
            f::ROLE_TRAITS_GET => Ok(vec![reply(env, f::ROLE_TRAITS, json(&k.library.role_traits_get(data).map_err(|e| refuse(t, e))?))]),
            f::ROLE_TRAITS_SET => {
                let req = strict(&env.data, "role_traits_set payload").map_err(|m| refused(t, m))?;
                let stored = k.library.role_traits_set(&req).map_err(|e| refuse(t, e))?;
                Ok(push(ctx, env, f::ROLE_TRAITS, json(&stored)))
            }
            AVATAR_GET => {
                let r = AvatarReq::parse(t, data)?;
                let role = r.role_id.as_deref().map(crate::ids::normalize).unwrap_or_default();
                if role.is_empty() && r.agent_id.is_none() {
                    return Err(refused(t, "name a roleId or an agentId"));
                }
                let id = if role.is_empty() { r.agent_id.clone().unwrap_or_default() } else { role };
                Ok(vec![reply(env, AVATAR, json(&k.avatars.resolve(&id, r.agent_id.as_deref())))])
            }
            AVATAR_SET => {
                let r = AvatarReq::parse(t, data)?;
                let id = r.target(t)?;
                let b64 = r.data.as_deref().map(str::trim).filter(|d| !d.is_empty()).ok_or_else(|| refused(t, "no image data"))?;
                let b64 = b64.split_once(";base64,").map(|(_, d)| d).unwrap_or(b64);
                let bytes = base64::engine::general_purpose::STANDARD.decode(b64).map_err(|e| refused(t, format!("data is not base64: {e}")))?;
                let up = avatars::validate(bytes).map_err(|m| refused(t, m))?;
                k.avatars.store(&id, &up).map_err(|m| Refusal(format!("{t} failed: {m}")))?;
                Ok(self.avatar_push(ctx, env, &r, &id))
            }
            AVATAR_CLEAR => {
                let r = AvatarReq::parse(t, data)?;
                let id = r.target(t)?;
                k.avatars.clear(&id);
                Ok(self.avatar_push(ctx, env, &r, &id))
            }
            AVATAR_MANIFEST => {
                let mut ids: Vec<String> = k.roles.all().into_iter().map(|r| r.id).collect();
                ids.extend(k.avatars.manifest().keys().filter(|i| !k.roles.exists(i)).cloned());
                let refs: Vec<_> = ids.iter().map(|i| k.avatars.resolve(i, None)).collect();
                Ok(vec![reply(env, AVATARS, json(&serde_json::json!({ "manifest": k.avatars.manifest(), "urlPrefix": avatars::URL_PREFIX, "avatars": refs })))])
            }
            _ => Err(refused(t, "unknown frame")),
        }
    }
}

impl RolesUnit {
    /// After a set / clear: the new `avatar` to every client, and the role itself (its avatar changed).
    fn avatar_push(&self, ctx: &FrameCtx, env: &Envelope, r: &AvatarReq, id: &str) -> Vec<Envelope> {
        let k = &self.0;
        let role = r.role_id.as_deref().map(crate::ids::normalize).filter(|x| !x.is_empty());
        let resolved = match (&role, &r.agent_id) {
            (Some(role), agent) => k.avatars.resolve(role, agent.as_deref()),
            (None, _) => k.avatars.resolve(id, Some(id)),
        };
        if r.agent_id.is_none() {
            if let Some(role) = role.as_deref().and_then(|x| k.roles.role(x)) {
                ctx.bus.publish(Envelope::new(f::ROLE, json(&k.with_avatar(role))));
            }
        }
        push(ctx, env, AVATAR, json(&resolved))
    }
}

// ---- libraries ---------------------------------------------------------------------------------------------------

pub struct LibraryUnit(pub Arc<Knowledge>);

const LIBRARY_FRAMES: &[&str] = &[
    f::PROMPT_SAVE,
    f::PROMPT_LIST,
    f::PROMPT_GET,
    f::PROMPT_DELETE,
    f::SKILL_SAVE,
    f::SKILL_LIST,
    f::SKILL_GET,
    f::SKILL_DELETE,
    f::TRAIT_SAVE,
    f::TRAIT_LIST,
    f::TRAIT_GET,
    f::TRAIT_DELETE,
    f::LAYER_SKILL_LIST,
    f::LAYER_SKILL_SAVE,
    f::LAYER_SKILL_DELETE,
];

#[derive(Deserialize)]
#[serde(rename_all = "camelCase")]
struct LayerReq {
    scope: String,
    #[serde(default)]
    project_id: Option<String>,
    #[serde(default)]
    role: Option<String>,
    #[serde(default)]
    name: Option<String>,
    #[serde(default)]
    description: String,
    #[serde(default)]
    body: Option<String>,
}

fn layer_list_json(scope: &SkillScope, skills: &[LayerSkill]) -> String {
    let views: Vec<_> = skills
        .iter()
        .map(|s| serde_json::json!({ "scope": scope.layer(), "name": s.name, "description": s.description, "body": s.body, "projectId": scope.project(), "role": scope.role() }))
        .collect();
    json(&serde_json::json!({ "scope": scope.layer(), "projectId": scope.project(), "role": scope.role(), "skills": views }))
}

fn tag_of(data: &str) -> Option<&str> {
    Some(data.trim()).filter(|d| !d.is_empty())
}

impl FrameUnit for LibraryUnit {
    fn name(&self) -> &'static str {
        "library"
    }
    fn frames(&self) -> &'static [&'static str] {
        LIBRARY_FRAMES
    }
    fn handle(&self, env: &Envelope, ctx: &FrameCtx) -> Result<Vec<Envelope>, Refusal> {
        let l = &self.0.library;
        let t = env.r#type.as_str();
        let data = env.data.trim();
        match t {
            f::PROMPT_SAVE => {
                let req = strict(&env.data, "prompt_save payload").map_err(|m| Refusal(m))?;
                Ok(push(ctx, env, f::PROMPT_ITEM, json(&l.prompt_save(&req).map_err(|e| refuse(t, e))?)))
            }
            f::PROMPT_LIST => Ok(vec![reply(env, f::PROMPTS, json(&l.prompt_list(tag_of(data)).map_err(|e| refuse(t, e))?))]),
            f::PROMPT_GET => match l.prompt_get(data).map_err(|e| refuse(t, e))? {
                Some(p) => Ok(vec![reply(env, f::PROMPT_ITEM, json(&p))]),
                None => Err(refused(t, format!("unknown prompt '{data}'"))),
            },
            f::PROMPT_DELETE => {
                if !l.prompt_delete(data).map_err(|e| refuse(t, e))? {
                    return Err(refused(t, format!("unknown prompt '{data}'")));
                }
                Ok(push(ctx, env, f::PROMPTS, json(&l.prompt_list(None).map_err(|e| refuse(t, e))?)))
            }
            f::SKILL_SAVE => {
                let req = strict(&env.data, "skill_save payload").map_err(|m| Refusal(m))?;
                Ok(push(ctx, env, f::SKILL_ITEM, json(&l.skill_save(&req).map_err(|e| refuse(t, e))?)))
            }
            f::SKILL_LIST => Ok(vec![reply(env, f::SKILLS, json(&l.skill_list(tag_of(data)).map_err(|e| refuse(t, e))?))]),
            f::SKILL_GET => match l.skill_get(data).map_err(|e| refuse(t, e))? {
                Some(s) => Ok(vec![reply(env, f::SKILL_ITEM, json(&s))]),
                None => Err(refused(t, format!("unknown skill '{data}'"))),
            },
            f::SKILL_DELETE => {
                let id = l.skill_get(data).map_err(|e| refuse(t, e))?.map(|s| s.id).ok_or_else(|| refused(t, format!("unknown skill '{data}'")))?;
                l.skill_delete(&id).map_err(|e| refuse(t, e))?;
                Ok(push(ctx, env, f::SKILLS, json(&l.skill_list(None).map_err(|e| refuse(t, e))?)))
            }
            f::TRAIT_SAVE => {
                let req = strict(&env.data, "trait_save payload").map_err(|m| Refusal(m))?;
                Ok(push(ctx, env, f::TRAIT_ITEM, json(&l.trait_save(&req).map_err(|e| refuse(t, e))?)))
            }
            f::TRAIT_LIST => Ok(vec![reply(env, f::TRAITS, json(&l.trait_list().map_err(|e| refuse(t, e))?))]),
            f::TRAIT_GET => match l.trait_get(data).map_err(|e| refuse(t, e))? {
                Some(x) => Ok(vec![reply(env, f::TRAIT_ITEM, json(&x))]),
                None => Err(refused(t, format!("unknown trait '{data}'"))),
            },
            f::TRAIT_DELETE => {
                if !l.trait_delete(data).map_err(|e| refuse(t, e))? {
                    return Err(refused(t, format!("unknown trait '{data}'")));
                }
                Ok(push(ctx, env, f::TRAITS, json(&l.trait_list().map_err(|e| refuse(t, e))?)))
            }
            f::LAYER_SKILL_LIST | f::LAYER_SKILL_SAVE | f::LAYER_SKILL_DELETE => {
                let req: LayerReq = serde_json::from_str(data).map_err(|_| refused(t, "malformed request"))?;
                let scope = SkillScope::parse(&req.scope, req.project_id.as_deref(), req.role.as_deref()).map_err(|m| refused(t, m))?;
                match t {
                    f::LAYER_SKILL_LIST => Ok(vec![reply(env, f::LAYER_SKILLS, layer_list_json(&scope, &l.layer_list(&scope).map_err(|e| refuse(t, e))?))]),
                    f::LAYER_SKILL_SAVE => {
                        let name = req.name.unwrap_or_default();
                        let all = l.layer_save(&scope, &name, &req.description, req.body.as_deref().unwrap_or("")).map_err(|e| refuse(t, e))?;
                        Ok(push(ctx, env, f::LAYER_SKILLS, layer_list_json(&scope, &all)))
                    }
                    _ => {
                        let all = l.layer_delete(&scope, req.name.as_deref().unwrap_or("")).map_err(|e| refuse(t, e))?;
                        Ok(push(ctx, env, f::LAYER_SKILLS, layer_list_json(&scope, &all)))
                    }
                }
            }
            _ => Err(refused(t, "unknown frame")),
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use infantry_core::bus::EventBus;

    #[test]
    fn frames_round_trip() {
        let dir = tempfile::tempdir().unwrap();
        let db = Db::open(dir.path().join("infantry.db")).unwrap();
        db.write(|c| c.execute_batch(crate::library::SCHEMA)).unwrap();
        let k = Arc::new(Knowledge::open(Arc::new(db), dir.path(), Arc::new(NoPorts)));
        let units = k.units();
        let ctx = FrameCtx { conn_id: 1, owner: true, bus: EventBus::default() };
        let call = |t: &str, d: &str| {
            let u = units.iter().find(|u| u.frames().contains(&t)).unwrap();
            let mut e = Envelope::new(t, d.to_string());
            e.request_id = Some("loopback:1".into());
            u.handle(&e, &ctx)
        };
        let roles = call(f::ROLE_LIST, "").unwrap();
        let v: serde_json::Value = serde_json::from_str(&roles[0].data).unwrap();
        assert_eq!(v[0]["avatar"]["tier"], "generated");
        assert_eq!(call(f::PROMPT_SAVE, r#"{"name":"a","body":"b","x":1}"#).unwrap_err().0.lines().next().unwrap().starts_with("malformed prompt_save payload"), true);
        let saved = call(f::PROMPT_SAVE, r#"{"name":"a","body":"b"}"#).unwrap();
        assert_eq!(saved[0].r#type, f::PROMPT_ITEM);
        assert!(call(f::PROMPT_GET, "nope").unwrap_err().0.starts_with("prompt_get refused: unknown prompt"));
        let png = {
            let mut b = vec![0x89, b'P', b'N', b'G', 0x0D, 0x0A, 0x1A, 0x0A, 0, 0, 0, 13];
            b.extend_from_slice(b"IHDR");
            b.extend_from_slice(&16u32.to_be_bytes());
            b.extend_from_slice(&16u32.to_be_bytes());
            base64::engine::general_purpose::STANDARD.encode(b)
        };
        let out = call(AVATAR_SET, &format!(r#"{{"roleId":"qa","data":"{png}"}}"#)).unwrap();
        let a: serde_json::Value = serde_json::from_str(&out[0].data).unwrap();
        assert_eq!((a["tier"].as_str(), a["url"].as_str()), (Some("override"), Some("/avatars/qa")));
        assert!(call(f::LAYER_SKILL_LIST, r#"{"scope":"nope"}"#).unwrap_err().0.contains("scope must be global, project or role"));
    }
}
