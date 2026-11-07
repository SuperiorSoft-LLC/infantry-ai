// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.plugins.official.notify.daemon

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import java.io.File
import java.time.LocalTime
import java.util.Base64
import java.util.UUID

/** NOTIFY-1: a ticket marked as a milestone: when it enters done, a `milestone` notification fires with [sound]. */
data class Milestone(val ticketId: String, val sound: String? = null, val lastFiredSeq: Long = 0, val setAt: Long = 0)

/**
 * NOTIFY-1: the plugin's whole behaviour, free of the daemon so it is tested directly.
 *  - [notify] builds a [NotifyMessage] (sound inlined as base64), queues it and pushes it through [push].
 *  - The queue holds every pushed notification until a client acks it ([ack]) or it is older than `notify.queueMaxAgeMinutes`;
 *    [pending] is what a client that connects receives, so a notification sent while no client was connected is delivered
 *    on the next connect. A client dedupes by id, so a replay never shows one twice.
 *  - Milestones: [setMilestone] marks a ticket; [onTicketDone] fires once per done event of a marked ticket (dedupe on seq).
 *    Nothing fires for a ticket that is not marked, and nothing in this plugin fires on its own.
 */
class NotifyService(
    stateDir: File,
    private val settings: () -> NotifySettings,
    private val push: (NotifyMessage) -> Unit,
    private val now: () -> Long = System::currentTimeMillis,
    private val localTime: () -> LocalTime = LocalTime::now,
    private val log: (String) -> Unit = {},
) {
    val sounds = NotifySounds(File(stateDir, "sounds").apply { mkdirs() })
    private val queueFile = File(stateDir, "pending.json")
    private val milestoneFile = File(stateDir, "milestones.json")
    private val deliveredFile = File(stateDir, "delivered.json")
    private val json = Json { ignoreUnknownKeys = true }
    private val lock = Any()
    private val queue = ArrayList<NotifyMessage>(load(queueFile) { a -> (a as? JsonArray)?.mapNotNull(NotifyMessage::fromJson) }.orEmpty())
    /** NOTIFY-PUSH-1: notification id -> the devices (`desktop`, `android`, `web`) that acked it; one device's ack never hides it from another. */
    private val delivered = HashMap<String, MutableSet<String>>().apply {
        load(deliveredFile) { o -> (o as? JsonObject)?.mapValues { (_, v) -> (v as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }?.toMutableSet() ?: mutableSetOf() } }?.let { putAll(it) }
    }
    private val milestones = LinkedHashMap<String, Milestone>().apply {
        load(milestoneFile) { o -> (o as? JsonObject)?.mapNotNull { (id, v) -> milestoneOf(id, v as? JsonObject) } }?.forEach { put(it.ticketId, it) }
    }

    sealed interface Outcome {
        data class Sent(val message: NotifyMessage, val silentReason: String? = null) : Outcome
        data class Off(val reason: String) : Outcome
    }

    /** One notification. Throws [IllegalArgumentException] for an unknown sound. */
    fun notify(req: NotifyRequest, projectId: String? = null): Outcome {
        val s = settings()
        if (!s.enabled) return Outcome.Off("notifications are off (setting notify.enabled)")
        val wanted = req.sound ?: if (req.level == NotifyLevel.MILESTONE) s.milestoneSound else s.defaultSound
        val sound = sounds.resolve(wanted)
        var silentReason: String? = null
        val carried = when {
            sound == null -> null
            req.level != NotifyLevel.URGENT && s.inQuietHours(localTime()) -> { silentReason = "quiet hours (${s.quietHours})"; null }
            sound.bytes.size > s.maxSoundBytes -> { silentReason = "sound ${sound.name} is ${sound.bytes.size} bytes, over notify.maxSoundBytes"; null }
            else -> sound
        }
        val msg = NotifyMessage(
            id = UUID.randomUUID().toString(), title = req.title, body = req.body, level = req.level, target = req.target, at = now(),
            sound = carried?.name, soundType = carried?.type, soundData = carried?.let { Base64.getEncoder().encodeToString(it.bytes) },
            ticketId = req.ticketId, url = req.url, projectId = projectId,
        )
        synchronized(lock) {
            prune()
            queue += msg
            while (queue.size > maxOf(0, s.queueMax)) queue.removeAt(0)
            saveQueue()
            trimDelivered()
        }
        runCatching { push(msg) }.onFailure { log("push failed: ${it.message}") }
        return Outcome.Sent(msg, silentReason)
    }

    /**
     * A client showed [id]. With a [device] only that device's copy is done (the queue keeps it for the others until it ages out);
     * without one (a client from before NOTIFY-PUSH-1) the notification leaves the queue for everyone. Returns false when it was not queued.
     */
    fun ack(id: String, device: String? = null): Boolean = synchronized(lock) {
        if (queue.none { it.id == id }) return@synchronized false
        if (device.isNullOrBlank()) { queue.removeAll { it.id == id }; delivered.remove(id); saveQueue() }
        else { delivered.getOrPut(id) { mutableSetOf() } += device.lowercase(); saveDelivered() }
        true
    }

    /**
     * What a connecting client receives: every queued notification younger than the max age, oldest first. With a [device] the
     * ones that device already acked, and the ones aimed at another device, are left out.
     */
    fun pending(device: String? = null): List<NotifyMessage> = synchronized(lock) {
        prune()
        val d = device?.lowercase()?.takeIf { it.isNotBlank() }
        queue.filter { m -> d == null || (d !in delivered[m.id].orEmpty() && (m.target == "all" || m.target.equals(d, true))) }
    }

    /** The connect replay (the hook does not know the device): only what no device acked yet; a device asks for the rest with `notify_pending`. */
    fun unacked(): List<NotifyMessage> = synchronized(lock) { prune(); queue.filter { delivered[it.id].isNullOrEmpty() } }

    fun setMilestone(ticketId: String, on: Boolean, sound: String?): Milestone? = synchronized(lock) {
        if (sound != null) sounds.resolve(sound)
        val result = if (on) Milestone(ticketId, sound, milestones[ticketId]?.lastFiredSeq ?: 0, now()).also { milestones[ticketId] = it }
        else { milestones.remove(ticketId); null }
        saveMilestones()
        result
    }

    fun milestones(): List<Milestone> = synchronized(lock) { milestones.values.toList() }

    /**
     * A ticket entered done (event [seq]). Fires one milestone notification when the ticket is marked and [seq] is new;
     * returns it, or null when nothing fired.
     */
    fun onTicketDone(ticketId: String, seq: Long, title: String?, projectId: String?): Outcome? {
        val m = synchronized(lock) {
            val m = milestones[ticketId] ?: return null
            if (seq <= m.lastFiredSeq) return null
            milestones[ticketId] = m.copy(lastFiredSeq = seq)
            saveMilestones()
            m
        }
        val name = title?.takeIf { it.isNotBlank() } ?: ticketId
        return runCatching {
            notify(NotifyRequest(title = "Milestone done: ${name.take(NotifyRequest.MAX_TITLE - 16)}", body = "$ticketId is done.",
                level = NotifyLevel.MILESTONE, sound = m.sound, ticketId = ticketId), projectId)
        }.getOrElse {
            // A sound removed since the ticket was marked: still notify, silent.
            log("milestone $ticketId: ${it.message}; sent silent")
            notify(NotifyRequest(title = "Milestone done: ${name.take(NotifyRequest.MAX_TITLE - 16)}", body = "$ticketId is done.",
                level = NotifyLevel.MILESTONE, sound = NotifySounds.NONE, ticketId = ticketId), projectId)
        }
    }

    private fun prune() {
        val maxAgeMs = settings().queueMaxAgeMinutes * 60_000
        val cutoff = now() - maxAgeMs
        if (queue.removeAll { it.at < cutoff }) saveQueue()
        trimDelivered()
    }

    private fun trimDelivered() {
        val live = queue.mapTo(HashSet()) { it.id }
        if (delivered.keys.retainAll(live)) saveDelivered()
    }

    private fun saveDelivered() = write(deliveredFile, JsonObject(delivered.mapValues { (_, d) -> JsonArray(d.map { JsonPrimitive(it) }) }).toString())

    private fun saveQueue() = write(queueFile, JsonArray(queue.map { it.toJson() }).toString())

    private fun saveMilestones() = write(milestoneFile, JsonObject(milestones.mapValues { (_, m) ->
        buildJsonObject { m.sound?.let { put("sound", it) }; put("lastFiredSeq", m.lastFiredSeq); put("setAt", m.setAt) }
    }).toString())

    private fun write(f: File, text: String) {
        runCatching {
            val tmp = File(f.parentFile, f.name + ".tmp")
            tmp.writeText(text)
            if (!tmp.renameTo(f)) { f.delete(); tmp.renameTo(f) }
        }.onFailure { log("could not write ${f.name}: ${it.message}") }
    }

    private fun <T> load(f: File, read: (kotlinx.serialization.json.JsonElement) -> T?): T? =
        if (!f.isFile) null else runCatching { read(json.parseToJsonElement(f.readText())) }.getOrNull()

    private fun milestoneOf(id: String, o: JsonObject?): Milestone? = o?.let {
        Milestone(id, (it["sound"] as? JsonPrimitive)?.contentOrNull, (it["lastFiredSeq"] as? JsonPrimitive)?.longOrNull ?: 0,
            (it["setAt"] as? JsonPrimitive)?.longOrNull ?: 0)
    }
}
