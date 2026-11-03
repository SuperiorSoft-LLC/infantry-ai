// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.mvp

import com.superiorsoftllc.infantry.protocol.Project
import com.superiorsoftllc.infantry.worktree.TicketBranches
import java.io.File

/**
 * BASE-1a: decides, at SET time, whether a project's base branch is one a ticket start can cut from.
 *
 * A start cuts in ONE repository, the board's ([cutRepo]: the configured repo, else the checkout the daemon's
 * sessions run in), never in the project's own directory. So the branch is resolved THERE, through
 * [TicketBranches.resolveBase] -- the very resolution a cut runs -- and a project whose directory is a DIFFERENT
 * repository is refused: its branch list (what `project_git_branches` shows) is not the list a cut can use.
 *
 * Every refusal is an [IllegalArgumentException] naming the reason; nothing is written by a refused check.
 */
class ProjectBaseCheck(
    private val branches: TicketBranches,
    /** INSTALL-CWD-1: per project, since with project folders on each project cuts in its own checkout. */
    private val cutRepo: (Project) -> File?,
) {
    /** The commit [branch] resolves to for [project]; throws [IllegalArgumentException] with the reason otherwise. */
    fun check(project: Project, branch: String): String {
        val name = checkName(branch)
        val repo = cutRepo(project)
            ?: throw IllegalArgumentException(
                if (project.directory.isNotEmpty() && branches.commonDir(File(project.directory)) == null) {
                    "project directory '${project.directory}' is not a git repository, so base branches do not apply: its tickets run in the folder with no branch"
                } else {
                    "ticket starts cut in no git repository (set INFANTRY_REPO, or run the daemon in a checkout), " +
                        "so there is nothing to resolve '$name' in"
                },
            )
        val repoCommon = branches.commonDir(repo)
            ?: throw IllegalArgumentException("the repository ticket starts cut in is not a git repository: ${repo.path}")
        if (project.directory.isNotEmpty()) {
            val projectCommon = branches.commonDir(File(project.directory))
            if (projectCommon != repoCommon) {
                throw IllegalArgumentException(
                    "project directory '${project.directory}' is not in the repository ticket starts cut in " +
                        "(${repo.path}); its branches are not the ones a ticket can be cut from",
                )
            }
        }
        return branches.resolveBase(repo, name)
            ?: throw IllegalArgumentException(
                if (branches.resolveBase(repo, "HEAD") == null) {
                    "repository ${repo.path} has no commits yet; make a first commit, then set a base branch"
                } else {
                    "base branch '$name' does not resolve to a commit in ${repo.path}"
                },
            )
    }

    companion object {
        const val MAX_BRANCH_LENGTH = 255

        /** A name that reaches git only as a ref: never an option, never a control character. */
        fun checkName(raw: String): String {
            val name = raw.trim()
            require(name.isNotEmpty()) { "base branch is blank" }
            require(name.length <= MAX_BRANCH_LENGTH) { "base branch is longer than $MAX_BRANCH_LENGTH characters" }
            require(!name.startsWith("-")) { "base branch must not start with '-': '${name.take(80)}'" }
            require(name.none { it.isISOControl() || it.isWhitespace() }) { "base branch contains whitespace or a control character" }
            return name
        }
    }
}
