// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
//! The start-brief sections this crate feeds: the role's instructions (persona + preferred tools/cards), its traits,
//! the project knowledge and role notes (from a [`KnowledgeSource`], the Notes plugin), the skills and the layered
//! skills. [`Briefs::instructions`] and [`Briefs::sections`] are what the daemon's `BoardEnv::instructions` /
//! `BoardEnv::brief_sections` call.
use std::collections::HashSet;
use std::sync::Arc;

use infantry_core::settings::Settings;

use crate::library::{Library, SkillScope, SkillView, TraitView};
use crate::roles::{briefing, RoleService};

pub const TRAITS_HEADER: &str = "Standing rules bound to your role (they apply to this whole session):";
pub const SKILLS_HEADER: &str = "SKILLS (this applies to your whole session, including after a context compaction).";
pub const LAYERED_HEADER: &str =
    "LAYERED SKILLS (global -> project -> role; a narrower layer overrides a wider one of the same name; follow them for your whole session).";
/// The setting bounding a layered skill inlined in full (`LayerSkillFrames.INLINE_MAX_SETTING`).
pub const INLINE_MAX_SETTING: &str = "limits.skills.inlineMaxChars";
pub const INLINE_MAX_DEFAULT: usize = 1_500;
/// The project switch that turns the skills section off (ticket override first).
pub const INCLUDE_SKILLS_SETTING: &str = "context.includeSkillsContext";

/// Where the knowledge comes from: the Notes plugin (via the daemon), behind a seam so the brief never names it.
pub trait KnowledgeSource: Send + Sync {
    /// The rendered PROJECT KNOWLEDGE section of `project` (counts + digest), None when unavailable.
    fn knowledge(&self, project: &str) -> Option<String>;
    /// The role notes of `role` in `project`: (name, description), by name.
    fn role_notes(&self, project: &str, role: &str) -> Vec<(String, String)>;
}

/// The hard ceilings of the `skills` section (P10-K15).
#[derive(Debug, Clone, Copy)]
pub struct SkillBudgets {
    pub body_chars: usize,
    pub index_lines: usize,
    pub index_chars: usize,
}

impl Default for SkillBudgets {
    fn default() -> Self {
        SkillBudgets { body_chars: 24_000, index_lines: 40, index_chars: 6_000 }
    }
}

fn len(s: &str) -> usize {
    s.chars().count()
}

fn skill_line(s: &SkillView) -> String {
    let d = s.description.replace(['\r', '\n'], " ");
    let d = d.trim();
    if d.is_empty() {
        s.name.clone()
    } else {
        format!("{}: {d}", s.name)
    }
}

/// The `skills` section: attached skills in full while they fit, then a one-line index of the rest. None = nothing.
pub fn render_skills(attached: &[String], library: &[SkillView], b: SkillBudgets) -> Option<String> {
    let mut ids: Vec<&str> = Vec::new();
    for a in attached.iter().map(|a| a.trim()).filter(|a| !a.is_empty()) {
        if !ids.contains(&a) {
            ids.push(a);
        }
    }
    let (mut inlined, mut refs, mut used) = (Vec::<String>::new(), Vec::<String>::new(), 0usize);
    for id in &ids {
        match library.iter().find(|s| s.id == *id) {
            None => refs.push(format!("- {id} (deleted)")),
            Some(s) => {
                let desc = s.description.trim();
                let block = format!("### Skill: {}{}\n{}", s.name, if desc.is_empty() { String::new() } else { format!("\n{desc}") }, s.body);
                if used + len(&block) + 1 <= b.body_chars {
                    used += len(&block) + 1;
                    inlined.push(block);
                } else {
                    refs.push(format!("- {} (attached to you, too long to inline: run `infantry skill show {}`)", skill_line(s), s.name));
                }
            }
        }
    }
    let attached_refs = refs.len();
    let mut rest: Vec<&SkillView> = library.iter().filter(|s| !ids.contains(&s.id.as_str()) && !s.attached_only).collect();
    rest.sort_by(|a, b| a.name.cmp(&b.name));
    refs.extend(rest.into_iter().map(|s| format!("- {}", skill_line(s))));
    if inlined.is_empty() && refs.is_empty() {
        return None;
    }
    let (mut shown, mut iused) = (Vec::<String>::new(), 0usize);
    for r in &refs {
        if shown.len() >= b.index_lines || iused + len(r) + 1 > b.index_chars {
            break;
        }
        iused += len(r) + 1;
        shown.push(r.clone());
    }
    let hidden = refs.len() - shown.len();
    let split = attached_refs.min(shown.len());
    let (shown_attached, shown_index) = shown.split_at(split);
    let mut out = vec![SKILLS_HEADER.to_string()];
    if !inlined.is_empty() || !shown_attached.is_empty() {
        out.push("Attached to you (always available; follow a skill whenever its description applies to your work):".into());
        out.extend(inlined);
        out.extend(shown_attached.iter().cloned());
    }
    if !shown_index.is_empty() {
        out.push(
            "Other skills in the Infantry AI daemon, one `name: description` line each. When a description matches your work, run `infantry skill show <name>` and follow it:"
                .into(),
        );
        out.extend(shown_index.iter().cloned());
    }
    if hidden > 0 {
        out.push(format!("- {hidden} more: `infantry skill list`"));
    }
    Some(out.join("\n"))
}

/// The `layeredSkills` section. None = no layered skill applies.
pub fn render_layered(skills: &[(SkillScope, crate::library::LayerSkill)], inline_max: usize) -> Option<String> {
    if skills.is_empty() {
        return None;
    }
    let mut out = vec![LAYERED_HEADER.to_string()];
    for (scope, s) in skills {
        let label = scope.layer();
        let desc = s.description.replace('\n', " ");
        let desc = desc.trim();
        if len(&s.body) <= inline_max {
            out.push(format!("### Skill: {} [{label}]{}\n{}", s.name, if desc.is_empty() { String::new() } else { format!("\n{desc}") }, s.body));
        } else {
            out.push(format!(
                "- {} [{label}]: {} (long: run `infantry skill show {} --scope {label}`)",
                s.name,
                if desc.is_empty() { "(no description)" } else { desc },
                s.name
            ));
        }
    }
    Some(out.join("\n"))
}

/// The `traits` section. None = no traits bound.
pub fn render_traits(traits: &[TraitView]) -> Option<String> {
    if traits.is_empty() {
        return None;
    }
    let mut parts = vec![TRAITS_HEADER.to_string()];
    parts.extend(traits.iter().map(|t| format!("## {} ({} v{})\n{}", t.name, t.id, t.version, t.body)));
    Some(parts.join("\n\n"))
}

/// The `roleNotes` section. None = the role has no notes in the project.
pub fn render_role_notes(role: &str, notes: &[(String, String)]) -> Option<String> {
    if notes.is_empty() {
        return None;
    }
    let list = notes.iter().map(|(n, d)| format!("- {n}: {d}")).collect::<Vec<_>>().join("\n");
    Some(format!(
        "ROLE NOTES (scope role: notes for the '{role}' role, they persist with the role and only sessions of that role see them):\n{list}\n\
         Session notes (scope session) are yours alone and are deleted when this session is removed. \
         Use `infantry memory index|show|search|save --scope role|session`; the default scope is the shared project notes."
    ))
}

/// What one start brief needs beyond project + role.
#[derive(Debug, Clone, Default)]
pub struct BriefInput<'a> {
    pub project_id: Option<&'a str>,
    pub role: Option<&'a str>,
    /// The traits the agent profile adds to its role's.
    pub agent_trait_ids: &'a [String],
    /// The session's frozen skill ids; None with no role = an anonymous spawn (no skills section).
    pub skill_ids: Option<&'a [String]>,
    /// The ticket's resolved `includeSkillsContext` (None = the project setting, else true).
    pub include_skills: Option<bool>,
}

/// The brief composer's view of roles, libraries and knowledge.
pub struct Briefs {
    pub roles: Arc<RoleService>,
    pub library: Arc<Library>,
    pub knowledge: Option<Arc<dyn KnowledgeSource>>,
    pub settings: Arc<dyn Settings>,
    pub budgets: SkillBudgets,
}

impl Briefs {
    pub fn new(roles: Arc<RoleService>, library: Arc<Library>, knowledge: Option<Arc<dyn KnowledgeSource>>, settings: Arc<dyn Settings>) -> Briefs {
        Briefs { roles, library, knowledge, settings, budgets: SkillBudgets::default() }
    }

    /// `BoardEnv::instructions`: the role's persona plus its preferred tools / cards that are loaded now.
    pub fn instructions(&self, role: Option<&str>, tools: &HashSet<String>, cards: &HashSet<String>) -> Option<String> {
        let id = crate::ids::normalize(role?.trim());
        let r = self.roles.role(&id)?;
        Some(briefing(&r, tools, cards)).filter(|t| !t.trim().is_empty())
    }

    /// `BoardEnv::brief_sections`: (source, text) in order traits -> knowledge -> roleNotes -> skills -> layeredSkills.
    pub fn sections(&self, input: &BriefInput) -> Vec<(String, String)> {
        let mut out = Vec::new();
        let project = input.project_id.map(str::trim).filter(|p| !p.is_empty());
        let role = input.role.map(|r| crate::ids::normalize(r.trim())).filter(|r| !r.is_empty());
        if let Some(r) = &role {
            if let Some(t) = self.library.effective_traits(r, input.agent_trait_ids).ok().and_then(|t| render_traits(&t)) {
                out.push(("traits".into(), t));
            }
        }
        if let (Some(p), Some(k)) = (project, &self.knowledge) {
            if let Some(text) = k.knowledge(p) {
                out.push(("knowledge".into(), text));
            }
            if let Some(r) = &role {
                if let Some(text) = render_role_notes(r, &k.role_notes(p, r)) {
                    out.push(("roleNotes".into(), text));
                }
            }
        }
        let include = input.include_skills.or_else(|| self.settings.get_bool(INCLUDE_SKILLS_SETTING)).unwrap_or(true);
        let attached: Option<Vec<String>> = input.skill_ids.map(<[String]>::to_vec).or_else(|| role.as_ref().map(|_| vec![]));
        if let (true, Some(ids)) = (include, attached) {
            if let Some(text) = self.library.skill_list(None).ok().and_then(|lib| render_skills(&ids, &lib, self.budgets)) {
                out.push(("skills".into(), text));
            }
        }
        let max = self.settings.get_i64(INLINE_MAX_SETTING).map(|n| n.max(0) as usize).unwrap_or(INLINE_MAX_DEFAULT);
        if let Some(text) = self.library.layer_resolve(project, role.as_deref()).ok().and_then(|l| render_layered(&l, max)) {
            out.push(("layeredSkills".into(), text));
        }
        out
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn skill(id: &str, name: &str, body: &str, attached_only: bool) -> SkillView {
        SkillView { id: id.into(), name: name.into(), description: "d".into(), body: body.into(), tags: vec![], created_at: 0, updated_at: 0, attached_only }
    }

    #[test]
    fn skills_section_inlines_attached_and_indexes_rest() {
        let lib = vec![skill("1", "alpha", "A body", false), skill("2", "beta", "B", false), skill("3", "secret", "S", true)];
        let s = render_skills(&["1".into(), "9".into()], &lib, SkillBudgets::default()).unwrap();
        assert!(s.starts_with(SKILLS_HEADER));
        assert!(s.contains("### Skill: alpha\nd\nA body"));
        assert!(s.contains("- 9 (deleted)"));
        assert!(s.contains("- beta: d") && !s.contains("secret"));
        assert!(render_skills(&[], &[], SkillBudgets::default()).is_none());
        let tight = render_skills(&[], &lib, SkillBudgets { body_chars: 0, index_lines: 1, index_chars: 100 }).unwrap();
        assert!(tight.ends_with("- 1 more: `infantry skill list`"));
    }

    #[test]
    fn traits_and_role_notes_text() {
        let t = TraitView { id: "terse".into(), name: "Terse".into(), version: 2, body: "Be brief.".into(), check: None, created_at: 0, updated_at: 0 };
        assert_eq!(render_traits(&[t]).unwrap(), format!("{TRAITS_HEADER}\n\n## Terse (terse v2)\nBe brief."));
        assert!(render_role_notes("qa", &[("flaky".into(), "known flakes".into())]).unwrap().contains("- flaky: known flakes\nSession notes"));
        assert!(render_role_notes("qa", &[]).is_none());
    }
}
