// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.plugins.official.triggers

import com.superiorsoftllc.infantry.ui.*

// S4c (docs/SHELL-LEAKS.md): the trigger card's time labels, out of core with their tests; only Triggers.kt uses them.

/** "in 12m" / "due now" -- a schedule's next slot, for the trigger card. */
internal fun nextFireLabel(nextFireAt: Long?, now: Long): String? =
    nextFireAt?.let { ago(now, it).takeUnless { label -> it <= now || label == "now" }?.let { label -> "in $label" } ?: "due now" }

/** "5m ago" / "never" -- the trigger's last accepted fire, for the trigger card. */
internal fun lastFireLabel(lastFiredAt: Long?, now: Long): String = lastFiredAt?.let { ago(it, now) + " ago" } ?: "never"
