package com.superiorsoftllc.agenthq.plugins.official.scripting.daemon

import java.io.File

/** SCRIPT-CMD-1 test helper, run as a child JVM: a deterministic process whose behaviour the first argument picks. */
object ExecProbe {
    @JvmStatic fun main(args: Array<String>) {
        when (args[0]) {
            "args" -> args.drop(1).forEach { println("[$it]") }
            "exit" -> { System.err.println("failing"); System.exit(args[1].toInt()) }
            "pwd" -> println(File(".").canonicalPath)
            "env" -> println(System.getenv(args[1]))
            "stdin" -> print(System.`in`.readBytes().decodeToString().uppercase())
            "sleep" -> Thread.sleep(args[1].toLong())
            "say" -> args.drop(1).forEach { println(it) }
            "tree" -> {
                val cp = System.getProperty("java.class.path")
                val child = ProcessBuilder(File(System.getProperty("java.home"), "bin/java").path, "-cp", cp, ExecProbe::class.java.name, "sleep", "60000").inheritIO().start()
                File(args[1]).writeText(child.pid().toString())
                Thread.sleep(60_000)
            }
        }
    }
}
