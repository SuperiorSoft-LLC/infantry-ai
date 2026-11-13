// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.plugins.official.remoterunner.daemon

import com.superiorsoftllc.infantry.daemon.api.runners.LinkAnswer
import com.superiorsoftllc.infantry.daemon.api.runners.LinkFailure
import com.superiorsoftllc.infantry.daemon.api.runners.RunnerLinks
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import com.superiorsoftllc.infantry.daemon.api.runners.RunnerLinkWire as F

/**
 * RUNNER-UPDATE-1: Update runner recreates the runner's local container on the image of the app's build with the same name, volume,
 * ports, restart policy and env; a new container that does not answer with a new enough contract is rolled back.
 */
class RunnerUpdaterTest {
    private val inspect = """
        [{"Name":"/runner-old-test","Image":"sha256:old",
          "Config":{"Image":"infantry-runner:local-66553c7d","Env":["INFANTRY_MODE=runner","INFANTRY_RUNNER_NAME=testbox","PATH=/usr/bin","INFANTRY_VERSION=dev"]},
          "HostConfig":{"PortBindings":{"8790/tcp":[{"HostIp":"","HostPort":"18791"}]},"RestartPolicy":{"Name":"unless-stopped","MaximumRetryCount":0},"NetworkMode":"bridge"},
          "Mounts":[{"Type":"volume","Name":"runner-old-test","Source":"/var/lib/docker/volumes/x","Destination":"/data","RW":true}]}]
    """.trimIndent()

    /** A fake docker: answers by the first words of each call, records every call. */
    private class FakeDocker(val answers: (List<String>) -> RunnerUpdater.Run) : RunnerUpdater.Docker {
        val calls = mutableListOf<List<String>>()
        override fun run(args: List<String>, timeoutSec: Long): RunnerUpdater.Run { calls += args; return answers(args) }
    }

    private class FakeLinks(var contract: String?, val url: String = "wss://127.0.0.1:18791") : RunnerLinks {
        override fun paired() = listOf("testbox")
        override fun endpoint(runner: String) = url
        override fun call(runner: String, type: String, data: String): LinkAnswer {
            val c = contract ?: throw LinkFailure("connection refused")
            return LinkAnswer(F.REPLY, """{"runnerId":"r","runnerName":"testbox","contract":"$c","issuedAt":1}""")
        }
    }

    private fun docker(newImageTags: String = "local-73ab2310\nlocal-66553c7d\n", runOk: Boolean = true) = FakeDocker { a ->
        when {
            a.take(2) == listOf("ps", "-a") -> RunnerUpdater.Run(0, "runner-old-test\n")
            a.take(2) == listOf("image", "inspect") && a.getOrNull(2) == "sha256:old" -> RunnerUpdater.Run(0, """["PATH=/usr/bin","INFANTRY_VERSION=dev"]""")
            a.take(2) == listOf("image", "inspect") && a.getOrNull(2) == "infantry-runner:local-73ab2310" -> RunnerUpdater.Run(0, "sha256:new\n")
            a.take(2) == listOf("image", "inspect") -> RunnerUpdater.Run(1, "No such image")
            a.first() == "images" -> RunnerUpdater.Run(0, newImageTags)
            a.first() == "pull" -> RunnerUpdater.Run(1, "pull access denied")
            a.first() == "inspect" && a.getOrNull(1) == "--format" -> RunnerUpdater.Run(1, "No such container")
            a.first() == "inspect" -> RunnerUpdater.Run(0, inspect)
            a.first() == "run" -> RunnerUpdater.Run(if (runOk) 0 else 125, if (runOk) "abc\n" else "port is already allocated")
            else -> RunnerUpdater.Run(0, "")
        }
    }

    private var now = 0L
    private fun updater(links: RunnerLinks, d: RunnerUpdater.Docker, local: Boolean = true) = RunnerUpdater(
        links, d, { RunnerUpdater.Settings(waitSec = 10) }, build = { "1.1.0+20261113.0100.73ab231" }, version = { "1.1.0" },
        sleep = { now += it }, clock = { now }, isThisMachine = { local },
    )

    private val noReport = RunnerUpdater.Report { _, _ -> }

    @Test
    fun `recreates the container on the app's build image with the same name, volume, ports, restart and its own env`() {
        val d = docker()
        val steps = mutableListOf<String>()
        val out = updater(FakeLinks(F.CONTRACT), d).update("testbox") { s, _ -> steps += s }
        out as RunnerUpdater.Outcome.Updated
        assertEquals("infantry-runner:local-73ab2310", out.image)
        assertEquals("runner-old-test", out.container)
        assertEquals(RunnerUpdater.STEPS, steps)
        val run = d.calls.first { it.first() == "run" }
        assertEquals(
            listOf(
                "run", "-d", "--name", "runner-old-test", "--restart", "unless-stopped", "-p", "18791:8790", "-v", "runner-old-test:/data",
                "-e", "INFANTRY_MODE=runner", "-e", "INFANTRY_RUNNER_NAME=testbox", "infantry-runner:local-73ab2310",
            ),
            run,
        )
        assertTrue(listOf("rename", "runner-old-test", "runner-old-test-before-update") in d.calls)
        assertTrue(listOf("rm", "runner-old-test-before-update") in d.calls, "the old container goes once the new one answers")
        assertFalse(d.calls.any { it.firstOrNull() == "rmi" }, "no image is ever deleted")
    }

    @Test
    fun `an image that is still too old is rolled back and the old runner comes back`() {
        val d = docker()
        val out = updater(FakeLinks("1.8.0"), d).update("testbox", noReport)
        out as RunnerUpdater.Outcome.Failed
        assertTrue(out.rolledBack)
        assertTrue("still contract 1.8.0" in out.reason, out.reason)
        val tail = d.calls.takeLast(3)
        assertEquals(listOf(listOf("rm", "-f", "runner-old-test"), listOf("rename", "runner-old-test-before-update", "runner-old-test"), listOf("start", "runner-old-test")), tail)
    }

    @Test
    fun `a runner that never answers is rolled back after the wait`() {
        val out = updater(FakeLinks(null), docker()).update("testbox", noReport)
        out as RunnerUpdater.Outcome.Failed
        assertTrue(out.rolledBack && "did not answer within 10 s" in out.reason, out.reason)
    }

    @Test
    fun `a new container that does not start is rolled back`() {
        val out = updater(FakeLinks(F.CONTRACT), docker(runOk = false)).update("testbox", noReport)
        out as RunnerUpdater.Outcome.Failed
        assertTrue(out.rolledBack && "port is already allocated" in out.reason, out.reason)
    }

    @Test
    fun `no matching image changes nothing`() {
        val d = docker(newImageTags = "local-66553c7d\n")
        val out = updater(FakeLinks(F.CONTRACT), d).update("testbox", noReport)
        out as RunnerUpdater.Outcome.Failed
        assertFalse(out.rolledBack)
        assertFalse(d.calls.any { it.firstOrNull() in setOf("rename", "stop", "run") }, "the runner is not touched")
    }

    @Test
    fun `a runner on another machine gets the one line to run there`() {
        val d = docker()
        val out = updater(FakeLinks(F.CONTRACT, url = "wss://203.0.113.5:8790"), d, local = false).update("testbox", noReport)
        out as RunnerUpdater.Outcome.Manual
        assertEquals(
            "docker pull ghcr.io/superiorsoft-llc/infantry-ai:1.1.0 && docker rm -f infantry-runner && docker run -d --restart unless-stopped " +
                "--name infantry-runner -e INFANTRY_MODE=runner -e INFANTRY_RUNNER_HOST=203.0.113.5 -p 8790:8790 -v infantry-runner:/data ghcr.io/superiorsoft-llc/infantry-ai:1.1.0",
            out.command,
        )
        assertTrue(d.calls.isEmpty(), "docker is never called for a runner elsewhere")
    }

    @Test
    fun `refs, shas and endpoints`() {
        assertEquals("registry:5000/infantry" to "local-1", RunnerUpdater.splitRef("registry:5000/infantry:local-1"))
        assertEquals("ghcr.io/x/y" to null, RunnerUpdater.splitRef("ghcr.io/x/y"))
        assertEquals("73ab231", RunnerUpdater.shaOf("1.1.0+20261113.0100.73ab231.dirty"))
        assertEquals(null, RunnerUpdater.shaOf("dev"))
        assertTrue(RunnerUpdater.shaMatches("73ab2310", "73ab231"))
        assertFalse(RunnerUpdater.shaMatches("73ab2310", "66553c7"))
        assertEquals("127.0.0.1" to 18790, RunnerUpdater.hostPort("wss://127.0.0.1:18790"))
        assertTrue(RunnerUpdater.localHost("localhost"))
    }
}
