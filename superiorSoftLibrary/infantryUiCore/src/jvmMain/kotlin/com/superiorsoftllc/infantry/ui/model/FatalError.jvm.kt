// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.ui.model

internal actual fun isUnrecoverableError(t: Throwable): Boolean = t is OutOfMemoryError
