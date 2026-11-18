// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
//! `.infantry/mcp.json` (P11-C): project-scoped defaults for `mcp serve`, read and refused exactly as `McpServeConfig`
//! does. Flags win over the file.

use serde_json::Value;
use std::path::{Path, PathBuf};

pub const RELATIVE_PATH: &str = ".infantry/mcp.json";
const LEGACY_RELATIVE_PATH: &str = ".agenthq/mcp.json";
const KEYS: [&str; 4] = ["projectId", "maxDepth", "maxSpawns", "allowKillAny"];

#[derive(Default, Clone, Debug, PartialEq)]
pub struct McpServeConfig {
    pub project_id: Option<String>,
    pub max_depth: Option<i32>,
    pub max_spawns: Option<i32>,
    pub allow_kill_any: Option<bool>,
}

/// `java.io.File.getAbsolutePath` of a cwd-relative file.
fn absolute(cwd: &Path, rel: &str) -> PathBuf {
    let mut p = cwd.to_path_buf();
    for part in rel.split('/') {
        p.push(part);
    }
    p
}

fn file_in(cwd: &Path) -> PathBuf {
    let new = absolute(cwd, RELATIVE_PATH);
    if new.is_file() {
        return new;
    }
    let old = absolute(cwd, LEGACY_RELATIVE_PATH);
    if old.is_file() {
        old
    } else {
        new
    }
}

fn problem(file: &Path, what: impl AsRef<str>) -> String {
    format!("{RELATIVE_PATH} ({}) {}", file.display(), what.as_ref())
}

/// `JsonElement.toString()` of a primitive: strings quoted and escaped, others as written.
fn shown(v: &Value) -> String {
    serde_json::to_string(v).unwrap_or_default()
}

/// kotlinx `intOrNull` of a non-string primitive.
fn int_or_null(v: &Value) -> Option<i32> {
    match v {
        Value::Number(n) => n.to_string().parse::<i32>().ok(),
        _ => None,
    }
}

/// `McpServeConfig.load`: None when there is no file; Err is the one-line refusal (`error: ` + it, exit 2).
pub fn load(cwd: &Path) -> Result<Option<McpServeConfig>, String> {
    let file = file_in(cwd);
    if !file.exists() {
        return Ok(None);
    }
    let bytes = std::fs::read(&file).map_err(|e| problem(&file, format!("cannot be read ({e})")))?;
    parse(&String::from_utf8_lossy(&bytes), &file).map(Some)
}

pub fn parse(text: &str, file: &Path) -> Result<McpServeConfig, String> {
    let root: Value = serde_json::from_str(text).map_err(|e| {
        let first = e.to_string().lines().next().unwrap_or("parse error").to_string();
        problem(file, format!("is not valid JSON ({first})"))
    })?;
    let Value::Object(root) = root else {
        return Err(problem(file, "must be a JSON object, e.g. {\"projectId\": \"my-project\"}"));
    };
    let mut unknown: Vec<&String> = root.keys().filter(|k| !KEYS.contains(&k.as_str())).collect();
    if !unknown.is_empty() {
        unknown.sort();
        let names: Vec<String> = unknown.iter().map(|k| format!("'{k}'")).collect();
        return Err(problem(file, format!("has unknown key(s) {}; allowed: {}", names.join(", "), KEYS.join(", "))));
    }
    let primitive = |key: &str| -> Result<Option<&Value>, String> {
        match root.get(key) {
            None | Some(Value::Null) => Ok(None),
            Some(v @ (Value::Array(_) | Value::Object(_))) => Err(problem(
                file,
                format!("'{key}' must be a {}, got {}", if key == "projectId" { "string" } else { "plain value" }, shown(v)),
            )),
            Some(v) => Ok(Some(v)),
        }
    };
    let count = |key: &str| -> Result<Option<i32>, String> {
        match primitive(key)? {
            None => Ok(None),
            Some(v) => int_or_null(v)
                .filter(|n| *n >= 0)
                .map(Some)
                .ok_or_else(|| problem(file, format!("'{key}' must be a whole number >= 0, got {}", shown(v)))),
        }
    };
    let project_id = match primitive("projectId")? {
        None => None,
        Some(v) => Some(
            v.as_str()
                .map(str::trim)
                .filter(|s| !s.is_empty())
                .map(str::to_string)
                .ok_or_else(|| problem(file, format!("'projectId' must be a non-empty string, got {}", shown(v))))?,
        ),
    };
    let allow_kill_any = match primitive("allowKillAny")? {
        None => None,
        Some(v) => Some(
            v.as_bool()
                .ok_or_else(|| problem(file, format!("'allowKillAny' must be true or false, got {}", shown(v))))?,
        ),
    };
    Ok(McpServeConfig { project_id, max_depth: count("maxDepth")?, max_spawns: count("maxSpawns")?, allow_kill_any })
}

/// Flags win over the file.
pub fn merge(flags: McpServeConfig, file: Option<McpServeConfig>) -> McpServeConfig {
    let file = file.unwrap_or_default();
    McpServeConfig {
        project_id: flags.project_id.or(file.project_id),
        max_depth: flags.max_depth.or(file.max_depth),
        max_spawns: flags.max_spawns.or(file.max_spawns),
        allow_kill_any: flags.allow_kill_any.or(file.allow_kill_any),
    }
}

/// A path compared as `java.io.File` equality compares it (case-insensitive on Windows).
fn same_path(a: &Path, b: &Path) -> bool {
    if cfg!(windows) {
        a.to_string_lossy().to_lowercase() == b.to_string_lossy().to_lowercase()
    } else {
        a == b
    }
}

fn canonical(p: &Path) -> PathBuf {
    let c = std::fs::canonicalize(p).unwrap_or_else(|_| {
        if p.is_absolute() {
            p.to_path_buf()
        } else {
            std::env::current_dir().map(|d| d.join(p)).unwrap_or_else(|_| p.to_path_buf())
        }
    });
    // canonicalize on Windows returns the `\\?\` verbatim form; java's canonicalFile does not.
    let s = c.to_string_lossy();
    match s.strip_prefix(r"\\?\UNC\") {
        Some(rest) => PathBuf::from(format!(r"\\{rest}")),
        None => PathBuf::from(s.strip_prefix(r"\\?\").unwrap_or(&s)),
    }
}

/// `McpServeConfig.projectForDir`: the registered project whose folder holds [dir] (the deepest one wins).
pub fn project_for_dir(projects: &[(String, String)], dir: &Path) -> Option<String> {
    let here = canonical(dir);
    projects
        .iter()
        .filter(|(_, d)| !d.trim().is_empty())
        .map(|(id, d)| (id, canonical(Path::new(d))))
        .filter(|(_, root)| here.ancestors().any(|a| same_path(a, root)))
        .fold(None::<(&String, PathBuf)>, |best, cur| match &best {
            Some((_, b)) if b.as_os_str().len() >= cur.1.as_os_str().len() => best,
            _ => Some(cur),
        })
        .map(|(id, _)| id.clone())
}

#[cfg(test)]
mod tests {
    use super::*;

    fn f() -> PathBuf {
        PathBuf::from("C:/w/.infantry/mcp.json")
    }

    #[test]
    fn parses_and_refuses_like_kotlin() {
        let c = parse(r#"{"projectId":" p1 ","maxDepth":2,"allowKillAny":true,"maxSpawns":null}"#, &f()).unwrap();
        assert_eq!(c, McpServeConfig { project_id: Some("p1".into()), max_depth: Some(2), max_spawns: None, allow_kill_any: Some(true) });
        assert!(parse(r#"{"z":1,"a":2}"#, &f()).unwrap_err().ends_with(
            "has unknown key(s) 'a', 'z'; allowed: projectId, maxDepth, maxSpawns, allowKillAny"
        ));
        assert!(parse(r#"{"maxDepth":"2"}"#, &f()).unwrap_err().ends_with("'maxDepth' must be a whole number >= 0, got \"2\""));
        assert!(parse(r#"{"maxDepth":-1}"#, &f()).unwrap_err().ends_with("got -1"));
        assert!(parse(r#"{"projectId":[1]}"#, &f()).unwrap_err().ends_with("'projectId' must be a string, got [1]"));
        assert!(parse(r#"{"allowKillAny":"true"}"#, &f()).unwrap_err().ends_with("must be true or false, got \"true\""));
        assert!(parse("[]", &f()).unwrap_err().contains("must be a JSON object"));
    }

    #[test]
    fn flags_win() {
        let m = merge(
            McpServeConfig { max_depth: Some(1), ..Default::default() },
            Some(McpServeConfig { max_depth: Some(3), project_id: Some("p".into()), ..Default::default() }),
        );
        assert_eq!(m.max_depth, Some(1));
        assert_eq!(m.project_id.as_deref(), Some("p"));
    }

    #[test]
    fn deepest_project_folder_wins() {
        let tmp = std::env::temp_dir().join(format!("infantry-cli-pfd-{}", std::process::id()));
        let inner = tmp.join("a").join("b");
        std::fs::create_dir_all(&inner).unwrap();
        let projects = vec![
            ("outer".to_string(), tmp.to_string_lossy().to_string()),
            ("inner".to_string(), tmp.join("a").to_string_lossy().to_string()),
            ("blank".to_string(), " ".to_string()),
        ];
        assert_eq!(project_for_dir(&projects, &inner).as_deref(), Some("inner"));
        assert_eq!(project_for_dir(&projects, &std::env::temp_dir()), None);
        let _ = std::fs::remove_dir_all(&tmp);
    }
}
