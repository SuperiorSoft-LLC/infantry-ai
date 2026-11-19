// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
//! Approval rules: what `allow_always` remembers per project. A non-shell tool's rule allows every call of that
//! tool; a shell tool's (`Bash`, `PowerShell`) allows calls whose first word is the rule's prefix and that carry no
//! shell control (chaining, piping, substitution, redirection, a second line).
use std::sync::{Arc, Mutex};

use infantry_core::db::{Db, DbError};
use infantry_protocol::prompts::ApprovalRule;
use regex::Regex;
use rusqlite::{params, OptionalExtension, Row};

pub const TABLE: &str = "infantry_approvals_rules";
pub const SHELL_TOOLS: [&str; 2] = ["Bash", "PowerShell"];

fn row(r: &Row) -> rusqlite::Result<ApprovalRule> {
    Ok(ApprovalRule { rule_id: r.get(0)?, project_id: r.get(1)?, tool: r.get(2)?, prefix: r.get(3)?, created_at: r.get(4)? })
}

/// The command's first word, or None for a blank command.
pub fn first_word(input: &str) -> Option<String> {
    input.split_whitespace().next().map(str::to_string)
}

pub struct ApprovalRules {
    db: Arc<Db>,
    clock: Arc<dyn Fn() -> i64 + Send + Sync>,
    shell_control: Regex,
    lock: Mutex<()>,
}

impl ApprovalRules {
    pub fn new(db: Arc<Db>, clock: Arc<dyn Fn() -> i64 + Send + Sync>) -> Result<Self, DbError> {
        db.write(|c| {
            c.execute_batch(&format!(
                "CREATE TABLE IF NOT EXISTS {TABLE} (rule_id TEXT NOT NULL PRIMARY KEY, project_id TEXT NOT NULL, tool TEXT NOT NULL, prefix TEXT, created_at INTEGER NOT NULL);
                 CREATE INDEX IF NOT EXISTS {TABLE}_project ON {TABLE} (project_id, created_at);"
            ))
        })?;
        Ok(ApprovalRules { db, clock, shell_control: Regex::new(r"[;&|`<>\r\n]|\$\(").unwrap(), lock: Mutex::new(()) })
    }

    fn rows_of(&self, project: &str) -> Vec<ApprovalRule> {
        self.db
            .read(|c| c.prepare(&format!("SELECT rule_id, project_id, tool, prefix, created_at FROM {TABLE} WHERE project_id = ?1 ORDER BY created_at, rule_id"))?.query_map([project], row)?.collect())
            .unwrap_or_default()
    }

    /// The rule of `project` that allows `tool` called with `input`.
    pub fn matches(&self, project: Option<&str>, tool: &str, input: &str) -> Option<ApprovalRule> {
        let project = project.map(str::trim).filter(|p| !p.is_empty())?;
        let rules = self.rows_of(project);
        if !SHELL_TOOLS.contains(&tool) {
            return rules.into_iter().find(|r| r.tool == tool && r.prefix.is_none());
        }
        if self.shell_control.is_match(input) {
            return None;
        }
        let word = first_word(input)?;
        rules.into_iter().find(|r| r.tool == tool && r.prefix.as_deref() == Some(word.as_str()))
    }

    /// Stores the rule `allow_always` makes (or returns the same one already stored). None = no rule can be made.
    pub fn add(&self, project: Option<&str>, tool: &str, input: &str) -> Option<ApprovalRule> {
        let project = project.map(str::trim).filter(|p| !p.is_empty())?;
        let name = tool.trim();
        if name.is_empty() {
            return None;
        }
        let prefix = if SHELL_TOOLS.contains(&name) { Some(first_word(input)?) } else { None };
        let _g = self.lock.lock().unwrap();
        if let Some(r) = self.rows_of(project).into_iter().find(|r| r.tool == name && r.prefix == prefix) {
            return Some(r);
        }
        let rule = ApprovalRule { rule_id: uuid::Uuid::new_v4().to_string(), project_id: project.into(), tool: name.into(), prefix, created_at: (self.clock)() };
        self.db
            .write(|c| c.execute(&format!("INSERT INTO {TABLE} (rule_id, project_id, tool, prefix, created_at) VALUES (?1, ?2, ?3, ?4, ?5)"), params![rule.rule_id, rule.project_id, rule.tool, rule.prefix, rule.created_at]))
            .ok()?;
        Some(rule)
    }

    /// The rules of `project`, oldest first; None = every project's.
    pub fn list(&self, project: Option<&str>) -> Vec<ApprovalRule> {
        match project.map(str::trim).filter(|p| !p.is_empty()) {
            Some(p) => self.rows_of(p),
            None => self
                .db
                .read(|c| c.prepare(&format!("SELECT rule_id, project_id, tool, prefix, created_at FROM {TABLE} ORDER BY project_id, created_at, rule_id"))?.query_map([], row)?.collect())
                .unwrap_or_default(),
        }
    }

    pub fn delete(&self, rule_id: &str) -> Result<ApprovalRule, String> {
        let id = rule_id.trim();
        if id.is_empty() {
            return Err("ruleId is required".into());
        }
        let _g = self.lock.lock().unwrap();
        let found = self
            .db
            .read(|c| c.query_row(&format!("SELECT rule_id, project_id, tool, prefix, created_at FROM {TABLE} WHERE rule_id = ?1"), [id], row).optional())
            .map_err(|e| e.to_string())?
            .ok_or_else(|| format!("unknown rule '{}'", id.chars().take(80).collect::<String>()))?;
        self.db.write(|c| c.execute(&format!("DELETE FROM {TABLE} WHERE rule_id = ?1"), [id])).map_err(|e| e.to_string())?;
        Ok(found)
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn shell_prefix_rules() {
        let dir = tempfile::tempdir().unwrap();
        let r = ApprovalRules::new(Arc::new(Db::open(dir.path().join("a.db")).unwrap()), Arc::new(|| 1)).unwrap();
        let rule = r.add(Some("p"), "Bash", "git status").unwrap();
        assert_eq!(rule.prefix.as_deref(), Some("git"));
        assert_eq!(r.add(Some("p"), "Bash", "git log").unwrap().rule_id, rule.rule_id);
        assert!(r.matches(Some("p"), "Bash", "git diff").is_some());
        assert!(r.matches(Some("p"), "Bash", "git diff | rm -rf x").is_none());
        assert!(r.matches(Some("q"), "Bash", "git diff").is_none());
        r.add(Some("p"), "Read", "/x");
        assert!(r.matches(Some("p"), "Read", "anything").is_some());
        assert_eq!(r.delete("nope").unwrap_err(), "unknown rule 'nope'");
        r.delete(&rule.rule_id).unwrap();
        assert_eq!(r.list(None).len(), 1);
    }
}
