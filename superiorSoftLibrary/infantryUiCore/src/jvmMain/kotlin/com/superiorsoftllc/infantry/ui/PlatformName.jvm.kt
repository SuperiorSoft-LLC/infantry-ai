// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.ui

actual fun platformName(): String = "${System.getProperty("os.name")} ${System.getProperty("os.version")} (${System.getProperty("os.arch")}), desktop"
