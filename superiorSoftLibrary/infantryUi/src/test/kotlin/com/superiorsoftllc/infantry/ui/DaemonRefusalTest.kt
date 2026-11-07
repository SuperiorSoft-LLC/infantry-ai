// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.ui

import kotlin.test.Test
import kotlin.test.assertTrue

class DaemonRefusalTest {
    @Test fun messageCarriesReasonLogsAndReadme() {
        val m = DaemonRefusal.details("the bearer token must be at least 32 characters", "/logs")
        assertTrue("at least 32 characters" in m && "/logs" in m && "Headless server" in m)
    }
}
