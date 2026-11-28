// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
//! PARITY-AUDIT-1 #28: the model and role allowlists a session START is checked against (settings
//! `limits.models.allowed`, `limits.roles.allowed`). Empty = no restriction (the default). A pattern is compared
//! case-insensitively; `*` matches any run of characters. Only a new start is checked; a running session never is.
use serde_json::Value;

pub const MODELS_KEY: &str = "limits.models.allowed";
pub const ROLES_KEY: &str = "limits.roles.allowed";

/// A setting value as a pattern list: a JSON array of strings, or a comma / newline separated string. Blanks dropped.
pub fn patterns(v: Option<&Value>) -> Vec<String> {
    let mut out: Vec<String> = match v {
        Some(Value::Array(a)) => a.iter().filter_map(Value::as_str).map(str::to_string).collect(),
        // A JSON array saved as text (the settings tools take the value as JSON text) reads as that array.
        Some(Value::String(s)) if s.trim_start().starts_with('[') => {
            return serde_json::from_str::<Value>(s).ok().filter(Value::is_array).map(|a| patterns(Some(&a))).unwrap_or_default()
        }
        Some(Value::String(s)) => s.split([',', '\n']).map(str::to_string).collect(),
        _ => Vec::new(),
    };
    out = out.into_iter().map(|p| p.trim().to_string()).filter(|p| !p.is_empty()).collect();
    out.dedup();
    out
}

/// [pattern] against [id]: case-insensitive, `*` = any run of characters (none included).
pub fn matches(pattern: &str, id: &str) -> bool {
    let (p, s) = (pattern.trim().to_lowercase(), id.trim().to_lowercase());
    if !p.contains('*') {
        return p == s;
    }
    let parts: Vec<&str> = p.split('*').collect();
    let (first, last) = (parts[0], parts[parts.len() - 1]);
    if !s.starts_with(first) {
        return false;
    }
    let mut at = first.len();
    for mid in &parts[1..parts.len() - 1] {
        match s[at..].find(mid) {
            Some(i) => at += i + mid.len(),
            None => return false,
        }
    }
    s.len() - at >= last.len() && s.ends_with(last)
}

fn one(what: &str, key: &str, allowed: &[String], value: Option<&str>, verb: &str) -> Result<(), String> {
    if allowed.is_empty() {
        return Ok(());
    }
    let v = value.map(str::trim).filter(|v| !v.is_empty());
    if v.is_some_and(|v| allowed.iter().any(|p| matches(p, v))) {
        return Ok(());
    }
    let named = v.map(|v| format!("`{v}` is not an allowed {what}")).unwrap_or_else(|| format!("no {what} named, and the {what}s are restricted"));
    Err(format!("{verb} refused: {named}. Allowed {what}s: {} (setting '{key}').", allowed.join(", ")))
}

/// Ok when the start may run; else the refusal. Empty lists return at once (the default path is unchanged).
pub fn check(models: &[String], roles: &[String], model: Option<&str>, role: Option<&str>, verb: &str) -> Result<(), String> {
    if models.is_empty() && roles.is_empty() {
        return Ok(());
    }
    one("model", MODELS_KEY, models, model, verb)?;
    one("role", ROLES_KEY, roles, role, verb)
}

#[cfg(test)]
mod tests {
    use super::*;
    use serde_json::json;

    #[test]
    fn default_empty_allows_everything() {
        assert!(check(&[], &[], Some("opus"), Some("pm"), "spawn").is_ok());
        assert!(check(&[], &[], None, None, "spawn").is_ok());
        assert_eq!(patterns(Some(&json!("[\"sonnet\"]"))), vec!["sonnet"]);
        assert_eq!(patterns(Some(&json!(r#"["sonnet"]"#))), vec!["sonnet"]);
        assert!(patterns(None).is_empty() && patterns(Some(&json!(""))).is_empty() && patterns(Some(&json!([]))).is_empty());
    }

    #[test]
    fn a_disallowed_model_or_role_is_refused_with_the_reason() {
        let m = patterns(Some(&json!(["sonnet"])));
        let e = check(&m, &[], Some("opus"), None, "spawn").unwrap_err();
        assert!(e.contains("`opus` is not an allowed model") && e.contains("sonnet") && e.contains(MODELS_KEY), "{e}");
        assert!(check(&m, &[], Some("Sonnet"), None, "spawn").is_ok());
        assert!(check(&m, &[], None, None, "spawn").unwrap_err().contains("no model named"));
        let r = patterns(Some(&json!("backend, qa")));
        let e = check(&[], &r, Some("opus"), Some("pm"), "spawn").unwrap_err();
        assert!(e.contains("`pm` is not an allowed role") && e.contains(ROLES_KEY), "{e}");
        assert!(check(&[], &r, None, Some("QA"), "spawn").is_ok());
    }

    #[test]
    fn star_matches_any_run() {
        assert!(matches("*-free", "opencode-go/beam-free"));
        assert!(matches("claude-*-5", "claude-sonnet-5"));
        assert!(matches("*", "x"));
        assert!(!matches("claude-*-5", "claude-sonnet-4"));
        assert!(!matches("a*a", "a"));
    }
}
