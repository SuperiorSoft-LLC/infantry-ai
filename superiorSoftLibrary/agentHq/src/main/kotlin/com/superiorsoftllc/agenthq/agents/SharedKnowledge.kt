package com.superiorsoftllc.agenthq.agents

/**
 * The shipped KNOWLEDGE.md (resource, read at runtime so an update reaches existing installs): the basics every role
 * needs, appended to every role instance's instructions at spawn. A profile or DB persona override cannot hide it.
 */
object SharedKnowledge {
    const val RESOURCE = "KNOWLEDGE.md"

    val text: String by lazy {
        SharedKnowledge::class.java.classLoader.getResourceAsStream(RESOURCE)
            ?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }?.replace("\r\n", "\n")?.trim().orEmpty()
    }

    /** [persona] followed by the shared knowledge (once). */
    fun withKnowledge(persona: String?): String? {
        val base = persona?.takeIf { it.isNotBlank() }
        if (text.isEmpty()) return base
        if (base != null && base.contains(text)) return base
        return if (base == null) text else "$base\n\n$text"
    }
}
