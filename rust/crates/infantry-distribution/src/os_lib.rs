// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
//! Infantry AI as a BattalionOS app: a library (cdylib) the OS's worker loads and runs on the app's own thread. The C ABI is
//! BattalionOS's app ABI v1 (its `docs/app-abi.md`): `battalion_abi_version`, `battalion_app_run(context_json)` which blocks
//! until stopped, `battalion_app_stop`. `run` is today's daemon start ([`crate::run_daemon`]) and `stop` is its graceful stop
//! (the same path as ctrl-c: sessions end, the ready file is cleared). Nothing here imports BattalionOS or a plugin host: the
//! ABI is three C symbols and a JSON string.

use std::ffi::CStr;
use std::os::raw::c_char;

/// The ABI version this library implements.
pub const ABI_VERSION: u32 = 1;

/// The library's file name inside the package folder (`lib/<name>`): the package script lays it there.
pub const LIBRARY_FILE: &str = if cfg!(windows) {
    "infantry_distribution.dll"
} else if cfg!(target_os = "macos") {
    "libinfantry_distribution.dylib"
} else {
    "libinfantry_distribution.so"
};

#[no_mangle]
pub extern "C" fn battalion_abi_version() -> u32 {
    ABI_VERSION
}

/// # Safety
/// `context` is a NUL-terminated UTF-8 JSON string valid for the call (the OS's worker passes it).
#[no_mangle]
pub unsafe extern "C" fn battalion_app_run(context: *const c_char) -> i32 {
    let text = if context.is_null() { String::new() } else { CStr::from_ptr(context).to_string_lossy().into_owned() };
    run_with_context(&text)
}

#[no_mangle]
pub extern "C" fn battalion_app_stop() {
    crate::STOP.notify_one();
}

/// The body of `run`, on a string: the context's `args` are the daemon's arguments, `appDir` is where this library lives.
pub fn run_with_context(context_json: &str) -> i32 {
    let ctx: serde_json::Value = serde_json::from_str(context_json).unwrap_or(serde_json::Value::Null);
    if let Some(dir) = ctx.get("appDir").and_then(|v| v.as_str()) {
        infantry_daemon::own_files::set_library(std::path::Path::new(dir).join("lib").join(LIBRARY_FILE));
    }
    let args: Vec<String> = ctx.get("args").and_then(|v| v.as_array()).map(|a| a.iter().filter_map(|x| x.as_str().map(String::from)).collect()).unwrap_or_default();
    crate::run_daemon(args);
    0
}
