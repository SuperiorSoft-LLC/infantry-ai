package com.superiorsoftllc.agenthq.ui.markdown

import com.superiorsoftllc.agenthq.ui.model.BuiltInChatMarkdownEngine
import com.superiorsoftllc.agenthq.ui.model.ChatBlock
import com.superiorsoftllc.agenthq.ui.model.ChatMarkdownEngine
import com.superiorsoftllc.agenthq.ui.model.ChatMarkdownFeature
import com.superiorsoftllc.agenthq.ui.model.ChatMarkdownRegistry
import java.io.File
import java.net.URLClassLoader
import java.util.ServiceLoader
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/**
 * What a plugin jar can see of the host: the JDK, the Kotlin runtime and the `ui.model` package (the engine interface
 * and [ChatBlock]); nothing else (no Compose, no controller, no other plugin). The jar's own classes come first.
 */
private class PluginClassLoader(url: java.net.URL, private val host: ClassLoader) :
    URLClassLoader(arrayOf(url), ClassLoader.getPlatformClassLoader()) {
    override fun loadClass(name: String, resolve: Boolean): Class<*> {
        synchronized(getClassLoadingLock(name)) {
            findLoadedClass(name)?.let { return it }
            if (name.startsWith("kotlin.") || name.startsWith("com.superiorsoftllc.agenthq.ui.model.")) {
                return host.loadClass(name)
            }
            return super.loadClass(name, resolve)
        }
    }
}

/**
 * MD-PLUGIN-1: runs a plugin engine behind a guard. The built-in engine never goes through this (no cost on the normal
 * path). A plugin parse that throws (anything but OutOfMemoryError) or takes longer than [timeoutMs] is that
 * message's failure: the message is shown with the built-in engine, [onFailure] is told once, and the engine is
 * disabled ([onFailure] unregisters it) so every later message uses the built-in engine.
 */
class GuardedMarkdownEngine(
    private val delegate: ChatMarkdownEngine,
    private val timeoutMs: Long = DEFAULT_TIMEOUT_MS,
    private val onFailure: (ChatMarkdownEngine, Throwable) -> Unit,
) : ChatMarkdownEngine {
    override val id get() = delegate.id
    override val label get() = delegate.label
    @Volatile private var disabled = false

    override fun parse(text: String, streaming: Boolean): List<ChatBlock> {
        if (disabled) return BuiltInChatMarkdownEngine.parse(text, streaming)
        val task = pool.submit<List<ChatBlock>> { delegate.parse(text, streaming) }
        return try {
            task.get(timeoutMs, TimeUnit.MILLISECONDS)
        } catch (e: java.util.concurrent.ExecutionException) {
            val cause = e.cause ?: e
            if (cause is OutOfMemoryError) throw cause
            fail(cause, text, streaming)
        } catch (e: TimeoutException) {
            task.cancel(true)
            fail(e, text, streaming)
        }
    }

    private fun fail(cause: Throwable, text: String, streaming: Boolean): List<ChatBlock> {
        if (!disabled) {
            disabled = true
            onFailure(delegate, cause)
        }
        return BuiltInChatMarkdownEngine.parse(text, streaming)
    }

    override fun supports(feature: ChatMarkdownFeature): Boolean = try { delegate.supports(feature) } catch (e: Exception) { false }

    companion object {
        const val DEFAULT_TIMEOUT_MS = 250L

        // Daemon threads: a plugin stuck in an endless loop must not keep the app from exiting.
        private val pool: ExecutorService = Executors.newCachedThreadPool { r -> Thread(r, "md-plugin").apply { isDaemon = true } }
    }
}

/**
 * MD-PLUGIN-1: loads markdown engines from the jars in `<dir>` into [registry]. A jar declares its engine(s) the standard
 * way, `META-INF/services/com.superiorsoftllc.agenthq.ui.model.ChatMarkdownEngine`, each with a public no-arg
 * constructor. Every jar gets its own [PluginClassLoader]. One bad jar never hides the others; each problem is
 * reported to [log] once. [unload] unregisters the engine and closes its jar.
 */
class MarkdownEnginePluginLoader(
    private val dir: File,
    private val registry: ChatMarkdownRegistry,
    private val timeoutMs: Long = GuardedMarkdownEngine.DEFAULT_TIMEOUT_MS,
    private val log: (String) -> Unit = { System.err.println("[markdown-plugin] $it") },
) {
    private val loaders = LinkedHashMap<String, URLClassLoader>()

    /** Ids of the engines this loader registered. */
    fun loadedIds(): List<String> = loaders.keys.toList()

    /** Load every jar in [dir]; returns the ids registered. Safe to call once at startup. */
    fun loadAll(): List<String> {
        val jars = dir.listFiles { f -> f.isFile && f.name.endsWith(".jar") }?.sortedBy { it.name }.orEmpty()
        return jars.flatMap { load(it) }
    }

    fun load(jar: File): List<String> {
        val loader = PluginClassLoader(jar.toURI().toURL(), ChatMarkdownEngine::class.java.classLoader)
        val ids = ArrayList<String>()
        try {
            for (engine in ServiceLoader.load(ChatMarkdownEngine::class.java, loader)) {
                val id = engine.id
                if (id == BuiltInChatMarkdownEngine.id || id in loaders) { log("${jar.name}: engine id '$id' is taken, skipped"); continue }
                registry.register(GuardedMarkdownEngine(engine, timeoutMs) { _, why ->
                    log("engine '$id' failed (${why::class.simpleName}: ${why.message}); disabled, the built-in engine is used")
                    unload(id)
                })
                loaders[id] = loader
                ids += id
            }
        } catch (e: Throwable) {
            if (e is OutOfMemoryError) throw e
            log("${jar.name}: cannot load (${e::class.simpleName}: ${e.message})")
        }
        if (ids.isEmpty()) runCatching { loader.close() }
        return ids
    }

    /** Remove engine [id] from the registry and close its jar; the chat uses the built-in engine for it from now on. */
    fun unload(id: String) {
        registry.unregister(id)
        loaders.remove(id)?.let { l -> if (loaders.values.none { it === l }) runCatching { l.close() } }
    }

    fun unloadAll() = loadedIds().forEach(::unload)
}
