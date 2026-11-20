// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
//! Infantry_Installer, daemon half. The installing itself (copy a plugin from a source, load it, remove it) is the
//! daemon's `plugin_admin` frame, owner only: a plugin cannot touch the file system, and an agent must not install
//! software. This half is the bootstrap plugin's presence in the daemon (it is loaded first, and the boot `installer` step
//! waits for it) and the one read an agent may make: what the sources offer and what is installed.
use infantry_plugin_sdk::types::CallContext;
use infantry_plugin_sdk::{export_plugin, host, Plugin};
use serde_json::{json, Value};

const TIMEOUT_MS: u32 = 30_000;
const AVAILABLE: &str = "infantry_installer_available";

pub struct Installer;

/// The `plugin_admin` payload for a tool call. Only `available` is ever sent from here: this half reads, it never installs.
pub fn admin_payload(tool: &str) -> Result<Value, String> {
    match tool {
        AVAILABLE => Ok(json!({ "args": ["available"] })),
        other => Err(format!("unknown tool {other}")),
    }
}

/// The text a `plugin_admin` answer (`{code, out, err}`) carries: its output, or its error.
pub fn answer(data: &str) -> Result<String, String> {
    let v: Value = serde_json::from_str(data).map_err(|e| format!("plugin_admin answered something that is not JSON: {e}"))?;
    let text = |k: &str| v.get(k).and_then(Value::as_str).unwrap_or("").to_string();
    if v.get("code").and_then(Value::as_i64).unwrap_or(1) == 0 {
        Ok(text("out"))
    } else {
        Err(text("err"))
    }
}

impl Plugin for Installer {
    fn call_tool(name: String, _args: String, ctx: CallContext) -> Result<String, String> {
        let payload = admin_payload(&name)?;
        let r = host::frames::request("plugin_admin", &payload.to_string(), ctx.client_session_id.as_deref(), TIMEOUT_MS).map_err(|e| format!("{name} failed: {e}"))?;
        if r.type_ == "error" {
            return Err(format!("{name}: {}", r.data));
        }
        answer(&r.data).map_err(|e| format!("{name}: {e}"))
    }
}

export_plugin!(Installer);

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn the_tool_only_ever_asks_for_the_list() {
        assert_eq!(admin_payload(AVAILABLE).unwrap(), json!({ "args": ["available"] }));
        assert!(admin_payload("infantry_installer_install").is_err());
    }

    #[test]
    fn an_answer_is_its_output_or_its_error() {
        assert_eq!(answer(r#"{"code":0,"out":"[]","err":""}"#).unwrap(), "[]");
        assert_eq!(answer(r#"{"code":1,"out":"","err":"owner only"}"#).unwrap_err(), "owner only");
        assert!(answer("nope").is_err());
    }
}
