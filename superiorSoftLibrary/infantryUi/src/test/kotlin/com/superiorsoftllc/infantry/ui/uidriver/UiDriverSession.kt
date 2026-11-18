// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.ui.uidriver

import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import java.io.File
import java.util.concurrent.TimeUnit
import javax.imageio.ImageIO
import kotlin.math.roundToInt

/**
 * UI-DRIVER-1: runs the driver's verbs on the thread that owns the offscreen UI ([test], a desktop Compose UI test
 * scene: Skia raster, no OS window). Every action is a Compose semantics action or an input event delivered to that
 * scene only; nothing here can reach the operating system's mouse, keyboard or focus.
 *
 * A selector is a JSON object with any of `tag`, `tagPrefix`, `text`, `desc` (all must match), plus `substring`
 * (text/desc), `unmerged` (search the unmerged tree, where tags inside a merged button live) and `index` (which match,
 * default 0). Plugin UI halves are driven the same way: by their testTags.
 *
 * Verbs: `health`, `tree`, `query`, `click`, `type`, `key`, `scroll`, `wait`, `shot`, `quit` (docs/wiki/UI-driver.md).
 * A failed action writes `fail-<n>.png` of the moment it failed and names it in the error.
 */
@OptIn(ExperimentalTestApi::class)
class UiDriverSession(private val test: ComposeUiTest, private val outDir: File, private val info: JsonObjectBuilder.() -> Unit = {}) {
    var quit: Boolean = false
        private set
    private var shots = 0
    private var failures = 0

    /** Answers queued requests until `quit`, or until no request came for [idleTimeoutMs] (an orphan never lingers). */
    fun serve(server: UiDriverServer, idleTimeoutMs: Long) {
        var last = System.currentTimeMillis()
        while (!quit) {
            val request = server.requests.poll(POLL_MS, TimeUnit.MILLISECONDS)
            if (request == null) {
                if (System.currentTimeMillis() - last > idleTimeoutMs) {
                    println("ui-driver: no request for ${idleTimeoutMs / 60_000} min, stopping")
                    return
                }
                continue
            }
            last = System.currentTimeMillis()
            request.answer.complete(answer(request.verb, request.body))
        }
    }

    fun answer(verb: String, body: JsonObject): UiDriverAnswer = try {
        if (verb != "quit") test.waitForIdle()
        when (verb) {
            "health" -> ok { info() }
            "tree" -> ok { put("tree", tree(body.bool("unmerged"))) }
            "query" -> {
                val nodes = test.onAllNodes(matcher(body), body.bool("unmerged")).fetchSemanticsNodes()
                val limit = (body.int("limit") ?: DEFAULT_QUERY_LIMIT).coerceIn(1, MAX_QUERY_LIMIT)
                ok {
                    put("count", nodes.size)
                    put("nodes", buildJsonArray { nodes.take(limit).forEach { add(nodeJson(it)) } })
                }
            }
            "click" -> {
                val (node, semantics) = pick(body)
                // A semantics click when the node offers one (no geometry involved); else a touch inside the offscreen scene.
                val via = if (semantics.config.getOrNull(SemanticsActions.OnClick) != null) {
                    node.performSemanticsAction(SemanticsActions.OnClick); "action"
                } else {
                    node.performClick(); "touch"
                }
                test.waitForIdle()
                ok { put("via", via) }
            }
            "type" -> {
                // "input", not "text": "text" is a selector field (match the node by its text).
                val text = body.str("input") ?: error("type needs \"input\" (the text to type)")
                val (node, _) = pick(body)
                if (body.bool("replace")) node.performTextClearance()
                node.performTextInput(text)
                if (body.bool("ime")) node.performImeAction()
                test.waitForIdle()
                ok {}
            }
            "scroll" -> {
                val (node, _) = pick(body)
                body.int("toIndex")?.let { node.performScrollToIndex(it) } ?: node.performScrollTo()
                test.waitForIdle()
                ok {}
            }
            "key" -> {
                // A key event delivered to the offscreen scene (the selected node, else the top root: a dialog's Escape).
                val name = body.str("key") ?: error("key needs \"key\" (one of ${KEYS.keys})")
                val key = KEYS[name] ?: error("unknown key '$name' (one of ${KEYS.keys})")
                val target = if (hasSelector(body)) pick(body).first else test.onAllNodes(isRoot()).let { it[it.fetchSemanticsNodes().size - 1] }
                target.performKeyInput { pressKey(key) }
                test.waitForIdle()
                ok {}
            }
            "wait" -> waitFor(body)
            "shot" -> {
                val name = (body.str("name") ?: "shot-${++shots}").let { require(NAME.matches(it)) { "shot name must match ${NAME.pattern}" }; it }
                val file = File(outDir, "$name.png")
                capture(if (hasSelector(body)) pick(body).first else null, file)
                ok { put("path", file.absolutePath) }
            }
            "quit" -> { quit = true; ok {} }
            else -> UiDriverAnswer(404, UiDriverServer.error("unknown verb '$verb'"))
        }
    } catch (t: Throwable) {
        val shot = runCatching { File(outDir, "fail-${++failures}.png").also { capture(null, it) }.absolutePath }.getOrNull()
        // The message only, cut short: a Compose assertion carries a tree dump; a stack trace never leaves the process.
        val message = (t.message ?: t::class.simpleName ?: "failed").take(MAX_ERROR_CHARS)
        UiDriverAnswer(400, buildJsonObject { put("ok", false); put("error", message); shot?.let { put("failShot", it) } })
    }

    private fun waitFor(body: JsonObject): UiDriverAnswer {
        val timeout = (body.long("timeoutMs") ?: DEFAULT_WAIT_MS).coerceIn(0, MAX_WAIT_MS)
        if (!hasSelector(body)) {
            // A real-time pause that keeps frames coming (a run's output, a daemon answer).
            val end = System.currentTimeMillis() + (body.long("ms") ?: 0).coerceIn(0, MAX_WAIT_MS)
            while (System.currentTimeMillis() < end) { test.waitForIdle(); Thread.sleep(POLL_MS) }
            return ok {}
        }
        val m = matcher(body)
        val unmerged = body.bool("unmerged")
        val gone = body.bool("gone")
        val count = body.int("count")
        runCatching {
            test.waitUntil(timeoutMillis = timeout) {
                val n = test.onAllNodes(m, unmerged).fetchSemanticsNodes().size
                when { gone -> n == 0; count != null -> n == count; else -> n > 0 }
            }
        }.onFailure { error("timed out after $timeout ms waiting for ${m.description}${if (gone) " to be gone" else count?.let { " x$it" } ?: ""}") }
        return ok { put("count", test.onAllNodes(m, unmerged).fetchSemanticsNodes().size) }
    }

    private fun pick(body: JsonObject): Pair<SemanticsNodeInteraction, SemanticsNode> {
        val m = matcher(body)
        val unmerged = body.bool("unmerged")
        val index = body.int("index") ?: 0
        val found = test.onAllNodes(m, unmerged).fetchSemanticsNodes()
        require(index in found.indices) { "no node matches ${m.description} at index $index (found ${found.size})" }
        return test.onAllNodes(m, unmerged)[index] to found[index]
    }

    private fun capture(node: SemanticsNodeInteraction?, file: File) {
        val target = node ?: test.onAllNodes(isRoot()).let { roots -> roots[roots.fetchSemanticsNodes().size - 1] }
        file.parentFile.mkdirs()
        ImageIO.write(target.captureToImage().toAwtImage(), "png", file)
    }

    private fun tree(unmerged: Boolean): String = buildString {
        fun walk(n: SemanticsNode, depth: Int) {
            append("  ".repeat(depth)).append(line(n)).append('\n')
            n.children.forEach { walk(it, depth + 1) }
        }
        test.onAllNodes(isRoot(), unmerged).fetchSemanticsNodes().forEach { walk(it, 0) }
    }

    private fun line(n: SemanticsNode): String = buildList {
        n.config.getOrNull(SemanticsProperties.TestTag)?.let { add("[$it]") }
        text(n)?.let { add("\"$it\"") }
        n.config.getOrNull(SemanticsProperties.EditableText)?.text?.let { add("edit=\"$it\"") }
        n.config.getOrNull(SemanticsProperties.ContentDescription)?.joinToString(" ")?.let { add("desc=\"$it\"") }
        n.config.getOrNull(SemanticsProperties.Role)?.let { add("role=$it") }
        if (n.config.getOrNull(SemanticsActions.OnClick) != null) add("clickable")
        val b = n.boundsInRoot
        add("(${b.left.roundToInt()},${b.top.roundToInt()},${b.right.roundToInt()},${b.bottom.roundToInt()})")
    }.joinToString(" ")

    private fun nodeJson(n: SemanticsNode): JsonObject = buildJsonObject {
        put("id", n.id)
        n.config.getOrNull(SemanticsProperties.TestTag)?.let { put("tag", it) }
        text(n)?.let { put("text", it) }
        n.config.getOrNull(SemanticsProperties.EditableText)?.text?.let { put("editable", it) }
        n.config.getOrNull(SemanticsProperties.ContentDescription)?.joinToString(" ")?.let { put("desc", it) }
        n.config.getOrNull(SemanticsProperties.Role)?.let { put("role", it.toString()) }
        n.config.getOrNull(SemanticsProperties.Selected)?.let { put("selected", it) }
        n.config.getOrNull(SemanticsProperties.ToggleableState)?.let { put("toggle", it.name) }
        if (n.config.getOrNull(SemanticsProperties.Disabled) != null) put("disabled", true)
        if (n.config.getOrNull(SemanticsActions.OnClick) != null) put("clickable", true)
        put("children", n.children.size)
        val b = n.boundsInRoot
        put("bounds", buildJsonArray { listOf(b.left, b.top, b.right, b.bottom).forEach { add(JsonPrimitive(it.roundToInt())) } })
    }

    private fun text(n: SemanticsNode): String? =
        n.config.getOrNull(SemanticsProperties.Text)?.joinToString(" ") { it.text }?.takeIf { it.isNotEmpty() }

    companion object {
        const val POLL_MS = 50L
        const val DEFAULT_WAIT_MS = 10_000L
        const val MAX_WAIT_MS = 10 * 60_000L
        const val DEFAULT_QUERY_LIMIT = 50
        const val MAX_QUERY_LIMIT = 1000
        const val MAX_ERROR_CHARS = 2000
        val NAME = Regex("[A-Za-z0-9._-]{1,80}")
        private val SELECTOR_KEYS = setOf("tag", "tagPrefix", "text", "desc")
        /** The keys `key` can press; add one here, never rename (scripts name them). */
        val KEYS: Map<String, Key> = mapOf(
            "Enter" to Key.Enter, "Escape" to Key.Escape, "Tab" to Key.Tab, "Backspace" to Key.Backspace, "Delete" to Key.Delete,
            "Space" to Key.Spacebar, "Up" to Key.DirectionUp, "Down" to Key.DirectionDown, "Left" to Key.DirectionLeft,
            "Right" to Key.DirectionRight, "Home" to Key.MoveHome, "End" to Key.MoveEnd, "PageUp" to Key.PageUp, "PageDown" to Key.PageDown,
        )

        fun hasSelector(body: JsonObject): Boolean = SELECTOR_KEYS.any { body.str(it) != null }

        /** The selector's matcher: every given field must match. */
        fun matcher(body: JsonObject): SemanticsMatcher {
            val sub = body.bool("substring")
            val parts = buildList {
                body.str("tag")?.let { add(hasTestTag(it)) }
                body.str("tagPrefix")?.let { p ->
                    add(SemanticsMatcher("testTag starts with '$p'") { it.config.getOrNull(SemanticsProperties.TestTag)?.startsWith(p) == true })
                }
                body.str("text")?.let { add(hasText(it, substring = sub)) }
                body.str("desc")?.let { add(hasContentDescription(it, substring = sub)) }
            }
            require(parts.isNotEmpty()) { "a selector needs tag, tagPrefix, text or desc" }
            return parts.reduce { a, b -> a and b }
        }

        private fun ok(fill: JsonObjectBuilder.() -> Unit) = UiDriverAnswer(200, buildJsonObject { put("ok", true); fill() })
        private fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
        private fun JsonObject.bool(key: String): Boolean = (this[key] as? JsonPrimitive)?.booleanOrNull == true
        private fun JsonObject.int(key: String): Int? = (this[key] as? JsonPrimitive)?.intOrNull
        private fun JsonObject.long(key: String): Long? = (this[key] as? JsonPrimitive)?.longOrNull
    }
}
