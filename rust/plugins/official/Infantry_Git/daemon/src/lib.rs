// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
//! Infantry_Git, daemon half. The project pipeline tools moved to the board plugin with the pipeline, so this half
//! registers nothing today; it exists so the module loads the same way as every other (UI half: Git pane).
use infantry_plugin_sdk::{export_plugin, Plugin};

pub struct Git;

impl Plugin for Git {}

export_plugin!(Git);
