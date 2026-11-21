// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
//! PM-ENFORCE-1: a role policy the daemon hands a session as data (`INFANTRY_ROLE_POLICY`, JSON), enforced on the
//! claude `PreToolUse` hook so a prompt that drifts cannot do the work itself. The harness names no role: the rules
//! (tools, git subcommands, command prefixes, the refusal text) come from the role plugin's policy file.
use serde::Deserialize;
use serde_json::Value;

/// The session env that carries the policy (absent = the session is not restricted).
pub const POLICY_ENV: &str = "INFANTRY_ROLE_POLICY";

#[derive(Debug, Clone, Default, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "camelCase", default)]
pub struct Policy {
    /// Tools refused outright (file writers).
    pub deny_tools: Vec<String>,
    /// `git` subcommands refused.
    pub deny_git: Vec<String>,
    /// Shell command prefixes refused (build and test suites).
    pub deny_commands: Vec<String>,
    /// The refusal text: names the worker to spawn and the spawn tool.
    pub message: String,
}

/// The shell tools whose `command` is inspected.
pub const SHELL_TOOLS: &[&str] = &["Bash", "PowerShell"];

impl Policy {
    pub fn from_env(env: &[(String, String)]) -> Option<Policy> {
        let raw = env.iter().rev().find(|(k, _)| k == POLICY_ENV)?.1.trim().to_string();
        serde_json::from_str::<Policy>(&raw).ok().filter(|p| !p.message.is_empty())
    }

    /// Every tool the hook must be called for (matcher alternation).
    pub fn matcher(&self) -> String {
        let mut m: Vec<&str> = self.deny_tools.iter().map(String::as_str).collect();
        m.extend(SHELL_TOOLS);
        m.join("|")
    }

    /// The refusal for one tool call, or None when it is allowed.
    pub fn refuse(&self, tool: &str, input: &Value) -> Option<String> {
        if self.deny_tools.iter().any(|t| t == tool) {
            return Some(format!("{tool} is refused for this role. {}", self.message));
        }
        if !SHELL_TOOLS.contains(&tool) {
            return None;
        }
        let cmd = input.get("command").and_then(Value::as_str)?;
        // Each segment of `a && b ; c | d` is judged on its own.
        for seg in cmd.split(['&', ';', '|', '\n']).map(str::trim).filter(|s| !s.is_empty()) {
            let words: Vec<&str> = seg.split_whitespace().collect();
            if words.first().is_some_and(|w| w.trim_end_matches(".exe") == "git") {
                // Skip git's global options (`-C dir`, `-c k=v`) to reach the subcommand.
                let mut i = 1;
                while i < words.len() && words[i].starts_with('-') {
                    i += if matches!(words[i], "-C" | "-c") { 2 } else { 1 };
                }
                if let Some(sub) = words.get(i).filter(|s| self.deny_git.iter().any(|g| g == *s)) {
                    return Some(format!("`git {sub}` is refused for this role. {}", self.message));
                }
            }
            let flat = words.join(" ");
            if let Some(c) = self.deny_commands.iter().find(|c| flat == **c || flat.starts_with(&format!("{c} "))) {
                return Some(format!("`{c}` is refused for this role. {}", self.message));
            }
        }
        None
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use serde_json::json;

    fn pol() -> Policy {
        Policy {
            deny_tools: vec!["Write".into(), "Edit".into()],
            deny_git: vec!["commit".into(), "merge".into()],
            deny_commands: vec!["cargo test".into()],
            message: "Spawn a worker.".into(),
        }
    }

    #[test]
    fn refuses_writes_git_and_builds_but_not_reads() {
        let p = pol();
        assert!(p.refuse("Write", &json!({})).unwrap().contains("Spawn a worker."));
        assert!(p.refuse("Bash", &json!({"command": "git merge feature"})).is_some());
        assert!(p.refuse("Bash", &json!({"command": "git -C x commit -m a"})).is_some());
        assert!(p.refuse("PowerShell", &json!({"command": "cd x; cargo test -p a"})).is_some());
        assert!(p.refuse("Bash", &json!({"command": "git log --oneline && git diff"})).is_none());
        assert!(p.refuse("Read", &json!({})).is_none());
        assert!(p.refuse("Bash", &json!({"command": "echo cargo testing"})).is_none());
    }

    #[test]
    fn env_absent_or_malformed_means_no_policy() {
        assert!(Policy::from_env(&[]).is_none());
        assert!(Policy::from_env(&[(POLICY_ENV.into(), "nope".into())]).is_none());
        let j = r#"{"denyTools":["Write"],"message":"m"}"#;
        assert_eq!(Policy::from_env(&[(POLICY_ENV.into(), j.into())]).unwrap().deny_tools, vec!["Write"]);
    }

    fn reply(a: &mut crate::hooks::Answers, body: &str) -> String {
        let mut out = Vec::new();
        a.call(1, body, &mut out);
        match out.pop() {
            Some(infantry_harness::Outbound::HookReply { body, .. }) => body,
            _ => panic!("no reply"),
        }
    }

    #[test]
    fn the_hook_denies_a_restricted_session_and_lets_a_free_one_act() {
        let merge = r#"{"hook_event_name":"PreToolUse","tool_name":"Bash","tool_input":{"command":"git merge x"}}"#;
        let write = r#"{"hook_event_name":"PreToolUse","tool_name":"Write","tool_input":{"file_path":"a"}}"#;
        let mut pm = crate::hooks::Answers::new(infantry_harness::ApprovalMode::AllowAll).with_policy(Some(pol()));
        for b in [merge, write] {
            let r = reply(&mut pm, b);
            assert!(r.contains("\"deny\"") && r.contains("Spawn a worker."), "{r}");
        }
        // The same calls from a session with no policy (a worker, or the policy turned off) get no decision.
        let mut worker = crate::hooks::Answers::new(infantry_harness::ApprovalMode::AllowAll);
        for b in [merge, write] {
            assert_eq!(reply(&mut worker, b), "{}");
        }
    }

    #[test]
    fn the_settings_call_the_hook_for_the_policy_tools_only_when_there_is_a_policy() {
        let ev = |_: &str| String::new();
        let mk = |m: Option<String>| {
            let w = crate::hooks::HookWiring {
                event_command: &ev,
                url: "http://x",
                approval: infantry_harness::ApprovalMode::AllowAll,
                timeout_sec: 5,
                watch_url: None,
                policy_matcher: m,
            };
            crate::hooks::settings(Some(&w), false)
        };
        assert!(mk(Some(pol().matcher())).contains("Write|Edit|Bash|PowerShell"));
        assert!(!mk(None).contains("Bash"));
    }
}
