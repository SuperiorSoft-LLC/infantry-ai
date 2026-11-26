// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
//! The single source of the contract version (CLI, MCP `serverInfo.version` and the daemon ready frame): the daemon contract (`EventVocabulary.VERSION` in infantryProtocol). Read it from the
//! Kotlin source at build time so the two bridges can never drift; `INFANTRY_CONTRACT_VERSION` overrides it (a build outside
//! the monorepo).

use std::path::Path;

const VOCAB: &str =
    "../../../superiorSoftLibrary/infantryProtocol/src/main/kotlin/com/superiorsoftllc/infantry/protocol/EventVocabulary.kt";

fn main() {
    println!("cargo:rerun-if-env-changed=INFANTRY_CONTRACT_VERSION");
    println!("cargo:rerun-if-changed={VOCAB}");
    let version = std::env::var("INFANTRY_CONTRACT_VERSION").ok().filter(|v| !v.trim().is_empty()).or_else(|| {
        let text = std::fs::read_to_string(Path::new(VOCAB)).ok()?;
        text.lines().find_map(|l| {
            let l = l.trim();
            let rest = l.strip_prefix("const val VERSION = \"")?;
            Some(rest.split('"').next()?.to_string())
        })
    });
    let version = version.expect("cannot find EventVocabulary.VERSION; set INFANTRY_CONTRACT_VERSION");
    println!("cargo:rustc-env=INFANTRY_CONTRACT_VERSION={version}");
}
