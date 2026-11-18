// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
//! The Infantry AI daemon plugin SDK.
//!
//! Write a daemon half once and run it either way:
//!
//! ```ignore
//! use infantry_plugin_sdk::{export_plugin, host, types::CallContext, Plugin};
//! struct Hello;
//! impl Plugin for Hello {
//!     fn call_tool(name: String, args: String, ctx: CallContext) -> Result<String, String> {
//!         host::log::info(&format!("{name} called"));
//!         Ok("hello".into())
//!     }
//! }
//! export_plugin!(Hello);
//! ```
//!
//! - `cargo build --target wasm32-wasip2 --lib` makes the WASM component (`daemon.component`);
//! - on a native target `export_plugin!` defines `sidecar_main()`, which a `bin` calls to run the same plugin as a
//!   sidecar (`daemon.sidecar`) speaking the JSON-lines mapping over stdio. A sidecar must never print to stdout
//!   itself; log through [`host::log`].
//!
//! [`host`] is the import facade: the same functions on both targets.

pub use wit_bindgen;

/// The raw bindings generated from the daemon-plugin world.
pub mod bindings {
    wit_bindgen::generate!({
        path: "../infantry-plugin-wit/wit",
        world: "daemon-plugin",
        pub_export_macro: true,
        export_macro_name: "__export_daemon_plugin",
        default_bindings_module: "infantry_plugin_sdk::bindings",
    });
}

/// The WIT `types` (Frame, Caller, CallContext, NamedEvent, SqlValue, ...).
pub use bindings::infantry::plugin::types;

pub mod host;
#[cfg(not(target_arch = "wasm32"))]
pub mod sidecar;

use types::{CallContext, Caller, Frame, HttpResponse, NamedEvent, RouteRequest};

/// A daemon half. Every export has a default, so a plugin implements only what it declares.
pub trait Plugin {
    fn start() -> Result<(), String> {
        Ok(())
    }
    fn stop() {}
    fn call_tool(name: String, _args: String, _ctx: CallContext) -> Result<String, String> {
        Err(format!("unknown tool {name}"))
    }
    fn handle_frame(frame: Frame, _caller: Caller) -> Result<Option<Frame>, String> {
        Err(format!("this plugin does not answer {}", frame.type_))
    }
    fn handle_route(request: RouteRequest) -> Result<HttpResponse, String> {
        Err(format!("this plugin does not answer {} {}", request.method, request.path))
    }
    fn on_hook(kind: String, _payload: String) -> Result<String, String> {
        Err(format!("unknown hook {kind}"))
    }
    fn on_event(_subscription: u64, _event: NamedEvent) {}
}

/// Exports `$t: Plugin` as the component's daemon-plugin world (wasm32), or defines `pub fn sidecar_main()` that
/// serves it over stdio (native).
#[macro_export]
macro_rules! export_plugin {
    ($t:ty) => {
        #[cfg(target_arch = "wasm32")]
        const _: () = {
            struct __InfantryGuest;
            impl $crate::bindings::Guest for __InfantryGuest {
                fn start() -> ::core::result::Result<(), ::std::string::String> {
                    <$t as $crate::Plugin>::start()
                }
                fn stop() {
                    <$t as $crate::Plugin>::stop()
                }
                fn call_tool(name: ::std::string::String, args: ::std::string::String, ctx: $crate::types::CallContext) -> ::core::result::Result<::std::string::String, ::std::string::String> {
                    <$t as $crate::Plugin>::call_tool(name, args, ctx)
                }
                fn handle_frame(frame: $crate::types::Frame, caller: $crate::types::Caller) -> ::core::result::Result<::core::option::Option<$crate::types::Frame>, ::std::string::String> {
                    <$t as $crate::Plugin>::handle_frame(frame, caller)
                }
                fn handle_route(request: $crate::types::RouteRequest) -> ::core::result::Result<$crate::types::HttpResponse, ::std::string::String> {
                    <$t as $crate::Plugin>::handle_route(request)
                }
                fn on_hook(kind: ::std::string::String, payload: ::std::string::String) -> ::core::result::Result<::std::string::String, ::std::string::String> {
                    <$t as $crate::Plugin>::on_hook(kind, payload)
                }
                fn on_event(subscription: u64, event: $crate::types::NamedEvent) {
                    <$t as $crate::Plugin>::on_event(subscription, event)
                }
            }
            $crate::bindings::__export_daemon_plugin!(__InfantryGuest with_types_in $crate::bindings);
        };

        /// Serves this plugin as a native sidecar over stdio (returns when the host closes stdin or calls stop).
        #[cfg(not(target_arch = "wasm32"))]
        pub fn sidecar_main() {
            $crate::sidecar::serve::<$t>()
        }
    };
}
