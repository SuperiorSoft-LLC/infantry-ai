package com.superiorsoftllc.agenthq.session

import kotlinx.serialization.Serializable

/**
 * Phase 3: the lifecycle of one **managed session**.
 *
 * A session is provider-neutral and logical. It is NOT a process: both supported CLIs (`opencode
 * run`, `claude -p`) are one-shot -- they consume a prompt, answer, and exit -- so one logical
 * session is a *sequence of runs* that share one provider-side session id. The daemon owns the
 * sequence; the provider CLI owns the conversational context behind [SessionRecord.providerSessionId].
 *
 * States are deliberately few, and each one means something a client can act on:
 *
 *  - [STARTING]       legacy: a run spawned with no output yet. M2-12: the daemon no longer assigns it (a
 *                     record persisted by an older daemon may still carry it, and is read as in flight).
 *  - [BUSY]           WORKING: a turn is in progress -- from the moment it is accepted, through reasoning,
 *                     tool calls and the streamed reply, until it ends. Process start/exit never sets it.
 *  - [WAITING_INPUT]  the provider signalled it needs a human answer before it can continue.
 *  - [IDLE]           no run in flight; the session is alive, persisted and resumable. The resting
 *                     state between prompts.
 *  - [EXITED]         the session ended on purpose or its run ended while no daemon was watching
 *                     (a kill, or a daemon restart whose child did not survive). Terminal, resumable.
 *  - [ORPHANED]       a daemon restart found this session's child process still alive: the run
 *                     outlived the daemon that owned it. Terminal for this daemon (it does not adopt
 *                     a pid it cannot reattach to), resumable, and recorded so the mismatch between
 *                     "daemon thought it was running" and "the process is gone" is never silent.
 *  - [FAILED]         abnormal end: non-zero exit, or a run that could not be started. Terminal but
 *                     resumable when a provider session id is known.
 *
 * INFERENCE, stated plainly: every state except [WAITING_INPUT] is *observed* -- it follows directly
 * from the process lifecycle and its exit code, which the runner reports. There is no guesswork.
 * [WAITING_INPUT] is DEFINED but not currently produced: neither CLI, run non-interactively (no
 * TTY), ever signals "I need input" -- it either finishes or blocks. The state exists so a future
 * interactive/PTY transport or an HTTP provider can raise it; today the daemon never assigns it.
 * See `README.md` ("Status reporting") for the full argument.
 */
@Serializable
enum class SessionState {
    STARTING,
    BUSY,
    WAITING_INPUT,
    IDLE,
    EXITED,
    ORPHANED,
    FAILED,
}
