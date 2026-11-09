// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.ui.block.layout

import androidx.compose.runtime.AbstractApplier
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Composition
import androidx.compose.runtime.CompositionLocalContext
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Recomposer
import androidx.compose.runtime.currentCompositionLocalContext
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.rememberUpdatedState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Job
import kotlinx.coroutines.Runnable
import kotlin.coroutines.CoroutineContext

/**
 * GAP-AUDIT-1 (a): what became of a module's override or injection the first time it was about to draw.
 * [Ok] = it composed in the probe and is drawn; [Faulted] = it threw there, so the original is drawn instead (a replace or
 * wrap: the node as core drew it; a before / after / injection: nothing).
 */
sealed interface OverrideVerdict {
    data object Ok : OverrideVerdict
    data class Faulted(val message: String) : OverrideVerdict
}

/**
 * GAP-AUDIT-1 (a), the error boundary the Block API provides (docs/PLUGIN-OVERRIDES.md section 3). Compose forbids a
 * try/catch around a composable call, so a throwing override cannot fall back while the window draws. Instead, before
 * a module's block is drawn the first time, it is composed once in a throwaway [Composition] of its own (no UI applier,
 * effects never run: [ProbeDispatcher]) with the window's composition locals; that composition is plain code, so its
 * exception is caught. Until the probe answers, the original is drawn (one frame); a block that threw is never drawn
 * and its fault is listed (Settings > Plugins > Registered, [faults]). The verdict is kept per block instance: a
 * reloaded module (a new instance) is probed again.
 *
 * Not covered: a block that composes fine and throws only on a later recomposition (a state it reads changes), and the
 * content of a lazy list / subcomposition, which composes at measure time, not in the probe.
 */
object OverrideHealth {
    /**
     * VOICEINPUT-BLANK-1: a verdict and the instance it was given to. Deliberately NOT a data class (nor a Pair): the state
     * map drops a put whose value EQUALS the stored one, and a live plugin swap hands the host equal copies of unchanged
     * registrations (a data-class [com.superiorsoftllc.infantry.ui.plugin.PanelProvider] copy). With an equality-compared
     * value the new copy's verdict was never stored, [verdict] never matched it, and every such block drew its fallback
     * (nothing) for good. Identity equality makes each record land.
     */
    private class Entry(instance: Any, val verdict: OverrideVerdict) {
        // PLUGIN-LIFECYCLE-1: weak. This map is process-wide; a strong ref to a module's registration (its renderer, panel
        // provider) pinned the module's class loader after the plugin was disabled or removed ("did not unload cleanly").
        private val ref = WeakRef(instance)
        fun isFor(other: Any): Boolean = ref.get() === other
    }

    private val verdicts = mutableStateMapOf<String, Entry>()

    /** The verdict for [key] if it was given to this very [instance], else null (not probed yet). */
    fun verdict(key: String, instance: Any): OverrideVerdict? = verdicts[key]?.takeIf { it.isFor(instance) }?.verdict

    /** Every faulted block by key (`<module>/<mode>/<node id>`), with the exception's message. */
    val faults: Map<String, String>
        get() = verdicts.mapNotNull { (k, v) -> (v.verdict as? OverrideVerdict.Faulted)?.let { k to it.message } }.toMap()

    internal fun record(key: String, instance: Any, verdict: OverrideVerdict) { verdicts[key] = Entry(instance, verdict) }

    /** Tests: forget every verdict. */
    fun clear() = verdicts.clear()

    /** The key of [module]'s [mode] block on node [node]. */
    fun key(module: String, mode: String, node: String) = "$module/$mode/$node"
}

/**
 * GAP-AUDIT-1 (a): draws [content] (a module's block, [instance] its registration) once the probe found it composes,
 * [fallback] until then and for good if it threw.
 */
@Composable
internal fun GuardedBlock(
    key: String,
    instance: Any,
    fallback: @Composable () -> Unit,
    /** What the probe composes; [content] unless the drawn form differs (a wrap draws its inner node as movable content). */
    probe: (@Composable () -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    when (OverrideHealth.verdict(key, instance)) {
        OverrideVerdict.Ok -> content()
        is OverrideVerdict.Faulted -> fallback()
        null -> {
            fallback()
            val locals = currentCompositionLocalContext
            val latest by rememberUpdatedState(probe ?: content)
            LaunchedEffect(key, instance) {
                val error = probeComposition(locals) { latest() }
                OverrideHealth.record(key, instance, if (error == null) OverrideVerdict.Ok else OverrideVerdict.Faulted(error.toString()))
            }
        }
    }
}

/**
 * GAP-AUDIT-1 (a): composes [content] once in a composition of its own and returns what it threw, or null. Nothing is
 * drawn (the applier drops every node) and no effect coroutine runs; the composition is disposed before this returns.
 */
fun probeComposition(locals: CompositionLocalContext? = null, content: @Composable () -> Unit): Throwable? {
    val job = Job()
    val recomposer = Recomposer(job + ProbeDispatcher)
    val composition = Composition(ProbeApplier(), recomposer)
    return try {
        composition.setContent { if (locals != null) CompositionLocalProvider(locals) { content() } else content() }
        null
    } catch (e: CancellationException) {
        throw e
    } catch (t: Throwable) {
        t
    } finally {
        runCatching { composition.dispose() }
        recomposer.cancel()
        job.cancel()
    }
}

/** The probe's applier: it takes every node and keeps none. */
private class ProbeApplier : AbstractApplier<Any>(Unit) {
    override fun insertTopDown(index: Int, instance: Any) {}
    override fun insertBottomUp(index: Int, instance: Any) {}
    override fun remove(index: Int, count: Int) {}
    override fun move(from: Int, to: Int, count: Int) {}
    override fun onClear() {}
}

/** The probe's effects dispatcher: it never runs what it is given, so a probed block's LaunchedEffect never starts. */
private object ProbeDispatcher : CoroutineDispatcher() {
    override fun dispatch(context: CoroutineContext, block: Runnable) {}
}
