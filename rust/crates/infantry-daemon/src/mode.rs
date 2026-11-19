// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
//! RUNNER-POOL-10: the deployment mode, `--mode both|runner|orchestrator` (then `INFANTRY_MODE`). A runner daemon runs
//! sessions for a remote orchestrator and holds no board: the orchestration units' frames are refused with the reason.

use infantry_core::frames::{FrameCtx, FrameUnit, Refusal};
use infantry_protocol::Envelope;
use std::sync::Arc;

#[derive(Debug, Clone, Copy, PartialEq, Eq, Default)]
pub enum Mode {
    #[default]
    Both,
    Runner,
    Orchestrator,
}

impl Mode {
    pub fn id(self) -> &'static str {
        match self {
            Mode::Both => "both",
            Mode::Runner => "runner",
            Mode::Orchestrator => "orchestrator",
        }
    }

    /// The first non-blank source wins; none = both; any other value is refused with the choices.
    pub fn resolve(sources: &[Option<&str>]) -> Result<Mode, String> {
        let Some(v) = sources.iter().flatten().map(|s| s.trim()).find(|s| !s.is_empty()) else { return Ok(Mode::Both) };
        match v.to_lowercase().as_str() {
            "both" => Ok(Mode::Both),
            "runner" => Ok(Mode::Runner),
            "orchestrator" => Ok(Mode::Orchestrator),
            _ => Err(format!("unknown daemon mode `{v}` (both | runner | orchestrator)")),
        }
    }

    /// Whether this daemon is the board (tickets, pipeline, pods, mail, knowledge records).
    pub fn has_board(self) -> bool {
        self != Mode::Runner
    }
}

/// An orchestration unit on a runner daemon: it keeps its frames so each one is refused with the reason, never unknown.
pub struct ModeGated {
    pub inner: Arc<dyn FrameUnit>,
}

impl FrameUnit for ModeGated {
    fn name(&self) -> &'static str {
        self.inner.name()
    }

    fn frames(&self) -> &'static [&'static str] {
        self.inner.frames()
    }

    fn handle(&self, env: &Envelope, _ctx: &FrameCtx) -> Result<Vec<Envelope>, Refusal> {
        Err(Refusal::new(&env.r#type, "this daemon runs in runner mode (--mode runner); the board, tickets and mail live on the orchestrator"))
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn the_first_non_blank_source_wins_and_a_bad_value_is_refused() {
        assert_eq!(Mode::resolve(&[None, Some(" ")]), Ok(Mode::Both));
        assert_eq!(Mode::resolve(&[Some("runner"), Some("orchestrator")]), Ok(Mode::Runner));
        assert_eq!(Mode::resolve(&[None, Some("Orchestrator")]), Ok(Mode::Orchestrator));
        assert!(Mode::resolve(&[Some("server")]).unwrap_err().contains("both | runner | orchestrator"));
        assert!(!Mode::Runner.has_board() && Mode::Both.has_board());
    }
}
