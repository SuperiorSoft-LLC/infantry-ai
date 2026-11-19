// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
//! One trigger's guard (Kotlin `TriggerGuard` + `TriggerCoalescer`, per trigger): a fixed coalescing window, one run
//! in flight, one follow-up slot, an hourly ceiling of starts, and a trip on any non-success. Nothing is retried; a
//! tripped guard stays tripped (the service builds a fresh guard to re-arm). Pure: time comes in, effects go out as
//! [`Out`] values for the service to perform.
use std::collections::{HashSet, VecDeque};
use std::sync::atomic::{AtomicU64, Ordering};

/// One process-wide run sequence (BUG-TriggerRunIds): run ids never collide across triggers or re-armed guards.
static RUN_SEQUENCE: AtomicU64 = AtomicU64::new(0);

pub const ATTENTION_CEILING: &str = "ceiling";
pub const ATTENTION_TRIPPED: &str = "trigger_tripped";

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Outcome {
    Succeeded,
    Failed,
    Killed,
    StartRefused,
}

impl Outcome {
    pub fn name(self) -> &'static str {
        match self {
            Outcome::Succeeded => "SUCCEEDED",
            Outcome::Failed => "FAILED",
            Outcome::Killed => "KILLED",
            Outcome::StartRefused => "START_REFUSED",
        }
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum FireResult {
    Started,
    Folded,
    FollowupFolded,
    RefusedCeiling,
    SuppressedTripped,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Run {
    pub run_id: String,
    pub trigger_id: String,
    pub first_event: String,
    pub last_event: String,
}

impl Run {
    /// The events a run carries (the first and last of its window).
    pub fn events(&self) -> Vec<String> {
        if self.first_event == self.last_event {
            vec![self.first_event.clone()]
        } else {
            vec![self.first_event.clone(), self.last_event.clone()]
        }
    }
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Trip {
    pub attention_kind: &'static str,
    pub outcome: String,
    pub detail: String,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub enum Out {
    Launch(Run),
    Trip(Trip),
    /// The events of a run that never launched (tripped or over the ceiling at release), with their disposition.
    Relabel(Vec<String>, &'static str),
}

#[derive(Debug, Clone)]
struct Pending {
    count: u32,
    first: String,
    last: String,
}

#[derive(Debug)]
pub struct Guard {
    trigger_id: String,
    tripped: bool,
    starts: VecDeque<i64>,
    runs: HashSet<String>,
    window_started_at: Option<i64>,
    pending: Option<Pending>,
    in_flight: Option<String>,
    followup: Option<Pending>,
    followup_events: Vec<(String, i64)>,
    failed: bool,
    /// Follow-up events dropped by a failed run: (event id, received at).
    pub dropped: Vec<(String, i64)>,
    coalesce_ms: i64,
    ceiling_runs: i64,
    ceiling_window_ms: i64,
}

impl Guard {
    pub fn new(trigger_id: &str, coalesce_ms: i64, ceiling_runs: i64, ceiling_window_ms: i64) -> Guard {
        Guard {
            trigger_id: trigger_id.into(),
            tripped: false,
            starts: VecDeque::new(),
            runs: HashSet::new(),
            window_started_at: None,
            pending: None,
            in_flight: None,
            followup: None,
            followup_events: Vec::new(),
            failed: false,
            dropped: Vec::new(),
            coalesce_ms,
            ceiling_runs,
            ceiling_window_ms,
        }
    }

    pub fn is_tripped(&self) -> bool {
        self.tripped
    }

    pub fn has_run(&self, run_id: &str) -> bool {
        self.runs.contains(run_id)
    }

    fn expire(&mut self, now: i64) {
        while self.starts.front().is_some_and(|t| *t <= now - self.ceiling_window_ms) {
            self.starts.pop_front();
        }
    }

    fn trip(&mut self, out: &mut Vec<Out>, kind: &'static str, outcome: &str, detail: String) {
        if self.tripped {
            return;
        }
        self.tripped = true;
        out.push(Out::Trip(Trip { attention_kind: kind, outcome: outcome.into(), detail }));
    }

    fn ceiling_detail(&self) -> String {
        format!("Hourly trigger run ceiling of {} starts was reached.", self.ceiling_runs)
    }

    /// The coalescer's release of the pending window: launched, or refused at the gate.
    fn start_pending(&mut self, now: i64, out: &mut Vec<Out>) {
        let Some(p) = self.pending.take() else { return };
        self.window_started_at = None;
        let run_id = format!("trigger-run-{}", RUN_SEQUENCE.fetch_add(1, Ordering::Relaxed) + 1);
        self.in_flight = Some(run_id.clone());
        let run = Run { run_id: run_id.clone(), trigger_id: self.trigger_id.clone(), first_event: p.first, last_event: p.last };
        self.expire(now);
        if self.tripped {
            out.push(Out::Relabel(run.events(), crate::model::disposition::SUPPRESSED_TRIPPED));
        } else if self.starts.len() as i64 >= self.ceiling_runs {
            let d = self.ceiling_detail();
            self.trip(out, ATTENTION_CEILING, "REFUSED_CEILING", d);
            out.push(Out::Relabel(run.events(), crate::model::disposition::REFUSED_CEILING));
        } else {
            self.starts.push_back(now);
            self.runs.insert(run_id);
            out.push(Out::Launch(run));
        }
    }

    /// One event.
    pub fn fire(&mut self, event_id: &str, now: i64, out: &mut Vec<Out>) -> FireResult {
        if self.tripped {
            return FireResult::SuppressedTripped;
        }
        self.expire(now);
        if self.starts.len() as i64 >= self.ceiling_runs {
            let d = self.ceiling_detail();
            self.trip(out, ATTENTION_CEILING, "REFUSED_CEILING", d);
            return FireResult::RefusedCeiling;
        }
        if self.failed {
            return FireResult::SuppressedTripped;
        }
        if self.in_flight.is_some() {
            self.followup = Some(match self.followup.take() {
                Some(p) => Pending { count: p.count + 1, first: p.first, last: event_id.into() },
                None => Pending { count: 1, first: event_id.into(), last: event_id.into() },
            });
            self.followup_events.push((event_id.into(), now));
            return FireResult::FollowupFolded;
        }
        let count = match self.pending.take() {
            None => {
                self.window_started_at = Some(now);
                self.pending = Some(Pending { count: 1, first: event_id.into(), last: event_id.into() });
                1
            }
            Some(p) => {
                let c = p.count + 1;
                self.pending = Some(Pending { count: c, first: p.first, last: event_id.into() });
                c
            }
        };
        if self.coalesce_ms <= 0 {
            self.start_pending(now, out);
            FireResult::Started
        } else if count == 1 {
            FireResult::Started
        } else {
            FireResult::Folded
        }
    }

    /// Releases the window once it has elapsed. True = a run was started (or refused at the gate).
    pub fn pump(&mut self, now: i64, out: &mut Vec<Out>) -> bool {
        let open = self.window_started_at;
        if !self.failed && self.in_flight.is_none() && self.pending.is_some() && open.is_some_and(|at| now - at >= self.coalesce_ms) {
            self.start_pending(now, out);
            return true;
        }
        false
    }

    /// A run ended. False = unknown run.
    pub fn complete(&mut self, run_id: &str, outcome: Outcome, now: i64, out: &mut Vec<Out>) -> bool {
        if !self.runs.contains(run_id) || self.in_flight.as_deref() != Some(run_id) {
            return false;
        }
        self.in_flight = None;
        self.runs.remove(run_id);
        if outcome == Outcome::Succeeded {
            if let Some(f) = self.followup.take() {
                self.pending = Some(f);
                self.window_started_at = Some(now);
            }
            self.followup_events.clear();
        } else {
            self.failed = true;
            self.followup = None;
            self.dropped.append(&mut self.followup_events);
            let detail = format!("Trigger run {run_id} ended with {}; automatic retries are disabled.", outcome.name());
            self.trip(out, ATTENTION_TRIPPED, outcome.name(), detail);
        }
        true
    }

    /// TRIGGER-NUDGE: a run that started no session gives back its start against the ceiling.
    pub fn refund_start(&mut self, run_id: &str) -> bool {
        if !self.runs.contains(run_id) {
            return false;
        }
        self.starts.pop_back().is_some()
    }

    pub fn running(&self) -> bool {
        !self.runs.is_empty()
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn launched(out: &[Out]) -> Vec<Run> {
        out.iter().filter_map(|o| if let Out::Launch(r) = o { Some(r.clone()) } else { None }).collect()
    }

    #[test]
    fn the_ceiling_trips_the_trigger() {
        let mut g = Guard::new("t", 0, 2, 3_600_000);
        let mut out = Vec::new();
        for (i, now) in [10, 20].iter().enumerate() {
            assert_eq!(g.fire(&format!("e{i}"), *now, &mut out), FireResult::Started);
            let run = launched(&out).pop().unwrap();
            out.clear();
            assert!(g.complete(&run.run_id, Outcome::Succeeded, *now, &mut out));
        }
        assert_eq!(g.fire("e3", 30, &mut out), FireResult::RefusedCeiling);
        assert!(g.is_tripped());
        assert!(matches!(&out[0], Out::Trip(t) if t.attention_kind == ATTENTION_CEILING));
        assert_eq!(g.fire("e4", 40, &mut out), FireResult::SuppressedTripped, "a tripped trigger stays tripped");
    }

    #[test]
    fn coalescing_folds_then_the_window_releases_one_run() {
        let mut g = Guard::new("t", 1_000, 6, 3_600_000);
        let mut out = Vec::new();
        assert_eq!(g.fire("a", 0, &mut out), FireResult::Started);
        assert_eq!(g.fire("b", 100, &mut out), FireResult::Folded);
        assert!(out.is_empty() && !g.pump(500, &mut out));
        assert!(g.pump(1_000, &mut out));
        let run = launched(&out).pop().unwrap();
        assert_eq!((run.first_event.as_str(), run.last_event.as_str()), ("a", "b"));
        assert_eq!(g.fire("c", 1_100, &mut out), FireResult::FollowupFolded);
    }

    #[test]
    fn a_failed_run_trips_and_drops_its_followup() {
        let mut g = Guard::new("t", 0, 6, 3_600_000);
        let mut out = Vec::new();
        g.fire("a", 0, &mut out);
        let run = launched(&out).pop().unwrap();
        g.fire("b", 5, &mut out);
        out.clear();
        assert!(g.complete(&run.run_id, Outcome::Failed, 10, &mut out));
        assert!(g.is_tripped() && matches!(&out[0], Out::Trip(t) if t.outcome == "FAILED"));
        assert_eq!(g.dropped, vec![("b".to_string(), 5)]);
    }

    #[test]
    fn a_refunded_start_does_not_count_toward_the_ceiling() {
        let mut g = Guard::new("t", 0, 1, 3_600_000);
        let mut out = Vec::new();
        g.fire("a", 0, &mut out);
        let run = launched(&out).pop().unwrap();
        assert!(g.refund_start(&run.run_id));
        g.complete(&run.run_id, Outcome::Succeeded, 1, &mut out);
        assert_eq!(g.fire("b", 2, &mut out), FireResult::Started);
    }
}
