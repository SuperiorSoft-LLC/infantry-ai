// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
//
// PREVIEW-BRANCH-1: a Gradle init script that turns `:superiorSoftLibrary:infantryUi:runRealDaemon` into a SAFE branch preview
// (docs/wiki/app-preview.md). The Run tab's "Preview app" command passes it from the main checkout (`{root}`), so it works on
// any branch that has runRealDaemon, even one cut before this file existed:
//
//   gradlew --init-script {root}/scripts/preview-app.init.gradle.kts -PpreviewBranch={branch} :superiorSoftLibrary:infantryUi:runRealDaemon
//
// Right before the window starts it:
//  - drops every INFANTRY_*, AGENTHQ_* and CLAUDE* variable the caller had (the owner daemon's token, state dir, port...);
//  - points the state at a fresh `<checkout>/build/preview-state` (never ~/.infantry);
//  - binds the daemon to a free port in 18900-18999 (strict: no fallback onto another port) and the runner link to another;
//  - uses the live endpoints (INFANTRY_ENV=prod);
//  - labels the window and the footer's build line "PREVIEW <branch>".

val previewPortFirst = 18900
val previewPortLast = 18999
val previewBranch: String = gradle.startParameter.projectProperties["previewBranch"]?.takeIf { it.isNotBlank() } ?: "branch"

fun freePreviewPort(taken: Set<Int>): Int =
    (previewPortFirst..previewPortLast).firstOrNull { p ->
        p !in taken && runCatching { java.net.ServerSocket(p, 1, java.net.InetAddress.getByName("127.0.0.1")).close() }.isSuccess
    } ?: error("PREVIEW: no free port in $previewPortFirst-$previewPortLast")

gradle.allprojects {
    tasks.withType(JavaExec::class.java).configureEach {
        if (name != "runRealDaemon") return@configureEach
        val task = this
        doFirst {
            val checkout = task.project.rootProject.projectDir
            val state = File(checkout, "build/preview-state")
            if (state.exists()) state.deleteRecursively()
            state.mkdirs()
            val env = task.environment
            env.keys.filter { k -> listOf("INFANTRY_", "AGENTHQ_", "CLAUDE").any { k.uppercase().startsWith(it) } }.forEach { env.remove(it) }
            val port = freePreviewPort(emptySet())
            val linkPort = freePreviewPort(setOf(port))
            env["INFANTRY_STATE_DIR"] = state.path
            env["INFANTRY_HOME"] = state.path
            env["INFANTRY_PORT"] = port.toString()
            env["INFANTRY_PORT_STRICT"] = "true"
            env["INFANTRY_LIMIT_RUNNERS_LINK_PORT"] = linkPort.toString()
            env["INFANTRY_ENV"] = "prod"
            val stamp = task.systemProperties["infantry.build"]?.toString()?.takeIf { it.isNotBlank() }
            task.systemProperty("infantry.env", "prod")
            task.systemProperty("infantry.build", "PREVIEW $previewBranch" + (stamp?.let { " ($it)" } ?: ""))
            task.systemProperty("infantry.window.label", "PREVIEW $previewBranch")
            println("[preview] PREVIEW $previewBranch: daemon ws://127.0.0.1:$port/ws, state ${state.path}, endpoints prod")
        }
    }
}
