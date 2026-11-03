package com.superiorsoftllc.infantry.plugins.official.scripting.daemon

import com.superiorsoftllc.infantry.daemon.api.AutomationMark
import java.util.concurrent.ScheduledExecutorService

/**
 * SCRIPT-ACTIVE-1: the one place that turns script/hook run starts and ends into the session's automation mark. Counts overlapping runs per
 * session (the mark stays until the last ends), and keeps a very short run visible for at least [minShowMs] so the card can be seen lighting up.
 * [sink] is `Sessions.setAutomation`; a throw there is ignored. Display only.
 */
class AutomationMarks(
    private val sink: (String, AutomationMark?) -> Unit,
    private val minShowMs: Long = 1000,
    private val clock: () -> Long = System::currentTimeMillis,
    private val later: (Long, () -> Unit) -> Unit = DEFAULT_LATER,
) {
    private class Entry(var count: Int, var mark: AutomationMark, var clearAt: Long = 0)
    private val live = HashMap<String, Entry>()

    /** Marks [session] while [script] runs; call the result once when the run ends. A blank session is a no-op. */
    fun begin(session: String?, script: String, hookId: String): AutoCloseable {
        if (session.isNullOrBlank()) return AutoCloseable { }
        start(session, AutomationMark(script, hookId, clock()))
        var done = false
        return AutoCloseable { synchronized(this) { if (done) return@synchronized; done = true }; end(session) }
    }

    /** Adapter for code that sets/clears marks directly (ScriptHooks): non-null = begin, null = end. */
    val setter: (String, AutomationMark?) -> Unit = { s, m -> if (m != null) start(s, m) else end(s) }

    private fun start(session: String, mark: AutomationMark) {
        val fresh = synchronized(this) {
            val e = live[session]
            if (e == null) { live[session] = Entry(1, mark); true } else { e.count++; e.clearAt = 0; false }
        }
        if (fresh) runCatching { sink(session, mark) }
    }

    private fun end(session: String) {
        val wait = synchronized(this) {
            val e = live[session] ?: return
            if (--e.count > 0) return
            val left = e.mark.since + minShowMs - clock()
            if (left <= 0) { live.remove(session); 0L } else { e.clearAt = clock() + left; left }
        }
        if (wait == 0L) runCatching { sink(session, null) }
        else later(wait) {
            val clear = synchronized(this) { live[session]?.takeIf { it.count == 0 }?.also { live.remove(session) } != null }
            if (clear) runCatching { sink(session, null) }
        }
    }

    companion object {
        private val timer: ScheduledExecutorService by lazy { java.util.concurrent.Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "automation-mark").apply { isDaemon = true } } }
        val DEFAULT_LATER: (Long, () -> Unit) -> Unit = { ms, f -> timer.schedule(f, ms, java.util.concurrent.TimeUnit.MILLISECONDS) }
    }
}
