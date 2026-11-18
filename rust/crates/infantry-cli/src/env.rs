// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
//! The environment, read the way the Kotlin CLI reads it.
//!
//! Two readers, as in Kotlin: [get] is `InfantryEnv.get` (an `INFANTRY_X` name falls back to its pre-rename `AGENTHQ_X`
//! spelling and back), [raw] is `System.getenv` (no alias; the MCP server reads its own limits this way).

const PREFIX: &str = "INFANTRY_";
const LEGACY_PREFIX: &str = "AGENTHQ_";

/// `System.getenv`: the variable, or None (unset or not Unicode).
pub fn raw(name: &str) -> Option<String> {
    std::env::var(name).ok()
}

/// The other spelling of [name] (`INFANTRY_PORT` <-> `AGENTHQ_PORT`); None for a name with neither prefix.
pub fn alias_of(name: &str) -> Option<String> {
    if let Some(rest) = name.strip_prefix(PREFIX) {
        Some(format!("{LEGACY_PREFIX}{rest}"))
    } else {
        name.strip_prefix(LEGACY_PREFIX).map(|rest| format!("{PREFIX}{rest}"))
    }
}

/// `InfantryEnv.get`: [name], else its alias.
pub fn get(name: &str) -> Option<String> {
    raw(name).or_else(|| alias_of(name).and_then(|a| raw(&a)))
}

/// A getter the code can be tested with: `Fn(&str) -> Option<String>`.
pub type Getenv<'a> = &'a dyn Fn(&str) -> Option<String>;

/// A trimmed, non-empty value of [name].
pub fn non_blank(getenv: Getenv, name: &str) -> Option<String> {
    getenv(name).map(|v| v.trim().to_string()).filter(|v| !v.is_empty())
}

/// One named limit (`LimitRegistry.LimitDef`): only the fields the bridge reads.
pub struct Limit {
    pub key: &'static str,
    pub default: i64,
    pub min: i64,
    pub max: Option<i64>,
    pub legacy_env: &'static [&'static str],
}

impl Limit {
    /// `INFANTRY_LIMIT_` + the key without `limits.`, camelCase split with `_`, dots to `_`, upper case.
    pub fn env(&self) -> String {
        let key = self.key.strip_prefix("limits.").unwrap_or(self.key);
        let chars: Vec<char> = key.chars().collect();
        let mut out = String::from("INFANTRY_LIMIT_");
        for (i, c) in chars.iter().enumerate() {
            if i > 0 && c.is_ascii_uppercase() {
                let p = chars[i - 1];
                if p.is_ascii_lowercase() || p.is_ascii_digit() {
                    out.push('_');
                }
            }
            out.push(if *c == '.' { '_' } else { c.to_ascii_uppercase() });
        }
        out
    }

    fn accepts(&self, v: i64) -> bool {
        v >= self.min && self.max.map_or(true, |m| v <= m)
    }

    fn range(&self) -> String {
        format!("{}..{}", self.min, self.max.map_or("no ceiling".to_string(), |m| m.to_string()))
    }

    /// `LimitRegistry.fromEnv`: the first set name wins; a bad value is an error naming the variable.
    pub fn from_env(&self, getenv: Getenv) -> Result<i64, String> {
        let mut names = vec![self.env()];
        names.extend(self.legacy_env.iter().map(|s| s.to_string()));
        for name in names {
            let Some(raw) = getenv(&name).map(|v| v.trim().to_string()).filter(|v| !v.is_empty()) else { continue };
            return match raw.parse::<i64>().ok().filter(|v| self.accepts(*v)) {
                Some(v) => Ok(v),
                None => Err(format!("{name} must be a whole number in {}, was '{raw}'", self.range())),
            };
        }
        Ok(self.default)
    }
}

pub const MCP_TOOLS_WATCH_SECONDS: Limit =
    Limit { key: "limits.mcp.toolsWatchSeconds", default: 5, min: 0, max: Some(86_400), legacy_env: &[] };
pub const MCP_MAX_RESULT_CHARS: Limit =
    Limit { key: "limits.mcp.maxResultChars", default: 8000, min: 0, max: Some(10_000_000), legacy_env: &[] };
pub const MCP_DEFAULT_DETAIL: Limit =
    Limit { key: "limits.mcp.defaultDetail", default: 0, min: 0, max: Some(3), legacy_env: &[] };
pub const MCP_BODY_CHARS: Limit =
    Limit { key: "limits.mcp.bodyChars", default: 0, min: 0, max: Some(10_000_000), legacy_env: &[] };
pub const PLUGIN_TOOL_TIMEOUT_MS: Limit =
    Limit { key: "limits.plugins.toolTimeoutMs", default: 60_000, min: 1, max: None, legacy_env: &[] };
pub const CLI_ANSWER_TIMEOUT_MS: Limit = Limit {
    key: "limits.cli.answerTimeoutMs",
    default: 20_000,
    min: 1,
    max: None,
    legacy_env: &["INFANTRY_CLI_ANSWER_TIMEOUT_MS", "AGENTHQ_CLI_ANSWER_TIMEOUT_MS"],
};

/// `answerTimeoutMs`: `INFANTRY_CLI_ANSWER_TIMEOUT_MS` (> 0), else the limit, else its default.
pub fn answer_timeout_ms(getenv: Getenv) -> i64 {
    getenv("INFANTRY_CLI_ANSWER_TIMEOUT_MS")
        .and_then(|v| v.trim().parse::<i64>().ok())
        .filter(|v| *v > 0)
        .unwrap_or_else(|| CLI_ANSWER_TIMEOUT_MS.from_env(getenv).unwrap_or(CLI_ANSWER_TIMEOUT_MS.default))
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn limit_env_names_match_kotlin() {
        assert_eq!(MCP_TOOLS_WATCH_SECONDS.env(), "INFANTRY_LIMIT_MCP_TOOLS_WATCH_SECONDS");
        assert_eq!(PLUGIN_TOOL_TIMEOUT_MS.env(), "INFANTRY_LIMIT_PLUGINS_TOOL_TIMEOUT_MS");
        assert_eq!(CLI_ANSWER_TIMEOUT_MS.env(), "INFANTRY_LIMIT_CLI_ANSWER_TIMEOUT_MS");
    }

    #[test]
    fn from_env_refuses_out_of_range() {
        let env = |n: &str| (n == "INFANTRY_LIMIT_MCP_DEFAULT_DETAIL").then(|| "9".to_string());
        assert_eq!(
            MCP_DEFAULT_DETAIL.from_env(&env).unwrap_err(),
            "INFANTRY_LIMIT_MCP_DEFAULT_DETAIL must be a whole number in 0..3, was '9'"
        );
        let none = |_: &str| None;
        assert_eq!(MCP_MAX_RESULT_CHARS.from_env(&none).unwrap(), 8000);
    }

    #[test]
    fn alias_both_ways() {
        assert_eq!(alias_of("INFANTRY_PORT").as_deref(), Some("AGENTHQ_PORT"));
        assert_eq!(alias_of("AGENTHQ_TOKEN").as_deref(), Some("INFANTRY_TOKEN"));
        assert_eq!(alias_of("PATH"), None);
    }
}
