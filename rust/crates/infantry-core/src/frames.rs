// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
//! The frozen FrameUnit contract (RUST-PARALLEL-COMMON) plus the [`Registry`] that maps a
//! frame type to the one unit that owns it.
use std::collections::BTreeMap;
use std::fmt;
use std::sync::Arc;

pub use crate::bus::EventBus;
pub use infantry_protocol::envelope::Envelope;

// ---- frozen contract: do not change without telling every unit ----
pub trait FrameUnit: Send + Sync {
    fn name(&self) -> &'static str;
    fn frames(&self) -> &'static [&'static str];            // frame types this unit owns
    fn handle(&self, env: &Envelope, ctx: &FrameCtx) -> Result<Vec<Envelope>, Refusal>;
}
pub struct FrameCtx { pub conn_id: u64, pub owner: bool, pub bus: EventBus }
pub struct Refusal(pub String); // the exact "<type> refused: …" text
// ---- end frozen contract ----

impl Refusal {
    /// `"<type> refused: <reason>"`, the text the Kotlin daemon sends.
    pub fn new(frame_type: &str, reason: impl fmt::Display) -> Self {
        Refusal(format!("{frame_type} refused: {reason}"))
    }
}

impl fmt::Debug for Refusal {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        f.debug_tuple("Refusal").field(&self.0).finish()
    }
}

impl fmt::Display for Refusal {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        f.write_str(&self.0)
    }
}

impl std::error::Error for Refusal {}

/// Why [`Registry::register`] refused a unit.
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum RegistryError {
    /// `frame` is already owned by `owner`; `unit` tried to claim it too.
    DuplicateOwner { frame: String, owner: &'static str, unit: &'static str },
    /// A unit with this name is already registered.
    DuplicateUnit(&'static str),
}

impl fmt::Display for RegistryError {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        match self {
            RegistryError::DuplicateOwner { frame, owner, unit } => {
                write!(f, "frame type `{frame}` is already owned by `{owner}`; `{unit}` cannot own it too")
            }
            RegistryError::DuplicateUnit(name) => write!(f, "a unit named `{name}` is already registered"),
        }
    }
}

impl std::error::Error for RegistryError {}

/// Maps each frame type to exactly one owning [`FrameUnit`].
#[derive(Default, Clone)]
pub struct Registry {
    units: Vec<Arc<dyn FrameUnit>>,
    by_frame: BTreeMap<&'static str, usize>,
}

impl Registry {
    pub fn new() -> Self {
        Self::default()
    }

    /// Registers `unit` for every frame type it lists. All or nothing: a frame type already
    /// owned by another unit (or listed twice by this one) refuses the whole unit.
    pub fn register(&mut self, unit: Arc<dyn FrameUnit>) -> Result<(), RegistryError> {
        let name = unit.name();
        if self.units.iter().any(|u| u.name() == name) {
            return Err(RegistryError::DuplicateUnit(name));
        }
        let mut seen: Vec<&'static str> = Vec::new();
        for &frame in unit.frames() {
            if let Some(&idx) = self.by_frame.get(frame) {
                return Err(RegistryError::DuplicateOwner { frame: frame.to_string(), owner: self.units[idx].name(), unit: name });
            }
            if seen.contains(&frame) {
                return Err(RegistryError::DuplicateOwner { frame: frame.to_string(), owner: name, unit: name });
            }
            seen.push(frame);
        }
        let idx = self.units.len();
        self.units.push(unit);
        for frame in seen {
            self.by_frame.insert(frame, idx);
        }
        Ok(())
    }

    /// The unit that owns `frame_type`, if any.
    pub fn owner(&self, frame_type: &str) -> Option<&Arc<dyn FrameUnit>> {
        self.by_frame.get(frame_type).map(|&i| &self.units[i])
    }

    /// Routes `env` to its owner. `None` when no unit owns the frame type.
    pub fn dispatch(&self, env: &Envelope, ctx: &FrameCtx) -> Option<Result<Vec<Envelope>, Refusal>> {
        self.owner(&env.r#type).map(|u| u.handle(env, ctx))
    }

    /// Every owned frame type with its unit name, sorted by frame type.
    pub fn frame_table(&self) -> Vec<(&'static str, &'static str)> {
        self.by_frame.iter().map(|(&f, &i)| (f, self.units[i].name())).collect()
    }

    pub fn units(&self) -> &[Arc<dyn FrameUnit>] {
        &self.units
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    struct Echo(&'static str, &'static [&'static str]);
    impl FrameUnit for Echo {
        fn name(&self) -> &'static str {
            self.0
        }
        fn frames(&self) -> &'static [&'static str] {
            self.1
        }
        fn handle(&self, env: &Envelope, ctx: &FrameCtx) -> Result<Vec<Envelope>, Refusal> {
            if !ctx.owner {
                return Err(Refusal::new(&env.r#type, "owner only"));
            }
            Ok(vec![Envelope::new(format!("{}_ok", env.r#type), env.data.clone())])
        }
    }

    fn ctx(owner: bool) -> FrameCtx {
        FrameCtx { conn_id: 7, owner, bus: EventBus::new(16) }
    }

    #[test]
    fn routes_to_owner_and_refuses_duplicates() {
        let mut r = Registry::new();
        r.register(Arc::new(Echo("a", &["ping", "list"]))).unwrap();
        let err = r.register(Arc::new(Echo("b", &["other", "list"]))).unwrap_err();
        assert_eq!(err, RegistryError::DuplicateOwner { frame: "list".into(), owner: "a", unit: "b" });
        // all or nothing: "other" was not claimed by the refused unit
        assert!(r.owner("other").is_none());
        assert_eq!(r.register(Arc::new(Echo("a", &["x"]))).unwrap_err(), RegistryError::DuplicateUnit("a"));
        assert!(r.register(Arc::new(Echo("c", &["y", "y"]))).is_err());

        let out = r.dispatch(&Envelope::new("ping", "1"), &ctx(true)).unwrap().unwrap();
        assert_eq!(out[0].r#type, "ping_ok");
        let refused = r.dispatch(&Envelope::new("ping", ""), &ctx(false)).unwrap().unwrap_err();
        assert_eq!(refused.0, "ping refused: owner only");
        assert!(r.dispatch(&Envelope::new("nope", ""), &ctx(true)).is_none());
        assert_eq!(r.frame_table(), vec![("list", "a"), ("ping", "a")]);
    }
}
