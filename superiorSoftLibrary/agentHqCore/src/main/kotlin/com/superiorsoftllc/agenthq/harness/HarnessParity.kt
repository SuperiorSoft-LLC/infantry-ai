package com.superiorsoftllc.agenthq.harness

/**
 * The DECLARED ways two harnesses are allowed to produce a different event stream for the same request. Anything
 * not listed here is a defect in the seam (or in a harness), never an allowance to be added to a test.
 *
 * [canonical] is the mapping a parity comparison applies to BOTH sides. It must be lossless except for the
 * declared divergences below: it merges, it never drops, reorders, retypes or rewrites an event.
 *
 * STREAM_GRANULARITY - how finely text and reasoning are cut. One harness may deliver a whole block as a single
 * [HarnessEvent.TextDelta] while another delivers it token by token; both are legal ("delta" says nothing about
 * size) and a consumer appends them either way. Adjacent events of the SAME kind are therefore equal to one event
 * carrying their concatenation. Text on either side of a tool call, and reasoning versus answer, stay apart.
 */
object HarnessParity {
    enum class Divergence { STREAM_GRANULARITY }

    val declared: Set<Divergence> = setOf(Divergence.STREAM_GRANULARITY)

    fun canonical(events: List<HarnessEvent>): List<HarnessEvent> {
        val out = ArrayList<HarnessEvent>(events.size)
        for (event in events) {
            val last = out.lastOrNull()
            when {
                event is HarnessEvent.TextDelta && last is HarnessEvent.TextDelta ->
                    out[out.lastIndex] = HarnessEvent.TextDelta(last.text + event.text)
                event is HarnessEvent.Reasoning && last is HarnessEvent.Reasoning ->
                    out[out.lastIndex] = HarnessEvent.Reasoning(last.text + event.text)
                else -> out += event
            }
        }
        return out
    }
}
