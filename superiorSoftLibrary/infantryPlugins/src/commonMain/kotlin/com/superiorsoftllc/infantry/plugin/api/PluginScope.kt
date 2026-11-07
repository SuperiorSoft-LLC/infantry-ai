// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.plugin.api

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi

/**
 * PLUGIN-HOTSWAP-1 (docs/wiki/Plugin-lifecycle.md): the lifetime of ONE loaded plugin half, owned by the host. Everything a
 * plugin starts through it stops by itself when the host unloads that half (disable, update, remove, live swap): its
 * coroutines are cancelled, every resource it [own]s is closed and every [onUnload] block runs, newest first. A plugin that
 * starts its work here needs no cleanup code of its own, and the host can swap it live without restarting the shell.
 *
 * A UI half reads it as `UiRegistrar.scope`, a daemon half as `DaemonApi.scope`. Work started anywhere else (a thread of
 * your own, a listener on a JVM-wide object, a static field) outlives the plugin and keeps its class loader reachable:
 * the host's leak check then sees the old version still in memory and falls back to a restart for that plugin.
 */
interface PluginScope {
    /** The plugin's coroutines: cancelled when the plugin unloads. A failing child does not cancel its siblings. */
    val coroutines: CoroutineScope

    /** False once the host has unloaded the plugin; a long loop of your own can check it. */
    val active: Boolean

    /** [resource] is closed when the plugin unloads (newest first); returns it. Owned after unload, it is closed at once. */
    fun <T : AutoCloseable> own(resource: T): T

    /** [block] runs once when the plugin unloads (newest first). Added after unload, it runs at once. */
    fun onUnload(block: () -> Unit)
}

/** Runs [block] every [periodMs] (first after one period) until the plugin unloads; cancel the job to stop it earlier. */
fun PluginScope.every(periodMs: Long, block: suspend () -> Unit): Job = coroutines.launch {
    while (isActive) {
        delay(periodMs)
        block()
    }
}

/**
 * The host's side of a [PluginScope]: the host makes one per loaded plugin half and calls [close] when it unloads it.
 * [close] never throws: it answers one line per cleanup step that failed (the host logs them).
 */
@OptIn(ExperimentalAtomicApi::class)
class OwnedPluginScope(
    /** The module (`Vendor_Module`) this scope belongs to: the coroutine name. */
    val module: String,
    dispatcher: CoroutineDispatcher = Dispatchers.Default,
) : PluginScope {
    private val job = SupervisorJob()
    override val coroutines: CoroutineScope = CoroutineScope(job + dispatcher + CoroutineName("plugin:$module"))

    /** The cleanups still to run, newest last; null once closed. */
    private val pending = AtomicReference<List<() -> Unit>?>(emptyList())

    override val active: Boolean get() = pending.load() != null

    override fun <T : AutoCloseable> own(resource: T): T {
        onUnload { resource.close() }
        return resource
    }

    override fun onUnload(block: () -> Unit) {
        while (true) {
            val now = pending.load() ?: run { runCatching(block); return }
            if (pending.compareAndSet(now, now + block)) return
        }
    }

    /** Cancels the coroutines, then runs every cleanup newest first. Safe to call twice (the second call does nothing). */
    fun close(): List<String> {
        val steps = pending.exchange(null) ?: return emptyList()
        val failed = mutableListOf<String>()
        runCatching { coroutines.cancel() }.onFailure { failed += "$module: cancel failed: ${it.message}" }
        for (step in steps.asReversed()) runCatching(step).onFailure { failed += "$module: cleanup failed: ${it::class.simpleName}: ${it.message}" }
        return failed
    }
}
