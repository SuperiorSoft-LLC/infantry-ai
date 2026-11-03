// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.usagemeter

import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * STATS-ACC-1: the price table lives in `usage-prices.json` in the state dir (USD per million tokens, first model-id
 * substring match wins), seeded once with [DEFAULTS] and re-read when the file changes. Edit the file to change prices;
 * the UI only ever displays what the report carries and flags every cost as an estimate.
 */
class UsagePrices(private val file: File) {
    private val json = Json { prettyPrint = true; ignoreUnknownKeys = true }
    private val ser = ListSerializer(UsageMeter.Price.serializer())
    @Volatile private var cached: List<UsageMeter.Price> = emptyList()
    @Volatile private var stamp = Long.MIN_VALUE

    fun current(): List<UsageMeter.Price> {
        val mod = if (file.exists()) file.lastModified() else -1L
        if (mod == stamp) return cached
        if (mod == -1L) runCatching { file.absoluteFile.parentFile?.mkdirs(); file.writeText(json.encodeToString(ser, DEFAULTS)) }
        cached = runCatching { json.decodeFromString(ser, file.readText()) }.getOrDefault(DEFAULTS)
        stamp = if (file.exists()) file.lastModified() else -1L
        return cached
    }

    companion object {
        /** List prices at the time of writing; an estimate, not a bill. Models matching nothing show cost n/a. */
        val DEFAULTS = listOf(
            UsageMeter.Price("haiku", 1.0, 5.0, 0.10, 1.25),
            UsageMeter.Price("sonnet", 3.0, 15.0, 0.30, 3.75),
            UsageMeter.Price("opus", 5.0, 25.0, 0.50, 6.25),
        )
    }
}

/** Coalesces bursts of "something was recorded" into at most one [fire] per [windowMs] (leading edge immediate, trailing edge guaranteed). */
class PushDebounce(private val windowMs: Long = 250, private val fire: () -> Unit) {
    private val exec = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "usage-meter-push").also { it.isDaemon = true } }
    private val pendingTrailing = AtomicReference<ScheduledFuture<*>?>(null)
    @Volatile private var lastFire = 0L

    @Synchronized
    fun poke() {
        val now = System.currentTimeMillis()
        if (now - lastFire >= windowMs) { lastFire = now; exec.execute { runCatching(fire) }; return }
        if (pendingTrailing.get()?.isDone == false) return
        pendingTrailing.set(exec.schedule({ lastFire = System.currentTimeMillis(); runCatching(fire) }, windowMs - (now - lastFire), TimeUnit.MILLISECONDS))
    }
}
