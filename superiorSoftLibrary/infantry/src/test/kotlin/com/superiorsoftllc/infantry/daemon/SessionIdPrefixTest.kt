// SPDX-License-Identifier: MPL-2.0
package com.superiorsoftllc.infantry.daemon

import kotlin.test.Test
import kotlin.test.assertEquals

class SessionIdPrefixTest {
    private val known = listOf("11b63ff2-b5cb-4913-8fb3-8a182144dc7a", "f238b2ef-0000-0000-0000-000000000000", "abcd1111-x", "abcd2222-y")

    @Test fun shortIdResolvesToTheOneFullId() = assertEquals(known[0], resolveSessionIdPrefix("11b63ff2", known))
    @Test fun fullIdStays() = assertEquals(known[1], resolveSessionIdPrefix(known[1], known))
    @Test fun ambiguousPrefixStays() = assertEquals("abcd", resolveSessionIdPrefix("abcd", known))
    @Test fun unknownStays() = assertEquals("zzzz", resolveSessionIdPrefix("zzzz", known))
}
