// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
//! SCRIPT-CHAT-1 / CHAT-IMAGE-1: `infantry_chat_note` and `infantry_chat_image` (script aliases `chat_note`, `chat_image`).
//! A note is one automation card in a session's chat: kept in the run log (a reload shows it again) and pushed to every
//! client as a `timeline_item`. `wake=true` also hands the session the text as a prompt (the daemon queues it when the
//! session is busy). An image is validated by its magic bytes, stored like a chat upload under
//! `<project>/.infantry/uploads/chat/<sha256>.<ext>` and posted as a note whose meta carries the stored path (and, when small,
//! the bytes as base64 so a remote client without the daemon's disk shows the same picture). Same project only, rate
//! limited, text capped. Every number is a setting (`scripting.chat.*`) with a named default.
use crate::host::{ToolInfo, ToolInvoker, ToolResult};
use crate::runlog::ScriptRunLog;
use base64::Engine;
use serde_json::{json, Map, Value};
use sha2::{Digest, Sha256};
use std::collections::{HashMap, VecDeque};
use std::path::{Path, PathBuf};
use std::sync::atomic::{AtomicU64, Ordering};
use std::sync::{Arc, Mutex};

pub const NOTE: &str = "infantry_chat_note";
pub const IMAGE: &str = "infantry_chat_image";
pub const NAMES: &[&str] = &[NOTE, IMAGE];

const LEVELS: [&str; 4] = ["info", "ok", "warn", "fail"];
/// `scripting.chat.maxChars`: the longest note text shown.
pub const DEFAULT_MAX_CHARS: usize = 400;
/// `scripting.chat.perMinute`: notes per minute per project.
pub const DEFAULT_PER_MINUTE: usize = 20;
/// `scripting.chat.inlineMaxBytes`: an image up to this size also travels inline (base64) in the card.
pub const DEFAULT_INLINE_MAX_BYTES: usize = 1_500_000;
/// `scripting.chat.imageMaxBytes`: the largest image accepted.
pub const DEFAULT_IMAGE_MAX_BYTES: usize = 10 * 1024 * 1024;
const WINDOW_MS: u64 = 60_000;
const UPLOADS_CHAT: &str = ".infantry/uploads/chat";

pub type ProjectOf = Arc<dyn Fn(&str) -> Option<String> + Send + Sync>;
pub type ProjectDir = Arc<dyn Fn(&str) -> Option<PathBuf> + Send + Sync>;
pub type Wake = Arc<dyn Fn(&str, &str) -> Result<(), String> + Send + Sync>;
pub type Config = Arc<dyn Fn(&str) -> Option<String> + Send + Sync>;

pub struct ChatTools {
    pub run_log: Arc<ScriptRunLog>,
    /// The project a session belongs to (`None` = unknown session).
    pub project_of: ProjectOf,
    pub project_dir: ProjectDir,
    /// Hands a session a prompt; the daemon queues it as the next turn when the session is busy.
    pub wake: Wake,
    pub config: Config,
    pub clock: Arc<dyn Fn() -> u64 + Send + Sync>,
    seq: AtomicU64,
    recent: Mutex<HashMap<String, VecDeque<u64>>>,
}

fn arg(a: &Map<String, Value>, k: &str) -> Option<String> {
    a.get(k).and_then(Value::as_str).map(str::trim).filter(|s| !s.is_empty()).map(str::to_string)
}

fn refuse(tool: &str, why: impl AsRef<str>) -> ToolResult {
    ToolResult::err(format!("{tool} refused: {}", why.as_ref()))
}

/// The (extension, mime) of `b` by its magic bytes: png, jpeg, gif or webp.
pub fn sniff(b: &[u8]) -> Option<(&'static str, &'static str)> {
    if b.starts_with(&[0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A]) {
        Some(("png", "image/png"))
    } else if b.starts_with(&[0xFF, 0xD8, 0xFF]) {
        Some(("jpg", "image/jpeg"))
    } else if b.starts_with(b"GIF87a") || b.starts_with(b"GIF89a") {
        Some(("gif", "image/gif"))
    } else if b.len() >= 12 && b.starts_with(b"RIFF") && &b[8..12] == b"WEBP" {
        Some(("webp", "image/webp"))
    } else {
        None
    }
}

impl ChatTools {
    pub fn new(run_log: Arc<ScriptRunLog>, project_of: ProjectOf, project_dir: ProjectDir, wake: Wake, config: Config, clock: Arc<dyn Fn() -> u64 + Send + Sync>) -> Self {
        ChatTools { run_log, project_of, project_dir, wake, config, clock, seq: AtomicU64::new(0), recent: Mutex::new(HashMap::new()) }
    }

    fn num(&self, key: &str, default: usize) -> usize {
        (self.config)(key).and_then(|v| v.trim().parse().ok()).filter(|n| *n > 0).unwrap_or(default)
    }

    pub fn catalog() -> Vec<ToolInfo> {
        let schema = |s: &str| serde_json::from_str::<Value>(s).unwrap_or(json!({"type": "object"}));
        vec![
            ToolInfo {
                name: NOTE.into(),
                description: format!(
                    "Post a status line into a session's chat as an automation card (cog icon, time, coloured by level), stored in its history. No turn, no inbox entry, no mail unless wake=true (then the agent also gets the text as a short prompt, as its next turn when it is busy: use it for real exceptions only). Same project only, rate limited, text capped at {DEFAULT_MAX_CHARS} characters. Script: ih.chat_note{{sessionId=..., text=..., level='ok'}}."
                ),
                input_schema: schema(r#"{"type":"object","properties":{"sessionId":{"type":"string","description":"target session (default: the caller's own)"},"text":{"type":"string","description":"the status line"},"level":{"type":"string","description":"info (default) | ok | warn (yellow WARNING, not an error) | fail"},"wake":{"type":"boolean","description":"also prompt the agent with the text (default false)"},"image":{"type":"string","description":"a file inside the project (or uploaded image id) shown inline with the line"}},"required":["text"]}"#),
                read_only: false,
            },
            ToolInfo {
                name: IMAGE.into(),
                description: "Show an image inline in a session's chat (desktop, web, Android), with a caption. path: a file inside the project directory or an uploaded image id. Only png/jpeg/gif/webp (checked by content), at most 10 MB. Stored under the project's uploads like chat images. Script: ih.chat_image{path=..., caption=..., sessionId=...}.".into(),
                input_schema: schema(r#"{"type":"object","properties":{"sessionId":{"type":"string","description":"target session (default: the caller's own)"},"path":{"type":"string","description":"a file inside the project, or an uploaded image id"},"caption":{"type":"string","description":"text shown with the image"},"level":{"type":"string","description":"info (default) | ok | warn | fail"}},"required":["path"]}"#),
                read_only: false,
            },
        ]
    }

    pub fn handle(&self, name: &str, a: &Map<String, Value>, inv: &ToolInvoker) -> Option<ToolResult> {
        match name {
            NOTE => Some(self.note(a, inv)),
            IMAGE => Some(self.image(a, inv)),
            _ => None,
        }
    }

    /// The target session and its project; refuses an unknown session or one of another project.
    fn target(&self, tool: &str, a: &Map<String, Value>, inv: &ToolInvoker) -> Result<(String, String), ToolResult> {
        let target = arg(a, "sessionId").or_else(|| inv.session_id.clone()).ok_or_else(|| refuse(tool, "needs sessionId"))?;
        let project = (self.project_of)(&target).ok_or_else(|| refuse(tool, format!("unknown session {target}")))?;
        let caller = inv.project_id.clone().or_else(|| inv.session_id.as_deref().and_then(|s| (self.project_of)(s))).ok_or_else(|| refuse(tool, "needs a project context"))?;
        if caller != project {
            return Err(refuse(tool, format!("session {target} is in another project")));
        }
        Ok((target, project))
    }

    fn note(&self, a: &Map<String, Value>, inv: &ToolInvoker) -> ToolResult {
        let Some(text) = arg(a, "text") else { return refuse(NOTE, "needs text") };
        let (target, project) = match self.target(NOTE, a, inv) {
            Ok(t) => t,
            Err(r) => return r,
        };
        if let Some(img) = arg(a, "image") {
            let mut fwd = Map::new();
            for (k, v) in a {
                if k != "image" && k != "sessionId" {
                    fwd.insert(k.clone(), v.clone());
                }
            }
            fwd.insert("sessionId".into(), json!(target));
            fwd.insert("path".into(), json!(img));
            fwd.insert("caption".into(), json!(text));
            return self.image(&fwd, inv);
        }
        let level = arg(a, "level").unwrap_or_else(|| "info".into()).to_lowercase();
        if !LEVELS.contains(&level.as_str()) {
            return refuse(NOTE, format!("level must be info, ok, warn or fail (got '{level}')"));
        }
        let wake = a.get("wake").and_then(Value::as_bool) == Some(true);
        self.post(&target, &project, &text, &level, wake, Map::new())
    }

    /// Rate-limits, builds the card, stores and pushes it, wakes when asked.
    fn post(&self, target: &str, project: &str, text: &str, level: &str, wake: bool, meta_extra: Map<String, Value>) -> ToolResult {
        let now = (self.clock)();
        {
            let per_minute = self.num("scripting.chat.perMinute", DEFAULT_PER_MINUTE);
            let mut recent = self.recent.lock().unwrap();
            let q = recent.entry(project.to_string()).or_default();
            while q.front().is_some_and(|t| now.saturating_sub(*t) > WINDOW_MS) {
                q.pop_front();
            }
            if q.len() >= per_minute {
                return refuse(NOTE, format!("rate limit: at most {per_minute} notes per minute per project"));
            }
            q.push_back(now);
        }
        let shown: String = text.chars().take(self.num("scripting.chat.maxChars", DEFAULT_MAX_CHARS)).collect();
        let suffix = match level {
            "warn" => " · WARNING",
            "fail" => " · FAILED",
            _ => "",
        };
        let mut meta = Map::new();
        meta.insert("level".into(), json!(level));
        meta.extend(meta_extra);
        let item = json!({
            "id": format!("note-{project}-{now}-{}", self.seq.fetch_add(1, Ordering::Relaxed) + 1), "sessionId": target, "at": now, "source": "automation",
            "label": "AUTOMATION · note", "text": format!("AUTOMATION · {shown}{suffix}"), "ok": level != "fail", "meta": Value::Object(meta),
        });
        self.run_log.post_note(item);
        if wake {
            if let Err(e) = (self.wake)(target, &shown) {
                return ToolResult::err(format!("noted in chat, but the wake failed: {e}"));
            }
        }
        ToolResult::ok(format!("noted in {target} ({level}{})", if wake { ", woken" } else { "" }))
    }

    fn image(&self, a: &Map<String, Value>, inv: &ToolInvoker) -> ToolResult {
        let Some(path) = arg(a, "path") else { return refuse(IMAGE, "needs path") };
        let (target, project) = match self.target(IMAGE, a, inv) {
            Ok(t) => t,
            Err(r) => return r,
        };
        let level = arg(a, "level").unwrap_or_else(|| "info".into()).to_lowercase();
        if !LEVELS.contains(&level.as_str()) {
            return refuse(IMAGE, format!("level must be info, ok, warn or fail (got '{level}')"));
        }
        let Some(dir) = (self.project_dir)(&project) else { return refuse(IMAGE, "the project has no directory") };
        let max = self.num("scripting.chat.imageMaxBytes", DEFAULT_IMAGE_MAX_BYTES);
        let bytes = match read_image(&dir, &path, max) {
            Ok(b) => b,
            Err(why) => return refuse(IMAGE, why),
        };
        let Some((ext, _)) = sniff(&bytes) else { return refuse(IMAGE, "not a png, jpeg, gif or webp image (its first bytes match none of them)") };
        let sha: String = Sha256::digest(&bytes).iter().map(|b| format!("{b:02x}")).collect();
        let uploads = dir.join(".infantry").join("uploads");
        let chat = uploads.join("chat");
        let stored = chat.join(format!("{sha}.{ext}"));
        let wrote = std::fs::create_dir_all(&chat)
            .and_then(|_| if uploads.join(".gitignore").exists() { Ok(()) } else { std::fs::write(uploads.join(".gitignore"), "*\n") })
            .and_then(|_| if stored.metadata().map(|m| m.len() == bytes.len() as u64).unwrap_or(false) { Ok(()) } else { std::fs::write(&stored, &bytes) });
        if let Err(e) = wrote {
            return refuse(IMAGE, format!("could not store the image: {e}"));
        }
        let caption = arg(a, "caption").unwrap_or_else(|| format!("{sha}.{ext}"));
        let mut meta = Map::new();
        meta.insert("image".into(), json!(stored.to_string_lossy().replace('\\', "/")));
        if bytes.len() <= self.num("scripting.chat.inlineMaxBytes", DEFAULT_INLINE_MAX_BYTES) {
            meta.insert("imageData".into(), json!(base64::engine::general_purpose::STANDARD.encode(&bytes)));
        }
        let r = self.post(&target, &project, &caption, &level, false, meta);
        if r.is_error {
            return ToolResult::err(r.text.replacen(NOTE, IMAGE, 1));
        }
        ToolResult::ok(format!("shown in {target} ({level}, {}.{ext}, {} bytes)", &sha[..12], bytes.len()))
    }
}

/// The bytes of `path` (a file inside the project dir, or an uploaded image id), after every check.
fn read_image(dir: &Path, path: &str, max: usize) -> Result<Vec<u8>, String> {
    let root = dir.canonicalize().map_err(|_| "the project has no directory".to_string())?;
    let file = if is_upload_id(path) {
        let stem = path.to_lowercase();
        let stem = stem.split('.').next().unwrap_or("").to_string();
        ["chat", "ticket"]
            .iter()
            .filter_map(|p| std::fs::read_dir(root.join(".infantry").join("uploads").join(p)).ok())
            .flatten()
            .flatten()
            .map(|e| e.path())
            .find(|p| p.file_name().and_then(|n| n.to_str()).map(|n| n.split('.').next() == Some(stem.as_str())).unwrap_or(false))
            .ok_or_else(|| format!("no uploaded image '{path}' in this project"))?
    } else {
        let p = Path::new(path);
        let joined = if p.is_absolute() { p.to_path_buf() } else { root.join(p) };
        let f = joined.canonicalize().map_err(|_| format!("no such file in the project: {}", path.chars().take(120).collect::<String>()))?;
        if !f.starts_with(&root) {
            return Err("path is outside the project directory".into());
        }
        f
    };
    let meta = file.metadata().map_err(|_| format!("no such file in the project: {}", path.chars().take(120).collect::<String>()))?;
    if !meta.is_file() {
        return Err(format!("no such file in the project: {}", path.chars().take(120).collect::<String>()));
    }
    if meta.len() as usize > max {
        return Err(format!("the file is over the {} MB limit", max / (1024 * 1024)));
    }
    std::fs::read(&file).map_err(|e| format!("could not read the file: {e}"))
}

/// A sha256 hex id with an optional short extension.
fn is_upload_id(s: &str) -> bool {
    let (h, ext) = match s.split_once('.') {
        Some((h, e)) => (h, Some(e)),
        None => (s, None),
    };
    h.len() == 64 && h.chars().all(|c| c.is_ascii_hexdigit()) && ext.map(|e| (1..=5).contains(&e.len()) && e.chars().all(|c| c.is_ascii_alphanumeric())).unwrap_or(true)
}

#[cfg(test)]
mod tests {
    use super::*;

    const PNG: [u8; 12] = [0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 0, 0, 0, 1];

    fn tools(dir: Option<PathBuf>, woken: Arc<Mutex<Vec<(String, String)>>>) -> (ChatTools, Arc<Mutex<Vec<Value>>>) {
        let log = Arc::new(ScriptRunLog::new(Arc::new(|| 50), Arc::new(|| 1_000), None));
        let pushed = Arc::new(Mutex::new(Vec::new()));
        let p = pushed.clone();
        log.set_on_stats(Arc::new(move |v: &Value| p.lock().unwrap().push(v.clone())));
        let t = ChatTools::new(
            log,
            Arc::new(|s: &str| match s {
                "s1" | "s1b" => Some("p1".to_string()),
                "s2" => Some("p2".to_string()),
                _ => None,
            }),
            Arc::new(move |_: &str| dir.clone()),
            Arc::new(move |s: &str, t: &str| {
                woken.lock().unwrap().push((s.into(), t.into()));
                Ok(())
            }),
            Arc::new(|_: &str| None),
            Arc::new(|| 1_000),
        );
        (t, pushed)
    }

    fn args(v: Value) -> Map<String, Value> {
        v.as_object().unwrap().clone()
    }

    fn pm() -> ToolInvoker {
        ToolInvoker::automation(Some("p1"), "script:t")
    }

    #[test]
    fn a_note_is_pushed_stored_and_wakes_only_when_asked() {
        let woken = Arc::new(Mutex::new(Vec::new()));
        let (t, pushed) = tools(None, woken.clone());
        let r = t.handle(NOTE, &args(json!({"sessionId": "s1", "text": "build ok", "level": "ok"})), &pm()).unwrap();
        assert!(!r.is_error, "{}", r.text);
        assert_eq!(r.text, "noted in s1 (ok)");
        let item = pushed.lock().unwrap()[0].clone();
        assert_eq!(item["sessionId"], "s1");
        assert_eq!(item["text"], "AUTOMATION · build ok");
        assert_eq!(item["source"], "automation");
        assert!(woken.lock().unwrap().is_empty());
        assert!(t.run_log.timeline_items(10).iter().any(|i| i["text"] == "AUTOMATION · build ok"), "kept for a reload");
        let r = t.handle(NOTE, &args(json!({"sessionId": "s1", "text": "disk full", "level": "warn", "wake": true})), &pm()).unwrap();
        assert_eq!(r.text, "noted in s1 (warn, woken)");
        assert_eq!(woken.lock().unwrap()[0], ("s1".to_string(), "disk full".to_string()));
        assert_eq!(pushed.lock().unwrap()[1]["text"], "AUTOMATION · disk full · WARNING");
    }

    #[test]
    fn a_note_refuses_bad_input() {
        let (t, _) = tools(None, Default::default());
        let call = |v: Value| t.handle(NOTE, &args(v), &pm()).unwrap();
        assert!(call(json!({"sessionId": "s1"})).text.contains("needs text"));
        assert!(call(json!({"sessionId": "nope", "text": "x"})).text.contains("unknown session"));
        assert!(call(json!({"sessionId": "s2", "text": "x"})).text.contains("another project"));
        assert!(call(json!({"sessionId": "s1", "text": "x", "level": "loud"})).text.contains("level must be"));
        for _ in 0..DEFAULT_PER_MINUTE {
            assert!(!call(json!({"sessionId": "s1", "text": "x"})).is_error);
        }
        assert!(call(json!({"sessionId": "s1", "text": "x"})).text.contains("rate limit"));
    }

    #[test]
    fn an_image_is_validated_stored_and_posted_with_its_bytes() {
        let tmp = tempfile::tempdir().unwrap();
        std::fs::write(tmp.path().join("shot.png"), PNG).unwrap();
        std::fs::write(tmp.path().join("notes.txt"), b"hello").unwrap();
        let (t, pushed) = tools(Some(tmp.path().to_path_buf()), Default::default());
        let r = t.handle(IMAGE, &args(json!({"sessionId": "s1", "path": "shot.png", "caption": "the screen"})), &pm()).unwrap();
        assert!(!r.is_error, "{}", r.text);
        let item = pushed.lock().unwrap()[0].clone();
        assert_eq!(item["text"], "AUTOMATION · the screen");
        let stored = item["meta"]["image"].as_str().unwrap();
        assert!(stored.contains("/.infantry/uploads/chat/") && stored.ends_with(".png"), "{stored}");
        assert!(Path::new(stored).is_file());
        assert_eq!(item["meta"]["imageData"], base64::engine::general_purpose::STANDARD.encode(PNG));
        assert!(tmp.path().join(".infantry/uploads/.gitignore").is_file());
        // the same bytes by upload id, and as the `image` of a note
        let id = Path::new(stored).file_name().unwrap().to_str().unwrap().to_string();
        assert!(!t.handle(IMAGE, &args(json!({"sessionId": "s1", "path": id})), &pm()).unwrap().is_error);
        let r = t.handle(NOTE, &args(json!({"sessionId": "s1", "text": "look", "image": "shot.png"})), &pm()).unwrap();
        assert!(!r.is_error, "{}", r.text);
        // refusals
        let bad = |p: &str| t.handle(IMAGE, &args(json!({"sessionId": "s1", "path": p})), &pm()).unwrap();
        assert!(bad("notes.txt").text.contains("not a png"));
        assert!(bad("../outside.png").text.contains("no such file") || bad("../outside.png").text.contains("outside"));
        assert!(bad("missing.png").text.contains("no such file"));
    }
}
