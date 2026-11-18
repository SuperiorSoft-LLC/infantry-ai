// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
//! A loaded plugin's `daemon.frames` as an infantry-core [`FrameUnit`]. The unit looks the plugin up by id on every
//! frame, so a hot swap is seen at once and an unloaded plugin refuses.
use crate::guard::CallError;
use crate::host::PluginHost;
use crate::Caller;
use infantry_core::frames::{Envelope, FrameCtx, FrameUnit, Refusal};
use infantry_plugin_wit::jsonl::Frame;
use std::sync::Arc;

pub struct PluginFrameUnit {
    host: Arc<PluginHost>,
    module_id: String,
    name: &'static str,
    frames: &'static [&'static str],
}

impl PluginFrameUnit {
    /// The unit for `module_id`'s declared frames as loaded now. The names are leaked once per unit (FrameUnit
    /// wants `'static`; a unit lives as long as its registration).
    pub fn new(host: Arc<PluginHost>, module_id: &str) -> Option<PluginFrameUnit> {
        let p = host.get(module_id)?;
        let name: &'static str = Box::leak(format!("plugin:{module_id}").into_boxed_str());
        let frames: Vec<&'static str> = p.decl.frames.iter().map(|f| &*Box::leak(f.r#type.clone().into_boxed_str())).collect();
        Some(PluginFrameUnit { host, module_id: module_id.into(), name, frames: Box::leak(frames.into_boxed_slice()) })
    }
}

fn refusal(frame_type: &str, e: &CallError) -> Refusal {
    let m = e.message();
    if m.starts_with(&format!("{frame_type} refused")) {
        Refusal(m.to_string())
    } else {
        Refusal::new(frame_type, m)
    }
}

impl FrameUnit for PluginFrameUnit {
    fn name(&self) -> &'static str {
        self.name
    }
    fn frames(&self) -> &'static [&'static str] {
        self.frames
    }
    fn handle(&self, env: &Envelope, ctx: &FrameCtx) -> Result<Vec<Envelope>, Refusal> {
        let Some(p) = self.host.get(&self.module_id) else {
            return Err(Refusal::new(&env.r#type, format!("plugin {} is not loaded", self.module_id)));
        };
        let frame = Frame { r#type: env.r#type.clone(), data: env.data.clone(), session_id: env.session_id.clone(), request_id: env.request_id.clone() };
        let caller = Caller { owner: ctx.owner, ..Default::default() };
        match p.handle_frame(&frame, &caller) {
            Ok(None) => Ok(Vec::new()),
            Ok(Some(f)) => {
                let mut out = Envelope::new(f.r#type, f.data);
                out.session_id = f.session_id;
                out.request_id = f.request_id.or_else(|| env.request_id.clone());
                Ok(vec![out])
            }
            Err(e) => Err(refusal(&env.r#type, &e)),
        }
    }
}
