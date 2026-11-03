// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.usage

enum class LaunchOrigin { HUMAN, UNATTENDED }

sealed interface GateDecision {
    data object Allow : GateDecision
    data class Warn(val reason: String) : GateDecision
    data class Refused(val reason: String, val disposition: String = "refused_launch_gate") : GateDecision
}

sealed interface PacingVerdict {
    data class Allow(val warning: String? = null) : PacingVerdict
    data class Over(val reason: String) : PacingVerdict
}
