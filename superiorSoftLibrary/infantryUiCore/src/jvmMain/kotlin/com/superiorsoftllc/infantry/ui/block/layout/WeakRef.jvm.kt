// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.ui.block.layout

internal actual class WeakRef actual constructor(value: Any) {
    private val ref = java.lang.ref.WeakReference(value)
    actual fun get(): Any? = ref.get()
}
