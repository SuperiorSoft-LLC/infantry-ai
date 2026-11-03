// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.orchestration

import kotlinx.serialization.Serializable
import java.io.File

/** One node of a pod as submitted. [dependsOn] holds other nodes' [name]s in the same spec. */
@Serializable
data class NodeSpec(
    val name: String,
    val prompt: String,
    val dependsOn: Set<String> = emptySet(),
    /** 1 = no retry. Total attempts including the first. */
    val maxAttempts: Int = 1,
    /** Optional agent profile id (or `role:<role>`) this node runs as. Null = no profile. */
    val agentId: String? = null,
    /** Optional provider id this node runs on. Null = the daemon's default provider. */
    val provider: String? = null,
    /** Optional model id this node runs as. Null = the provider's default model. */
    val model: String? = null,
)

/**
 * A pod as submitted. [repo] / [baseRef] / [workingDir] are passed straight to
 * SessionManager.startNew for every attempt: with a repo, every attempt gets its OWN worktree.
 * They are held in memory only (not persisted): after a daemon restart a pod is INTERRUPTED, never
 * resumed, so nothing needs them across a restart.
 *
 * NOT `@Serializable`, on purpose: it holds local `File` paths, which are the DAEMON's business and
 * must never be client-supplied over the wire. A client sends [PodSubmitRequest] instead.
 */
data class PodSpec(
    val goal: String,
    val nodes: List<NodeSpec>,
    val maxConcurrency: Int = 1,
    val repo: File? = null,
    val baseRef: String? = null,
    val workingDir: File? = null,
    /**
     * BUG-G2b: the project every attempt's session belongs to; its first turn renders that project's knowledge and
     * skills. The daemon resolves it at submit ([PodSubmitRequest.projectId], else its default project).
     */
    val projectId: String? = null,
    /**
     * BUG-G2d: the session this pod was submitted from (the `pod_submit` envelope's `sessionId`), or null for a pod
     * the user or the UI submitted. Every session the pod starts is reported against it, so it counts in that
     * session's pod spawn cap. In memory only, like the paths above.
     */
    val parentSessionId: String? = null,
)

/**
 * Phase 5b: what a `pod_submit` frame carries, and the ONLY way a client can ask for a pod.
 *
 * It is [PodSpec] minus the local paths. A client describes WHAT to run; the daemon alone decides
 * WHERE it runs. That is why the paths are not fields here -- a `java.io.File` has no serializable
 * form, and even if it did, a client naming a path on the operator's disk is a hole, not a feature.
 * The daemon's own `INFANTRY_REPO` / working directory are supplied at [toSpec].
 */
@Serializable
data class PodSubmitRequest(
    val goal: String,
    val nodes: List<NodeSpec>,
    val maxConcurrency: Int = 1,
    /**
     * Contract 1.57.0 (BUG-G2b), optional: the project the pod's sessions belong to (`infantry submit --project`).
     * Absent or blank = the daemon's default project, supplied at [toSpec].
     */
    val projectId: String? = null,
) {
    /**
     * The [PodSpec] to hand the orchestrator: the client's DAG verbatim, plus the daemon's own
     * local locations. Validation still happens in [PodValidator.validate] inside `submit`, so a
     * malformed request writes nothing. [defaultProjectId] is used only when the request names no project.
     */
    fun toSpec(
        repo: File? = null,
        baseRef: String? = null,
        workingDir: File? = null,
        defaultProjectId: String? = null,
        parentSessionId: String? = null,
    ): PodSpec = PodSpec(
        goal = goal,
        nodes = nodes,
        maxConcurrency = maxConcurrency,
        repo = repo,
        baseRef = baseRef,
        workingDir = workingDir,
        projectId = projectId?.trim()?.takeIf { it.isNotEmpty() } ?: defaultProjectId,
        parentSessionId = parentSessionId,
    )
}

/** A pod spec that cannot be scheduled (see PodValidator for every message). Nothing was written. */
class InvalidPodException(message: String) : IllegalArgumentException(message)
