// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.settings

/**
 * P10-K11 (K-D9): every id in [ids] must name an existing skill and appear once, or [what] is refused. [unknownOf] returns the
 * ids that name no skill (the store lookup stays in the caller). Shared by the project baseline and `ticket_save`.
 */
fun requireKnownSkills(ids: List<String>, what: String, unknownOf: (List<String>) -> List<String>) {
    val duplicate = ids.groupingBy { it }.eachCount().entries.firstOrNull { it.value > 1 }?.key
    require(duplicate == null) { "$what: skill '${duplicate!!.take(80)}' is listed twice" }
    val unknown = unknownOf(ids)
    require(unknown.isEmpty()) { "$what: unknown skill ${unknown.joinToString { "'${it.take(80)}'" }}" }
}
