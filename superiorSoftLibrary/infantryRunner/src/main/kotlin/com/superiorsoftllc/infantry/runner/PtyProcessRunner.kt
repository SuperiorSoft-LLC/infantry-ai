package com.superiorsoftllc.infantry.runner

/**
 * Parked, on purpose.
 *
 * Phase 0 (infantry-phase0-spike/FINDINGS.md) proved that pipes drive `opencode run` with no TTY,
 * no ConPTY and no native library, so Pty4J was NOT added as a dependency and this runner has
 * nothing real to delegate to. The class exists only so the seam is complete and switching runners
 * is a flag (`INFANTRY_RUNNER=pty`), never a code change.
 *
 * It fails loudly rather than silently falling back to pipes: a fallback would hide which runner
 * was requested. If the deferred PTY ticket is ever picked up, implement [start] here and add the
 * Pty4J dependency to build.gradle.kts.
 */
class PtyProcessRunner : ProcessRunner {
    override val id: String = "pty"

    override fun start(spec: ProcessSpec, listener: ProcessListener): RunningProcess {
        throw UnsupportedOperationException(
            "PtyProcessRunner is not implemented: Phase 0 chose pipes and Pty4J is not a dependency. " +
                "Use INFANTRY_RUNNER=piped (the default), or implement Pty4J here if the deferred ticket is picked up.",
        )
    }
}
