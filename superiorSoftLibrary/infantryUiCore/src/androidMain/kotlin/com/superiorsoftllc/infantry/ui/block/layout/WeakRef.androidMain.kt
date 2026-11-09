// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.ui.block.layout

// Plugins are compiled in here and never unloaded, so a strong reference leaks nothing.
internal actual class WeakRef actual constructor(private val value: Any) {
    actual fun get(): Any? = value
}
