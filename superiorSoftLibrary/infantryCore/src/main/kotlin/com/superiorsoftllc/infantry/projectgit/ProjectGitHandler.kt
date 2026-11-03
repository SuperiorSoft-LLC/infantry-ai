// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.projectgit

import com.superiorsoftllc.infantry.mvp.ProjectStore
import com.superiorsoftllc.infantry.protocol.Envelope
import kotlinx.serialization.json.Json

/**
 * CP-2 slice (b): the `project_git_*` WIRE SURFACE over a [ProjectStore] and a [ProjectGitService].
 *
 * Like `ProjectHandler` it only decodes, asks and encodes: which project, and what its directory is,
 * is the store's answer; what git says is the service's. A request naming no known project, or a
 * path the service refuses to hand to git, is the standing `<frame> refused: <why>` error; a git
 * read that merely could not happen (no directory, not a repository) is NOT an error -- it is an
 * answer with `available = false`, so the client can show the reason where the data would be.
 */
class ProjectGitHandler(
    private val store: ProjectStore,
    private val service: ProjectGitService,
    /**
     * BRANCH-SWITCH-1: how a frame reaches EVERY connected client (the daemon's client list). After a switch that
     * happened, the project's fresh `project_git` status goes out here, so every open pane sees the new branch.
     */
    private val push: (Envelope) -> Unit = {},
    /**
     * UX-CHANGES-1: the directory a session runs in (its worktree, or the shared checkout), or null for an unknown
     * session. `project_git_worktrees` marks and opens on the worktree that holds it.
     */
    private val sessionCheckout: (String) -> String? = { null },
    private val json: Json = Json { ignoreUnknownKeys = true; encodeDefaults = true },
) {
    /** Answer one frame, or null when it is not a `project_git_*` request. */
    fun answer(envelope: Envelope): Envelope? = when (envelope.type) {
        Envelope.PROJECT_GIT_STATUS -> request(envelope.type) {
            val project = store.get(envelope.data)
            Envelope(Envelope.PROJECT_GIT, json.encodeToString(ProjectGitStatus.serializer(), service.status(project)))
        }

        Envelope.PROJECT_GIT_DIFF -> request(envelope.type) {
            val body = ProjectGitDiffRequest.decode(envelope.data)
            val project = store.get(body.projectId)
            Envelope(
                Envelope.PROJECT_GIT_PATCH,
                json.encodeToString(ProjectGitPatch.serializer(), service.diff(project, body.path, body.staged, body.worktree)),
            )
        }

        Envelope.PROJECT_GIT_LOG -> request(envelope.type) {
            val body = ProjectGitLogRequest.decode(envelope.data)
            val project = store.get(body.projectId)
            Envelope(
                Envelope.PROJECT_GIT_HISTORY,
                json.encodeToString(ProjectGitHistory.serializer(), service.log(project, body.limit)),
            )
        }

        Envelope.PROJECT_GIT_BRANCHES -> request(envelope.type) {
            val body = ProjectGitBranchesRequest.decode(envelope.data)
            val project = store.get(body.projectId)
            Envelope(
                Envelope.PROJECT_GIT_BRANCH_LIST,
                json.encodeToString(ProjectGitBranchList.serializer(), service.branches(project)),
            )
        }

        Envelope.PROJECT_GIT_BRANCH_CHANGES -> request(envelope.type) {
            val body = ProjectGitBranchChangesRequest.decode(envelope.data)
            val project = store.get(body.projectId)
            Envelope(
                Envelope.PROJECT_GIT_BRANCH_DIFFSTAT,
                json.encodeToString(ProjectGitBranchDiffstat.serializer(), service.branchChanges(project, body.branch, body.base)),
            )
        }

        Envelope.PROJECT_GIT_BRANCH_DIFF -> request(envelope.type) {
            val body = ProjectGitBranchDiffRequest.decode(envelope.data)
            val project = store.get(body.projectId)
            Envelope(
                Envelope.PROJECT_GIT_PATCH,
                json.encodeToString(ProjectGitPatch.serializer(), service.branchDiff(project, body.branch, body.base, body.path)),
            )
        }

        // UX-CHANGES-1 (contract 1.88.0): the project's worktrees, and one worktree's branch, ahead/behind, changes and
        // history. An unknown session id is not a refusal: the list simply marks no worktree as the session's.
        Envelope.PROJECT_GIT_WORKTREES -> request(envelope.type) {
            val body = ProjectGitWorktreesRequest.decode(envelope.data)
            val project = store.get(body.projectId)
            val sessionDir = body.sessionId?.takeIf { it.isNotBlank() }?.let(sessionCheckout)
            Envelope(
                Envelope.PROJECT_GIT_WORKTREE_LIST,
                json.encodeToString(ProjectGitWorktreeList.serializer(), service.worktrees(project, sessionDir)),
            )
        }

        Envelope.PROJECT_GIT_WORKTREE_STATUS -> request(envelope.type) {
            val body = ProjectGitWorktreeStatusRequest.decode(envelope.data)
            val project = store.get(body.projectId)
            Envelope(
                Envelope.PROJECT_GIT_WORKTREE,
                json.encodeToString(ProjectGitWorktreeStatus.serializer(), service.worktreeStatus(project, body.path, body.historyLimit)),
            )
        }

        // BRANCH-SWITCH-1 (contract 1.74.0): the one writing verb. A switch git was not asked to make is an
        // answer (`switched = false` + code + reason), never an error frame; only an unknown project or a
        // malformed payload is refused.
        Envelope.PROJECT_GIT_CHECKOUT -> request(envelope.type) {
            val body = ProjectGitCheckoutRequest.decode(envelope.data)
            val project = store.get(body.projectId)
            val result = service.checkout(project, body.branch, body.create)
            if (result.switched) {
                push(Envelope(Envelope.PROJECT_GIT, json.encodeToString(ProjectGitStatus.serializer(), service.status(project))))
            }
            Envelope(Envelope.PROJECT_GIT_CHECKOUT_RESULT, json.encodeToString(ProjectGitCheckoutResult.serializer(), result))
        }

        else -> null
    }

    private inline fun request(frame: String, block: () -> Envelope): Envelope = try {
        block()
    } catch (e: IllegalArgumentException) {
        Envelope(Envelope.ERROR, "$frame refused: ${e.message ?: "invalid request"}")
    } catch (e: Exception) {
        Envelope(Envelope.ERROR, "$frame failed: ${e.message ?: e::class.simpleName}")
    }
}
