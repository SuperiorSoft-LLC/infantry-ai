package com.superiorsoftllc.agenthq.orchestration

/** Pod lifecycle (stored by name in pods.state). */
enum class PodState { RUNNING, CANCELLING, CONVERGED, FAILED, CANCELLED, INTERRUPTED;
    val isTerminal: Boolean get() = this == CONVERGED || this == FAILED || this == CANCELLED || this == INTERRUPTED
}

/** Node lifecycle (stored by name in pod_nodes.state). */
enum class NodeState { PENDING, RUNNING, SUCCEEDED, FAILED, CANCELLED, SKIPPED;
    val isTerminal: Boolean get() = this != PENDING && this != RUNNING
}

/** Review of a converged pod's node (stored by name in pod_nodes.review_state; null = not queued). */
enum class ReviewState { PENDING, APPROVED, REJECTED }
