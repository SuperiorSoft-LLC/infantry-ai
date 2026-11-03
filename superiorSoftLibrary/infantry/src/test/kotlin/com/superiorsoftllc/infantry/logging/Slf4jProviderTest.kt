package com.superiorsoftllc.infantry.logging

import org.slf4j.spi.SLF4JServiceProvider
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.util.ServiceLoader
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * BUG-INSTALL-SLF4J-1: Ktor and sqlite log through SLF4J. With no provider on the daemon's runtime classpath SLF4J
 * binds its NOP logger and every one of those lines is dropped. The daemon ships slf4j-simple, which writes to
 * whatever `System.err` is at the moment of the call -- so the installed app's redirect (InstallLogs) catches it.
 *
 * Why ServiceLoader and not `LoggerFactory.getILoggerFactory()`: this test classpath also carries `gradleTestKit()`,
 * whose jar ships Gradle's own SLF4J provider. That one never exists at runtime, so asking SLF4J "who is bound" here
 * would test Gradle's classpath, not the daemon's. The provider the daemon ships is looked up and driven directly.
 */
class Slf4jProviderTest {
    private val shipped = "org.slf4j.simple.SimpleServiceProvider"

    @Test fun theDaemonShipsSlf4jSimpleWritingInfoToTheCurrentStderr() {
        val providers = ServiceLoader.load(SLF4JServiceProvider::class.java).toList()
        val provider = assertNotNull(
            providers.firstOrNull { it.javaClass.name == shipped },
            "no $shipped on the daemon runtime; found ${providers.map { it.javaClass.name }}",
        )
        provider.initialize()
        val logger = provider.loggerFactory.getLogger("io.ktor.server.Application")

        val captured = ByteArrayOutputStream()
        val realErr = System.err
        System.setErr(PrintStream(captured, true, Charsets.UTF_8))
        try {
            logger.info("slf4j-daemon-marker")
            logger.debug("slf4j-debug-marker")
        } finally {
            System.setErr(realErr)
        }
        val text = captured.toString(Charsets.UTF_8)
        assertTrue("slf4j-daemon-marker" in text, "an INFO line reaches the System.err of the call: '$text'")
        assertFalse("slf4j-debug-marker" in text, "the default level is INFO, DEBUG is dropped: '$text'")
    }
}
