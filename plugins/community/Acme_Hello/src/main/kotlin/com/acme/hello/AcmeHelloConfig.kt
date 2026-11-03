package com.acme.hello

import com.superiorsoftllc.infantry.plugin.api.ConfigClassSlot
import com.superiorsoftllc.infantry.plugin.api.ConfigDeclaration
import com.superiorsoftllc.infantry.plugin.api.ConfigFieldDeclaration
import com.superiorsoftllc.infantry.plugin.api.ConfigFieldTypes
import kotlinx.serialization.json.JsonPrimitive

/**
 * EXAMPLE-1: module.json's `config` as Kotlin, what a compile-time build (web, Android) carries. The desktop reads
 * module.json; AcmeHelloConfigTest pins the two equal.
 */
object AcmeHelloConfig {
    const val GREETING_NODE = "acme.hello.greeting"

    val DECLARATIONS = listOf(
        ConfigDeclaration(
            node = "acme.hello", group = "Hello",
            fields = listOf(ConfigFieldDeclaration("greeting", ConfigFieldTypes.TEXT, "Greeting", JsonPrimitive("Hello from Acme_Hello, a community module."))),
        ),
        ConfigDeclaration(
            node = GREETING_NODE, group = "Hello greeting", tab = "Plugins", order = 900,
            title = "Hello greeting (Acme_Hello)", applies = "Applies to: the Acme_Hello block under the Board button, live.",
            keywords = listOf("acme", "hello", "greeting", "example"),
            fields = listOf(
                ConfigFieldDeclaration("greeting", ConfigFieldTypes.RADIO, "Greeting", JsonPrimitive("Hello World"), options = listOf("Hello World", "Hello Nation"), order = 1),
                ConfigFieldDeclaration("message", ConfigFieldTypes.TEXT, "Custom message", JsonPrimitive(""), order = 2),
                ConfigFieldDeclaration("shout", ConfigFieldTypes.CHECKBOX, "Shout", JsonPrimitive(false), order = 3),
                ConfigFieldDeclaration("classes", ConfigFieldTypes.CLASSES, "Classes", order = 4, slots = mapOf("text" to ConfigClassSlot(listOf("big", "bold", "muted")))),
            ),
        ),
    )
}
