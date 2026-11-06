// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.plugins.official.notify

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

@Composable
internal actual fun rememberNotifier(): Notifier = remember { WebNotifier() }

/**
 * The browser's notification, best effort: the Notification API (the permission is asked on the first notification; a
 * browser may want a click first, then the next one shows) and an Audio element for the sound.
 */
internal class WebNotifier : Notifier {
    override val platform = "web"
    private val clicks = MutableSharedFlow<String>(extraBufferCapacity = 8, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    override val opened: Flow<String> = clicks

    override fun show(p: NotifyPayload): Boolean {
        if (!jsSupported()) return false
        when (jsPermission()) {
            "granted" -> {}
            "default" -> { jsRequestPermission(); return false }
            else -> return false
        }
        val ticket = p.ticketId
        val url = p.url
        return jsShow(p.title, p.body, p.id) { if (ticket != null) clicks.tryEmit(ticket) else if (url != null) jsOpen(url) }
    }

    @OptIn(ExperimentalEncodingApi::class)
    override fun play(type: String, bytes: ByteArray): Boolean = jsPlay("data:$type;base64," + Base64.encode(bytes))
}

private fun jsSupported(): Boolean = js("typeof Notification !== 'undefined'")
private fun jsPermission(): String = js("Notification.permission")
private fun jsRequestPermission(): Unit = js("{ try { Notification.requestPermission(); } catch (e) {} }")
private fun jsOpen(url: String): Unit = js("{ window.open(url, '_blank'); }")
private fun jsShow(title: String, body: String, tag: String, onClick: () -> Unit): Boolean =
    js("{ try { var n = new Notification(title, { body: body, tag: tag }); n.onclick = function () { window.focus(); onClick(); n.close(); }; return true; } catch (e) { return false; } }")
private fun jsPlay(src: String): Boolean = js("{ try { new Audio(src).play().catch(function () {}); return true; } catch (e) { return false; } }")
