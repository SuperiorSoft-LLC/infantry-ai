// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
//! IMG-1 / CHAT-IMAGE-REMOTE-1: `image_upload` -> `image` `{path, relPath, sha256, bytes, mimeType}` and `image_fetch`
//! `{id, projectId?}` -> `image_bytes` `{id, mimeType, base64}` | `{id, missing: true}`, over the host's
//! `project_files` frame (files under `<project>/.infantry/`). Every refusal happens before anything is written.
use crate::Ask;
use base64::Engine;
use serde_json::{json, Value};
use sha2::{Digest, Sha256};

/// 10 MB decoded; purpose `file` takes 20 MB (Kotlin ChatPanelState's limits). Both are named settings, in MB.
pub const MAX_BYTES: usize = 10 * 1024 * 1024;
pub const MAX_FILE_BYTES: usize = 20 * 1024 * 1024;
pub const IMAGE_MB_KEY: &str = "limits.chat.imageMaxMb";
pub const FILE_MB_KEY: &str = "limits.chat.fileMaxMb";

/// The (image, file) byte limits from the settings; a missing or invalid value (not 1-1024 MB) = the default.
pub fn limits(get: impl Fn(&str) -> Option<String>) -> (usize, usize) {
    let mb = |k: &str, d: usize| get(k).and_then(|v| v.trim().parse::<usize>().ok()).filter(|n| (1..=1024).contains(n)).map_or(d, |n| n * 1024 * 1024);
    (mb(IMAGE_MB_KEY, MAX_BYTES), mb(FILE_MB_KEY, MAX_FILE_BYTES))
}
pub const PURPOSES: [&str; 3] = ["chat", "ticket", "file"];
const KEYS: [&str; 5] = ["projectId", "purpose", "fileName", "mimeType", "base64"];
const UPLOADS: &str = "uploads";
const UPLOADS_REL: &str = ".infantry/uploads";
const ACCEPTED: &str = "png, jpeg, gif or webp";
const FETCH_PURPOSES: [&str; 2] = ["chat", "ticket"];
const TYPES: [(&str, &str); 4] = [("png", "image/png"), ("jpg", "image/jpeg"), ("gif", "image/gif"), ("webp", "image/webp")];

/// The (extension, mime) of `bytes` by their magic bytes.
pub fn sniff(b: &[u8]) -> Option<(&'static str, &'static str)> {
    let i = if b.starts_with(&[0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A]) {
        0
    } else if b.starts_with(&[0xFF, 0xD8, 0xFF]) {
        1
    } else if b.starts_with(b"GIF87a") || b.starts_with(b"GIF89a") {
        2
    } else if b.starts_with(b"RIFF") && b.len() >= 12 && &b[8..12] == b"WEBP" {
        3
    } else {
        return None;
    };
    Some(TYPES[i])
}

fn of_mime(m: &str) -> Option<&'static str> {
    match m.trim().to_lowercase().split(';').next().unwrap_or("").trim() {
        "image/png" => Some("image/png"),
        "image/jpeg" | "image/jpg" | "image/pjpeg" => Some("image/jpeg"),
        "image/gif" => Some("image/gif"),
        "image/webp" => Some("image/webp"),
        _ => None,
    }
}

/// A file name's extension when it is 1-10 letters or digits (lowercased), else `bin`.
pub fn file_extension(name: &str) -> String {
    let base = name.rsplit(['/', '\\']).next().unwrap_or("");
    let ext = match base.rfind('.') {
        Some(i) => base[i + 1..].to_lowercase(),
        None => String::new(),
    };
    if (1..=10).contains(&ext.len()) && ext.chars().all(|c| c.is_ascii_lowercase() || c.is_ascii_digit()) {
        ext
    } else {
        "bin".into()
    }
}

/// An uploaded image's id: `<sha256>.<png|jpg|gif|webp>`.
pub fn is_upload_id(id: &str) -> bool {
    match id.split_once('.') {
        Some((h, e)) => h.len() == 64 && h.chars().all(|c| c.is_ascii_digit() || ('a'..='f').contains(&c)) && TYPES.iter().any(|t| t.0 == e),
        None => false,
    }
}

fn files(ask: Ask, body: Value) -> Result<Value, String> {
    let r = ask("project_files", &body.to_string(), None)?;
    if r.type_ == "error" {
        return Err(r.data);
    }
    serde_json::from_str(&r.data).map_err(|e| format!("project_files answered no JSON: {e}"))
}

/// The `image` answer body for an `image_upload` payload; Err = the refusal reason.
pub fn upload(data: &str, ask: Ask, limits: (usize, usize)) -> Result<Value, String> {
    let o: Value = serde_json::from_str(data).map_err(|_| "payload must be a JSON object".to_string())?;
    let o = o.as_object().ok_or("payload must be a JSON object")?;
    if let Some(k) = o.keys().find(|k| !KEYS.contains(&k.as_str())) {
        return Err(format!("payload has an unknown field '{}'", k.chars().take(40).collect::<String>()));
    }
    let field = |k: &str, required: bool| -> Result<String, String> {
        match o.get(k) {
            None | Some(Value::Null) if required => Err(format!("payload is missing '{k}'")),
            None | Some(Value::Null) => Ok(String::new()),
            Some(Value::String(s)) => Ok(s.clone()),
            Some(_) => Err(format!("payload field '{k}' must be a string")),
        }
    };
    let (project, purpose, file_name, mime, b64) = (field("projectId", true)?, field("purpose", true)?, field("fileName", false)?, field("mimeType", false)?, field("base64", true)?);
    if !PURPOSES.contains(&purpose.as_str()) {
        return Err(format!("purpose '{}' is not accepted; only chat or ticket or file", purpose.chars().take(40).collect::<String>()));
    }
    let limit = if purpose == "file" { limits.1 } else { limits.0 };
    let too_large = || format!("the file is over the {} MB limit ({limit} bytes)", limit / (1024 * 1024));
    let enc: String = b64.chars().filter(|c| *c != '\r' && *c != '\n').collect();
    if enc.len() > (limit + 2) / 3 * 4 {
        return Err(too_large());
    }
    let bytes = base64::engine::general_purpose::STANDARD.decode(enc.as_bytes()).map_err(|e| format!("base64 is malformed: {e}"))?;
    if bytes.len() > limit {
        return Err(too_large());
    }
    if bytes.is_empty() {
        return Err("the file is empty".into());
    }
    let (ext, mime_type) = if purpose == "file" {
        let m = mime.trim().chars().take(120).collect::<String>();
        (file_extension(&file_name), if m.is_empty() { "application/octet-stream".to_string() } else { m })
    } else {
        let (ext, real) = sniff(&bytes).ok_or_else(|| format!("not a {ACCEPTED} image (its first bytes match none of them)"))?;
        if !mime.trim().is_empty() {
            let declared = of_mime(&mime).ok_or_else(|| format!("mimeType '{}' is not accepted; only {ACCEPTED}", mime.chars().take(80).collect::<String>()))?;
            if declared != real {
                return Err(format!("mimeType '{}' contradicts the file's bytes, which are {real}", mime.chars().take(80).collect::<String>()));
            }
        }
        (ext.to_string(), real.to_string())
    };
    let sha: String = Sha256::digest(&bytes).iter().map(|b| format!("{b:02x}")).collect();
    let at = |path: String, op: &str| json!({"op": op, "projectId": project, "path": path});
    let ignore = format!("{UPLOADS}/.gitignore");
    if files(ask, at(ignore.clone(), "stat"))?["exists"] != true {
        let mut put = at(ignore, "put");
        put["base64"] = json!(base64::engine::general_purpose::STANDARD.encode(b"*\n"));
        files(ask, put)?;
    }
    let name = format!("{sha}.{ext}");
    let rel = format!("{UPLOADS}/{purpose}/{name}");
    // Dedupe by content: the name is the hash, so an existing file of the right size is these bytes.
    let st = files(ask, at(rel.clone(), "stat"))?;
    let path = if st["size"].as_u64() != Some(bytes.len() as u64) {
        let mut put = at(rel.clone(), "put");
        put["base64"] = json!(enc);
        files(ask, put)?["path"].as_str().unwrap_or("").to_string()
    } else {
        st["path"].as_str().unwrap_or("").to_string()
    };
    // IMG-5: "last used", the clock the retention sweep reads; a dedupe hit refreshes it.
    files(ask, at(rel, "touch"))?;
    Ok(json!({"path": path, "relPath": format!("{UPLOADS_REL}/{purpose}/{name}"), "sha256": sha, "bytes": bytes.len(), "mimeType": mime_type}))
}

/// The `image_bytes` answer body for an `image_fetch` payload; Err = the refusal reason.
pub fn fetch(data: &str, ask: Ask) -> Result<Value, String> {
    let o: Value = serde_json::from_str(data).ok().filter(Value::is_object).ok_or("payload must be a JSON object")?;
    let id = o["id"].as_str().unwrap_or("").trim().to_lowercase();
    if !is_upload_id(&id) {
        return Err(format!("'{}' is not an uploaded image id", id.chars().take(80).collect::<String>()));
    }
    let r = ask("project_list", "", None)?;
    let mut ids: Vec<String> = serde_json::from_str::<Value>(&r.data).ok().and_then(|v| v.as_array().cloned()).unwrap_or_default().iter().filter_map(|p| p["id"].as_str().map(String::from)).collect();
    if let Some(h) = o["projectId"].as_str().filter(|h| ids.iter().any(|i| i == h)).map(String::from) {
        ids.retain(|i| *i != h);
        ids.insert(0, h);
    }
    let mime = TYPES.iter().find(|t| id.ends_with(&format!(".{}", t.0))).map(|t| t.1).unwrap_or("application/octet-stream");
    for p in &ids {
        for purpose in FETCH_PURPOSES {
            if let Ok(v) = files(ask, json!({"op": "get", "projectId": p, "path": format!("{UPLOADS}/{purpose}/{id}")})) {
                if let Some(b) = v["base64"].as_str() {
                    return Ok(json!({"id": id, "mimeType": mime, "base64": b}));
                }
            }
        }
    }
    Ok(json!({"id": id, "missing": true}))
}

#[cfg(test)]
mod tests {
    use super::*;
    use infantry_plugin_sdk::types::Frame;
    use std::cell::RefCell;
    use std::collections::HashMap;

    const PNG: [u8; 9] = [0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 1];

    #[test]
    fn an_upload_is_stored_by_hash_then_fetched_and_a_bad_one_writes_nothing() {
        let disk: RefCell<HashMap<String, String>> = RefCell::new(HashMap::new());
        let ask = |ty: &str, data: &str, _: Option<&str>| -> Result<Frame, String> {
            let d: Value = serde_json::from_str(data).unwrap_or(Value::Null);
            let key = format!("{}/{}", d["projectId"].as_str().unwrap_or(""), d["path"].as_str().unwrap_or(""));
            let body = match (ty, d["op"].as_str()) {
                ("project_list", _) => json!([{"id": "p1"}]),
                (_, Some("put")) => {
                    disk.borrow_mut().insert(key.clone(), d["base64"].as_str().unwrap().into());
                    json!({"path": format!("/abs/{key}")})
                }
                (_, Some("stat")) => match disk.borrow().get(&key) {
                    Some(b) => json!({"exists": true, "size": base64::engine::general_purpose::STANDARD.decode(b).unwrap().len(), "path": format!("/abs/{key}")}),
                    None => json!({"exists": false}),
                },
                (_, Some("get")) => match disk.borrow().get(&key) {
                    Some(b) => json!({"exists": true, "base64": b}),
                    None => json!({"exists": false}),
                },
                _ => json!({}),
            };
            Ok(Frame { type_: ty.into(), data: body.to_string(), session_id: None, request_id: None })
        };
        let b64 = base64::engine::general_purpose::STANDARD.encode(PNG);
        let up = upload(&json!({"projectId": "p1", "purpose": "chat", "mimeType": "image/png", "base64": b64}).to_string(), &ask, (MAX_BYTES, MAX_FILE_BYTES)).unwrap();
        assert_eq!(up["mimeType"], "image/png");
        assert!(up["relPath"].as_str().unwrap().starts_with(".infantry/uploads/chat/"));
        assert!(disk.borrow().contains_key("p1/uploads/.gitignore"));
        let id = format!("{}.png", up["sha256"].as_str().unwrap());
        assert_eq!(fetch(&json!({"id": id}).to_string(), &ask).unwrap()["base64"], b64);

        let before = disk.borrow().len();
        assert!(upload(&json!({"projectId": "p1", "purpose": "chat", "mimeType": "image/gif", "base64": b64}).to_string(), &ask, (MAX_BYTES, MAX_FILE_BYTES)).unwrap_err().contains("contradicts"));
        assert!(upload(&json!({"projectId": "p1", "purpose": "zip", "base64": "AA=="}).to_string(), &ask, (MAX_BYTES, MAX_FILE_BYTES)).unwrap_err().contains("not accepted"));
        assert!(upload(&json!({"projectId": "p1", "purpose": "chat", "base64": "AA==", "x": 1}).to_string(), &ask, (MAX_BYTES, MAX_FILE_BYTES)).unwrap_err().contains("unknown field"));
        assert_eq!(disk.borrow().len(), before);
        assert_eq!(fetch(&json!({"id": "../x"}).to_string(), &ask).unwrap_err(), "'../x' is not an uploaded image id");
        assert_eq!(file_extension("a/b.TXT"), "txt");
        // The limits are settings: a 1 MB file limit refuses 2 MB; images keep their own limit.
        let big = base64::engine::general_purpose::STANDARD.encode(vec![7u8; 2 * 1024 * 1024]);
        assert!(upload(&json!({"projectId": "p1", "purpose": "file", "fileName": "a.zip", "base64": big}).to_string(), &ask, (MAX_BYTES, 1024 * 1024)).unwrap_err().contains("over the 1 MB limit"));
        assert!(upload(&json!({"projectId": "p1", "purpose": "file", "fileName": "a.zip", "base64": big}).to_string(), &ask, (MAX_BYTES, MAX_FILE_BYTES)).is_ok());
        let set = |k: &str| match k { IMAGE_MB_KEY => Some("3".to_string()), FILE_MB_KEY => Some("0".to_string()), _ => None };
        assert_eq!(limits(set), (3 * 1024 * 1024, MAX_FILE_BYTES));
        assert_eq!(limits(|_| None), (MAX_BYTES, MAX_FILE_BYTES));
    }
}
