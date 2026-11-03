package com.superiorsoftllc.infantry.ui

import com.superiorsoftllc.infantry.ui.model.bgJobsText
import com.superiorsoftllc.infantry.ui.protocol.BgJobView
import com.superiorsoftllc.infantry.ui.protocol.SessionRecord
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class BgJobsTextTest {
    private val lenient = Json { ignoreUnknownKeys = true }

    @Test
    fun registeredJobsNameThemAndNoJobsShowNothing() {
        val withJobs = lenient.decodeFromString(
            SessionRecord.serializer(),
            """{"id":"s","provider":"p","workingDir":".","spawnedAt":0,"lastHeartbeat":0,"state":"BUSY",
               "bgJobs":[{"id":"1","label":"gate"},{"id":"2","label":"build"}]}""",
        )
        assertEquals(listOf(BgJobView("1", "gate"), BgJobView("2", "build")), withJobs.bgJobs)
        assertEquals("running: gate, build", bgJobsText(withJobs))
        assertNull(bgJobsText(withJobs.copy(bgJobs = emptyList())))
    }
}
