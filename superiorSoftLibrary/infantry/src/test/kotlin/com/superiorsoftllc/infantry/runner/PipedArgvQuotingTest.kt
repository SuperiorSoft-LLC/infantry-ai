// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.runner

import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals

/** EXEC-QUOTE-1: every argument reaches the child byte-identical, whatever quotes, spaces or trailing backslash it holds. */
class PipedArgvQuotingTest {
    @Test
    fun argumentsArriveByteIdentical() {
        val dir = Files.createTempDirectory("argv-echo").toFile()
        File(dir, "Echo.java").writeText(
            "public class Echo { public static void main(String[] a) { for (String s : a) System.out.println(\"[\" + s + \"]\"); } }"
        )
        val javaBin = File(System.getProperty("java.home"), "bin/java").path
        val args = listOf("plain", "two words", "say \"hi\"", "Merge X: \"no harness\" while ... (t) into y", "C:\\dir with space\\", "trail\\", "x\\\"y", "")
        val out = StringBuilder()
        val done = java.util.concurrent.CountDownLatch(1)
        val p = PipedProcessRunner().start(
            ProcessSpec(command = listOf(javaBin, File(dir, "Echo.java").path) + args),
            object : ProcessListener {
                override fun onStdout(line: String) { out.appendLine(line) }
                override fun onStderr(line: String) {}
                override fun onExit(exitCode: Int) { done.countDown() }
            },
        )
        done.await(60, java.util.concurrent.TimeUnit.SECONDS)
        assertEquals(args.joinToString("\n") { "[$it]" }, out.toString().trim().replace("\r", ""))
        dir.deleteRecursively()
    }
}
