// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
//! Infantry AI roles, traits, skills, layered skills, the prompt library and worker avatars (RUST-KNOW-1).
//!
//! An L1 platform crate (it reads role files and avatar files from the state dir, which the WASM plugin grants do
//! not reach, and the start brief needs it in process). The project knowledge itself (memory, decisions, issues)
//! is the Infantry_Notes WASM plugin; [`brief::KnowledgeSource`] is the seam the brief reads it through.
//!
//! Composition root wiring:
//! ```ignore
//! let k = Arc::new(Knowledge::open(db, &state_dir, ports));          // ports: impl DaemonPorts
//! for u in k.units() { registry.register(u); }                       // RolesUnit + LibraryUnit
//! router = router.merge(infantry_knowledge::avatars::router(k.avatars.clone()));   // GET /avatars/{id}
//! let briefs = Briefs::new(k.roles.clone(), k.library.clone(), Some(notes_source), settings);
//! // BoardEnv::instructions -> briefs.instructions(role, &tools, &cards)
//! // BoardEnv::brief_sections -> briefs.sections(&BriefInput { .. })
//! // agent tools: tools::descriptors() / tools::call(&k, name, &args, &caller)
//! ```
pub mod avatars;
pub mod brief;
pub mod ids;
pub mod library;
pub mod roles;
pub mod tools;
pub mod unit;

pub use brief::{BriefInput, Briefs, KnowledgeSource};
pub use unit::{DaemonPorts, Knowledge, LibraryUnit, NoPorts, RolesUnit};
