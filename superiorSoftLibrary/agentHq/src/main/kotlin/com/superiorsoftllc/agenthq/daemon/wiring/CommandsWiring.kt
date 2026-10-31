package com.superiorsoftllc.agenthq.daemon.wiring

import com.superiorsoftllc.agenthq.commands.CommandRegistry
import com.superiorsoftllc.agenthq.commands.RunManager
import com.superiorsoftllc.agenthq.keystore.KeystoreHandler
import com.superiorsoftllc.agenthq.keystore.Keystore
import com.superiorsoftllc.agenthq.protocol.Envelope
import com.superiorsoftllc.agenthq.runner.PipedProcessRunner
import com.superiorsoftllc.agenthq.runner.Pty4jTerminalRunner
import com.superiorsoftllc.agenthq.settings.LimitRegistry
import com.superiorsoftllc.agenthq.settings.SettingsService

/** Commands wiring (CORE-SPLIT-4a1): the run manager. */
object CommandsWiring {
    fun buildRunManager(
        registry: () -> CommandRegistry,
        emit: (Envelope) -> Unit,
        settings: SettingsService,
        locked: () -> Boolean,
        keystore: Keystore,
    ): RunManager = RunManager(
        registry, PipedProcessRunner(),
        emit = emit,
        terminal = Pty4jTerminalRunner(),
        limits = RunManager.RunLimits(
            scrollbackBytes = { settings.limitCap(LimitRegistry.RUN_SCROLLBACK_BYTES) },
            history = { settings.limitCap(LimitRegistry.RUN_HISTORY) },
            inputBytesPerSecond = { settings.limitCap(LimitRegistry.RUN_INPUT_BYTES_PER_SECOND) },
            inputMaxFrameBytes = { settings.limitCap(LimitRegistry.RUN_INPUT_MAX_FRAME_BYTES) },
            inputQueueBytes = { settings.limitCap(LimitRegistry.RUN_INPUT_QUEUE_BYTES) },
            maxCols = { settings.limitCap(LimitRegistry.RUN_MAX_COLS) },
            maxRows = { settings.limitCap(LimitRegistry.RUN_MAX_ROWS) },
        ),
        restricted = locked,
        // SEC-FIX-1 H1(b): user secrets only; a built-in (the update token) never reaches a run.
        secrets = KeystoreHandler.runSecrets(keystore::fields),
    )
}
