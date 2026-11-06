// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.plugins.official.notify

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.superiorsoftllc.infantry.plugin.api.ConfigDeclaration
import com.superiorsoftllc.infantry.plugin.api.ConfigFieldDeclaration
import com.superiorsoftllc.infantry.plugin.api.InjectDeclaration
import com.superiorsoftllc.infantry.plugin.api.ModuleManifest
import com.superiorsoftllc.infantry.ui.Palette
import com.superiorsoftllc.infantry.ui.TextButton
import com.superiorsoftllc.infantry.ui.block.layout.BlockInjection
import com.superiorsoftllc.infantry.ui.plugin.InfantryModule
import com.superiorsoftllc.infantry.ui.plugin.ModuleSource
import com.superiorsoftllc.infantry.ui.plugin.UiPlugin
import com.superiorsoftllc.infantry.ui.plugin.UiRegistrar
import com.superiorsoftllc.infantry.ui.plugin.config.FieldType
import com.superiorsoftllc.infantry.ui.shell.LinkContext
import com.superiorsoftllc.infantry.ui.shell.LocalShellContext
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonElement

/** The field type of the settings card's sound list (module.json `fieldTypes`). */
const val NOTIFY_SOUNDS_FIELD = "notify.sounds"

/** How long the Play button and the sound list wait for the daemon's answer. */
const val NOTIFY_ANSWER_TIMEOUT_MS = 5_000L

private val notifyManifest = ModuleManifest(
    name = "Infantry_Notify", version = "1.0.0", author = "Infantry AI",
    description = "System notifications for milestones only (Windows toast, Android notification, browser notification) with custom sounds and a script bridge.",
    inject = listOf(InjectDeclaration(into = "infantry.shell.windowLayer", name = "listener", at = "last", order = 900)),
    fieldTypes = listOf(NOTIFY_SOUNDS_FIELD),
    config = listOf(
        ConfigDeclaration(
            node = "infantry.notify.sounds", group = "Notification sounds", title = "Notification sounds", tab = "Plugins", order = 921,
            applies = "Applies to: the sounds a notification can play. Add your own .wav/.mp3/.ogg to <state dir>/plugin-data/Infantry_Notify/sounds/.",
            keywords = listOf("notify", "notification", "sound", "toast", "milestone"),
            fields = listOf(ConfigFieldDeclaration(key = "notify.soundList", type = NOTIFY_SOUNDS_FIELD, label = "Sounds", scope = "device", order = 1)),
        ),
    ),
)

private val listenerInjection = BlockInjection { NotifyListener() }

/** Infantry_Notify's UI half, compiled in on web and Android. */
val InfantryNotify = InfantryModule(
    notifyManifest,
    inject = mapOf("listener" to listenerInjection),
    fieldTypes = mapOf(NOTIFY_SOUNDS_FIELD to SoundsField),
)

/** The entry of Infantry_Notify (module.json `entry`): desktop loads it from its signed jar; web and Android compile it in. */
class NotifyPlugin : UiPlugin, ModuleSource {
    override fun register(registrar: UiRegistrar) {
        registrar.inject("listener", listenerInjection)
        registrar.fieldType(NOTIFY_SOUNDS_FIELD, SoundsField)
    }

    override fun modules(): List<InfantryModule> = listOf(InfantryNotify)
}

/**
 * The invisible block in the shell's window layer: shows every `notify_push` meant for this platform as a system notification,
 * plays its sound, acks it (so the daemon's queue drops it) and opens the ticket of a clicked notification. When the link
 * goes live it asks for the queue (`notify_pending`), so one sent while no client was connected arrives now.
 */
@Composable
fun NotifyListener() {
    val ctx = LocalShellContext.current ?: return
    val link = ctx.link
    val notifier = rememberNotifier()
    val dedupe = remember { NotifyDedupe() }
    val handle: suspend (NotifyPayload) -> Unit = { p -> deliver(p, notifier, dedupe, link) }
    LaunchedEffect(link) { link.frames(NotifyFrames.PUSH).collect { NotifyPayload.parse(it)?.let { p -> handle(p) } } }
    LaunchedEffect(link) { link.frames(NotifyFrames.PENDING_LIST).collect { NotifyPayload.parseList(it).forEach { p -> handle(p) } } }
    LaunchedEffect(link.live) { if (link.live) link.sendFrame(NotifyFrames.PENDING, "{}") }
    LaunchedEffect(notifier) { notifier.opened.collect { ctx.board.openTicket(it) } }
}

/** One notification on this client: shown once (by id), the sound played, the daemon told it arrived. */
internal suspend fun deliver(p: NotifyPayload, notifier: Notifier, dedupe: NotifyDedupe, link: LinkContext): Boolean {
    if (!NotifyRouting.shows(p.target, notifier.platform)) return false
    if (!dedupe.firstTime(p.id)) return false
    val shown = runCatching { notifier.show(p) }.getOrDefault(false)
    val data = p.soundData
    if (data != null) decodeSound(data)?.let { runCatching { notifier.play(p.soundType ?: "audio/wav", it) } }
    if (shown) link.sendFrame(NotifyFrames.ACK, NotifyFrames.ack(p.id))
    return shown
}

/** The settings card's list of sounds (built-ins and the owner's files), each with a Play button. It stores no value. */
object SoundsField : FieldType {
    @Composable
    override fun Editor(field: ConfigFieldDeclaration, value: JsonElement?, enabled: Boolean, onChange: (JsonElement?) -> Unit) {
        val link = LocalShellContext.current?.link
        val notifier = rememberNotifier()
        val scope = rememberCoroutineScope()
        var rows by remember { mutableStateOf<List<SoundRow>?>(null) }
        var note by remember { mutableStateOf<String?>(null) }
        LaunchedEffect(link?.live) {
            if (link == null || !link.live) { note = "Not connected to a daemon."; return@LaunchedEffect }
            val answer = request(link, NotifyFrames.SOUNDS, "{}", NotifyFrames.SOUNDS_LIST)
            if (answer == null) note = "The daemon did not answer (is the Infantry_Notify daemon half on?)." else { rows = NotifyLists.sounds(answer); note = null }
        }
        Column(Modifier.padding(vertical = 4.dp)) {
            note?.let { Text(it, color = Palette.Muted, fontSize = 11.sp) }
            rows?.forEach { r ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(r.name, fontSize = 12.sp, modifier = Modifier.weight(1f))
                    Text(if (r.builtin) "built in" else "${r.bytes / 1024} KB", color = Palette.Muted, fontSize = 11.sp, modifier = Modifier.padding(horizontal = 8.dp))
                    TextButton(onClick = {
                        val l = link ?: return@TextButton
                        scope.launch { note = playSound(l, notifier, r.name) }
                    }, enabled = link?.live == true) { Text("Play", fontSize = 12.sp) }
                }
            }
            Text("Use a name in the Notifications card (Default sound, Milestone sound) or as the sound of infantry_notify; none = silent.",
                color = Palette.Muted, fontSize = 10.sp, modifier = Modifier.padding(top = 4.dp))
        }
    }
}

/** Fetches sound [name] from the daemon and plays it; returns a note when it could not. */
internal suspend fun playSound(link: LinkContext, notifier: Notifier, name: String): String? {
    val answer = request(link, NotifyFrames.SOUND_GET, NotifyFrames.soundGet(name), NotifyFrames.SOUND) { it.contains("\"$name\"") }
        ?: return "The daemon did not answer."
    val (_, type, data) = NotifyLists.sound(answer) ?: return "$name: no such sound."
    val bytes = decodeSound(data) ?: return "$name: unreadable."
    return if (notifier.play(type, bytes)) null else "$name: this device cannot play $type."
}

/**
 * Sends one request frame and waits for its answer. The frames flow has no replay, so the wait subscribes before the send
 * (an unconfined start runs it up to its first suspension at once).
 */
internal suspend fun request(link: LinkContext, type: String, data: String, answer: String, match: (String) -> Boolean = { true }): String? =
    withTimeoutOrNull(NOTIFY_ANSWER_TIMEOUT_MS) {
        coroutineScope {
            val wait = async(start = CoroutineStart.UNDISPATCHED) { link.frames(answer).first(match) }
            if (!link.sendFrame(type, data)) { wait.cancel(); null } else wait.await()
        }
    }
