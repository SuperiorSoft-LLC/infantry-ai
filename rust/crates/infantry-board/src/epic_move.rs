// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
//! PARITY-AUDIT-1 #3: move tickets between epics, one or many (Kotlin `EpicMove`, `infantry_ticket_epic`).
//!
//! Every id is resolved before anything is written: one unknown ticket (or unknown epic id) refuses the whole call. A
//! move is a `ticket_save` of `epicId` alone, so status, order, branch and worker are kept. An in_progress ticket whose
//! epic base differs from the new one is refused (its branch was cut from the old base) unless `force`. An unknown epic
//! NAME is created in the tickets' project, only when something moves.
use infantry_orchestrator_api::dto::{status, Epic, EpicSave, TicketSave};
use infantry_orchestrator_api::OrchError;
use infantry_protocol::Envelope;
use serde::Serialize;

use crate::board::Board;
use crate::tools::short;

/// The target that clears the epic.
pub const NONE: &str = "none";

#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct Refusal {
    pub ticket_id: String,
    pub reason: String,
}

#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct EpicMoveOutcome {
    pub epic_id: Option<String>,
    pub epic_name: Option<String>,
    pub created: bool,
    pub moved: Vec<String>,
    pub unchanged: Vec<String>,
    pub refused: Vec<Refusal>,
    pub warnings: Vec<String>,
    pub message: String,
}

fn label(base: &str) -> String {
    if base.is_empty() { "the project's base".into() } else { format!("'{base}'") }
}

impl Board {
    /// Moves `ids` (full ids or suffixes) to `target` (epic id, epic name, or `none`).
    pub fn epic_move(&self, ids: &[String], target: &str, force: bool, publish: &dyn Fn(Envelope)) -> Result<EpicMoveOutcome, OrchError> {
        let mut wanted: Vec<String> = ids.iter().map(|x| x.trim().to_string()).filter(|x| !x.is_empty()).collect();
        wanted.dedup();
        if wanted.is_empty() {
            return Err(OrchError::Refused("name at least one ticket".into()));
        }
        let mut found = Vec::new();
        for id in &wanted {
            let t = self.find(id)?;
            if !found.iter().any(|x: &infantry_orchestrator_api::dto::Ticket| x.id == t.id) {
                found.push(t);
            }
        }
        let name = target.trim();
        let clear = name.is_empty() || name.eq_ignore_ascii_case(NONE);
        let mut projects: Vec<Option<String>> = found.iter().map(|t| t.project_id.clone()).collect();
        projects.sort();
        projects.dedup();
        if !clear && projects.len() != 1 {
            return Err(OrchError::Refused(format!("the tickets belong to {} projects; an epic belongs to one, so move them per project", projects.len())));
        }
        let project = projects.into_iter().next().flatten();
        let existing: Option<Epic> = if clear {
            None
        } else {
            let Some(p) = project.as_deref() else {
                return Err(OrchError::Refused("the tickets have no project, so an epic has nowhere to live".into()));
            };
            let known = self.epics(p)?;
            let hit = known.iter().find(|e| e.id == name).or_else(|| known.iter().find(|e| e.name.eq_ignore_ascii_case(name))).cloned();
            if hit.is_none() && name.starts_with("epic-") {
                return Err(OrchError::Refused(format!("no epic '{name}' in project {p}")));
            }
            hit
        };
        let new_base = existing.as_ref().map(|e| e.base_branch.clone()).unwrap_or_default();
        let (mut unchanged, mut refused, mut warnings, mut to_move) = (Vec::new(), Vec::new(), Vec::new(), Vec::new());
        for t in found {
            let same = if clear { t.epic_id.is_none() } else { existing.as_ref().is_some_and(|e| t.epic_id.as_deref() == Some(e.id.as_str())) };
            if same {
                unchanged.push(t.id);
                continue;
            }
            let old_base = t.epic_id.as_deref().and_then(|id| self.epic(id).ok()).map(|e| e.base_branch).unwrap_or_default();
            if t.status == status::IN_PROGRESS && old_base != new_base {
                let why = format!(
                    "in progress on a branch cut from {}; the {} cuts from {}",
                    label(&old_base),
                    if clear { "project" } else { "new epic" },
                    label(&new_base)
                );
                if !force {
                    refused.push(Refusal { ticket_id: t.id, reason: format!("{why}: finish or unassign it first, or pass force (its branch keeps its base)") });
                    continue;
                }
                warnings.push(format!("{}: {why}; moved anyway (force), its branch keeps its old base", short(&t.id)));
            }
            to_move.push(t);
        }
        let mut created = false;
        let epic = match (clear, existing) {
            (true, _) => None,
            (false, Some(e)) => Some(e),
            (false, None) if to_move.is_empty() => None,
            (false, None) => {
                created = true;
                Some(self.epic_save(EpicSave { project_id: project.clone().unwrap_or_default(), name: name.to_string(), goal: String::new() })?)
            }
        };
        let mut moved = Vec::new();
        for t in to_move {
            let req = TicketSave { id: Some(t.id.clone()), epic_id: Some(epic.as_ref().map(|e| e.id.clone()).unwrap_or_default()), ..Default::default() };
            moved.push(self.save(req, publish)?.ticket.id);
        }
        let where_ = match &epic {
            Some(e) => format!("epic {} ({})", e.name, e.id),
            None if clear => "no epic".into(),
            None => format!("epic '{name}'"),
        };
        let mut message = format!("Moved {} ticket(s) to {where_}", moved.len());
        if created {
            message.push_str(", created it");
        }
        if !moved.is_empty() {
            message.push_str(&format!(": {}", moved.iter().map(|x| short(x)).collect::<Vec<_>>().join(", ")));
        }
        message.push('.');
        if !unchanged.is_empty() {
            message.push_str(&format!(" Already there: {}.", unchanged.iter().map(|x| short(x)).collect::<Vec<_>>().join(", ")));
        }
        for r in &refused {
            message.push_str(&format!("\nNot moved {}: {}", short(&r.ticket_id), r.reason));
        }
        for w in &warnings {
            message.push_str(&format!("\nWarning {w}"));
        }
        Ok(EpicMoveOutcome { epic_id: epic.as_ref().map(|e| e.id.clone()), epic_name: epic.map(|e| e.name), created, moved, unchanged, refused, warnings, message })
    }
}
