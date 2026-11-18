// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
//! The plugin host's test guest.
use infantry_plugin_sdk::types::{CallContext, Caller, Frame};
use infantry_plugin_sdk::{export_plugin, host, Plugin};

#[cfg(not(feature = "v2"))]
const VERSION: &str = "v1";
#[cfg(feature = "v2")]
const VERSION: &str = "v2";

struct Fixture;

impl Plugin for Fixture {
    fn start() -> Result<(), String> {
        host::log::info(&format!("fixture {VERSION} started"));
        Ok(())
    }
    fn stop() {
        host::log::info(&format!("fixture {VERSION} stopping"));
    }
    fn call_tool(name: String, args: String, _ctx: CallContext) -> Result<String, String> {
        match name.as_str() {
            "fixture_version" => Ok(VERSION.into()),
            "fixture_spin" => {
                let mut n: u64 = 0;
                loop {
                    n = std::hint::black_box(n.wrapping_add(1));
                }
            }
            "fixture_panic" => panic!("fixture asked to panic"),
            "fixture_slow" => {
                // Waits on the host (a frame the test answers slowly), then says which version answered.
                host::frames::request("slow", &args, None, 10_000)?;
                Ok(VERSION.into())
            }
            "fixture_storage" => {
                host::storage::kv_set("k", &args)?;
                Ok(host::storage::kv_get("k")?.unwrap_or_default())
            }
            "fixture_error" => Err("bad arguments".into()),
            other => Err(format!("unknown tool {other}")),
        }
    }
    fn handle_frame(frame: Frame, caller: Caller) -> Result<Option<Frame>, String> {
        if !caller.owner {
            return Err("owner only".into());
        }
        Ok(Some(Frame { type_: format!("{}_reply", frame.type_), data: frame.data, session_id: None, request_id: None }))
    }
}

export_plugin!(Fixture);
