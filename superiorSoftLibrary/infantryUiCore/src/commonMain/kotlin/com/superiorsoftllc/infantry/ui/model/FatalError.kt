// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.ui.model

/** True for an error a render guard must not swallow (the JVM OutOfMemoryError); JVM-only, so it is per platform. */
internal expect fun isUnrecoverableError(t: Throwable): Boolean
