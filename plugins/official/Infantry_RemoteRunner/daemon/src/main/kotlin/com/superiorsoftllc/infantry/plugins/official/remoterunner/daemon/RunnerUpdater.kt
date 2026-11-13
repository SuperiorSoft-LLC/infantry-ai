// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.plugins.official.remoterunner.daemon

import com.superiorsoftllc.infantry.daemon.api.runners.LinkFailure
import com.superiorsoftllc.infantry.daemon.api.runners.RunnerLinks
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import java.net.InetAddress
import java.net.NetworkInterface
import java.net.URI
import java.util.concurrent.TimeUnit
import com.superiorsoftllc.infantry.daemon.api.runners.RunnerLinkWire as F

/**
 * RUNNER-UPDATE-1: "Update runner" for a paired runner that is older than the app (the pool reads it `outdated`).
 *
 * A runner that runs in Docker on THIS machine (its paired endpoint's port is published by a local container) is recreated on the
 * image that matches the app: same container name, same volumes (`/data` keeps its logins, pairing and chat history), same
 * published ports, restart policy and the env it was started with. The old container is only renamed and stopped until the new one
 * answers the link with a new enough contract; on any failure the new one is removed and the old one comes back (the rollback),
 * and the old image is never deleted. The pairing lives in the volume, so the runner re-links without a new pairing.
 *
 * A runner this machine cannot reach with docker (another machine, or not a container) gets [Outcome.Manual]: the exact one-line
 * command to run there.
 *
 * Image choice ([pickImage]): the `runners.update.image` setting when set; else, in the repository the container runs from, the
 * local tag `local-<sha>` of this app's build (a locally built runner image), then the app's version tag, then the container's own
 * tag pulled again (a channel tag such as `latest` or `nightly`). The new container's contract is the proof: an image that is still
 * too old is rolled back with that reason.
 */
class RunnerUpdater(
    private val links: RunnerLinks,
    private val docker: Docker,
    private val settingsOf: () -> Settings,
    private val build: () -> String?,
    private val version: () -> String?,
    private val sleep: (Long) -> Unit = { Thread.sleep(it) },
    private val clock: () -> Long = System::currentTimeMillis,
    private val isThisMachine: (String) -> Boolean = ::localHost,
) {
    /** Read at each use, so a settings change applies to the next update. */
    private val settings: Settings get() = settingsOf()

    /** The settings an update reads (module.json `daemon.config`, node `infantry.runners.update`). */
    data class Settings(
        val image: String = "",
        val registryImage: String = DEFAULT_REGISTRY_IMAGE,
        val waitSec: Long = DEFAULT_WAIT_SEC,
        val dockerSec: Long = DEFAULT_DOCKER_SEC,
    )

    /** One docker CLI call: exit code and the combined output. */
    data class Run(val exit: Int, val out: String) {
        val ok: Boolean get() = exit == 0
    }

    /** The docker CLI. Tests pass a fake; [ProcessDocker] runs the real one. */
    fun interface Docker {
        fun run(args: List<String>, timeoutSec: Long): Run
    }

    /** Where an update is: each step is reported before it starts, so a status line names what is running now. */
    fun interface Report {
        fun step(step: String, detail: String)
    }

    sealed interface Outcome {
        /** The runner answers on [image] with [contract]; [container] kept its name, volumes, ports and env. */
        data class Updated(val container: String, val image: String, val contract: String, val previousImage: String) : Outcome
        /** Nothing was changed, or the change was rolled back: [reason] says why. */
        data class Failed(val reason: String, val rolledBack: Boolean = false) : Outcome
        /** This machine cannot update it: run [command] on the runner's machine. */
        data class Manual(val reason: String, val command: String) : Outcome
    }

    /** What `docker inspect` says about the runner's container: everything a recreate keeps. */
    data class Container(
        val name: String,
        val imageRef: String,
        val imageId: String,
        val env: List<String>,
        val ports: List<String>,
        val volumes: List<String>,
        val restart: String?,
        val network: String?,
    )

    fun update(runner: String, report: Report): Outcome {
        val endpoint = links.endpoint(runner) ?: return Outcome.Failed("no paired runner '$runner'")
        val (host, port) = hostPort(endpoint) ?: return Outcome.Failed("runner '$runner' has an unreadable endpoint ($endpoint)")
        val manual = { reason: String -> Outcome.Manual(reason, manualCommand(host, port, settings.image.ifBlank { registryTarget() })) }
        if (!isThisMachine(host)) return manual("runner '$runner' runs on $host, which this app cannot update with docker; run this there")
        report.step(STEP_FIND, "looking for the container that publishes port $port")
        val ps = docker.run(listOf("ps", "-a", "--filter", "publish=$port", "--format", "{{.Names}}"), settings.dockerSec)
        if (!ps.ok) return manual("docker is not usable here (${ps.out.oneLine()}); run this on the runner's machine")
        val name = ps.out.lines().map { it.trim() }.firstOrNull { it.isNotEmpty() }
            ?: return manual("no Docker container on this machine publishes port $port; if the runner is a container elsewhere, run this there")
        val inspected = docker.run(listOf("inspect", name), settings.dockerSec)
        if (!inspected.ok) return Outcome.Failed("docker inspect $name failed: ${inspected.out.oneLine()}")
        val c = parseInspect(inspected.out) ?: return Outcome.Failed("docker inspect $name answered something this app cannot read")
        val baked = docker.run(listOf("image", "inspect", c.imageId, "--format", "{{json .Config.Env}}"), settings.dockerSec)
            .takeIf { it.ok }?.out?.let(::parseStringArray).orEmpty().toSet()
        report.step(STEP_IMAGE, "choosing the runner image of this app's build")
        val image = pickImage(c) ?: return Outcome.Failed(
            "no runner image matching this app was found (build ${build() ?: "unknown"}): build or pull one, or set runners.update.image",
        )
        val imageId = docker.run(listOf("image", "inspect", image, "--format", "{{.Id}}"), settings.dockerSec).takeIf { it.ok }?.out?.trim()
        if (imageId != null && imageId == c.imageId) {
            return Outcome.Failed("the newest image found ($image) is the one the runner already runs; build or publish a newer one, or set runners.update.image")
        }
        report.step(STEP_RECREATE, "recreating $name on $image (same name, volumes, ports and env)")
        val old = "$name$OLD_SUFFIX"
        // A leftover of an earlier update whose restore failed may be the only copy of a runner: never delete it here.
        if (docker.run(listOf("inspect", "--format", "{{.Id}}", old), settings.dockerSec).ok) {
            return Outcome.Failed("a container named $old is left from an earlier update: restore it (`docker rename $old $name`) or remove it, then update again")
        }
        val renamed = docker.run(listOf("rename", name, old), settings.dockerSec)
        if (!renamed.ok) return Outcome.Failed("could not set the old container aside: ${renamed.out.oneLine()}")
        docker.run(listOf("stop", old), settings.dockerSec)
        val started = docker.run(runArgs(c, image, baked), settings.dockerSec)
        if (!started.ok) return rollback(name, old, "the new container did not start: ${started.out.oneLine()}")
        report.step(STEP_WAIT, "waiting for runner '$runner' to answer on the new image")
        val contract = awaitContract(runner)
            ?: return rollback(name, old, "runner '$runner' did not answer within ${settings.waitSec} s on $image")
        if (F.contractOlder(contract, F.MIN_RUNNER_CONTRACT)) {
            return rollback(name, old, "$image is still contract $contract (the app needs ${F.MIN_RUNNER_CONTRACT}); build or publish a newer runner image, or set runners.update.image")
        }
        docker.run(listOf("rm", old), settings.dockerSec)
        return Outcome.Updated(name, image, contract, c.imageRef)
    }

    /** The image to recreate [c] on, or null when none is available (see the class). Pulls a registry candidate first. */
    fun pickImage(c: Container): String? {
        settings.image.trim().takeIf { it.isNotEmpty() }?.let { wanted ->
            if (hasImage(wanted) || docker.run(listOf("pull", wanted), settings.dockerSec).ok) return wanted
            return null
        }
        val (repo, tag) = splitRef(c.imageRef)
        val sha = build()?.let(::shaOf)
        if (sha != null) {
            val tags = docker.run(listOf("images", repo, "--format", "{{.Tag}}"), settings.dockerSec).takeIf { it.ok }?.out?.lines().orEmpty().map { it.trim() }
            tags.firstOrNull { t -> t.startsWith(LOCAL_TAG) && shaMatches(t.removePrefix(LOCAL_TAG), sha) }?.let { return "$repo:$it" }
        }
        version()?.takeIf { VERSION.matches(it) }?.let { v ->
            val ref = "$repo:$v"
            if (hasImage(ref) || docker.run(listOf("pull", ref), settings.dockerSec).ok) return ref
        }
        if (tag != null && !tag.startsWith(LOCAL_TAG) && docker.run(listOf("pull", "$repo:$tag"), settings.dockerSec).ok) return "$repo:$tag"
        return null
    }

    private fun hasImage(ref: String): Boolean = docker.run(listOf("image", "inspect", ref, "--format", "{{.Id}}"), settings.dockerSec).ok

    private fun registryTarget(): String = version()?.takeIf { VERSION.matches(it) }?.let { "${settings.registryImage}:$it" } ?: "${settings.registryImage}:latest"

    private fun rollback(name: String, old: String, reason: String): Outcome {
        docker.run(listOf("rm", "-f", name), settings.dockerSec)
        val back = docker.run(listOf("rename", old, name), settings.dockerSec).ok && docker.run(listOf("start", name), settings.dockerSec).ok
        return Outcome.Failed(if (back) "$reason; the old runner is back as it was" else "$reason; restoring the old container failed: start it with `docker start $old`", back)
    }

    /** Polls the runner's hello until it answers (its contract) or `runners.update.waitSec` passes (null). */
    private fun awaitContract(runner: String): String? {
        val until = clock() + settings.waitSec * MS_PER_SEC
        while (clock() < until) {
            val answer = try { links.call(runner, F.HELLO, "{}") } catch (_: LinkFailure) { null }
            if (answer != null && answer.type == F.REPLY) {
                runCatching { F.json.decodeFromString(F.HelloAnswer.serializer(), answer.data).contract }.getOrNull()?.let { return it }
            }
            sleep(POLL_MS)
        }
        return null
    }

    companion object {
        const val DEFAULT_REGISTRY_IMAGE = "ghcr.io/superiorsoft-llc/infantry-ai"
        const val DEFAULT_WAIT_SEC = 120L
        const val DEFAULT_DOCKER_SEC = 600L
        const val STEP_FIND = "find container"
        const val STEP_IMAGE = "choose image"
        const val STEP_RECREATE = "recreate"
        const val STEP_WAIT = "wait for runner"
        val STEPS = listOf(STEP_FIND, STEP_IMAGE, STEP_RECREATE, STEP_WAIT)

        /** The suffix the old container carries while the new one proves itself (removed on success, renamed back on failure). */
        const val OLD_SUFFIX = "-before-update"
        const val LOCAL_TAG = "local-"
        private const val POLL_MS = 2_000L
        private const val MS_PER_SEC = 1_000L
        private const val MIN_SHA = 7
        private const val SHOWN = 300
        private val VERSION = Regex("""^\d+\.\d+\.\d+$""")
        private val lenient = Json { ignoreUnknownKeys = true }

        /** `wss://host:port` -> (host, port); null when unreadable. */
        fun hostPort(url: String): Pair<String, Int>? = runCatching {
            val u = URI(url.trim())
            val port = if (u.port > 0) u.port else return null
            (u.host?.removePrefix("[")?.removeSuffix("]") ?: return null) to port
        }.getOrNull()

        /** `repo[:tag]` with a registry port kept in the repo (`host:5000/x:tag` -> `host:5000/x`, `tag`); a digest is dropped. */
        fun splitRef(ref: String): Pair<String, String?> {
            val noDigest = ref.substringBefore('@')
            val slash = noDigest.lastIndexOf('/')
            val colon = noDigest.lastIndexOf(':')
            return if (colon > slash) noDigest.substring(0, colon) to noDigest.substring(colon + 1) else noDigest to null
        }

        /** The commit of a build string (`1.0.1+20261023.1432.e85b223[.dirty]`), or the string itself when it is a bare sha. */
        fun shaOf(build: String): String? {
            val parts = build.substringAfter('+', "").split('.')
            val sha = if (parts.size >= 3) parts[2] else build
            return sha.lowercase().takeIf { Regex("^[0-9a-f]{$MIN_SHA,40}$").matches(it) }
        }

        /** Two short shas name the same commit when the shorter is a prefix of the longer (both at least 7 characters). */
        fun shaMatches(a: String, b: String): Boolean {
            val x = a.lowercase()
            val y = b.lowercase()
            return x.length >= MIN_SHA && y.length >= MIN_SHA && (x.startsWith(y) || y.startsWith(x))
        }

        /**
         * The `docker run` that recreates [c] on [image]. [baked] is the OLD image's env: those entries came from the image, not from
         * the owner's `docker run`, so the new image supplies its own; every other entry (`-e` the owner passed, overrides included) stays.
         */
        fun runArgs(c: Container, image: String, baked: Set<String>): List<String> {
            val args = mutableListOf("run", "-d", "--name", c.name)
            c.restart?.takeIf { it.isNotBlank() && it != "no" }?.let { args += listOf("--restart", it) }
            c.network?.takeIf { it.isNotBlank() && it != "default" && it != "bridge" }?.let { args += listOf("--network", it) }
            c.ports.forEach { args += listOf("-p", it) }
            c.volumes.forEach { args += listOf("-v", it) }
            // An env the old image set (PATH, JAVA_HOME, INFANTRY_VERSION ...) comes from the NEW image; the container's own stay.
            c.env.filter { it !in baked }.forEach { args += listOf("-e", it) }
            args += image
            return args
        }

        /** One container from `docker inspect <name>` (a JSON array of one object). */
        fun parseInspect(text: String): Container? = runCatching {
            val o = (lenient.parseToJsonElement(text) as JsonArray).first() as JsonObject
            val config = o.obj("Config")
            val host = o.obj("HostConfig")
            val ports = host?.obj("PortBindings")?.flatMap { (containerPort, bindings) ->
                (bindings as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }.map { b ->
                    val ip = b.str("HostIp").orEmpty()
                    val hp = b.str("HostPort").orEmpty()
                    val cp = containerPort.removeSuffix("/tcp")
                    listOf(ip, hp, cp).filter { it.isNotEmpty() }.joinToString(":")
                }
            }.orEmpty()
            val volumes = (o["Mounts"] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }.mapNotNull { m ->
                val dest = m.str("Destination") ?: return@mapNotNull null
                val src = if (m.str("Type") == "volume") m.str("Name") else m.str("Source")
                src?.let { s -> "$s:$dest" + if (m["RW"]?.let { (it as? JsonPrimitive)?.contentOrNull } == "false") ":ro" else "" }
            }
            val restart = host?.obj("RestartPolicy")?.let { r ->
                val n = r.str("Name").orEmpty()
                val max = r.str("MaximumRetryCount")?.toIntOrNull() ?: 0
                if (n == "on-failure" && max > 0) "$n:$max" else n
            }
            Container(
                name = o.str("Name")?.removePrefix("/") ?: return null,
                imageRef = config?.str("Image") ?: return null,
                imageId = o.str("Image") ?: return null,
                env = (config["Env"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.contentOrNull },
                ports = ports,
                volumes = volumes,
                restart = restart,
                network = host?.str("NetworkMode"),
            )
        }.getOrNull()

        /** `["A=1","B=2"]` -> the strings; empty when unreadable. */
        fun parseStringArray(text: String): List<String> = runCatching {
            (lenient.parseToJsonElement(text.trim()) as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
        }.getOrDefault(emptyList())

        /**
         * The one line to run on a runner machine this app cannot reach with docker: pull, replace the container (the volume keeps
         * logins, pairing and history), the same `docker run` the Runners card's first-run hint shows, on [image].
         */
        fun manualCommand(host: String, port: Int, image: String): String =
            "docker pull $image && docker rm -f infantry-runner && docker run -d --restart unless-stopped --name infantry-runner " +
                "-e INFANTRY_MODE=runner -e INFANTRY_RUNNER_HOST=$host -p $port:8790 -v infantry-runner:/data $image"

        /** Whether [host] names this machine: loopback, `localhost`, this host's name, or one of its interface addresses. */
        fun localHost(host: String): Boolean {
            val h = host.lowercase()
            if (h == "localhost" || h == "127.0.0.1" || h == "::1" || h == "0:0:0:0:0:0:0:1") return true
            return runCatching {
                if (h == InetAddress.getLocalHost().hostName.lowercase()) return true
                val mine = NetworkInterface.getNetworkInterfaces().toList().flatMap { it.inetAddresses.toList() }.map { it.hostAddress.substringBefore('%').lowercase() }
                h in mine
            }.getOrDefault(false)
        }

        private fun JsonObject.obj(key: String): JsonObject? = this[key] as? JsonObject
        private fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.contentOrNull
        private fun JsonArray?.orEmpty(): List<JsonElement> = this ?: emptyList()
        private fun String.oneLine(): String = trim().replace(Regex("\\s+"), " ").take(SHOWN)
    }
}

/** The real docker CLI (`docker` on PATH). Output is read whole; a call past [timeoutSec] is killed and answers exit -1. */
object ProcessDocker : RunnerUpdater.Docker {
    override fun run(args: List<String>, timeoutSec: Long): RunnerUpdater.Run = try {
        val p = ProcessBuilder(listOf("docker") + args).redirectErrorStream(true).start()
        p.outputStream.close()
        val out = StringBuilder()
        val reader = Thread { runCatching { p.inputStream.bufferedReader().forEachLine { out.appendLine(it) } } }.apply { isDaemon = true; start() }
        if (!p.waitFor(timeoutSec, TimeUnit.SECONDS)) {
            p.destroyForcibly()
            RunnerUpdater.Run(-1, "docker ${args.firstOrNull().orEmpty()} took longer than $timeoutSec s")
        } else {
            reader.join(READ_JOIN_MS)
            RunnerUpdater.Run(p.exitValue(), synchronized(out) { out.toString() })
        }
    } catch (e: Exception) {
        RunnerUpdater.Run(-1, "docker could not run: ${e.message}")
    }

    private const val READ_JOIN_MS = 2_000L
}
