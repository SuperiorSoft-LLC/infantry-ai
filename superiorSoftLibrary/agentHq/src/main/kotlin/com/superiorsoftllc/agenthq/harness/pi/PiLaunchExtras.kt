package com.superiorsoftllc.agenthq.harness.pi

import java.io.File

/**
 * A generic seam: extra pi argv decided at session START, from the working directory and the session env. Empty by default,
 * so a pi start is exactly [PiCommand]'s unless something is registered. It is NOT part of the restart fingerprint, so
 * changing what it returns never restarts a running session. The pi tool bridge experiment registers here.
 */
object PiLaunchExtras {
    @Volatile var args: (workingDir: File?, env: Map<String, String>) -> List<String> = { _, _ -> emptyList() }
}
