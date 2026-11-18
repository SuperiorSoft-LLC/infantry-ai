// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
//! Infantry_Health as a native sidecar (`daemon.sidecar`): the same plugin code, served over stdio.
fn main() {
    infantry_health_daemon::sidecar_main();
}
