// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
//! The ONE notification bus. Anything that tells the owner something posts a [`Notification`]; the hub stamps it,
//! dedupes it, keeps a bounded history (sticky ones for replay on connect), routes it by level to channels
//! (`notifications.route.<level>`, default `inApp`, urgent `inApp,os`), hands it to the presenters registered for
//! those channels, and broadcasts it as a `notification_push` frame. The owner's dismiss and action clicks come
//! back as `notification_dismiss` / `notification_action`.
use std::collections::{HashMap, VecDeque};
use std::sync::{Arc, Mutex, RwLock};

use serde::{Deserialize, Serialize};

pub const NOTIFICATION_PUSH: &str = "notification_push";
pub const NOTIFICATION_ACTION: &str = "notification_action";
pub const NOTIFICATION_DISMISS: &str = "notification_dismiss";
pub const NOTIFICATION_LIST: &str = "notification_list";
pub const NOTIFICATIONS: &str = "notifications";

#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash, Serialize, Deserialize, Default)]
#[serde(rename_all = "lowercase")]
pub enum NotificationLevel {
    #[default]
    Info,
    Success,
    Warning,
    Error,
    Urgent,
}

impl NotificationLevel {
    pub fn of(text: &str) -> Self {
        match text.trim().to_ascii_lowercase().as_str() {
            "success" | "milestone" => Self::Success,
            "warning" | "warn" => Self::Warning,
            "error" => Self::Error,
            "urgent" => Self::Urgent,
            _ => Self::Info,
        }
    }
    pub fn wire(self) -> &'static str {
        match self {
            Self::Info => "info",
            Self::Success => "success",
            Self::Warning => "warning",
            Self::Error => "error",
            Self::Urgent => "urgent",
        }
    }
}

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
pub struct NotificationAction {
    pub id: String,
    pub label: String,
}

fn is_false(b: &bool) -> bool {
    !*b
}
fn is_zero(n: &i64) -> bool {
    *n == 0
}
fn is_info(l: &NotificationLevel) -> bool {
    *l == NotificationLevel::Info
}

/// One notice to the owner. Encoded without nulls or defaults (the Kotlin wire).
#[derive(Debug, Clone, PartialEq, Default, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct Notification {
    pub title: String,
    #[serde(default, skip_serializing_if = "String::is_empty")]
    pub body: String,
    #[serde(default, skip_serializing_if = "is_info")]
    pub level: NotificationLevel,
    #[serde(default, skip_serializing_if = "String::is_empty")]
    pub id: String,
    #[serde(default, skip_serializing_if = "String::is_empty")]
    pub source: String,
    #[serde(default, skip_serializing_if = "Vec::is_empty")]
    pub actions: Vec<NotificationAction>,
    #[serde(default, skip_serializing_if = "is_false")]
    pub sticky: bool,
    #[serde(default, skip_serializing_if = "is_zero")]
    pub timeout_ms: i64,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub dedupe_key: Option<String>,
    #[serde(default, skip_serializing_if = "Vec::is_empty")]
    pub channels: Vec<String>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub ticket_id: Option<String>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub session_id: Option<String>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub project_id: Option<String>,
    #[serde(default, skip_serializing_if = "is_zero")]
    pub at: i64,
}

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct NotificationActionEvent {
    pub notification_id: String,
    pub action_id: String,
    #[serde(default)]
    pub source: String,
}

pub const IN_APP: &str = "inApp";
pub const OS: &str = "os";

/// Shows notifications on one channel (the OS toast, a phone push, a sound...). False = not shown.
pub trait NotificationPresenter: Send + Sync {
    fn show(&self, n: &Notification) -> bool;
    fn dismiss(&self, _id: &str) {}
}

#[derive(Debug, Clone, PartialEq)]
pub struct NotificationSettings {
    pub routes: HashMap<NotificationLevel, Vec<String>>,
    /// channel -> presenter id; blank = the first registered.
    pub presenters: HashMap<String, String>,
    /// `HH:mm-HH:mm`; inside it only `inApp` is used (urgent still goes everywhere).
    pub quiet_hours: String,
    pub dedupe_window_sec: i64,
    pub history_max: usize,
    pub title_max_chars: usize,
    pub body_max_chars: usize,
    /// `notifications.osDesktop` (default on): the pushed frame names the `os` channel so the desktop app raises an
    /// OS notification for it; off = the frame never names `os` (in-app toast only).
    pub os_desktop: bool,
}

impl Default for NotificationSettings {
    fn default() -> Self {
        let mut routes = HashMap::new();
        for l in [NotificationLevel::Info, NotificationLevel::Success, NotificationLevel::Warning, NotificationLevel::Error] {
            routes.insert(l, vec![IN_APP.to_string()]);
        }
        routes.insert(NotificationLevel::Urgent, vec![IN_APP.to_string(), OS.to_string()]);
        NotificationSettings { routes, presenters: HashMap::new(), quiet_hours: String::new(), dedupe_window_sec: 600, history_max: 100, title_max_chars: 200, body_max_chars: 4000, os_desktop: true }
    }
}

impl NotificationSettings {
    /// From `notifications.*` keys (`route.<level>` = comma list, `presenter.<channel>`, `quietHours`, ...).
    pub fn of(get: impl Fn(&str) -> Option<String>) -> Self {
        let mut s = Self::default();
        for l in [NotificationLevel::Info, NotificationLevel::Success, NotificationLevel::Warning, NotificationLevel::Error, NotificationLevel::Urgent] {
            if let Some(v) = get(&format!("notifications.route.{}", l.wire())) {
                s.routes.insert(l, v.split(',').map(|c| c.trim().to_string()).filter(|c| !c.is_empty()).collect());
            }
        }
        for c in [IN_APP, OS] {
            if let Some(v) = get(&format!("notifications.presenter.{c}")) {
                s.presenters.insert(c.into(), v);
            }
        }
        if let Some(q) = get("notifications.quietHours") {
            s.quiet_hours = q;
        }
        let num = |k: &str| get(k).and_then(|v| v.trim().parse::<i64>().ok());
        if let Some(n) = num("notifications.dedupeWindowSec") {
            s.dedupe_window_sec = n.max(0);
        }
        if let Some(v) = get("notifications.osDesktop") {
            s.os_desktop = !matches!(v.trim().to_ascii_lowercase().as_str(), "false" | "off" | "0" | "no");
        }
        if let Some(n) = num("notifications.historyMax") {
            s.history_max = n.clamp(1, 10_000) as usize;
        }
        s
    }

    /// Whether `minute_of_day` is inside the quiet hours.
    pub fn quiet(&self, minute_of_day: Option<u32>) -> bool {
        let Some((from, to)) = parse_quiet_hours(&self.quiet_hours) else { return false };
        let Some(m) = minute_of_day else { return false };
        if from <= to {
            m >= from && m < to
        } else {
            m >= from || m < to
        }
    }
}

/// `HH:mm-HH:mm` -> (from, to) minutes of the day.
pub fn parse_quiet_hours(text: &str) -> Option<(u32, u32)> {
    let (a, b) = text.trim().split_once('-')?;
    let min = |t: &str| -> Option<u32> {
        let (h, m) = t.trim().split_once(':')?;
        let (h, m) = (h.parse::<u32>().ok()?, m.parse::<u32>().ok()?);
        (h < 24 && m < 60).then_some(h * 60 + m)
    };
    Some((min(a)?, min(b)?))
}

type Broadcast = Arc<dyn Fn(&str, String) + Send + Sync>;
type ActionListener = Arc<dyn Fn(&NotificationActionEvent) + Send + Sync>;

#[derive(Default)]
struct HubState {
    history: VecDeque<Notification>,
    seen: VecDeque<String>,
    last_by_key: HashMap<String, i64>,
}

pub struct NotificationHub {
    settings: Arc<dyn Fn() -> NotificationSettings + Send + Sync>,
    clock: Arc<dyn Fn() -> i64 + Send + Sync>,
    /// Local minute of the day for quiet hours; None = no quiet hours applied.
    minute_of_day: Arc<dyn Fn() -> Option<u32> + Send + Sync>,
    broadcast: RwLock<Option<Broadcast>>,
    presenters: RwLock<Vec<(String, String, Arc<dyn NotificationPresenter>)>>,
    listeners: RwLock<Vec<(String, ActionListener)>>,
    state: Mutex<HubState>,
}

impl NotificationHub {
    pub fn new(settings: Arc<dyn Fn() -> NotificationSettings + Send + Sync>) -> Self {
        Self::with(settings, Arc::new(crate::mail::mailbox::now_ms), Arc::new(|| None))
    }

    pub fn with(settings: Arc<dyn Fn() -> NotificationSettings + Send + Sync>, clock: Arc<dyn Fn() -> i64 + Send + Sync>, minute_of_day: Arc<dyn Fn() -> Option<u32> + Send + Sync>) -> Self {
        NotificationHub { settings, clock, minute_of_day, broadcast: RwLock::new(None), presenters: RwLock::new(Vec::new()), listeners: RwLock::new(Vec::new()), state: Mutex::new(HubState::default()) }
    }

    /// Where pushes go: `(frame type, json data)`; the daemon publishes them on its bus.
    pub fn set_broadcast(&self, f: impl Fn(&str, String) + Send + Sync + 'static) {
        *self.broadcast.write().unwrap() = Some(Arc::new(f));
    }

    /// Registers a presenter for `channel` under `presenter_id`.
    pub fn present(&self, presenter_id: &str, channel: &str, presenter: Arc<dyn NotificationPresenter>) {
        self.presenters.write().unwrap().push((presenter_id.into(), channel.into(), presenter));
    }

    pub fn on_action(&self, module: &str, listener: impl Fn(&NotificationActionEvent) + Send + Sync + 'static) {
        self.listeners.write().unwrap().push((module.into(), Arc::new(listener)));
    }

    /// Posts a notification for `source`; returns its id. A duplicate (same id seen, or the same dedupe key
    /// inside the window) answers the id without showing it again.
    pub fn post(&self, notification: Notification, source: &str) -> String {
        let cfg = (self.settings)();
        let now = (self.clock)();
        let mut n = notification;
        if n.id.trim().is_empty() {
            n.id = format!("notification-{}", uuid::Uuid::new_v4());
        }
        if n.source.trim().is_empty() {
            n.source = if source.trim().is_empty() { "daemon".into() } else { source.into() };
        }
        n.at = if n.at == 0 { now } else { n.at };
        n.title = n.title.chars().take(cfg.title_max_chars).collect();
        n.body = n.body.chars().take(cfg.body_max_chars).collect();
        {
            let mut s = self.state.lock().unwrap();
            if s.seen.contains(&n.id) {
                return n.id;
            }
            if let Some(key) = n.dedupe_key.clone().filter(|k| !k.trim().is_empty()) {
                if s.last_by_key.get(&key).is_some_and(|last| now - last < cfg.dedupe_window_sec * 1000) {
                    return n.id;
                }
                s.last_by_key.insert(key, now);
                if s.last_by_key.len() > cfg.history_max * 4 {
                    let cutoff = now - cfg.dedupe_window_sec * 1000;
                    s.last_by_key.retain(|_, t| *t >= cutoff);
                }
            }
            s.seen.push_back(n.id.clone());
            while s.seen.len() > cfg.history_max * 4 {
                s.seen.pop_front();
            }
            s.history.push_back(n.clone());
            // Bounded history; a sticky notice is kept for replay until dismissed.
            while s.history.len() > cfg.history_max {
                match s.history.iter().position(|h| !h.sticky) {
                    Some(i) => {
                        s.history.remove(i);
                    }
                    None => break,
                }
            }
        }
        let mut channels = if n.channels.is_empty() { cfg.routes.get(&n.level).cloned().unwrap_or_else(|| vec![IN_APP.into()]) } else { n.channels.clone() };
        if n.level != NotificationLevel::Urgent && cfg.quiet((self.minute_of_day)()) {
            channels.retain(|c| c == IN_APP);
        }
        for c in &channels {
            if c == IN_APP {
                continue;
            }
            let chosen = cfg.presenters.get(c).cloned().unwrap_or_default();
            let ps = self.presenters.read().unwrap().clone();
            let pick = ps.iter().find(|(id, ch, _)| ch == c && (chosen.is_empty() || *id == chosen));
            if let Some((_, _, p)) = pick {
                p.show(&n);
            }
        }
        if channels.iter().any(|c| c == IN_APP) {
            // The frame carries the channels it was routed to, so a desktop client raises an OS notification for `os`.
            let mut f = n.clone();
            f.channels = channels.iter().filter(|c| cfg.os_desktop || c.as_str() != OS).cloned().collect();
            self.push(NOTIFICATION_PUSH, serde_json::to_string(&f).unwrap_or_default());
        }
        n.id
    }

    fn push(&self, frame: &str, data: String) {
        let b = self.broadcast.read().unwrap().clone();
        if let Some(b) = b {
            b(frame, data);
        }
    }

    /// The notices to show a client that just connected (sticky ones).
    pub fn replay(&self) -> Vec<Notification> {
        self.state.lock().unwrap().history.iter().filter(|n| n.sticky).cloned().collect()
    }

    /// The recent history, newest last.
    pub fn history(&self) -> Vec<Notification> {
        self.state.lock().unwrap().history.iter().cloned().collect()
    }

    pub fn dismissed(&self, id: &str) {
        self.state.lock().unwrap().history.retain(|n| n.id != id);
        for (_, _, p) in self.presenters.read().unwrap().iter() {
            p.dismiss(id);
        }
        self.push(NOTIFICATION_DISMISS, serde_json::json!({ "id": id }).to_string());
    }

    /// The owner clicked an action: the posting module's listeners hear it.
    pub fn action(&self, e: &NotificationActionEvent) {
        let source = self.state.lock().unwrap().history.iter().find(|n| n.id == e.notification_id).map(|n| n.source.clone());
        let ev = NotificationActionEvent { source: source.clone().unwrap_or_else(|| e.source.clone()), ..e.clone() };
        for (module, l) in self.listeners.read().unwrap().clone() {
            if source.as_deref().is_none_or(|s| s == module) {
                l(&ev);
            }
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::sync::atomic::{AtomicUsize, Ordering};

    struct Os(AtomicUsize);
    impl NotificationPresenter for Os {
        fn show(&self, _n: &Notification) -> bool {
            self.0.fetch_add(1, Ordering::SeqCst);
            true
        }
    }

    #[test]
    fn posts_dedupes_routes_and_quiet_hours() {
        let hub = NotificationHub::with(Arc::new(|| NotificationSettings { quiet_hours: "22:00-07:00".into(), ..Default::default() }), Arc::new(|| 1_000), Arc::new(|| Some(23 * 60)));
        let pushes = Arc::new(Mutex::new(Vec::new()));
        let p = pushes.clone();
        hub.set_broadcast(move |t, d| p.lock().unwrap().push((t.to_string(), d)));
        let os = Arc::new(Os(AtomicUsize::new(0)));
        hub.present("toast", OS, os.clone());
        let n = Notification { title: "Build done".into(), dedupe_key: Some("b".into()), level: NotificationLevel::of("warn"), ..Default::default() };
        let id = hub.post(n.clone(), "board");
        assert!(id.starts_with("notification-"));
        hub.post(n, "board");
        assert_eq!(pushes.lock().unwrap().len(), 1);
        let pushed: serde_json::Value = serde_json::from_str(&pushes.lock().unwrap()[0].1).unwrap();
        assert_eq!(pushed["level"], "warning");
        assert!(pushed.get("ticketId").is_none());
        // Urgent goes to the OS even in quiet hours; a warning with an explicit OS channel does not.
        hub.post(Notification { title: "x".into(), channels: vec![OS.into()], ..Default::default() }, "");
        assert_eq!(os.0.load(Ordering::SeqCst), 0);
        hub.post(Notification { title: "fire".into(), level: NotificationLevel::Urgent, ..Default::default() }, "");
        assert_eq!(os.0.load(Ordering::SeqCst), 1);
        assert_eq!(parse_quiet_hours("7:5-8:00"), Some((425, 480)));
    }

    #[test]
    fn the_pushed_frame_names_os_unless_os_desktop_is_off() {
        for (on, want) in [(true, true), (false, false)] {
            let hub = NotificationHub::with(Arc::new(move || NotificationSettings { os_desktop: on, ..Default::default() }), Arc::new(|| 1_000), Arc::new(|| None));
            let pushed = Arc::new(Mutex::new(Vec::<String>::new()));
            let p = pushed.clone();
            hub.set_broadcast(move |_, d| p.lock().unwrap().push(d));
            hub.post(Notification { title: "needs you".into(), level: NotificationLevel::Urgent, ..Default::default() }, "");
            hub.post(Notification { title: "fyi".into(), ..Default::default() }, "");
            let got = pushed.lock().unwrap().clone();
            let n0: Notification = serde_json::from_str(&got[0]).unwrap();
            let n1: Notification = serde_json::from_str(&got[1]).unwrap();
            assert_eq!(n0.channels.iter().any(|c| c == OS), want);
            assert!(!n1.channels.iter().any(|c| c == OS));
        }
    }
}
