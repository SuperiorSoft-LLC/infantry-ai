// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.plugin.host

import java.io.File
import java.lang.management.ManagementFactory
import java.lang.ref.WeakReference

/**
 * PLUGIN-HOTSWAP-1 (docs/wiki/Plugin-lifecycle.md): did an unloaded plugin really leave memory? The host keeps a
 * [WeakReference] to the class loader it closed and asks [collected]: it requests a GC and checks the reference until
 * it clears or [collected]'s timeout passes. A loader still reachable means something outside the plugin (a host
 * singleton, a thread, a JVM-wide listener, remembered UI state) still holds one of its objects: the host warns and falls
 * back to its restart path for that plugin.
 *
 * A GC request is a hint, so the answer is "collected within the timeout", not proof of a leak; the timeout is the
 * host's named setting.
 */
object LeakWatch {
    /** The step between two GC requests while [collected] waits. */
    const val POLL_MS = 50L

    /** True when [ref] cleared within [timeoutMs] (the plugin left memory); false when it is still reachable. */
    fun collected(
        ref: WeakReference<*>,
        timeoutMs: Long,
        gc: () -> Unit = { System.gc() },
        now: () -> Long = System::currentTimeMillis,
        pause: (Long) -> Unit = { Thread.sleep(it) },
    ): Boolean {
        val end = now() + timeoutMs.coerceAtLeast(0)
        while (true) {
            if (ref.get() == null) return true
            gc()
            if (ref.get() == null) return true
            if (now() >= end) return false
            pause(POLL_MS)
        }
    }

    /**
     * Dev aid: writes a heap dump of the live objects to [file] (open it in a heap profiler and ask for the path from the
     * GC roots to the plugin's class loader). Null when this JVM cannot dump (no HotSpot diagnostic bean), else the file.
     */
    fun heapDump(file: File): File? = runCatching {
        file.parentFile?.mkdirs()
        if (file.exists()) file.delete()
        val bean = ManagementFactory.newPlatformMXBeanProxy(
            ManagementFactory.getPlatformMBeanServer(), "com.sun.management:type=HotSpotDiagnostic",
            Class.forName("com.sun.management.HotSpotDiagnosticMXBean"),
        )
        bean.javaClass.getMethod("dumpHeap", String::class.java, Boolean::class.javaPrimitiveType).invoke(bean, file.absolutePath, true)
        file
    }.getOrNull()
}
