// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
//! The db-backed libraries: prompts, skills, traits, role -> traits and role -> model pins, plus the layered skills
//! (global / project / role) kept in the `settings` table. Same tables and same rows as the Kotlin daemon.
use std::collections::HashSet;
use std::sync::Arc;
use std::time::{SystemTime, UNIX_EPOCH};

use infantry_core::db::Db;
use rusqlite::{params, OptionalExtension};
use serde::{Deserialize, Serialize};

use crate::ids;

pub fn now_ms() -> i64 {
    SystemTime::now().duration_since(UNIX_EPOCH).map(|d| d.as_millis() as i64).unwrap_or(0)
}

/// What fails a library call: a refusal of the input (`... refused: <msg>`) or a storage error (`... failed: <msg>`).
#[derive(Debug, Clone, PartialEq)]
pub enum LibError {
    Invalid(String),
    Failed(String),
}

impl LibError {
    pub fn render(&self, frame: &str) -> String {
        match self {
            LibError::Invalid(m) => format!("{frame} refused: {m}"),
            LibError::Failed(m) => format!("{frame} failed: {m}"),
        }
    }
}

impl From<infantry_core::db::DbError> for LibError {
    fn from(e: infantry_core::db::DbError) -> Self {
        LibError::Failed(e.to_string())
    }
}

type R<T> = Result<T, LibError>;

fn bad<T>(m: impl Into<String>) -> R<T> {
    Err(LibError::Invalid(m.into()))
}

pub const MAX_NAME_CHARS: usize = 120;
pub const MAX_BODY_BYTES: usize = 64 * 1024;
pub const MAX_TAGS: usize = 16;
pub const MAX_TAG_CHARS: usize = 32;
pub const MAX_SKILL_DESCRIPTION: usize = 500;

/// Trimmed, lower-cased, distinct, at most [`MAX_TAGS`] of at most [`MAX_TAG_CHARS`].
pub fn normalize_tags(tags: &[String]) -> R<Vec<String>> {
    let mut out: Vec<String> = Vec::new();
    for t in tags.iter().map(|t| t.trim().to_lowercase()).filter(|t| !t.is_empty()) {
        if t.chars().count() > MAX_TAG_CHARS {
            return bad(format!("tag '{}' is longer than {MAX_TAG_CHARS} chars", crate::roles::take(&t, 40)));
        }
        if !out.contains(&t) {
            out.push(t);
        }
    }
    if out.len() > MAX_TAGS {
        return bad(format!("{} tags; the limit is {MAX_TAGS}", out.len()));
    }
    Ok(out)
}

fn check_name_body(name: &str, body: &str) -> R<String> {
    let name = name.trim();
    if name.is_empty() {
        return bad("name must not be blank");
    }
    if name.chars().count() > MAX_NAME_CHARS {
        return bad(format!("name is {} chars; the limit is {MAX_NAME_CHARS}", name.chars().count()));
    }
    if body.trim().is_empty() {
        return bad("body must not be blank");
    }
    if body.len() > MAX_BODY_BYTES {
        return bad(format!("body is {} bytes; the limit is {MAX_BODY_BYTES}", body.len()));
    }
    Ok(name.to_string())
}

fn tags_of(text: String) -> Vec<String> {
    serde_json::from_str(&text).unwrap_or_default()
}

fn tags_json(t: &[String]) -> String {
    serde_json::to_string(t).unwrap_or_else(|_| "[]".into())
}

// ---- prompts ------------------------------------------------------------------------------------------------------

#[derive(Debug, Clone, Default, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct PromptSave {
    pub name: String,
    pub body: String,
    #[serde(default)]
    pub tags: Vec<String>,
}

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct PromptView {
    pub id: String,
    pub name: String,
    pub body: String,
    pub tags: Vec<String>,
    pub created_at: i64,
    pub updated_at: i64,
}

#[derive(Debug, Clone, Default, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct SkillSave {
    pub name: String,
    #[serde(default)]
    pub description: String,
    pub body: String,
    #[serde(default)]
    pub tags: Vec<String>,
    #[serde(default)]
    pub attached_only: Option<bool>,
}

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct SkillView {
    pub id: String,
    pub name: String,
    pub description: String,
    pub body: String,
    pub tags: Vec<String>,
    pub created_at: i64,
    pub updated_at: i64,
    pub attached_only: bool,
}

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct TraitCheck {
    pub kind: String,
    #[serde(default = "pm")]
    pub report_to: String,
    #[serde(default)]
    pub fields: Vec<String>,
    #[serde(default = "yes")]
    pub reprompt: bool,
}

fn pm() -> String {
    ids::PM.into()
}
fn yes() -> bool {
    true
}

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct TraitSave {
    pub id: String,
    pub name: String,
    pub body: String,
    #[serde(default)]
    pub check: Option<TraitCheck>,
}

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct TraitView {
    pub id: String,
    pub name: String,
    pub version: i64,
    pub body: String,
    #[serde(default)]
    pub check: Option<TraitCheck>,
    pub created_at: i64,
    pub updated_at: i64,
}

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct RoleTraitsSave {
    pub role_id: String,
    pub trait_ids: Vec<String>,
}

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct RoleTraits {
    pub role_id: String,
    #[serde(default)]
    pub trait_ids: Vec<String>,
    #[serde(default)]
    pub updated_at: i64,
}

#[derive(Debug, Clone, Default, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct RoleBindingSave {
    pub role_id: String,
    #[serde(default)]
    pub provider: Option<String>,
    #[serde(default)]
    pub model: Option<String>,
    #[serde(default)]
    pub effort: Option<String>,
}

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct RoleBinding {
    pub role_id: String,
    pub provider: Option<String>,
    pub model: Option<String>,
    pub effort: Option<String>,
    #[serde(default)]
    pub updated_at: i64,
}

pub const TRAIT_MAX_NAME: usize = 80;
pub const TRAIT_MAX_BODY: usize = 2048;
pub const TRAIT_MAX_FIELDS: usize = 12;
pub const ROLE_MAX_TRAITS: usize = 8;

/// What the libraries ask of the daemon (Kotlin `RolePorts`).
pub trait LibraryPorts: Send + Sync {
    fn role_exists(&self, role_id: &str) -> bool;
    /// Ids of the providers the daemon lists. Empty = no provider check.
    fn provider_ids(&self) -> HashSet<String>;
    /// The effort levels a binding may name. Empty = no effort check.
    fn effort_levels(&self) -> HashSet<String>;
    /// Err = the model allowlist refuses `model`.
    fn check_model(&self, _model: &str) -> Result<(), String> {
        Ok(())
    }
    /// The agents whose profile adds `trait_id` (they block its delete).
    fn agents_binding_trait(&self, _trait_id: &str) -> Vec<String> {
        vec![]
    }
}

fn is_field(s: &str) -> bool {
    let b = s.as_bytes();
    !b.is_empty() && b.len() <= 32 && b[0].is_ascii_alphabetic() && b.iter().all(|c| c.is_ascii_alphanumeric() || b" _-".contains(c))
}

/// The libraries over one `infantry.db`.
pub struct Library {
    db: Arc<Db>,
    ports: Arc<dyn LibraryPorts>,
}

impl Library {
    pub fn new(db: Arc<Db>, ports: Arc<dyn LibraryPorts>) -> Library {
        Library { db, ports }
    }

    pub fn db(&self) -> &Arc<Db> {
        &self.db
    }

    // -- prompts

    pub fn prompt_save(&self, s: &PromptSave) -> R<PromptView> {
        let name = check_name_body(&s.name, &s.body)?;
        let tags = normalize_tags(&s.tags)?;
        let at = now_ms();
        let body = s.body.clone();
        let id = self.db.write(|c| {
            let tx = c.transaction()?;
            let found: Option<String> = tx.query_row("SELECT id FROM prompts WHERE name = ?1", [&name], |r| r.get(0)).optional()?;
            let id = match found {
                Some(id) => {
                    tx.execute("UPDATE prompts SET body = ?2, tags = ?3, updated_at = ?4 WHERE id = ?1", params![id, body, tags_json(&tags), at])?;
                    id
                }
                None => {
                    let id = uuid::Uuid::new_v4().to_string();
                    tx.execute("INSERT INTO prompts(id, name, body, tags, created_at, updated_at) VALUES (?1, ?2, ?3, ?4, ?5, ?5)", params![id, name, body, tags_json(&tags), at])?;
                    id
                }
            };
            tx.commit()?;
            Ok(id)
        })?;
        self.prompt_get(&id)?.ok_or_else(|| LibError::Failed("the prompt did not read back".into()))
    }

    fn prompt_row(r: &rusqlite::Row) -> rusqlite::Result<PromptView> {
        Ok(PromptView { id: r.get(0)?, name: r.get(1)?, body: r.get(2)?, tags: tags_of(r.get(3)?), created_at: r.get(4)?, updated_at: r.get(5)? })
    }

    pub fn prompt_list(&self, tag: Option<&str>) -> R<Vec<PromptView>> {
        let all = self.db.read(|c| {
            let mut st = c.prepare("SELECT id, name, body, tags, created_at, updated_at FROM prompts ORDER BY name, id")?;
            let rows = st.query_map([], Self::prompt_row)?.collect::<rusqlite::Result<Vec<_>>>();
            rows
        })?;
        let tag = tag.map(|t| t.trim().to_lowercase()).filter(|t| !t.is_empty());
        Ok(all.into_iter().filter(|p| tag.as_ref().is_none_or(|t| p.tags.contains(t))).collect())
    }

    pub fn prompt_get(&self, id: &str) -> R<Option<PromptView>> {
        Ok(self.db.read(|c| c.query_row("SELECT id, name, body, tags, created_at, updated_at FROM prompts WHERE id = ?1", [id], Self::prompt_row).optional())?)
    }

    pub fn prompt_delete(&self, id: &str) -> R<bool> {
        Ok(self.db.write(|c| c.execute("DELETE FROM prompts WHERE id = ?1", [id]))? > 0)
    }

    // -- skills

    pub fn skill_save(&self, s: &SkillSave) -> R<SkillView> {
        let name = check_name_body(&s.name, &s.body)?;
        let description = s.description.trim().to_string();
        if description.chars().count() > MAX_SKILL_DESCRIPTION {
            return bad(format!("description is {} chars; the limit is {MAX_SKILL_DESCRIPTION}", description.chars().count()));
        }
        let tags = normalize_tags(&s.tags)?;
        let at = now_ms();
        let (body, attached) = (s.body.clone(), s.attached_only);
        let id = self.db.write(|c| {
            let tx = c.transaction()?;
            let found: Option<(String, i64)> = tx.query_row("SELECT id, attached_only FROM skills WHERE name = ?1", [&name], |r| Ok((r.get(0)?, r.get(1)?))).optional()?;
            let id = match found {
                Some((id, stored)) => {
                    let flag = attached.map(i64::from).unwrap_or(stored);
                    tx.execute(
                        "UPDATE skills SET description = ?2, body = ?3, tags = ?4, updated_at = ?5, attached_only = ?6 WHERE id = ?1",
                        params![id, description, body, tags_json(&tags), at, flag],
                    )?;
                    id
                }
                None => {
                    let id = uuid::Uuid::new_v4().to_string();
                    tx.execute(
                        "INSERT INTO skills(id, name, description, body, tags, created_at, updated_at, attached_only) VALUES (?1, ?2, ?3, ?4, ?5, ?6, ?6, ?7)",
                        params![id, name, description, body, tags_json(&tags), at, i64::from(attached.unwrap_or(false))],
                    )?;
                    id
                }
            };
            tx.commit()?;
            Ok(id)
        })?;
        self.skill_get(&id)?.ok_or_else(|| LibError::Failed("the skill did not read back".into()))
    }

    fn skill_row(r: &rusqlite::Row) -> rusqlite::Result<SkillView> {
        Ok(SkillView {
            id: r.get(0)?,
            name: r.get(1)?,
            description: r.get(2)?,
            body: r.get(3)?,
            tags: tags_of(r.get(4)?),
            created_at: r.get(5)?,
            updated_at: r.get(6)?,
            attached_only: r.get::<_, i64>(7)? != 0,
        })
    }

    const SKILL_COLS: &'static str = "id, name, description, body, tags, created_at, updated_at, attached_only";

    pub fn skill_list(&self, tag: Option<&str>) -> R<Vec<SkillView>> {
        let all = self.db.read(|c| {
            let mut st = c.prepare(&format!("SELECT {} FROM skills ORDER BY name, id", Self::SKILL_COLS))?;
            let rows = st.query_map([], Self::skill_row)?.collect::<rusqlite::Result<Vec<_>>>();
            rows
        })?;
        let tag = tag.map(|t| t.trim().to_lowercase()).filter(|t| !t.is_empty());
        Ok(all.into_iter().filter(|p| tag.as_ref().is_none_or(|t| p.tags.contains(t))).collect())
    }

    /// By id, then by exact name.
    pub fn skill_get(&self, key: &str) -> R<Option<SkillView>> {
        let sql = format!("SELECT {} FROM skills WHERE id = ?1", Self::SKILL_COLS);
        let by_name = format!("SELECT {} FROM skills WHERE name = ?1", Self::SKILL_COLS);
        Ok(self.db.read(|c| match c.query_row(&sql, [key], Self::skill_row).optional()? {
            Some(s) => Ok(Some(s)),
            None => c.query_row(&by_name, [key], Self::skill_row).optional(),
        })?)
    }

    pub fn skill_delete(&self, id: &str) -> R<bool> {
        Ok(self.db.write(|c| c.execute("DELETE FROM skills WHERE id = ?1", [id]))? > 0)
    }

    // -- traits

    fn valid_check(&self, check: &TraitCheck) -> R<TraitCheck> {
        if check.kind != "report" {
            return bad(format!("check kind '{}' is unknown; v1 knows only 'report'", crate::roles::take(&check.kind, 40)));
        }
        let report_to = ids::normalize(check.report_to.trim());
        if !ids::is_valid(&report_to) || !self.ports.role_exists(&report_to) {
            return bad(format!("check reportTo '{}' is not a role in the catalog", crate::roles::take(&report_to, 40)));
        }
        let fields: Vec<String> = check.fields.iter().map(|f| f.trim().to_string()).collect();
        if fields.is_empty() || fields.len() > TRAIT_MAX_FIELDS {
            return bad(format!("check fields has {} labels; it needs 1..{TRAIT_MAX_FIELDS}", fields.len()));
        }
        if let Some(b) = fields.iter().find(|f| !is_field(f)) {
            return bad(format!("check field '{}' must match [A-Za-z][A-Za-z0-9 _-]{{0,31}}", crate::roles::take(b, 40)));
        }
        let mut seen = HashSet::new();
        if let Some(d) = fields.iter().find(|f| !seen.insert(f.to_lowercase())) {
            return bad(format!("check fields has a duplicate label '{d}' (labels are unique ignoring case)"));
        }
        Ok(TraitCheck { kind: check.kind.clone(), report_to, fields, reprompt: check.reprompt })
    }

    pub fn trait_save(&self, s: &TraitSave) -> R<TraitView> {
        let id = s.id.trim().to_string();
        if !ids::is_valid(&id) {
            return bad(format!("id '{}' must match [a-z][a-z0-9-]{{0,31}} (a slug: lowercase letter first, at most 32 chars)", crate::roles::take(&id, 40)));
        }
        let name = s.name.trim().to_string();
        if name.is_empty() {
            return bad("name must not be blank");
        }
        if name.chars().count() > TRAIT_MAX_NAME {
            return bad(format!("name is {} chars; the limit is {TRAIT_MAX_NAME}", name.chars().count()));
        }
        if s.body.trim().is_empty() {
            return bad("body must not be blank");
        }
        if s.body.len() > TRAIT_MAX_BODY {
            return bad(format!("body is {} bytes; the limit is {TRAIT_MAX_BODY} (2 KB): anything longer belongs in a skill", s.body.len()));
        }
        let check = s.check.as_ref().map(|c| self.valid_check(c)).transpose()?;
        let at = now_ms();
        let check_json = check.as_ref().map(|c| serde_json::to_string(c).unwrap_or_default());
        let body = s.body.clone();
        let tid = id.clone();
        self.db.write(|c| {
            let tx = c.transaction()?;
            let cur = tx.query_row("SELECT id, name, version, body, check_spec, created_at, updated_at FROM traits WHERE id = ?1", [&tid], Self::trait_row).optional()?;
            match cur {
                None => {
                    tx.execute("INSERT INTO traits(id, name, version, body, check_spec, created_at, updated_at) VALUES (?1, ?2, 1, ?3, ?4, ?5, ?5)", params![tid, name, body, check_json, at])?;
                }
                Some(cur) if cur.name == name && cur.body == body && cur.check == check => {}
                Some(cur) => {
                    tx.execute(
                        "UPDATE traits SET name = ?2, version = ?3, body = ?4, check_spec = ?5, updated_at = ?6 WHERE id = ?1",
                        params![tid, name, cur.version + 1, body, check_json, at],
                    )?;
                }
            }
            tx.commit()
        })?;
        self.trait_get(&id)?.ok_or_else(|| LibError::Failed("the trait did not read back".into()))
    }

    fn trait_row(r: &rusqlite::Row) -> rusqlite::Result<TraitView> {
        let spec: Option<String> = r.get(4)?;
        Ok(TraitView {
            id: r.get(0)?,
            name: r.get(1)?,
            version: r.get(2)?,
            body: r.get(3)?,
            check: spec.and_then(|s| serde_json::from_str(&s).ok()),
            created_at: r.get(5)?,
            updated_at: r.get(6)?,
        })
    }

    pub fn trait_list(&self) -> R<Vec<TraitView>> {
        Ok(self.db.read(|c| {
            let mut st = c.prepare("SELECT id, name, version, body, check_spec, created_at, updated_at FROM traits ORDER BY id")?;
            let rows = st.query_map([], Self::trait_row)?.collect::<rusqlite::Result<Vec<_>>>();
            rows
        })?)
    }

    pub fn trait_get(&self, id: &str) -> R<Option<TraitView>> {
        Ok(self.db.read(|c| c.query_row("SELECT id, name, version, body, check_spec, created_at, updated_at FROM traits WHERE id = ?1", [id], Self::trait_row).optional())?)
    }

    /// Refused while a role or an agent binds it; Ok(false) = no such trait.
    pub fn trait_delete(&self, id: &str) -> R<bool> {
        let mut refs: Vec<String> = self.role_traits_list()?.into_iter().filter(|r| r.trait_ids.iter().any(|t| t == id)).map(|r| format!("role '{}'", r.role_id)).collect();
        refs.extend(self.ports.agents_binding_trait(id).into_iter().map(|a| format!("agent '{a}'")));
        if self.trait_get(id)?.is_none() {
            return Ok(false);
        }
        if !refs.is_empty() {
            return bad(format!("trait '{id}' is still bound to {}: unbind it first", refs.join(", ")));
        }
        Ok(self.db.write(|c| c.execute("DELETE FROM traits WHERE id = ?1", [id]))? > 0)
    }

    // -- role traits

    pub fn role_traits_set(&self, s: &RoleTraitsSave) -> R<RoleTraits> {
        let role = ids::normalize(s.role_id.trim());
        if !ids::is_valid(&role) || !self.ports.role_exists(&role) {
            return bad(format!("unknown role '{}'", crate::roles::take(&role, 40)));
        }
        let list: Vec<String> = s.trait_ids.iter().map(|t| t.trim().to_string()).collect();
        if list.len() > ROLE_MAX_TRAITS {
            return bad(format!("{} traits; a role binds at most {ROLE_MAX_TRAITS}", list.len()));
        }
        let mut seen = HashSet::new();
        for t in &list {
            if !seen.insert(t.clone()) {
                return bad(format!("trait '{t}' is listed twice"));
            }
            if self.trait_get(t)?.is_none() {
                return bad(format!("unknown trait '{}' (trait_list shows the library)", crate::roles::take(t, 40)));
            }
        }
        let at = now_ms();
        let json = serde_json::to_string(&list).unwrap_or_else(|_| "[]".into());
        self.db.write(|c| {
            c.execute(
                "INSERT INTO role_traits(role_id, trait_ids, updated_at) VALUES (?1, ?2, ?3) ON CONFLICT(role_id) DO UPDATE SET trait_ids = excluded.trait_ids, updated_at = excluded.updated_at",
                params![role, json, at],
            )
        })?;
        Ok(RoleTraits { role_id: role, trait_ids: list, updated_at: at })
    }

    pub fn role_traits_get(&self, role_id: &str) -> R<RoleTraits> {
        let role = ids::normalize(role_id.trim());
        let row = self.db.read(|c| {
            c.query_row("SELECT trait_ids, updated_at FROM role_traits WHERE role_id = ?1", [&role], |r| Ok((r.get::<_, String>(0)?, r.get::<_, i64>(1)?))).optional()
        })?;
        Ok(match row {
            Some((t, at)) => RoleTraits { role_id: role, trait_ids: tags_of(t), updated_at: at },
            None => RoleTraits { role_id: role, trait_ids: vec![], updated_at: 0 },
        })
    }

    pub fn role_traits_list(&self) -> R<Vec<RoleTraits>> {
        Ok(self.db.read(|c| {
            let mut st = c.prepare("SELECT role_id, trait_ids, updated_at FROM role_traits ORDER BY role_id")?;
            let rows = st.query_map([], |r| Ok(RoleTraits { role_id: ids::normalize(&r.get::<_, String>(0)?), trait_ids: tags_of(r.get(1)?), updated_at: r.get(2)? }))?.collect::<rusqlite::Result<Vec<_>>>();
            rows
        })?)
    }

    /// The traits bound to a session: the role's, then the agent's additions, de-duplicated; unknown ids dropped.
    pub fn effective_traits(&self, role_id: &str, agent_trait_ids: &[String]) -> R<Vec<TraitView>> {
        let mut ids_: Vec<String> = self.role_traits_get(role_id)?.trait_ids;
        for a in agent_trait_ids {
            if !ids_.contains(a) {
                ids_.push(a.clone());
            }
        }
        let mut out = Vec::new();
        for id in ids_ {
            if let Some(t) = self.trait_get(&id)? {
                out.push(t);
            }
        }
        Ok(out)
    }

    // -- role bindings

    pub fn role_binding_set(&self, s: &RoleBindingSave) -> R<RoleBinding> {
        let role = ids::normalize(s.role_id.trim());
        if !ids::is_valid(&role) || !self.ports.role_exists(&role) {
            return bad(format!("unknown role '{}'", crate::roles::take(&role, 40)));
        }
        let blank = |v: &Option<String>| v.as_deref().map(str::trim).filter(|x| !x.is_empty()).map(String::from);
        let (provider, model, effort) = (blank(&s.provider), blank(&s.model), blank(&s.effort));
        if let Some(p) = &provider {
            let known = self.ports.provider_ids();
            if !known.is_empty() && !known.contains(p) {
                let mut k: Vec<_> = known.into_iter().collect();
                k.sort();
                return bad(format!("unknown provider '{}'; known: {}", crate::roles::take(p, 40), k.join(", ")));
            }
        }
        if let Some(e) = &effort {
            let known = self.ports.effort_levels();
            if !known.is_empty() && !known.contains(e) {
                let mut k: Vec<_> = known.into_iter().collect();
                k.sort();
                return bad(format!("unknown effort '{}'; known: {}", crate::roles::take(e, 40), k.join(", ")));
            }
        }
        if let Some(m) = &model {
            self.ports.check_model(m).map_err(LibError::Invalid)?;
        }
        let at = now_ms();
        let row = RoleBinding { role_id: role, provider, model, effort, updated_at: at };
        let r = row.clone();
        self.db.write(|c| {
            c.execute(
                "INSERT INTO role_bindings(role_id, provider, model, effort, updated_at) VALUES (?1, ?2, ?3, ?4, ?5) \
                 ON CONFLICT(role_id) DO UPDATE SET provider = excluded.provider, model = excluded.model, effort = excluded.effort, updated_at = excluded.updated_at",
                params![r.role_id, r.provider, r.model, r.effort, r.updated_at],
            )
        })?;
        Ok(row)
    }

    pub fn role_binding_get(&self, role_id: &str) -> R<RoleBinding> {
        let role = ids::normalize(role_id.trim());
        let row = self.db.read(|c| {
            c.query_row("SELECT role_id, provider, model, effort, updated_at FROM role_bindings WHERE role_id = ?1", [&role], Self::binding_row).optional()
        })?;
        Ok(row.unwrap_or(RoleBinding { role_id: role, provider: None, model: None, effort: None, updated_at: 0 }))
    }

    fn binding_row(r: &rusqlite::Row) -> rusqlite::Result<RoleBinding> {
        Ok(RoleBinding { role_id: ids::normalize(&r.get::<_, String>(0)?), provider: r.get(1)?, model: r.get(2)?, effort: r.get(3)?, updated_at: r.get(4)? })
    }

    pub fn role_binding_list(&self) -> R<Vec<RoleBinding>> {
        Ok(self.db.read(|c| {
            let mut st = c.prepare("SELECT role_id, provider, model, effort, updated_at FROM role_bindings ORDER BY role_id")?;
            let rows = st.query_map([], Self::binding_row)?.collect::<rusqlite::Result<Vec<_>>>();
            rows
        })?)
    }

    // -- layered skills (settings table)

    fn setting(&self, key: &str) -> R<Option<String>> {
        Ok(self.db.read(|c| c.query_row("SELECT value FROM settings WHERE key = ?1", [key], |r| r.get(0)).optional())?)
    }

    pub fn layer_list(&self, scope: &SkillScope) -> R<Vec<LayerSkill>> {
        let mut v: Vec<LayerSkill> = self.setting(&scope.key())?.and_then(|t| serde_json::from_str(&t).ok()).unwrap_or_default();
        v.sort_by(|a, b| a.name.cmp(&b.name));
        Ok(v)
    }

    fn layer_put(&self, scope: &SkillScope, skills: &[LayerSkill]) -> R<()> {
        let (key, text, at) = (scope.key(), serde_json::to_string(skills).unwrap_or_else(|_| "[]".into()), now_ms());
        self.db.write(|c| {
            c.execute(
                "INSERT INTO settings(key, value, updated_at) VALUES (?1, ?2, ?3) ON CONFLICT(key) DO UPDATE SET value = excluded.value, updated_at = excluded.updated_at",
                params![key, text, at],
            )
        })?;
        Ok(())
    }

    pub fn layer_save(&self, scope: &SkillScope, name: &str, description: &str, body: &str) -> R<Vec<LayerSkill>> {
        let name = check_name_body(name, body)?;
        let mut all = self.layer_list(scope)?;
        all.retain(|s| s.name != name);
        all.push(LayerSkill { name, description: description.trim().to_string(), body: body.to_string() });
        all.sort_by(|a, b| a.name.cmp(&b.name));
        self.layer_put(scope, &all)?;
        Ok(all)
    }

    /// Err(Invalid) when the layer has no such skill.
    pub fn layer_delete(&self, scope: &SkillScope, name: &str) -> R<Vec<LayerSkill>> {
        let mut all = self.layer_list(scope)?;
        let before = all.len();
        all.retain(|s| s.name != name.trim());
        if all.len() == before {
            return bad(format!("unknown skill '{}' in the {} layer", crate::roles::take(name.trim(), 120), scope.layer()));
        }
        self.layer_put(scope, &all)?;
        Ok(all)
    }

    /// Global, then project, then role: a narrower layer's skill wins on a name clash. Sorted by name.
    pub fn layer_resolve(&self, project_id: Option<&str>, role: Option<&str>) -> R<Vec<(SkillScope, LayerSkill)>> {
        let mut scopes = vec![SkillScope::Global];
        if let Some(p) = project_id.filter(|p| !p.is_empty()) {
            scopes.push(SkillScope::Project(p.into()));
            if let Some(r) = role.filter(|r| !r.is_empty()) {
                scopes.push(SkillScope::Role(p.into(), ids::normalize(r)));
            }
        }
        let mut out: Vec<(SkillScope, LayerSkill)> = Vec::new();
        for s in scopes {
            for k in self.layer_list(&s)? {
                out.retain(|(_, o)| o.name != k.name);
                out.push((s.clone(), k));
            }
        }
        out.sort_by(|a, b| a.1.name.cmp(&b.1.name));
        Ok(out)
    }
}

/// One layered skill, as the settings row stores it.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
pub struct LayerSkill {
    pub name: String,
    #[serde(default)]
    pub description: String,
    #[serde(default)]
    pub body: String,
}

/// The layer a skill lives in.
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum SkillScope {
    Global,
    Project(String),
    Role(String, String),
}

impl SkillScope {
    pub fn key(&self) -> String {
        match self {
            SkillScope::Global => "skills.layer.global".into(),
            SkillScope::Project(p) => format!("skills.layer.project.{p}"),
            SkillScope::Role(p, r) => format!("skills.layer.role.{p}.{r}"),
        }
    }
    pub fn layer(&self) -> &'static str {
        match self {
            SkillScope::Global => "global",
            SkillScope::Project(_) => "project",
            SkillScope::Role(..) => "role",
        }
    }
    pub fn project(&self) -> Option<&str> {
        match self {
            SkillScope::Global => None,
            SkillScope::Project(p) | SkillScope::Role(p, _) => Some(p),
        }
    }
    pub fn role(&self) -> Option<&str> {
        match self {
            SkillScope::Role(_, r) => Some(r),
            _ => None,
        }
    }
    /// From a `layer_skill_*` request; Err = the refusal reason.
    pub fn parse(scope: &str, project: Option<&str>, role: Option<&str>) -> Result<SkillScope, String> {
        let p = project.map(str::trim).filter(|p| !p.is_empty());
        let r = role.map(str::trim).filter(|r| !r.is_empty());
        match scope.trim() {
            "global" => Ok(SkillScope::Global),
            "project" => p.map(|p| SkillScope::Project(p.into())).ok_or_else(|| "the project layer needs a projectId".into()),
            "role" => match (p, r) {
                (Some(p), Some(r)) if ids::is_valid(&ids::normalize(r)) => Ok(SkillScope::Role(p.into(), ids::normalize(r))),
                (Some(_), Some(r)) => Err(format!("'{}' is not a role id", crate::roles::take(r, 40))),
                _ => Err("the role layer needs a projectId and a role".into()),
            },
            _ => Err("scope must be global, project or role".into()),
        }
    }
}

/// Builds a fresh database with the tables this crate touches (Kotlin DDL), for tests and a new state dir.
pub const SCHEMA: &str = "
CREATE TABLE IF NOT EXISTS settings (key TEXT NOT NULL PRIMARY KEY, value TEXT NOT NULL, updated_at INTEGER NOT NULL);
CREATE TABLE IF NOT EXISTS prompts (id TEXT NOT NULL PRIMARY KEY, name TEXT NOT NULL UNIQUE, body TEXT NOT NULL, tags TEXT NOT NULL DEFAULT '[]', created_at INTEGER NOT NULL, updated_at INTEGER NOT NULL);
CREATE TABLE IF NOT EXISTS skills (id TEXT NOT NULL PRIMARY KEY, name TEXT NOT NULL UNIQUE, description TEXT NOT NULL DEFAULT '', body TEXT NOT NULL, tags TEXT NOT NULL DEFAULT '[]', created_at INTEGER NOT NULL, updated_at INTEGER NOT NULL, attached_only INTEGER NOT NULL DEFAULT 0);
CREATE TABLE IF NOT EXISTS traits (id TEXT NOT NULL PRIMARY KEY, name TEXT NOT NULL, version INTEGER NOT NULL DEFAULT 1 CHECK (version >= 1), body TEXT NOT NULL, check_spec TEXT, created_at INTEGER NOT NULL, updated_at INTEGER NOT NULL);
CREATE TABLE IF NOT EXISTS role_traits (role_id TEXT NOT NULL PRIMARY KEY, trait_ids TEXT NOT NULL DEFAULT '[]', updated_at INTEGER NOT NULL);
CREATE TABLE IF NOT EXISTS role_bindings (role_id TEXT NOT NULL PRIMARY KEY, provider TEXT, model TEXT, effort TEXT, updated_at INTEGER NOT NULL);
";

#[cfg(test)]
pub(crate) mod tests {
    use super::*;

    pub struct Ports;
    impl LibraryPorts for Ports {
        fn role_exists(&self, r: &str) -> bool {
            ids::is_built_in(r)
        }
        fn provider_ids(&self) -> HashSet<String> {
            ["claude".to_string()].into()
        }
        fn effort_levels(&self) -> HashSet<String> {
            ["low".to_string(), "high".to_string()].into()
        }
    }

    pub fn lib() -> (tempfile::TempDir, Library) {
        let dir = tempfile::tempdir().unwrap();
        let db = Db::open(dir.path().join("infantry.db")).unwrap();
        db.write(|c| c.execute_batch(SCHEMA)).unwrap();
        (dir, Library::new(Arc::new(db), Arc::new(Ports)))
    }

    #[test]
    fn prompts_and_skills_upsert_by_name() {
        let (_d, l) = lib();
        let a = l.prompt_save(&PromptSave { name: " Review ".into(), body: "b1".into(), tags: vec!["X".into(), "x".into()] }).unwrap();
        let b = l.prompt_save(&PromptSave { name: "Review".into(), body: "b2".into(), tags: vec![] }).unwrap();
        assert_eq!((a.id.clone(), a.tags.clone()), (b.id.clone(), vec!["x".to_string()]));
        assert_eq!(b.body, "b2");
        assert_eq!(l.prompt_list(Some("x")).unwrap().len(), 0);
        assert!(matches!(l.prompt_save(&PromptSave { name: "n".into(), body: " ".into(), tags: vec![] }), Err(LibError::Invalid(_))));
        let s = l.skill_save(&SkillSave { name: "deploy".into(), body: "do".into(), attached_only: Some(true), ..Default::default() }).unwrap();
        let s2 = l.skill_save(&SkillSave { name: "deploy".into(), body: "do2".into(), ..Default::default() }).unwrap();
        assert!(s2.attached_only && s.id == s2.id);
        assert_eq!(l.skill_get("deploy").unwrap().unwrap().id, s.id);
    }

    #[test]
    fn traits_version_and_binding_guard() {
        let (_d, l) = lib();
        let t = l.trait_save(&TraitSave { id: "terse".into(), name: "Terse".into(), body: "Be brief.".into(), check: None }).unwrap();
        assert_eq!(t.version, 1);
        assert_eq!(l.trait_save(&TraitSave { id: "terse".into(), name: "Terse".into(), body: "Be brief.".into(), check: None }).unwrap().version, 1);
        assert_eq!(l.trait_save(&TraitSave { id: "terse".into(), name: "Terse".into(), body: "Be briefer.".into(), check: None }).unwrap().version, 2);
        l.role_traits_set(&RoleTraitsSave { role_id: "git".into(), trait_ids: vec!["terse".into()] }).unwrap();
        assert_eq!(l.role_traits_get("version-control").unwrap().trait_ids, vec!["terse"]);
        assert!(l.trait_delete("terse").unwrap_err().render("trait_delete").contains("still bound to role 'version-control'"));
        assert!(l.role_traits_set(&RoleTraitsSave { role_id: "qa".into(), trait_ids: vec!["nope".into()] }).is_err());
        let bad_check = TraitCheck { kind: "report".into(), report_to: "pm".into(), fields: vec!["A".into(), "a".into()], reprompt: true };
        assert!(l.trait_save(&TraitSave { id: "x".into(), name: "x".into(), body: "b".into(), check: Some(bad_check) }).is_err());
    }

    #[test]
    fn bindings_and_layers() {
        let (_d, l) = lib();
        assert!(l.role_binding_set(&RoleBindingSave { role_id: "qa".into(), provider: Some("nope".into()), ..Default::default() }).is_err());
        let b = l.role_binding_set(&RoleBindingSave { role_id: "qa".into(), provider: Some("claude".into()), model: Some(" ".into()), effort: Some("high".into()) }).unwrap();
        assert_eq!((b.model, l.role_binding_list().unwrap().len()), (None, 1));
        l.layer_save(&SkillScope::Global, "style", "", "global").unwrap();
        l.layer_save(&SkillScope::Role("p".into(), "qa".into()), "style", "", "role").unwrap();
        let r = l.layer_resolve(Some("p"), Some("qa")).unwrap();
        assert_eq!((r.len(), r[0].1.body.as_str()), (1, "role"));
        assert!(l.layer_delete(&SkillScope::Global, "nope").is_err());
    }
}
