// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.ui.block.layout

/** PLUGIN-LIFECYCLE-1: a weak reference where the platform has plugin unloading (desktop); strong where plugins are compiled in (Android, web). */
internal expect class WeakRef(value: Any) {
    fun get(): Any?
}
