package com.superiorsoftllc.infantry.harness

/**
 * The TRANSLATION interface: the only place a harness's wire schema is allowed to be known. One instance per run.
 *
 * [translate] takes one stdout line of the vendor CLI and returns zero or more neutral [HarnessEvent]s; [terminal]
 * is asked once, when the process has exited, for the run's single [HarnessEvent.Completion] / [HarnessEvent.Error].
 * Everything a caller sees was produced by this interface. A harness that emits an event any other way has bypassed
 * the seam, and the parity guarantee no longer covers it.
 */
interface HarnessTranslator {
    fun translate(line: String): List<HarnessEvent>
    fun terminal(exitCode: Int, stderrTail: String): HarnessEvent
}
