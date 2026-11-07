// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.ui.model

/**
 * BASE-1b: one row of the Git tab's "Base branch for new tasks" picker. [branch] null is the "Default (checked-out
 * HEAD)" row, now "None": it clears the setting, and the daemon then refuses a start (BUG-BASE-087) unless INFANTRY_BASE_BRANCH is set.
 */
data class BaseBranchChoice(val branch: String?, val checkedOut: Boolean = false) {
    val label: String get() = branch ?: BASE_BRANCH_DEFAULT_LABEL
}

const val BASE_BRANCH_DEFAULT_LABEL = "None (ticket starts are refused until one is set)"

/**
 * The picker's rows for [query]: the Default row first, then the project's local [branches] in the daemon's order, each
 * kept when its name contains [query] (case-insensitive substring; a blank query keeps everything). The Default row is
 * kept on a blank query or when its own label matches, so typing "def" still offers it.
 */
fun baseBranchChoices(branches: List<String>, current: String?, query: String): List<BaseBranchChoice> {
    val q = query.trim()
    val all = listOf(BaseBranchChoice(null)) + branches.distinct().map { BaseBranchChoice(it, checkedOut = it == current) }
    return if (q.isEmpty()) all else all.filter { it.label.contains(q, ignoreCase = true) }
}

/** True when [choice] is what [baseBranch] (the project's stored setting, `""` = none) says now. */
fun isCurrentBase(choice: BaseBranchChoice, baseBranch: String): Boolean =
    if (choice.branch == null) baseBranch.isEmpty() else choice.branch == baseBranch
