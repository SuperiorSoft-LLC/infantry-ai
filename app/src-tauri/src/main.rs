// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
// Prevents additional console window on Windows in release, DO NOT REMOVE!!
#![cfg_attr(not(debug_assertions), windows_subsystem = "windows")]

fn main() {
    infantry_tauri_lib::run()
}
