// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
//! Device notifications with sound (`notify_push`): a queue every device drains (desktop, Android, web), acked per
//! device, with built-in synthesised sounds (chime, bell, alarm) and the owner's own `.wav/.mp3/.ogg` files, quiet
//! hours, and milestone tickets that fire once when they are done. State is JSON in the plugin's state dir:
//! `pending.json`, `delivered.json`, `milestones.json`, `sounds/`.
use std::collections::{BTreeMap, HashMap, HashSet};
use std::f64::consts::PI;
use std::fs;
use std::path::{Path, PathBuf};
use std::sync::{Arc, Mutex, OnceLock};

use base64::Engine;
use serde::{Deserialize, Serialize};

use super::hub::parse_quiet_hours;

pub const NOTIFY_PUSH: &str = "notify_push";
pub const NOTIFY_ACK: &str = "notify_ack";
pub const NOTIFY_PENDING: &str = "notify_pending";
pub const NOTIFY_PENDING_LIST: &str = "notify_pending_list";
pub const NOTIFY_SOUNDS: &str = "notify_sounds";
pub const NOTIFY_SOUNDS_LIST: &str = "notify_sounds_list";
pub const NOTIFY_SOUND_GET: &str = "notify_sound_get";
pub const NOTIFY_SOUND: &str = "notify_sound";

pub const LEVELS: [&str; 3] = ["info", "milestone", "urgent"];
pub const TARGETS: [&str; 4] = ["all", "desktop", "android", "web"];
pub const MAX_TITLE: usize = 200;
pub const MAX_BODY: usize = 2000;
pub const NONE: &str = "none";
pub const BUILTIN: [&str; 3] = ["chime", "bell", "alarm"];
const EXTENSIONS: [&str; 3] = ["wav", "mp3", "ogg"];
const RATE: u32 = 22_050;

#[derive(Debug, Clone, PartialEq, Default, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct NotifyRequest {
    pub title: String,
    #[serde(default)]
    pub body: String,
    #[serde(default)]
    pub level: Option<String>,
    #[serde(default)]
    pub sound: Option<String>,
    #[serde(default)]
    pub target: Option<String>,
    #[serde(default)]
    pub ticket_id: Option<String>,
    #[serde(default)]
    pub url: Option<String>,
}

impl NotifyRequest {
    /// Trims and checks; answers the normalised request.
    pub fn validated(&self) -> Result<NotifyRequest, String> {
        let title = self.title.trim();
        if title.is_empty() {
            return Err("title is required".into());
        }
        if title.chars().count() > MAX_TITLE {
            return Err(format!("title is longer than {MAX_TITLE} characters"));
        }
        let body = self.body.trim();
        if body.chars().count() > MAX_BODY {
            return Err(format!("body is longer than {MAX_BODY} characters"));
        }
        let level = self.level.as_deref().map(str::trim).filter(|s| !s.is_empty()).unwrap_or("info").to_lowercase();
        if !LEVELS.contains(&level.as_str()) {
            return Err(format!("level must be one of {}", LEVELS.join(", ")));
        }
        let target = self.target.as_deref().map(str::trim).filter(|s| !s.is_empty()).unwrap_or("all").to_lowercase();
        if !TARGETS.contains(&target.as_str()) {
            return Err(format!("target must be one of {}", TARGETS.join(", ")));
        }
        let sound = self.sound.as_deref().map(str::trim).filter(|s| !s.is_empty()).map(str::to_string);
        if let Some(s) = &sound {
            if !valid_name(s) {
                return Err(format!("sound must be a sound id or a plain file name (no folders): {s}"));
            }
        }
        let url = self.url.as_deref().map(str::trim).filter(|s| !s.is_empty()).map(str::to_string);
        if let Some(u) = &url {
            if !(u.starts_with("http://") || u.starts_with("https://")) {
                return Err("url must be an http(s) link".into());
            }
        }
        Ok(NotifyRequest { title: title.into(), body: body.into(), level: Some(level), sound, target: Some(target), ticket_id: self.ticket_id.clone(), url })
    }
}

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct NotifyMessage {
    pub id: String,
    pub title: String,
    pub body: String,
    pub level: String,
    pub target: String,
    pub at: i64,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub sound: Option<String>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub sound_type: Option<String>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub sound_data: Option<String>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub ticket_id: Option<String>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub url: Option<String>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub project_id: Option<String>,
}

#[derive(Debug, Clone, PartialEq)]
pub struct NotifySettings {
    pub enabled: bool,
    pub quiet_hours: String,
    pub default_sound: String,
    pub milestone_sound: String,
    pub queue_max_age_minutes: i64,
    pub queue_max: usize,
    pub max_sound_bytes: usize,
}

impl Default for NotifySettings {
    fn default() -> Self {
        NotifySettings { enabled: true, quiet_hours: String::new(), default_sound: NONE.into(), milestone_sound: NONE.into(), queue_max_age_minutes: 1440, queue_max: 50, max_sound_bytes: 524_288 }
    }
}

impl NotifySettings {
    pub fn in_quiet_hours(&self, minute_of_day: u32) -> bool {
        match parse_quiet_hours(&self.quiet_hours) {
            None => false,
            Some((from, to)) if from <= to => minute_of_day >= from && minute_of_day < to,
            Some((from, to)) => minute_of_day >= from || minute_of_day < to,
        }
    }
}

pub fn valid_name(n: &str) -> bool {
    !n.trim().is_empty() && n.chars().count() <= 128 && !n.contains('/') && !n.contains('\\') && !n.contains("..") && !n.contains(':') && !n.chars().any(char::is_control)
}

fn type_of(ext: &str) -> &'static str {
    match ext.to_ascii_lowercase().as_str() {
        "mp3" => "audio/mpeg",
        "ogg" => "audio/ogg",
        _ => "audio/wav",
    }
}

#[derive(Debug, Clone, PartialEq)]
pub struct Sound {
    pub name: String,
    pub r#type: String,
    pub bytes: Vec<u8>,
}

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
pub struct ListedSound {
    pub name: String,
    pub builtin: bool,
    pub size: u64,
}

fn wav(samples: &[i16]) -> Vec<u8> {
    let data = (samples.len() * 2) as u32;
    let mut b = Vec::with_capacity(44 + data as usize);
    b.extend_from_slice(b"RIFF");
    b.extend_from_slice(&(36 + data).to_le_bytes());
    b.extend_from_slice(b"WAVEfmt ");
    b.extend_from_slice(&16u32.to_le_bytes());
    b.extend_from_slice(&1u16.to_le_bytes());
    b.extend_from_slice(&1u16.to_le_bytes());
    b.extend_from_slice(&RATE.to_le_bytes());
    b.extend_from_slice(&(RATE * 2).to_le_bytes());
    b.extend_from_slice(&2u16.to_le_bytes());
    b.extend_from_slice(&16u16.to_le_bytes());
    b.extend_from_slice(b"data");
    b.extend_from_slice(&data.to_le_bytes());
    for s in samples {
        b.extend_from_slice(&s.to_le_bytes());
    }
    b
}

fn notes(seq: &[(f64, f64)], decay: f64, partial: f64) -> Vec<i16> {
    let mut out = Vec::new();
    for &(freq, sec) in seq {
        let n = (RATE as f64 * sec) as usize;
        for i in 0..n {
            let t = i as f64 / RATE as f64;
            let env = (t / 0.008).min(1.0) * (-decay * t).exp();
            let v = (2.0 * PI * freq * t).sin() + partial * (2.0 * PI * 2.0 * freq * t).sin();
            out.push((v / (1.0 + partial) * env * 0.6 * i16::MAX as f64) as i16);
        }
    }
    out
}

fn beeps(freq: f64, count: usize, on_sec: f64, off_sec: f64) -> Vec<i16> {
    let mut out = Vec::new();
    for _ in 0..count {
        let on = (RATE as f64 * on_sec) as usize;
        for i in 0..on {
            let t = i as f64 / RATE as f64;
            let edge = (t / 0.005).min((on_sec - t) / 0.005).min(1.0);
            out.push(((2.0 * PI * freq * t).sin() * edge * 0.8 * i16::MAX as f64) as i16);
        }
        out.extend(std::iter::repeat_n(0, (RATE as f64 * off_sec) as usize));
    }
    out
}

/// A built-in sound as a WAV, synthesised once.
pub fn synth(id: &str) -> Option<&'static [u8]> {
    static CACHE: OnceLock<HashMap<&'static str, Vec<u8>>> = OnceLock::new();
    let cache = CACHE.get_or_init(|| {
        HashMap::from([
            // Three rising notes (C6 E6 G6), soft attack, each decaying.
            ("chime", wav(&notes(&[(1046.5, 0.16), (1318.5, 0.16), (1568.0, 0.42)], 6.0, 0.0))),
            // One A5 with a long ring and an octave partial.
            ("bell", wav(&notes(&[(880.0, 1.1)], 3.2, 0.35))),
            // Three loud 1 kHz beeps.
            ("alarm", wav(&beeps(1000.0, 3, 0.22, 0.12))),
        ])
    });
    cache.get(id).map(Vec::as_slice)
}

pub struct NotifySounds {
    dir: PathBuf,
}

impl NotifySounds {
    pub fn new(dir: PathBuf) -> Self {
        let _ = fs::create_dir_all(&dir);
        NotifySounds { dir }
    }

    pub fn list(&self) -> Vec<ListedSound> {
        let mut out: Vec<ListedSound> = BUILTIN.iter().map(|b| ListedSound { name: (*b).into(), builtin: true, size: synth(b).map(|s| s.len() as u64).unwrap_or(0) }).collect();
        let mut own: Vec<ListedSound> = fs::read_dir(&self.dir)
            .into_iter()
            .flatten()
            .flatten()
            .filter(|e| e.path().is_file() && e.path().extension().and_then(|x| x.to_str()).is_some_and(|x| EXTENSIONS.contains(&x.to_ascii_lowercase().as_str())))
            .map(|e| ListedSound { name: e.file_name().to_string_lossy().into_owned(), builtin: false, size: e.metadata().map(|m| m.len()).unwrap_or(0) })
            .collect();
        own.sort_by_key(|s| s.name.to_lowercase());
        out.extend(own);
        out
    }

    /// None for no sound (`none` / blank).
    pub fn resolve(&self, name: Option<&str>) -> Result<Option<Sound>, String> {
        let n = name.map(str::trim).unwrap_or("");
        if n.is_empty() || n.eq_ignore_ascii_case(NONE) {
            return Ok(None);
        }
        if !valid_name(n) {
            return Err(format!("sound must be a sound id or a plain file name (no folders): {n}"));
        }
        let lower = n.to_lowercase();
        if let Some(b) = synth(&lower) {
            return Ok(Some(Sound { name: lower, r#type: "audio/wav".into(), bytes: b.to_vec() }));
        }
        let f = self.dir.join(n);
        let ext = f.extension().and_then(|x| x.to_str()).unwrap_or("").to_ascii_lowercase();
        if !f.is_file() || !EXTENSIONS.contains(&ext.as_str()) {
            return Err(format!("unknown sound '{n}' (built in: {}; or a .wav/.mp3/.ogg file in {})", BUILTIN.join(", "), self.dir.display()));
        }
        let bytes = fs::read(&f).map_err(|e| e.to_string())?;
        Ok(Some(Sound { name: n.into(), r#type: type_of(&ext).into(), bytes }))
    }
}

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct Milestone {
    #[serde(skip)]
    pub ticket_id: String,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub sound: Option<String>,
    #[serde(default)]
    pub last_fired_seq: i64,
    #[serde(default)]
    pub set_at: i64,
}

#[derive(Debug, Clone, PartialEq)]
pub enum NotifyOutcome {
    Sent { message: NotifyMessage, silent_reason: Option<String> },
    Off(String),
}

#[derive(Default)]
struct PushState {
    queue: Vec<NotifyMessage>,
    delivered: BTreeMap<String, HashSet<String>>,
    milestones: BTreeMap<String, Milestone>,
}

type Push = Arc<dyn Fn(&NotifyMessage) + Send + Sync>;

pub struct NotifyService {
    dir: PathBuf,
    pub sounds: NotifySounds,
    settings: Arc<dyn Fn() -> NotifySettings + Send + Sync>,
    clock: Arc<dyn Fn() -> i64 + Send + Sync>,
    minute_of_day: Arc<dyn Fn() -> u32 + Send + Sync>,
    push: Mutex<Option<Push>>,
    state: Mutex<PushState>,
}

fn load<T: for<'de> Deserialize<'de>>(p: &Path) -> Option<T> {
    serde_json::from_str(&fs::read_to_string(p).ok()?).ok()
}

fn write(p: &Path, text: String) {
    let tmp = p.with_extension("json.tmp");
    let res = fs::write(&tmp, text).and_then(|_| fs::rename(&tmp, p));
    if let Err(e) = res {
        eprintln!("[infantry] notify: could not write {}: {e}", p.display());
    }
}

impl NotifyService {
    pub fn new(state_dir: PathBuf, settings: Arc<dyn Fn() -> NotifySettings + Send + Sync>, clock: Arc<dyn Fn() -> i64 + Send + Sync>, minute_of_day: Arc<dyn Fn() -> u32 + Send + Sync>) -> Self {
        let _ = fs::create_dir_all(&state_dir);
        let mut st = PushState {
            queue: load(&state_dir.join("pending.json")).unwrap_or_default(),
            delivered: load::<BTreeMap<String, Vec<String>>>(&state_dir.join("delivered.json")).unwrap_or_default().into_iter().map(|(k, v)| (k, v.into_iter().collect())).collect(),
            milestones: load(&state_dir.join("milestones.json")).unwrap_or_default(),
        };
        for (id, m) in st.milestones.iter_mut() {
            m.ticket_id = id.clone();
        }
        NotifyService { sounds: NotifySounds::new(state_dir.join("sounds")), dir: state_dir, settings, clock, minute_of_day, push: Mutex::new(None), state: Mutex::new(st) }
    }

    pub fn set_push(&self, f: impl Fn(&NotifyMessage) + Send + Sync + 'static) {
        *self.push.lock().unwrap() = Some(Arc::new(f));
    }

    fn save(&self, st: &PushState) {
        write(&self.dir.join("pending.json"), serde_json::to_string(&st.queue).unwrap_or_default());
        let d: BTreeMap<&String, Vec<&String>> = st.delivered.iter().map(|(k, v)| (k, v.iter().collect())).collect();
        write(&self.dir.join("delivered.json"), serde_json::to_string(&d).unwrap_or_default());
        write(&self.dir.join("milestones.json"), serde_json::to_string(&st.milestones).unwrap_or_default());
    }

    fn prune(&self, st: &mut PushState) {
        let cutoff = (self.clock)() - (self.settings)().queue_max_age_minutes * 60_000;
        st.queue.retain(|m| m.at >= cutoff);
        let live: HashSet<String> = st.queue.iter().map(|m| m.id.clone()).collect();
        st.delivered.retain(|k, _| live.contains(k));
    }

    pub fn notify(&self, req: &NotifyRequest, project_id: Option<&str>) -> Result<NotifyOutcome, String> {
        let req = req.validated()?;
        let s = (self.settings)();
        if !s.enabled {
            return Ok(NotifyOutcome::Off("notifications are off (setting notify.enabled)".into()));
        }
        let level = req.level.clone().unwrap_or_else(|| "info".into());
        let wanted = req.sound.clone().unwrap_or_else(|| if level == "milestone" { s.milestone_sound.clone() } else { s.default_sound.clone() });
        let sound = self.sounds.resolve(Some(&wanted))?;
        let mut silent = None;
        let carried = match sound {
            None => None,
            Some(_) if level != "urgent" && s.in_quiet_hours((self.minute_of_day)()) => {
                silent = Some(format!("quiet hours ({})", s.quiet_hours));
                None
            }
            Some(snd) if snd.bytes.len() > s.max_sound_bytes => {
                silent = Some(format!("sound {} is {} bytes, over notify.maxSoundBytes", snd.name, snd.bytes.len()));
                None
            }
            Some(snd) => Some(snd),
        };
        let msg = NotifyMessage {
            id: uuid::Uuid::new_v4().to_string(),
            title: req.title.clone(),
            body: req.body.clone(),
            level,
            target: req.target.clone().unwrap_or_else(|| "all".into()),
            at: (self.clock)(),
            sound: carried.as_ref().map(|c| c.name.clone()),
            sound_type: carried.as_ref().map(|c| c.r#type.clone()),
            sound_data: carried.as_ref().map(|c| base64::engine::general_purpose::STANDARD.encode(&c.bytes)),
            ticket_id: req.ticket_id.clone(),
            url: req.url.clone(),
            project_id: project_id.map(str::to_string),
        };
        {
            let mut st = self.state.lock().unwrap();
            self.prune(&mut st);
            st.queue.push(msg.clone());
            let over = st.queue.len().saturating_sub(s.queue_max);
            st.queue.drain(..over);
            self.save(&st);
        }
        if let Some(p) = self.push.lock().unwrap().clone() {
            p(&msg);
        }
        Ok(NotifyOutcome::Sent { message: msg, silent_reason: silent })
    }

    /// A device saw it; with no device the message leaves the queue for everyone.
    pub fn ack(&self, id: &str, device: Option<&str>) -> bool {
        let mut st = self.state.lock().unwrap();
        if !st.queue.iter().any(|m| m.id == id) {
            return false;
        }
        match device.map(str::trim).filter(|d| !d.is_empty()) {
            None => {
                st.queue.retain(|m| m.id != id);
                st.delivered.remove(id);
            }
            Some(d) => {
                st.delivered.entry(id.into()).or_default().insert(d.to_lowercase());
            }
        }
        self.save(&st);
        true
    }

    /// What `device` has not acked yet (all of the queue when None).
    pub fn pending(&self, device: Option<&str>) -> Vec<NotifyMessage> {
        let mut st = self.state.lock().unwrap();
        self.prune(&mut st);
        let d = device.map(str::trim).filter(|d| !d.is_empty()).map(str::to_lowercase);
        st.queue
            .iter()
            .filter(|m| match &d {
                None => true,
                Some(d) => !st.delivered.get(&m.id).is_some_and(|s| s.contains(d)) && (m.target == "all" || m.target.eq_ignore_ascii_case(d)),
            })
            .cloned()
            .collect()
    }

    /// What no device has acked: replayed to a client that connects.
    pub fn unacked(&self) -> Vec<NotifyMessage> {
        let mut st = self.state.lock().unwrap();
        self.prune(&mut st);
        st.queue.iter().filter(|m| st.delivered.get(&m.id).is_none_or(HashSet::is_empty)).cloned().collect()
    }

    pub fn set_milestone(&self, ticket_id: &str, on: bool, sound: Option<&str>) -> Result<Option<Milestone>, String> {
        if sound.is_some() {
            self.sounds.resolve(sound)?;
        }
        let mut st = self.state.lock().unwrap();
        let out = if on {
            let last = st.milestones.get(ticket_id).map(|m| m.last_fired_seq).unwrap_or(0);
            let m = Milestone { ticket_id: ticket_id.into(), sound: sound.map(str::to_string), last_fired_seq: last, set_at: (self.clock)() };
            st.milestones.insert(ticket_id.into(), m.clone());
            Some(m)
        } else {
            st.milestones.remove(ticket_id);
            None
        };
        self.save(&st);
        Ok(out)
    }

    pub fn milestones(&self) -> Vec<Milestone> {
        self.state.lock().unwrap().milestones.values().cloned().collect()
    }

    /// A marked ticket moved to done (`seq` = the board event's sequence, so a replay does not fire twice).
    pub fn on_ticket_done(&self, ticket_id: &str, seq: i64, title: Option<&str>, project_id: Option<&str>) -> Option<NotifyOutcome> {
        let m = {
            let mut st = self.state.lock().unwrap();
            let m = st.milestones.get_mut(ticket_id)?;
            if seq <= m.last_fired_seq {
                return None;
            }
            m.last_fired_seq = seq;
            let m = m.clone();
            self.save(&st);
            m
        };
        let name = title.filter(|t| !t.trim().is_empty()).unwrap_or(ticket_id);
        let req = |sound: Option<String>| NotifyRequest {
            title: format!("Milestone done: {}", name.chars().take(MAX_TITLE - 16).collect::<String>()),
            body: format!("{ticket_id} is done."),
            level: Some("milestone".into()),
            sound,
            target: None,
            ticket_id: Some(ticket_id.into()),
            url: None,
        };
        // A sound removed since the ticket was marked: still notify, silent.
        self.notify(&req(m.sound.clone()), project_id).or_else(|_| self.notify(&req(Some(NONE.into())), project_id)).ok()
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn queue_ack_sounds_milestones() {
        let dir = tempfile::tempdir().unwrap();
        let svc = NotifyService::new(dir.path().to_path_buf(), Arc::new(NotifySettings::default), Arc::new(|| 1_000), Arc::new(|| 12 * 60));
        assert_eq!(NotifyRequest { title: " ".into(), ..Default::default() }.validated().unwrap_err(), "title is required");
        assert!(NotifyRequest { title: "x".into(), sound: Some("../a.wav".into()), ..Default::default() }.validated().is_err());
        let NotifyOutcome::Sent { message, .. } = svc.notify(&NotifyRequest { title: "Hi".into(), sound: Some("chime".into()), ..Default::default() }, None).unwrap() else { panic!() };
        assert_eq!(message.sound_type.as_deref(), Some("audio/wav"));
        assert!(svc.ack(&message.id, Some("Android")));
        assert!(svc.pending(Some("android")).is_empty());
        assert_eq!(svc.pending(Some("desktop")).len(), 1);
        let wav = synth("bell").unwrap();
        assert_eq!(&wav[..4], b"RIFF");
        assert_eq!(svc.sounds.list().len(), 3);
        svc.set_milestone("T1", true, None).unwrap();
        assert!(svc.on_ticket_done("T1", 5, Some("Ship it"), None).is_some());
        assert!(svc.on_ticket_done("T1", 5, Some("Ship it"), None).is_none());
        // Reloads from disk.
        let again = NotifyService::new(dir.path().to_path_buf(), Arc::new(NotifySettings::default), Arc::new(|| 1_000), Arc::new(|| 0));
        assert_eq!(again.pending(None).len(), 2);
        assert_eq!(again.milestones()[0].ticket_id, "T1");
    }
}
