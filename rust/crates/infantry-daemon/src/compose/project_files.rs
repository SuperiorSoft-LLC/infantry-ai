// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
//! `project_files` `{op, projectId, path, ...}` -> `project_files`: a plugin's files under `<project>/.infantry/`
//! (Kotlin `ProjectFiles`, DP-10a), so a WASM guest without a files import still reads and writes them. `path` is
//! relative to that folder and may hold only plain components (no `..`, no root, no drive).
//!
//! - `stat` -> `{exists, size?, mtime?, path}`; `get` `{maxBytes?}` -> `{exists, base64?, size?}`;
//! - `put` `{base64, mtime?}` -> `{path, size}` (parents made); `touch` `{mtime}` -> `{path}`;
//! - `list` -> `{files:[{name,size,mtime}]}` (regular files directly in the folder); `delete` -> `{deleted}` (one regular file).
use base64::Engine;
use infantry_projects::store::ProjectStore;
use serde_json::{json, Value};
use std::path::{Component, Path, PathBuf};
use std::time::{Duration, SystemTime, UNIX_EPOCH};

pub const PROJECT_FILES: &str = "project_files";
/// The folder inside a project these files live in.
pub const ROOT: &str = ".infantry";
/// The most bytes one `get` or `put` carries (decoded), and the default `get` cap.
pub const MAX_BYTES: u64 = 24 * 1024 * 1024;

fn safe_rel(path: &str) -> Result<PathBuf, String> {
    let p = Path::new(path.trim());
    if path.trim().is_empty() || !p.components().all(|c| matches!(c, Component::Normal(_))) {
        return Err(format!("path '{}' must be relative inside {ROOT}/ (plain names only)", path.chars().take(120).collect::<String>()));
    }
    Ok(p.to_path_buf())
}

fn mtime_ms(m: &std::fs::Metadata) -> Option<i64> {
    m.modified().ok()?.duration_since(UNIX_EPOCH).ok().map(|d| d.as_millis() as i64)
}

fn set_mtime(file: &Path, ms: i64) -> Result<(), String> {
    let f = std::fs::OpenOptions::new().write(true).open(file).map_err(|e| e.to_string())?;
    f.set_modified(UNIX_EPOCH + Duration::from_millis(ms.max(0) as u64)).map_err(|e| e.to_string())
}

/// The answer body, or the refusal reason.
pub fn answer(projects: &ProjectStore, data: &str) -> Result<Value, String> {
    let d: Value = serde_json::from_str(data).map_err(|_| "payload must be a JSON object".to_string())?;
    let s = |k: &str| d.get(k).and_then(Value::as_str).unwrap_or("").to_string();
    let project = projects.get(&s("projectId")).map_err(|e| e.to_string())?;
    if project.directory.trim().is_empty() {
        return Err(format!("project {} has no directory", project.id));
    }
    let file = Path::new(&project.directory).join(ROOT).join(safe_rel(&s("path"))?);
    let shown = file.display().to_string();
    match s("op").as_str() {
        "stat" => Ok(match std::fs::metadata(&file) {
            Ok(m) if m.is_file() => json!({"exists": true, "size": m.len(), "mtime": mtime_ms(&m), "path": shown}),
            _ => json!({"exists": false, "path": shown}),
        }),
        "get" => {
            let cap = d.get("maxBytes").and_then(Value::as_u64).unwrap_or(MAX_BYTES).min(MAX_BYTES);
            match std::fs::metadata(&file) {
                Ok(m) if m.is_file() && m.len() > cap => Err(format!("{shown} is {} bytes, over the {cap} cap", m.len())),
                Ok(m) if m.is_file() => {
                    let bytes = std::fs::read(&file).map_err(|e| e.to_string())?;
                    Ok(json!({"exists": true, "size": bytes.len(), "base64": base64::engine::general_purpose::STANDARD.encode(bytes), "path": shown}))
                }
                _ => Ok(json!({"exists": false, "path": shown})),
            }
        }
        "put" => {
            let enc: String = s("base64").chars().filter(|c| *c != '\r' && *c != '\n').collect();
            if enc.len() as u64 > (MAX_BYTES + 2) / 3 * 4 {
                return Err(format!("over the {MAX_BYTES} byte limit"));
            }
            let bytes = base64::engine::general_purpose::STANDARD.decode(enc.as_bytes()).map_err(|e| format!("base64 is malformed: {e}"))?;
            if let Some(dir) = file.parent() {
                std::fs::create_dir_all(dir).map_err(|e| e.to_string())?;
            }
            let tmp = file.with_extension(format!("tmp-{}", std::process::id()));
            std::fs::write(&tmp, &bytes).and_then(|_| std::fs::rename(&tmp, &file)).map_err(|e| {
                let _ = std::fs::remove_file(&tmp);
                e.to_string()
            })?;
            if let Some(ms) = d.get("mtime").and_then(Value::as_i64) {
                set_mtime(&file, ms)?;
            }
            Ok(json!({"path": shown, "size": bytes.len()}))
        }
        "touch" => {
            let ms = d.get("mtime").and_then(Value::as_i64).unwrap_or_else(|| SystemTime::now().duration_since(UNIX_EPOCH).map(|x| x.as_millis() as i64).unwrap_or(0));
            set_mtime(&file, ms)?;
            Ok(json!({"path": shown}))
        }
        // Regular files directly in the folder (a symlink or sub-folder is not listed), for a retention sweep.
        "list" => {
            let mut out = Vec::new();
            if let Ok(rd) = std::fs::read_dir(&file) {
                for e in rd.flatten() {
                    if let (Ok(ty), Ok(m)) = (e.file_type(), e.metadata()) {
                        if ty.is_file() {
                            out.push(json!({"name": e.file_name().to_string_lossy(), "size": m.len(), "mtime": mtime_ms(&m)}));
                        }
                    }
                }
            }
            Ok(json!({"files": out, "path": shown}))
        }
        "delete" => Ok(json!({"deleted": std::fs::metadata(&file).map(|m| m.is_file()).unwrap_or(false) && std::fs::remove_file(&file).is_ok(), "path": shown})),
        other => Err(format!("unknown op '{}' (stat, get, put, touch, list, delete)", other.chars().take(40).collect::<String>())),
    }
}

#[cfg(test)]
mod tests {
    use super::safe_rel;

    #[test]
    fn only_plain_relative_paths_are_taken() {
        assert!(safe_rel("uploads/chat/a.png").is_ok());
        for bad in ["", "../x", "uploads/../../x", "/etc/passwd", "C:\\x", "\\\\srv\\x"] {
            assert!(safe_rel(bad).is_err(), "{bad}");
        }
    }
}
