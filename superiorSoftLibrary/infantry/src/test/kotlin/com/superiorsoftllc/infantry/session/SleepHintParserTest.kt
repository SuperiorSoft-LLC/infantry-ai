package com.superiorsoftllc.infantry.session

import kotlin.test.Test
import kotlin.test.assertEquals

class SleepHintParserTest {
    private fun p(cmd: String?, tool: String = "Bash", bg: Boolean = false, timeout: Long? = null) = SleepHint.parse(tool, cmd, bg, timeout)

    @Test fun `recognised forms`() {
        val table = mapOf(
            "sleep 30" to 30L, "sleep 30s" to 30L, "sleep 2m" to 120L, "sleep 1h" to 3600L, "sleep 1m 30s" to 90L,
            "sleep 7.2" to 8L, "Start-Sleep 20" to 20L, "Start-Sleep -Seconds 45" to 45L, "start-sleep -s 10" to 10L,
            "Start-Sleep -Milliseconds 9000" to 9L, "timeout /t 60" to 60L, "timeout /T 60 /nobreak" to 60L,
            "sleep 30 && echo done" to 30L, "make; sleep 90; echo ok" to 90L, "echo a\nsleep 15\necho b" to 15L,
            "git pull | sleep 12" to 12L,
        )
        for ((cmd, secs) in table) assertEquals(secs, p(cmd), cmd)
    }

    @Test fun `rejections`() {
        val none = listOf(
            "sleep 0", "sleep -5", "sleep infinity", "sleep abc", "sleep \$N", "sleep", "sleep 3", "Start-Sleep", "Start-Sleep -Seconds x",
            "timeout 30 make", "ping -n 30 127.0.0.1", "echo \"sleep 60\"", "while true; do sleep 60; done", "for i in 1 2; do sleep 20; done",
            "ls", "",
        )
        for (cmd in none) assertEquals(null, p(cmd), cmd)
        assertEquals(null, p(null))
        assertEquals(null, p("sleep 60", bg = true))
        assertEquals(null, p("sleep 60", tool = "Read"))
    }

    @Test fun `capped at the tool timeout`() {
        assertEquals(60L, p("sleep 300", timeout = 60))
        assertEquals(300L, p("sleep 300", timeout = 600))
        assertEquals(null, p("sleep 300", timeout = 2))
    }
}
