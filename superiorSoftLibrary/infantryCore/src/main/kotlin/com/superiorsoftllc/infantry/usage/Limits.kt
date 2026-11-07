// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.usage

import com.superiorsoftllc.infantry.daemon.api.usage.LimitWindow
import com.superiorsoftllc.infantry.session.InfantryEnv
import com.superiorsoftllc.infantry.settings.LimitRegistry

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import java.io.File
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.time.OffsetDateTime
import java.util.concurrent.TimeUnit

/*
 * U1 (contract 1.59.0): plan-limit meters, read live from each provider's own usage endpoint.
 *
 * SECURITY. A source's credential is read from the operator's local files when the source runs, sent ONLY as the
 * Authorization header of its own provider's endpoint, and then dropped: it is never logged, stored, put in a
 * [ProviderLimits] (so never on the wire), and never part of a reason -- a reason is one of the fixed codes below,
 * never an exception message. `LimitsTest` pins this.
 *
 * NO SIGNAL IS NEVER 0%. A source that cannot answer gives `status = "unknown"`, no windows and a [ProviderLimits.reason].
 */


/** One provider's windows (wire: the `limits` frame carries a list of these). */
@Serializable
data class ProviderLimits(
    val provider: String,
    /** "ok" (windows read) or "unknown" (see [reason]). */
    val status: String,
    val windows: List<LimitWindow>,
    /** Null when ok, else a fixed code: see [LimitReason]. */
    val reason: String?,
    val observedAt: Long,
    /**
     * USAGE-CARD-1: true when the last read failed ([reason]) and [windows] are the last GOOD reading, kept while it is
     * younger than `limits.usage.staleMaxHours`; [observedAt] is then when that good reading was taken.
     */
    val stale: Boolean = false,
    /** USAGE-CARD-1: when the source will be read again after a failure (Retry-After or backoff); null = no backoff. */
    val retryAt: Long? = null,
) {
    companion object {
        fun unknown(provider: String, now: Long, reason: String) = ProviderLimits(provider, "unknown", emptyList(), reason, now)
    }
}

/** The only strings a [ProviderLimits.reason] may hold (plus `http_<code>`). */
object LimitReason {
    const val NO_CREDENTIALS = "no_credentials"
    const val EXPIRED = "expired"
    const val NO_SUBSCRIPTION = "no_subscription"
    const val RATE_LIMITED = "rate_limited"
    const val NETWORK = "network"
    const val BAD_RESPONSE = "bad_response"
    const val LIVE_SOURCES_OFF = "live_sources_off"
    const val SOURCE_FAILED = "source_failed"
    /** USAGE-PACE (1.119.0): the first live read has not landed yet. */
    const val NOT_READ_YET = "not_read_yet"

    fun ofStatus(status: Int): String? = when (status) {
        in 200..299 -> null
        401 -> EXPIRED
        403 -> NO_SUBSCRIPTION
        429 -> RATE_LIMITED
        else -> "http_$status"
    }
}

/** [retryAfterMs]: the response's `Retry-After` as a delay in ms (seconds or an HTTP date), null when absent or unreadable. */
data class LimitHttpResponse(val status: Int, val body: String, val retryAfterMs: Long? = null)

/** `Retry-After`: delta-seconds or an HTTP date (RFC 1123), as a delay from [now]; null when it reads as neither. */
fun parseRetryAfter(value: String?, now: Long): Long? {
    val v = value?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    v.toLongOrNull()?.let { return if (it >= 0) it * 1000 else null }
    return runCatching {
        java.time.ZonedDateTime.parse(v, java.time.format.DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli() - now
    }.getOrNull()?.coerceAtLeast(0)
}

/** The one HTTP call a live source makes; injectable so tests never touch the network. Throws on a network failure. */
fun interface LimitHttp {
    fun get(url: String, headers: Map<String, String>): LimitHttpResponse
}

/** The JDK client, 12 s connect and request timeout. */
object JdkLimitHttp : LimitHttp {
    private val timeout: Duration = Duration.ofSeconds(12)
    private val client: HttpClient by lazy { HttpClient.newBuilder().connectTimeout(timeout).build() }

    override fun get(url: String, headers: Map<String, String>): LimitHttpResponse {
        val request = HttpRequest.newBuilder(URI.create(url)).timeout(timeout).GET()
        headers.forEach { (k, v) -> request.header(k, v) }
        val response = client.send(request.build(), HttpResponse.BodyHandlers.ofString())
        val retryAfter = parseRetryAfter(response.headers().firstValue("Retry-After").orElse(null), System.currentTimeMillis())
        return LimitHttpResponse(response.statusCode(), response.body() ?: "", retryAfter)
    }
}

/** A provider's plan limits, read at [now]. Never throws for an expected failure: it reports a reason instead. */
interface LimitSource {
    val provider: String
    fun read(now: Long): ProviderLimits
}

/** Expected share of a window at [now] (elapsed / window x 100); the delta is [percent] minus it, to one decimal. */
object LimitPace {
    fun delta(percent: Double?, resetsAt: Long?, windowMinutes: Int, now: Long): Double? {
        if (percent == null || resetsAt == null || windowMinutes <= 0) return null
        val windowMs = windowMinutes * 60_000.0
        val elapsed = windowMs - (resetsAt - now).toDouble().coerceIn(0.0, windowMs)
        val expected = elapsed / windowMs * 100.0
        return Math.round((percent - expected) * 10.0) / 10.0
    }
}

/** Shared HTTP read: credential, call, status -> reason, parse -> windows with pace. */
private fun liveRead(
    provider: String,
    now: Long,
    http: LimitHttp,
    url: String,
    credential: () -> String?,
    headers: (String) -> Map<String, String>,
    parse: (body: String, now: Long) -> List<LimitWindow>,
): ProviderLimits {
    val secret = credential() ?: return ProviderLimits.unknown(provider, now, LimitReason.NO_CREDENTIALS)
    // Any failure of the call itself is `network`: the exception (whose message could carry anything) is dropped.
    val response = try {
        http.get(url, headers(secret))
    } catch (e: Exception) {
        return ProviderLimits.unknown(provider, now, LimitReason.NETWORK)
    }
    LimitReason.ofStatus(response.status)?.let { reason ->
        // USAGE-CARD-1: the provider's own Retry-After, when it sent one, is when the service may ask again.
        return ProviderLimits.unknown(provider, now, reason).copy(retryAt = response.retryAfterMs?.let { now + it })
    }
    val windows = runCatching { parse(response.body, now) }.getOrNull()
    if (windows.isNullOrEmpty()) return ProviderLimits.unknown(provider, now, LimitReason.BAD_RESPONSE)
    return ProviderLimits(provider, "ok", windows, null, now)
}

/** A window from a provider's raw `{percent, resetsAt}` pair, with its pace. */
private fun window(provider: String, label: String, minutes: Int, percent: JsonElement?, resetsAt: JsonElement?, now: Long): LimitWindow {
    val pct = percentOf(percent)
    val reset = timeOf(resetsAt)
    return LimitWindow(provider, label, minutes, pct, reset, now, "live", LimitPace.delta(pct, reset, minutes, now))
}

/** A number, or a string such as `"42"` / `"42.5%"`. */
private fun percentOf(element: JsonElement?): Double? {
    val p = element as? JsonPrimitive ?: return null
    return if (p.isString) p.content.trim().removeSuffix("%").trim().toDoubleOrNull() else p.doubleOrNull
}

/** ISO-8601 (with or without an offset), or epoch seconds / millis. */
private fun timeOf(element: JsonElement?): Long? {
    val p = element as? JsonPrimitive ?: return null
    val number = if (p.isString) p.content.trim().toDoubleOrNull() else p.doubleOrNull
    if (number != null) return number.toLong().let { if (it < 100_000_000_000L) it * 1000 else it }
    if (!p.isString) return null
    return runCatching { OffsetDateTime.parse(p.content.trim()).toInstant().toEpochMilli() }.getOrNull()
}

/** Claude: `GET https://api.anthropic.com/api/oauth/usage` with Claude Code's own OAuth access token. */
class ClaudeOAuthUsageSource(
    private val http: LimitHttp = JdkLimitHttp,
    private val token: () -> String? = { LocalCredentials.claudeAccessToken() },
) : LimitSource {
    override val provider = "claude"

    override fun read(now: Long): ProviderLimits = liveRead(
        provider, now, http, URL, token,
        headers = { t ->
            mapOf(
                "Authorization" to "Bearer $t",
                "anthropic-beta" to "oauth-2025-04-20",
                "User-Agent" to "claude-cli/1.0 (external, cli)",
                "Accept" to "application/json",
            )
        },
        parse = ::parse,
    )

    companion object {
        const val URL = "https://api.anthropic.com/api/oauth/usage"

        /** `five_hour` / `seven_day` objects, or a `limits` array some accounts get instead. */
        fun parse(body: String, now: Long): List<LimitWindow> {
            val root = Json.parseToJsonElement(body).jsonObject
            val byKind = LinkedHashMap<String, JsonObject>()
            (root["five_hour"] as? JsonObject)?.let { byKind["five_hour"] = it }
            (root["seven_day"] as? JsonObject)?.let { byKind["seven_day"] = it }
            (root["limits"] as? JsonArray)?.forEach { item ->
                val obj = item as? JsonObject ?: return@forEach
                val kind = listOf("type", "name", "window", "id").firstNotNullOfOrNull { obj.str(it) }?.lowercase() ?: return@forEach
                when {
                    "five" in kind || "5h" in kind || "session" in kind -> byKind.putIfAbsent("five_hour", obj)
                    "seven" in kind || "week" in kind -> byKind.putIfAbsent("seven_day", obj)
                }
            }
            return listOfNotNull(
                byKind["five_hour"]?.let { claudeWindow("Current session", 300, it, now) },
                byKind["seven_day"]?.let { claudeWindow("Current week", 10_080, it, now) },
            )
        }

        private fun claudeWindow(label: String, minutes: Int, obj: JsonObject, now: Long) =
            window("claude", label, minutes, obj["utilization"] ?: obj["percent"], obj["resets_at"] ?: obj["resetsAt"], now)
    }
}

/** OpenCode Go: `GET https://opencode.ai/zen/go/v1/usage` with the `opencode-go` key from opencode's auth.json. */
class OpenCodeGoUsageSource(
    private val http: LimitHttp = JdkLimitHttp,
    private val key: () -> String? = { LocalCredentials.openCodeGoKey() },
) : LimitSource {
    override val provider = "opencode-go"

    override fun read(now: Long): ProviderLimits = liveRead(
        provider, now, http, URL, key,
        headers = { k -> mapOf("Authorization" to "Bearer $k", "Accept" to "application/json") },
        parse = ::parse,
    )

    companion object {
        const val URL = "https://opencode.ai/zen/go/v1/usage"

        /** `usage.rolling` (5h), `usage.weekly` (7d), `usage.monthly` (30d), each `{percent, resetsAt}`. */
        fun parse(body: String, now: Long): List<LimitWindow> {
            val usage = Json.parseToJsonElement(body).jsonObject.obj("usage") ?: return emptyList()
            return listOf(Triple("rolling", "5h limit", 300), Triple("weekly", "Weekly limit", 10_080), Triple("monthly", "Monthly limit", 43_200))
                .mapNotNull { (key, label, minutes) ->
                    usage.obj(key)?.let { window("opencode-go", label, minutes, it["percent"], it["resetsAt"] ?: it["resets_at"], now) }
                }
        }
    }
}

/** Reads the providers' local credentials. Values are returned to the caller only; nothing here logs them. */
object LocalCredentials {
    private val home: File get() = File(System.getProperty("user.home"))

    /** `$CLAUDE_CONFIG_DIR/.credentials.json`, else `~/.claude/.credentials.json` -> `claudeAiOauth.accessToken`. */
    fun claudeAccessToken(env: (String) -> String? = InfantryEnv::get, userHome: File = home): String? {
        val dir = env("CLAUDE_CONFIG_DIR")?.takeIf { it.isNotBlank() }?.let(::File) ?: File(userHome, ".claude")
        return jsonFile(File(dir, ".credentials.json"))?.obj("claudeAiOauth")?.str("accessToken")
    }

    /** `$XDG_DATA_HOME/opencode/auth.json`, else `~/.local/share/opencode/auth.json` -> `["opencode-go"].key`. */
    fun openCodeGoKey(env: (String) -> String? = InfantryEnv::get, userHome: File = home): String? {
        val dir = env("XDG_DATA_HOME")?.takeIf { it.isNotBlank() }?.let { File(it, "opencode") }
            ?: File(userHome, ".local/share/opencode")
        return jsonFile(File(dir, "auth.json"))?.obj("opencode-go")?.str("key")
    }

    private fun jsonFile(file: File): JsonObject? =
        runCatching { Json.parseToJsonElement(file.readText()).jsonObject }.getOrNull()
}

private fun JsonObject.obj(key: String): JsonObject? = this[key] as? JsonObject
private fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content?.takeIf { it.isNotBlank() }

/**
 * USAGE-CARD-1: how the service rides out a failing source. [staleMaxMs]: a last good reading is kept (shown stale, and used
 * by pacing) while it is younger than this. [backoffBaseMs] / [backoffMaxMs]: after a transient failure with no
 * `Retry-After`, the source is not asked again for base x 2^(failures-1), at most max. [manualFloorMs]: a forced read
 * (the Refresh button, `usage_get force`) inside this long after the last read answers the last read instead.
 */
data class UsageReadTuning(
    val staleMaxMs: Long = LimitRegistry.USAGE_STALE_MAX_HOURS.default * 3_600_000L,
    val backoffBaseMs: Long = LimitService.DEFAULT_MIN_INTERVAL_MS,
    val backoffMaxMs: Long = LimitRegistry.USAGE_BACKOFF_MAX_MINUTES.default * 60_000L,
    val manualFloorMs: Long = LimitRegistry.USAGE_MANUAL_REFRESH_FLOOR_SECONDS.default * 1000L,
) {
    /** base x 2^(failures-1), capped at max (never below base); 0 failures = no wait. */
    fun backoffMs(failures: Int): Long {
        if (failures <= 0) return 0
        val base = backoffBaseMs.coerceAtLeast(1)
        val cap = backoffMaxMs.coerceAtLeast(base)
        var d = base
        repeat((failures - 1).coerceAtMost(30)) { d = (d * 2).coerceAtMost(cap) }
        return d
    }
}

/**
 * The daemon's limit meters: every source, read on request at most once per [minIntervalMs] (the answer in between is
 * the cached one, so a burst of requests cannot earn a 429). [liveEnabled] false = no source runs, no file is read, no
 * HTTP call is made; every provider reads `unknown` / `live_sources_off`.
 *
 * USAGE-CARD-1: ONE source per provider (the first listed wins: a harness plugin that polls the same provider as a
 * built-in source is not asked, so one refresh is one call to that endpoint and the panel shows one card). Per provider
 * the service keeps the last good reading: a transient failure (429, network, bad answer, `http_*`) answers that reading
 * marked [ProviderLimits.stale] while it is younger than [UsageReadTuning.staleMaxMs], and the source is not asked again
 * until its backoff ends (`Retry-After`, else exponential), whoever asks (the refresher, a client's Refresh, pacing).
 */
class LimitService(
    private val sources: List<LimitSource>,
    private val liveEnabled: () -> Boolean,
    private val clock: () -> Long = System::currentTimeMillis,
    private val minIntervalMs: Long = DEFAULT_MIN_INTERVAL_MS,
    private val log: (String) -> Unit = { System.err.println(it) },
    /** HARNESS H5c: usage sources contributed by harness plugins, read on every call (a module may load or unload). */
    private val extraSources: () -> List<LimitSource> = { emptyList() },
) {
    /** USAGE-CARD-1: read at every snapshot, so a changed setting counts at once. The daemon wires it to its settings. */
    @Volatile var tuning: () -> UsageReadTuning = { UsageReadTuning() }

    private val loggedDuplicates = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    /** DEMO-USAGE-HIDE-1: sources a daemon plugin offered; non-empty = they are read INSTEAD of every other source, live or not. */
    @Volatile var replacement: () -> List<LimitSource> = { emptyList() }

    private fun allSources(): List<LimitSource> {
        runCatching(replacement).getOrDefault(emptyList()).takeIf { it.isNotEmpty() }?.let { return it }
        val all = sources + runCatching(extraSources).getOrDefault(emptyList())
        val kept = all.distinctBy { it.provider }
        if (kept.size != all.size) {
            all.filter { s -> kept.none { it === s } }.map { it.provider }.filter { loggedDuplicates.add(it) }
                .forEach { log("[infantry] limits $it: a second usage source for the same provider is ignored (one read per refresh)") }
        }
        return kept
    }

    /** Per provider: the last good reading, the consecutive transient failures, and when it may be read again. */
    private class SourceState(var lastGood: ProviderLimits? = null, var failures: Int = 0, var retryAt: Long = 0, var lastReason: String? = null)
    private val states = HashMap<String, SourceState>()

    private var cached: List<ProviderLimits>? = null
    private var cachedAt = 0L
    /** One thread at most; it ends when idle (ticket 3b30398d: a daemon's limit service never kept a thread after it stopped). */
    private val worker by lazy {
        java.util.concurrent.ThreadPoolExecutor(
            1, 1, 30, TimeUnit.SECONDS, java.util.concurrent.LinkedBlockingQueue(),
        ) { r -> Thread(r, "infantry-limits").apply { isDaemon = true } }.apply { allowCoreThreadTimeOut(true) }
    }

    @Synchronized
    fun snapshot(force: Boolean = false): List<ProviderLimits> {
        val now = clock()
        if (!liveEnabled() && runCatching(replacement).getOrDefault(emptyList()).isEmpty()) return allSources().map { ProviderLimits.unknown(it.provider, now, LimitReason.LIVE_SOURCES_OFF) }
        val t = tuning()
        cached?.let { last ->
            val age = now - cachedAt
            if (!force && age < minIntervalMs) return expire(last, now, t)
            // A forced read just after the last one (a double click) answers the last one: a click cannot hammer the endpoint.
            if (force && age < t.manualFloorMs) return expire(last, now, t)
        }
        val fresh = allSources().map { readOne(it, now, t) }
        cached = fresh
        cachedAt = now
        return fresh
    }

    private fun readOne(s: LimitSource, now: Long, t: UsageReadTuning): ProviderLimits {
        val st = states.getOrPut(s.provider) { SourceState() }
        if (st.retryAt > now) return failedView(s.provider, st, now, t) // backing off: the source is not asked
        val r = runCatching { s.read(now) }.getOrElse { ProviderLimits.unknown(s.provider, now, LimitReason.SOURCE_FAILED) }
        if (r.reason == null) {
            st.lastGood = r; st.failures = 0; st.retryAt = 0; st.lastReason = null
            return r
        }
        if (!UsageRefresher.isFailure(r)) {
            // Not signed in, no subscription...: a retry cannot fix it, and an old reading would mislead.
            st.lastGood = null; st.failures = 0; st.retryAt = 0; st.lastReason = r.reason
            log("[infantry] limits ${s.provider}: unknown (${r.reason})")
            return r
        }
        st.failures++
        st.lastReason = r.reason
        st.retryAt = r.retryAt?.coerceAtLeast(now) ?: (now + t.backoffMs(st.failures))
        // The reason is a fixed code, never an exception message: nothing here can echo a credential.
        log("[infantry] limits ${s.provider}: unknown (${r.reason}); next read in ${(st.retryAt - now) / 60_000} min")
        return failedView(s.provider, st, now, t)
    }

    private fun failedView(provider: String, st: SourceState, now: Long, t: UsageReadTuning): ProviderLimits {
        val reason = st.lastReason ?: LimitReason.SOURCE_FAILED
        val retry = st.retryAt.takeIf { it > now }
        val good = st.lastGood?.takeIf { now - it.observedAt < t.staleMaxMs }
        return good?.copy(stale = true, reason = reason, retryAt = retry)
            ?: ProviderLimits.unknown(provider, now, reason).copy(retryAt = retry)
    }

    /** A reading that grew older than the stale limit while cached is unknown from then on (pacing fails open). */
    private fun expire(list: List<ProviderLimits>, now: Long, t: UsageReadTuning): List<ProviderLimits> = list.map { p ->
        if (p.windows.isNotEmpty() && now - p.observedAt >= t.staleMaxMs)
            ProviderLimits.unknown(p.provider, now, p.reason ?: LimitReason.SOURCE_FAILED).copy(retryAt = p.retryAt)
        else p
    }

    /**
     * USAGE-PACE: the last read, never blocking (the pacing gate runs inside the board's synchronized start). A stale or
     * missing read starts a refresh off this thread; until it lands the answer is the old read (a stale-but-young one
     * included, USAGE-CARD-1), or every source `unknown` (the pacer then fails open).
     */
    fun cachedOrRefresh(): List<ProviderLimits> {
        val now = clock()
        if (!liveEnabled()) return allSources().map { ProviderLimits.unknown(it.provider, now, LimitReason.LIVE_SOURCES_OFF) }
        val (last, at) = synchronized(this) { cached to cachedAt }
        if (last == null || now - at >= minIntervalMs) snapshotAsync {}
        return last?.let { expire(it, now, tuning()) } ?: allSources().map { ProviderLimits.unknown(it.provider, now, LimitReason.NOT_READ_YET) }
    }

    /** [snapshot] off the caller's thread (a live read can take 12 s per source), then [onReady]. */
    fun snapshotAsync(onReady: (List<ProviderLimits>) -> Unit) {
        if (allSources().isEmpty()) return
        worker.execute { runCatching { onReady(snapshot()) } }
    }

    companion object {
        const val DEFAULT_MIN_INTERVAL_MS = 5 * 60_000L

        /** `INFANTRY_LIMITS_LIVE=false` (or `0` / `off`) turns the token-reading HTTP calls off. Default on. */
        const val LIVE_ENV = "INFANTRY_LIMITS_LIVE"

        /** No sources: answers an empty list, reads nothing. */
        val NONE = LimitService(emptyList(), liveEnabled = { false })

        fun liveFromEnv(env: (String) -> String? = InfantryEnv::get): Boolean =
            env(LIVE_ENV)?.trim()?.lowercase() !in setOf("false", "0", "off", "no")

        fun fromEnv(env: (String) -> String? = InfantryEnv::get, http: LimitHttp = JdkLimitHttp, extraSources: () -> List<LimitSource> = { emptyList() }): LimitService =
            LimitService(listOf(ClaudeOAuthUsageSource(http), OpenCodeGoUsageSource(http)), liveEnabled = { liveFromEnv(env) }, extraSources = extraSources)
    }
}
