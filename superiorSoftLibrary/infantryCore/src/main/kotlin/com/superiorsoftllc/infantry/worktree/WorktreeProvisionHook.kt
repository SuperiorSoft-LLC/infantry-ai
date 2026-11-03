package com.superiorsoftllc.infantry.worktree

import java.io.File

/** A worktree-provision seam: runs once for each new ticket worktree. Contributed by data or a plugin, never named in core. */
fun interface WorktreeProvisionHook {
    /** @return a warning for the start's answer, or null when nothing is lacking (or this provisioner does not apply). */
    fun provision(mainRoot: File, worktree: File): String?
}

/** Runs every provisioner; the warnings are joined, none means null. */
class WorktreeProvisions(private val list: List<WorktreeProvisionHook>) {
    fun provision(mainRoot: File, worktree: File): String? =
        list.mapNotNull { it.provision(mainRoot, worktree) }.takeIf { it.isNotEmpty() }?.joinToString("; ")

    companion object {
        /** The provisioners registered at startup (outside core logic: [AndroidSdkProvision] reads its detect files from data). */
        fun default(): WorktreeProvisions = WorktreeProvisions(listOf(AndroidSdkProvision()))
    }
}
