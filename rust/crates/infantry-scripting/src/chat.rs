// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
//! SCRIPT-CHAT-1 / CHAT-IMAGE-1: `infantry_chat_note` and `infantry_chat_image` (script aliases `chat_note`, `chat_image`).
//! A note is one automation card in a session's chat: kept in the run log (a reload shows it again) and pushed to every
//! client as a `timeline_item`. `wake=true` also hands the session the text as a prompt (the daemon queues it when the
//! session is busy). An image is validated by its magic bytes, stored like a chat upload under
//! `<project>/.infantry/uploads/chat/<sha256>.<ext>` and posted as a note whose meta carries the stored path (and, when small,
//! the bytes as base64 so a remote client without the daemon's disk shows the same picture). Same project only, rate
//! limited, text capped. Every number is a setting (`scripting.chat.*`) with a named default.
//!
//! CHAT-IMG-2: ONE image capability for every caller (MCP tool, Lua `ih.chat_image`, and plugins through the daemon's
//! `chat_image` frame, which lands here too). The source is exactly one of `path` (a file: inside the project, or
//! outside it unless `scripting.chat.imageProjectOnly` is on; secret places are always refused, see [`secret_place`]),
//! `data` (base64 bytes) or `url` (an https image the daemon fetches through [`Fetch`]). `sessionId: "starter"` names the
//! session that started the caller (a ticket worker's PM).
//!
//! CHAT-IMG-4: a live session with no project (the owner's starter chat, a project-less agent) is a valid target too.
//! The caller may post to it when it is the caller itself, the caller's starter, or the caller has owner rights; its
//! images are stored under the daemon state's uploads (`with_projectless`), never in a project. A relative `path`
//! needs a project; an absolute one keeps every check (secret places, the daemon's state, `imageProjectOnly`).
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
/// Fetches an https URL, at most `max` bytes (more = an error).
pub type Fetch = Arc<dyn Fn(&str, usize) -> Result<Vec<u8>, String> + Send + Sync>;
/// `scripting.chat.imageProjectOnly`: `true` refuses every file outside the project (default `false`).
pub const PROJECT_ONLY_KEY: &str = "scripting.chat.imageProjectOnly";

pub struct ChatTools {
    pub run_log: Arc<ScriptRunLog>,
    /// The project a session belongs to (`None` = unknown session).
    pub project_of: ProjectOf,
    pub project_dir: ProjectDir,
    /// Hands a session a prompt; the daemon queues it as the next turn when the session is busy.
    pub wake: Wake,
    pub config: Config,
    pub clock: Arc<dyn Fn() -> u64 + Send + Sync>,
    /// The session that started a session (`sessionId: "starter"`).
    starter_of: Option<ProjectOf>,
    fetch: Option<Fetch>,
    /// Extra places no image is read from (the daemon's own state and secret dirs).
    denied: Vec<PathBuf>,
    /// A live session that exists but has no project (CHAT-IMG-4). None = only project sessions are targets.
    known: Option<Arc<dyn Fn(&str) -> bool + Send + Sync>>,
    /// Where a project-less session's images are stored (the daemon state's `uploads` dir).
    state_uploads: Option<PathBuf>,
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
        ChatTools { run_log, project_of, project_dir, wake, config, clock, starter_of: None, fetch: None, denied: Vec::new(), known: None, state_uploads: None, seq: AtomicU64::new(0), recent: Mutex::new(HashMap::new()) }
    }

    pub fn with_starter_of(mut self, f: ProjectOf) -> Self {
        self.starter_of = Some(f);
        self
    }
    pub fn with_fetch(mut self, f: Fetch) -> Self {
        self.fetch = Some(f);
        self
    }
    /// CHAT-IMG-4: `known(id)` is true for a live session with no project; its images go to `uploads` (the daemon's
    /// state uploads dir, e.g. `<state>/uploads`).
    pub fn with_projectless(mut self, known: Arc<dyn Fn(&str) -> bool + Send + Sync>, uploads: PathBuf) -> Self {
        self.known = Some(known);
        self.state_uploads = Some(uploads);
        self
    }
    pub fn with_denied(mut self, dirs: Vec<PathBuf>) -> Self {
        self.denied = dirs.into_iter().map(|d| d.canonicalize().unwrap_or(d)).collect();
        self
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
                    "Post a status line into a session's chat as an automation card (cog icon, time, coloured by level), stored in its history. No turn, no inbox entry, no mail unless wake=true (then the agent also gets the text as a short prompt, as its next turn when it is busy: use it for real exceptions only). Targets: a session of your project, or a session with no project when it is you, your starter (sessionId \"starter\") or you are the owner. Rate limited, text capped at {DEFAULT_MAX_CHARS} characters. Script: ih.chat_note{{sessionId=..., text=..., level='ok'}}."
                ),
                input_schema: schema(r#"{"type":"object","properties":{"sessionId":{"type":"string","description":"target session (default: the caller's own)"},"text":{"type":"string","description":"the status line"},"level":{"type":"string","description":"info (default) | ok | warn (yellow WARNING, not an error) | fail"},"wake":{"type":"boolean","description":"also prompt the agent with the text (default false)"},"image":{"type":"string","description":"an image shown inline with the line: a file (absolute, or relative to the project), or an uploaded image id; same checks as infantry_chat_image"}},"required":["text"]}"#),
                read_only: false,
            },
            ToolInfo {
                name: IMAGE.into(),
                description: "Show an image inline in a session's chat (desktop, web, Android), with a caption. Give ONE of: path (a file in or outside the project, e.g. your screenshot folder; secret places like .ssh, key stores, browser profiles and the daemon's state are refused; or an uploaded image id), data (base64 bytes) or url (an https image the daemon fetches). Only png/jpeg/gif/webp (checked by content), at most 10 MB. Stored under the project's uploads (a session with no project: the daemon state's uploads). sessionId: default your own session; \"starter\" = the session that started you (a ticket worker shows its screenshots to its PM this way); a session with no project (the owner's starter chat) takes images from itself, the sessions it started, or the owner. After taking screenshots, show the key ones to your starter with a one-line caption. Script: ih.chat_image{path=..., caption=..., sessionId=...}. Plugins: the chat_image frame, same fields.".into(),
                input_schema: schema(r#"{"type":"object","properties":{"sessionId":{"type":"string","description":"target session (default: the caller's own)"},"path":{"type":"string","description":"a file (absolute, or relative to the project), or an uploaded image id"},"data":{"type":"string","description":"the image bytes, base64"},"url":{"type":"string","description":"an https image URL the daemon fetches"},"caption":{"type":"string","description":"text shown with the image"},"level":{"type":"string","description":"info (default) | ok | warn | fail"}}}"#),
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

    /// The target session and its project (`None` = a project-less session); refuses an unknown session, one of
    /// another project, or a project-less one the caller may not post to.
    fn target(&self, tool: &str, a: &Map<String, Value>, inv: &ToolInvoker) -> Result<(String, Option<String>), ToolResult> {
        let mut target = arg(a, "sessionId").or_else(|| inv.session_id.clone()).ok_or_else(|| refuse(tool, "needs sessionId"))?;
        let starter = inv.session_id.as_deref().and_then(|me| self.starter_of.as_ref().and_then(|f| f(me)));
        if target.eq_ignore_ascii_case("starter") {
            let me = inv.session_id.clone().ok_or_else(|| refuse(tool, "sessionId \"starter\" needs a calling session"))?;
            target = starter.clone().ok_or_else(|| refuse(tool, format!("session {me} has no starter")))?;
        }
        let caller = inv.project_id.clone().or_else(|| inv.session_id.as_deref().and_then(|s| (self.project_of)(s)));
        match (self.project_of)(&target) {
            Some(project) => {
                let caller = caller.ok_or_else(|| refuse(tool, "needs a project context"))?;
                if caller != project {
                    return Err(refuse(tool, format!("session {target} is in another project")));
                }
                Ok((target, Some(project)))
            }
            None if self.known.as_ref().is_some_and(|k| k(&target)) => {
                let mine = inv.session_id.as_deref() == Some(target.as_str()) || starter.as_deref() == Some(target.as_str());
                if !(mine || inv.owner) {
                    return Err(refuse(tool, format!("session {target} has no project; only itself, a session it started, or the owner may post to it")));
                }
                Ok((target, None))
            }
            None => Err(refuse(tool, format!("unknown session {target}"))),
        }
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
        self.post(&target, project.as_deref(), &text, &level, wake, Map::new())
    }

    /// Rate-limits, builds the card, stores and pushes it, wakes when asked.
    fn post(&self, target: &str, project: Option<&str>, text: &str, level: &str, wake: bool, meta_extra: Map<String, Value>) -> ToolResult {
        let now = (self.clock)();
        {
            let per_minute = self.num("scripting.chat.perMinute", DEFAULT_PER_MINUTE);
            let mut recent = self.recent.lock().unwrap();
            let key = project.map(str::to_string).unwrap_or_else(|| format!("session:{target}"));
            let q = recent.entry(key).or_default();
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
            "id": format!("note-{}-{now}-{}", project.unwrap_or(target), self.seq.fetch_add(1, Ordering::Relaxed) + 1), "sessionId": target, "at": now, "source": "automation",
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
        let (path, data, url) = (arg(a, "path"), arg(a, "data"), arg(a, "url"));
        if [&path, &data, &url].iter().filter(|o| o.is_some()).count() != 1 {
            return refuse(IMAGE, "needs exactly one of path, data or url");
        }
        let (target, project) = match self.target(IMAGE, a, inv) {
            Ok(t) => t,
            Err(r) => return r,
        };
        let level = arg(a, "level").unwrap_or_else(|| "info".into()).to_lowercase();
        if !LEVELS.contains(&level.as_str()) {
            return refuse(IMAGE, format!("level must be info, ok, warn or fail (got '{level}')"));
        }
        // Where relative paths and upload ids resolve, and where the image is stored: the project's `.infantry/uploads`,
        // or the daemon state's uploads for a project-less session.
        let (dir, uploads) = match &project {
            Some(p) => match (self.project_dir)(p) {
                Some(d) => {
                    let u = d.join(".infantry").join("uploads");
                    (Some(d), u)
                }
                None => return refuse(IMAGE, "the project has no directory"),
            },
            None => match &self.state_uploads {
                Some(u) => (None, u.clone()),
                None => return refuse(IMAGE, "this daemon has no uploads dir for a session without a project"),
            },
        };
        let max = self.num("scripting.chat.imageMaxBytes", DEFAULT_IMAGE_MAX_BYTES);
        let got = if let Some(path) = path {
            let project_only = (self.config)(PROJECT_ONLY_KEY).map(|v| v.trim().eq_ignore_ascii_case("true")).unwrap_or(false);
            read_image(dir.as_deref(), &uploads, &path, max, project_only, &self.denied)
        } else if let Some(d) = data {
            base64::engine::general_purpose::STANDARD
                .decode(d.trim())
                .map_err(|_| "data is not base64".to_string())
                .and_then(|b| if b.len() > max { Err(format!("the image is over the {} MB limit", max / (1024 * 1024))) } else { Ok(b) })
        } else {
            let url = url.unwrap_or_default();
            if !url.to_ascii_lowercase().starts_with("https://") {
                return refuse(IMAGE, "url must be https");
            }
            match &self.fetch {
                Some(f) => f(&url, max).map_err(|e| format!("could not fetch the image: {e}")),
                None => Err("this daemon cannot fetch URLs".into()),
            }
        };
        let bytes = match got {
            Ok(b) => b,
            Err(why) => return refuse(IMAGE, why),
        };
        let Some((ext, _)) = sniff(&bytes) else { return refuse(IMAGE, "not a png, jpeg, gif or webp image (its first bytes match none of them)") };
        let sha: String = Sha256::digest(&bytes).iter().map(|b| format!("{b:02x}")).collect();
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
        let r = self.post(&target, project.as_deref(), &caption, &level, false, meta);
        if r.is_error {
            return ToolResult::err(r.text.replacen(NOTE, IMAGE, 1));
        }
        ToolResult::ok(format!("shown in {target} ({level}, {}.{ext}, {} bytes)", &sha[..12], bytes.len()))
    }
}

/// Places no chat image is ever read from, whatever the setting: ssh and gpg keys, cloud credentials, the daemon's key
/// dir, key stores and browser profiles. `p` is checked as given and canonical (so a symlink into one is refused too).
pub fn secret_place(p: &Path) -> bool {
    let s = p.to_string_lossy().replace('\\', "/").to_lowercase();
    let s = s.trim_start_matches("//?/");
    const DIRS: [&str; 8] = [".ssh", ".gnupg", ".infantry-keys", ".aws", ".azure", ".kube", ".docker", ".password-store"];
    const PROFILES: [&str; 8] = [
        "google/chrome/user data", "microsoft/edge/user data", "bravesoftware", "mozilla/firefox", "/.mozilla/",
        "application support/google/chrome", "/.config/google-chrome", "/.config/chromium",
    ];
    const KEY_EXT: [&str; 7] = ["jks", "keystore", "p12", "pfx", "pem", "key", "kdbx"];
    s.split('/').any(|c| DIRS.contains(&c))
        || PROFILES.iter().any(|x| s.contains(x))
        || p.extension().and_then(|e| e.to_str()).map(|e| KEY_EXT.contains(&e.to_lowercase().as_str())).unwrap_or(false)
}

/// The bytes of `path` (a file in or outside the project dir, or an uploaded image id in `uploads`), after every check.
/// `dir` is the project dir; `None` (a project-less session) takes only absolute paths, and with `project_only` none.
fn read_image(dir: Option<&Path>, uploads: &Path, path: &str, max: usize, project_only: bool, denied: &[PathBuf]) -> Result<Vec<u8>, String> {
    let root = match dir {
        Some(d) => Some(d.canonicalize().map_err(|_| "the project has no directory".to_string())?),
        None => None,
    };
    let file = if is_upload_id(path) {
        let stem = path.to_lowercase();
        let stem = stem.split('.').next().unwrap_or("").to_string();
        ["chat", "ticket"]
            .iter()
            .filter_map(|p| std::fs::read_dir(uploads.join(p)).ok())
            .flatten()
            .flatten()
            .map(|e| e.path())
            .find(|p| p.file_name().and_then(|n| n.to_str()).map(|n| n.split('.').next() == Some(stem.as_str())).unwrap_or(false))
            .ok_or_else(|| format!("no uploaded image '{path}' here"))?
    } else {
        let p = Path::new(path);
        let joined = match (&root, p.is_absolute()) {
            (_, true) => p.to_path_buf(),
            (Some(r), false) => r.join(p),
            (None, false) => return Err("this session has no project: give an absolute path, data or url".into()),
        };
        let f = joined.canonicalize().map_err(|_| format!("no such file: {}", path.chars().take(120).collect::<String>()))?;
        if !root.as_ref().is_some_and(|r| f.starts_with(r)) {
            if project_only {
                return Err(format!("path is outside the project directory ({PROJECT_ONLY_KEY} is on)"));
            }
            if secret_place(&joined) || secret_place(&f) || denied.iter().any(|d| f.starts_with(d) || joined.starts_with(d)) {
                return Err("that place holds secrets; images are not read from it".into());
            }
        }
        f
    };
    let meta = file.metadata().map_err(|_| format!("no such file: {}", path.chars().take(120).collect::<String>()))?;
    if !meta.is_file() {
        return Err(format!("no such file: {}", path.chars().take(120).collect::<String>()));
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

    #[test]
    fn chat_img_2_sources_places_and_limits() {
        let proj = tempfile::tempdir().unwrap();
        let out = tempfile::tempdir().unwrap();
        let state = tempfile::tempdir().unwrap();
        let shot = out.path().join("1-desktop.png");
        std::fs::write(&shot, PNG).unwrap();
        std::fs::write(out.path().join("notes.txt"), b"hello").unwrap();
        std::fs::create_dir_all(out.path().join(".ssh")).unwrap();
        std::fs::write(out.path().join(".ssh/id.png"), PNG).unwrap();
        std::fs::write(state.path().join("x.png"), PNG).unwrap();
        let big = out.path().join("big.png");
        let mut b = PNG.to_vec();
        b.resize(DEFAULT_IMAGE_MAX_BYTES + 1, 0);
        std::fs::write(&big, &b).unwrap();
        let (t, pushed) = tools(Some(proj.path().to_path_buf()), Default::default());
        let t = t
            .with_denied(vec![state.path().to_path_buf()])
            .with_starter_of(Arc::new(|s: &str| (s == "s1").then(|| "s1b".to_string())))
            .with_fetch(Arc::new(|u: &str, _| if u.ends_with(".png") { Ok(PNG.to_vec()) } else { Ok(b"<html>".to_vec()) }));
        let call = |v: Value| t.handle(IMAGE, &args(v), &pm()).unwrap();
        let p = |x: &Path| x.to_string_lossy().to_string();
        // outside the project: allowed, stored in the project's uploads
        let r = call(json!({"sessionId": "s1", "path": p(&shot)}));
        assert!(!r.is_error, "{}", r.text);
        let stored = pushed.lock().unwrap()[0]["meta"]["image"].as_str().unwrap().to_string();
        assert!(Path::new(&stored).starts_with(proj.path().canonicalize().unwrap()) || stored.contains("/.infantry/uploads/chat/"), "{stored}");
        // secret places, the daemon's state, non-images, size
        assert!(call(json!({"sessionId": "s1", "path": p(&out.path().join(".ssh/id.png"))})).text.contains("secrets"));
        assert!(call(json!({"sessionId": "s1", "path": p(&state.path().join("x.png"))})).text.contains("secrets"));
        assert!(call(json!({"sessionId": "s1", "path": p(&out.path().join("notes.txt"))})).text.contains("not a png"));
        assert!(call(json!({"sessionId": "s1", "path": p(&big)})).text.contains("10 MB"));
        // bytes and url
        let enc = base64::engine::general_purpose::STANDARD.encode(PNG);
        assert!(!call(json!({"sessionId": "s1", "data": enc})).is_error);
        assert!(call(json!({"sessionId": "s1", "data": "!!"})).text.contains("base64"));
        assert!(!call(json!({"sessionId": "s1", "url": "https://x.test/a.png"})).is_error);
        assert!(call(json!({"sessionId": "s1", "url": "https://x.test/a.html"})).text.contains("not a png"));
        assert!(call(json!({"sessionId": "s1", "url": "http://x.test/a.png"})).text.contains("https"));
        assert!(call(json!({"sessionId": "s1", "path": "a", "data": "b"})).text.contains("exactly one"));
        // "starter" resolves the caller's starter
        let worker = ToolInvoker { session_id: Some("s1".into()), ..pm() };
        let r = t.handle(IMAGE, &args(json!({"sessionId": "starter", "data": base64::engine::general_purpose::STANDARD.encode(PNG)})), &worker).unwrap();
        assert!(r.text.contains("shown in s1b"), "{}", r.text);
        assert_eq!(pushed.lock().unwrap().last().unwrap()["sessionId"], "s1b");
    }

    #[test]
    fn chat_img_2_project_only_setting_refuses_outside_files() {
        let proj = tempfile::tempdir().unwrap();
        let out = tempfile::tempdir().unwrap();
        std::fs::write(out.path().join("a.png"), PNG).unwrap();
        let log = Arc::new(ScriptRunLog::new(Arc::new(|| 50), Arc::new(|| 1_000), None));
        let d = proj.path().to_path_buf();
        let t = ChatTools::new(
            log,
            Arc::new(|_: &str| Some("p1".to_string())),
            Arc::new(move |_: &str| Some(d.clone())),
            Arc::new(|_: &str, _: &str| Ok(())),
            Arc::new(|k: &str| (k == PROJECT_ONLY_KEY).then(|| "true".to_string())),
            Arc::new(|| 1_000),
        );
        let r = t.handle(IMAGE, &args(json!({"sessionId": "s1", "path": out.path().join("a.png").to_string_lossy()})), &pm()).unwrap();
        assert!(r.text.contains("outside the project"), "{}", r.text);
    }

    #[test]
    fn chat_img_4_projectless_sessions_take_notes_and_images() {
        let proj = tempfile::tempdir().unwrap();
        let state = tempfile::tempdir().unwrap();
        let out = tempfile::tempdir().unwrap();
        let shot = out.path().join("shot.png");
        std::fs::write(&shot, PNG).unwrap();
        std::fs::write(state.path().join("x.png"), PNG).unwrap();
        let uploads = state.path().join("uploads");
        let (t, pushed) = tools(Some(proj.path().to_path_buf()), Default::default());
        // "lead" and "other" are live sessions without a project; s1 (project p1) was started by "lead".
        let t = t
            .with_denied(vec![state.path().to_path_buf()])
            .with_starter_of(Arc::new(|s: &str| matches!(s, "s1" | "lead2").then(|| "lead".to_string())))
            .with_projectless(Arc::new(|s: &str| matches!(s, "lead" | "other" | "lead2")), uploads.clone());
        let as_s = |sid: &str| ToolInvoker { session_id: Some(sid.into()), project_id: None, owner: false, ..pm() };
        let png64 = base64::engine::general_purpose::STANDARD.encode(PNG);
        // a project worker shows its screenshot to its project-less starter
        let r = t.handle(IMAGE, &args(json!({"sessionId": "starter", "path": shot.to_string_lossy()})), &as_s("s1")).unwrap();
        assert!(r.text.contains("shown in lead"), "{}", r.text);
        let stored = pushed.lock().unwrap().last().unwrap()["meta"]["image"].as_str().unwrap().to_string();
        assert!(Path::new(&stored).is_file() && Path::new(&stored).canonicalize().unwrap().starts_with(uploads.canonicalize().unwrap()), "{stored}");
        assert!(!proj.path().join(".infantry").exists(), "nothing stored in a project");
        assert!(uploads.join(".gitignore").is_file());
        // a project-less session posts to itself (note and image, by data and by upload id)
        assert!(!t.handle(NOTE, &args(json!({"text": "hello"})), &as_s("lead")).unwrap().is_error);
        assert!(!t.handle(IMAGE, &args(json!({"data": png64})), &as_s("lead")).unwrap().is_error);
        let id = Path::new(&stored).file_name().unwrap().to_str().unwrap().to_string();
        assert!(!t.handle(IMAGE, &args(json!({"path": id})), &as_s("lead")).unwrap().is_error);
        // a project-less worker shows to its project-less starter
        assert!(t.handle(NOTE, &args(json!({"sessionId": "starter", "text": "x"})), &as_s("lead2")).unwrap().text.contains("noted in lead"));
        // the owner may post to any project-less session
        assert!(!t.handle(NOTE, &args(json!({"sessionId": "other", "text": "x"})), &ToolInvoker { owner: true, ..pm() }).unwrap().is_error);
        // refusals: a stranger, a relative path, the daemon's state, an unknown session
        assert!(t.handle(NOTE, &args(json!({"sessionId": "other", "text": "x"})), &as_s("s1")).unwrap().text.contains("has no project"));
        assert!(t.handle(NOTE, &args(json!({"sessionId": "other", "text": "x"})), &ToolInvoker { owner: false, agent: true, ..pm() }).unwrap().text.contains("has no project"));
        assert!(t.handle(IMAGE, &args(json!({"path": "shot.png"})), &as_s("lead")).unwrap().text.contains("absolute path"));
        assert!(t.handle(IMAGE, &args(json!({"path": state.path().join("x.png").to_string_lossy()})), &as_s("lead")).unwrap().text.contains("secrets"));
        assert!(t.handle(NOTE, &args(json!({"sessionId": "ghost", "text": "x"})), &as_s("lead")).unwrap().text.contains("unknown session ghost"));
    }

    #[test]
    fn chat_img_4_project_only_refuses_projectless_files() {
        let state = tempfile::tempdir().unwrap();
        let out = tempfile::tempdir().unwrap();
        std::fs::write(out.path().join("a.png"), PNG).unwrap();
        let log = Arc::new(ScriptRunLog::new(Arc::new(|| 50), Arc::new(|| 1_000), None));
        let t = ChatTools::new(log, Arc::new(|_: &str| None), Arc::new(|_: &str| None), Arc::new(|_: &str, _: &str| Ok(())), Arc::new(|k: &str| (k == PROJECT_ONLY_KEY).then(|| "true".to_string())), Arc::new(|| 1_000))
            .with_projectless(Arc::new(|s: &str| s == "lead"), state.path().join("uploads"));
        let inv = ToolInvoker { session_id: Some("lead".into()), ..Default::default() };
        let r = t.handle(IMAGE, &args(json!({"path": out.path().join("a.png").to_string_lossy()})), &inv).unwrap();
        assert!(r.text.contains("outside the project"), "{}", r.text);
        let r = t.handle(IMAGE, &args(json!({"data": base64::engine::general_purpose::STANDARD.encode(PNG)})), &inv).unwrap();
        assert!(!r.is_error, "{}", r.text);
    }

    #[test]
    fn secret_places_are_named() {
        for p in ["C:/Users/a/.ssh/x.png", "/home/a/.infantry-keys/k.png", "C:/Users/a/AppData/Local/Google/Chrome/User Data/x.png", "/x/store.jks", "/home/a/.mozilla/x.png"] {
            assert!(secret_place(Path::new(p)), "{p}");
        }
        assert!(!secret_place(Path::new("C:/Users/a/bos-andui-results/1-desktop.png")));
    }
}
