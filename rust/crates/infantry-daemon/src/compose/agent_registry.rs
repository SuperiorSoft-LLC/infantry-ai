// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
//! The agent registry read model: the role catalog, then the plugins' `agents` blocks, under the user's enable/disable
//! overrides (`agent-overrides.json` in the state dir). One value serves the `agent_registry` frame and the
//! `infantry_agent_list|get|enable|disable` tools, so they can never disagree.
use super::host_frames::{s, scan_modules, write_json, AGENT_OVERRIDES_FILE};
use infantry_knowledge::roles::RoleService;
use serde_json::{json, Map, Value};
use std::path::PathBuf;
use std::sync::Arc;

pub struct AgentRegistry {
    pub plugins_dir: Option<PathBuf>,
    pub roles: Arc<RoleService>,
    pub state_dir: PathBuf,
}

impl AgentRegistry {
    fn overrides(&self) -> Map<String, Value> {
        std::fs::read_to_string(self.state_dir.join(AGENT_OVERRIDES_FILE)).ok().and_then(|t| serde_json::from_str(&t).ok()).unwrap_or_default()
    }

    /// Every agent entry: `{definition, contributor, source, enabled, effectiveInstructions}`.
    pub fn list(&self) -> Vec<Value> {
        let off = self.overrides();
        let enabled = |id: &str| off.get(id).and_then(Value::as_bool).unwrap_or(true);
        let mut out: Vec<Value> = vec![];
        for r in self.roles.all() {
            let def = json!({ "id": r.id, "name": r.name, "role": r.id });
            out.push(json!({ "definition": def, "contributor": "roles", "source": "roles", "enabled": enabled(&r.id), "effectiveInstructions": r.persona }));
        }
        let mut mods = scan_modules(&self.plugins_dir);
        mods.sort_by(|a, b| a.id.cmp(&b.id));
        for m in mods {
            for a in m.manifest.get("agents").and_then(Value::as_array).into_iter().flatten() {
                let id = s(a, "id");
                if id.is_empty() || out.iter().any(|o| o["definition"]["id"] == id) {
                    continue;
                }
                let own = s(a, "instructions").to_string();
                let effective = if own.is_empty() { a.get("role").and_then(Value::as_str).and_then(|r| self.roles.persona(r)).unwrap_or_default() } else { own };
                out.push(json!({ "definition": a, "contributor": m.id, "source": "manifest", "enabled": enabled(id), "effectiveInstructions": effective }));
            }
        }
        out
    }

    pub fn get(&self, id: &str) -> Result<Value, String> {
        self.list().into_iter().find(|a| a["definition"]["id"] == id).ok_or_else(|| format!("no agent has the id '{id}'"))
    }

    /// Enable or disable one agent: the user's override, so only an owner may (an agent caller is refused).
    pub fn set_enabled(&self, id: &str, enable: bool) -> Result<Value, String> {
        self.get(id)?;
        let mut off = self.overrides();
        if enable {
            off.remove(id);
        } else {
            off.insert(id.into(), false.into());
        }
        write_json(&self.state_dir.join(AGENT_OVERRIDES_FILE), &Value::Object(off))?;
        self.get(id)
    }

    /// The `agent_registry` frame's ops (list, get, enable, disable).
    pub fn op(&self, op: &str, id: &str, owner: bool) -> Result<Value, String> {
        match op {
            "" | "list" => Ok(Value::Array(self.list())),
            "get" => self.get(id),
            "enable" | "disable" => {
                if !owner {
                    return Err("owner only".into());
                }
                self.set_enabled(id, op == "enable")
            }
            other => Err(format!("unknown op '{other}' (list, get, enable, disable)")),
        }
    }
}
