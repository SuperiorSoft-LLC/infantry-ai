// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
//! PM-ENFORCE-1: which role-policy entry (a module's `rolePolicy` file) a session of a role starts with. The entry goes
//! to the session as `INFANTRY_ROLE_POLICY` and the harness refuses the calls it names. Data only: core names no role
//! or plugin; the entry says which setting turns it off, and that setting is on unless it is `false`.
use serde_json::Value;

/// The session env that carries the entry.
pub const ENV: &str = "INFANTRY_ROLE_POLICY";

/// The env pair for a session of [role]: the first policy file that lists the role, unless its setting is off.
/// [setting] resolves a key for the session's project and role (`None` = unset = on).
pub fn env_for(files: &[String], role: Option<&str>, setting: &dyn Fn(&str) -> Option<Value>) -> Option<(String, String)> {
    let role = role.map(|r| r.trim().to_lowercase()).filter(|r| !r.is_empty())?;
    for text in files {
        let Ok(doc) = serde_json::from_str::<Value>(text) else { continue };
        let Some(entry) = doc.get("roles").and_then(|r| r.get(&role)).filter(|e| e.is_object()) else { continue };
        let off = entry.get("setting").and_then(Value::as_str).and_then(setting).and_then(|v| v.as_bool()) == Some(false);
        return (!off).then(|| (ENV.to_string(), entry.to_string()));
    }
    None
}

#[cfg(test)]
mod tests {
    use super::*;
    use serde_json::json;

    fn file() -> String {
        include_str!("../../../plugins/official/Infantry_Roles/roles/policy.json").to_string()
    }

    #[test]
    fn pm_gets_the_entry_by_default_and_a_worker_gets_none() {
        let none = |_: &str| None;
        let (k, v) = env_for(&[file()], Some("pm"), &none).unwrap();
        assert_eq!(k, ENV);
        assert!(v.contains("agents_spawn") && v.contains("fullstack") && v.contains("version-control"));
        for r in ["fullstack", "git", "version-control", "backend"] {
            assert!(env_for(&[file()], Some(r), &none).is_none(), "{r}");
        }
        assert!(env_for(&[file()], None, &none).is_none());
    }

    #[test]
    fn the_setting_set_to_false_turns_it_off_and_true_keeps_it() {
        assert!(env_for(&[file()], Some("PM"), &|_| Some(json!(false))).is_none());
        assert!(env_for(&[file()], Some("pm"), &|_| Some(json!(true))).is_some());
        assert!(env_for(&["not json".into()], Some("pm"), &|_| None).is_none());
    }
}
