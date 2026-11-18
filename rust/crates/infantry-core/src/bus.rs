// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
//! In-process event bus: a broadcast of [`Envelope`]s (frames to fan out to clients) and a
//! broadcast of [`NamedEvent`]s (internal events by name, e.g. "session.ended").
use infantry_protocol::envelope::Envelope;
use tokio::sync::broadcast;

/// An internal event: a dotted name plus a JSON payload.
#[derive(Debug, Clone, PartialEq)]
pub struct NamedEvent {
    pub name: String,
    pub payload: serde_json::Value,
}

/// Cheap to clone; every clone publishes to the same channels.
#[derive(Clone, Debug)]
pub struct EventBus {
    frames: broadcast::Sender<Envelope>,
    events: broadcast::Sender<NamedEvent>,
}

impl EventBus {
    /// `capacity` is per channel; a slow subscriber that falls behind gets `Lagged`.
    pub fn new(capacity: usize) -> Self {
        let capacity = capacity.max(1);
        EventBus { frames: broadcast::channel(capacity).0, events: broadcast::channel(capacity).0 }
    }

    /// Publishes a frame. Returns how many subscribers got it (0 when nobody listens).
    pub fn publish(&self, env: Envelope) -> usize {
        self.frames.send(env).unwrap_or(0)
    }

    pub fn subscribe(&self) -> broadcast::Receiver<Envelope> {
        self.frames.subscribe()
    }

    /// Emits a named event. Returns how many subscribers got it.
    pub fn emit(&self, name: impl Into<String>, payload: serde_json::Value) -> usize {
        self.events.send(NamedEvent { name: name.into(), payload }).unwrap_or(0)
    }

    pub fn subscribe_events(&self) -> broadcast::Receiver<NamedEvent> {
        self.events.subscribe()
    }
}

impl Default for EventBus {
    fn default() -> Self {
        EventBus::new(1024)
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn frames_and_named_events_fan_out() {
        let bus = EventBus::new(8);
        assert_eq!(bus.publish(Envelope::new("x", "")), 0);
        let mut a = bus.subscribe();
        let mut b = bus.clone().subscribe();
        let mut ev = bus.subscribe_events();
        assert_eq!(bus.publish(Envelope::new("turn", "hi")), 2);
        assert_eq!(a.try_recv().unwrap().data, "hi");
        assert_eq!(b.try_recv().unwrap().r#type, "turn");
        bus.emit("session.ended", serde_json::json!({"id": "s1"}));
        let e = ev.try_recv().unwrap();
        assert_eq!(e.name, "session.ended");
        assert_eq!(e.payload["id"], "s1");
    }
}
