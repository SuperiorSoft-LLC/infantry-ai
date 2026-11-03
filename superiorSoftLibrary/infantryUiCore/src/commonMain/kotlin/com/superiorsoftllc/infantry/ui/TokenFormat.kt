// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.ui

/** 1234 -> "1.2k"; tokens read at a glance. Kit helper (RUNTIME-10: stays when the Usage pane left for Infantry_Usage). */
fun compact(n: Long): String = when {
    n >= 1_000_000 -> "${(n / 100_000) / 10.0}M"
    n >= 1_000 -> "${(n / 100) / 10.0}k"
    else -> n.toString()
}
