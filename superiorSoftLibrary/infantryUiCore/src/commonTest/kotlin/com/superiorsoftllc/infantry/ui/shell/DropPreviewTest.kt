package com.superiorsoftllc.infantry.ui.shell

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** S7: the consent dialog words its tier sentence from the signer's name. */
class DropPreviewTest {
    private fun preview(signer: String?) = DropPreview("p.ihq", "M", "1", null, signer, core = true, asks = emptyList())

    @Test fun `the signer name is the common name of the distinguished name`() {
        assertEquals("Acme Ltd", preview("CN=Acme Ltd, O=Acme, C=US").signerName)
        assertEquals("Infantry AI", preview("O=Infantry,CN=Infantry AI").signerName)
        assertEquals("Acme, Inc", preview("""CN=Acme\, Inc,O=Acme""").signerName)
    }

    @Test fun `a name with no CN is shown whole, and unsigned has no name`() {
        assertEquals("O=Acme", preview("O=Acme").signerName)
        assertNull(preview(null).signerName)
    }
}
