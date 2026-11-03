package com.superiorsoftllc.infantry.harness

import java.io.File
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream

/** HARNESS-PLUGIN-3: packs a compiled test plugin (every `<Simple>*.class` beside [anchor]) into a jar, as a real build would. */
object TestHarnessJars {
    const val API_SERVICES = "META-INF/services/com.superiorsoftllc.infantry.harness.api.HarnessPlugin"

    fun pack(out: File, anchor: Class<*>, services: Map<String, String> = mapOf(API_SERVICES to anchor.name)): File {
        val path = anchor.name.replace('.', '/')
        val classDir = File(anchor.getResource("/$path.class")!!.toURI()).parentFile
        out.parentFile?.mkdirs()
        JarOutputStream(out.outputStream()).use { j ->
            classDir.listFiles { f -> f.name.startsWith(anchor.simpleName) && f.name.endsWith(".class") }!!.forEach { f ->
                j.putNextEntry(JarEntry("${path.substringBeforeLast('/')}/${f.name}"))
                j.write(f.readBytes())
                j.closeEntry()
            }
            services.forEach { (name, text) ->
                j.putNextEntry(JarEntry(name))
                j.write((text + "\n").toByteArray())
                j.closeEntry()
            }
        }
        return out
    }

    /** The test class path minus every class whose name starts with [prefix]: the plugin can only come from its jar. */
    fun hiding(prefix: String, base: ClassLoader = TestHarnessJars::class.java.classLoader): ClassLoader = object : ClassLoader("hides-$prefix", base) {
        override fun loadClass(name: String, resolve: Boolean): Class<*> {
            if (name.startsWith(prefix)) throw ClassNotFoundException("$name is hidden from the parent")
            return super.loadClass(name, resolve)
        }
    }
}
