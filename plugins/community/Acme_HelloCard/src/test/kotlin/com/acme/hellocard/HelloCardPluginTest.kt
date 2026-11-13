// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
package com.acme.hellocard

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class HelloCardPluginTest {
    @Test fun `body parses title and text, and survives junk`() {
        assertEquals(HelloCardBody("Hi", "there"), HelloCardBody.parse("""{"title":"Hi","text":"there"}"""))
        assertEquals(HelloCardBody("Hello", ""), HelloCardBody.parse("not json"))
        assertEquals(HelloCardBody("Hello", ""), HelloCardBody.parse("""{"title":3}"""))
        assertEquals("https://upload.wikimedia.org/x.png", HelloCardBody.parse("""{"title":"Hi","image":"https://upload.wikimedia.org/x.png"}""").image)
    }

    @Test fun `module json declares the chat card it registers`() {
        val text = File("module.json").readText()
        assertTrue(""""registry": "chat.cards", "key": "$HELLO_CARD"""" in text, text)
        assertTrue(""""images": { "hosts": ["upload.wikimedia.org"""" in text, "CARD-IMG-1: the card declares its image hosts")
    }
}
