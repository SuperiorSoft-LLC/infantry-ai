package com.superiorsoftllc.infantry.usagemeter

import java.time.Instant
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals

class LocalDayTest {
    private val at0141z = Instant.parse("2026-11-04T01:41:29Z").toEpochMilli()

    @Test fun newYorkEveningIsStillNov3() = assertEquals("2026-11-03", localDay(at0141z, ZoneId.of("America/New_York")))

    @Test fun utcStaysNov4() = assertEquals("2026-11-04", localDay(at0141z, ZoneId.of("UTC")))

    @Test fun localMidnightSplitsTheDay() {
        val ny = ZoneId.of("America/New_York")
        assertEquals("2026-11-03", localDay(Instant.parse("2026-11-04T04:59:59Z").toEpochMilli(), ny))
        assertEquals("2026-11-04", localDay(Instant.parse("2026-11-04T05:00:00Z").toEpochMilli(), ny))
    }
}
