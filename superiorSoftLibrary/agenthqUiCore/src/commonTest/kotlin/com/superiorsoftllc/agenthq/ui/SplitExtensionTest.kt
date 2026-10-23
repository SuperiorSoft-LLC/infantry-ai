package com.superiorsoftllc.agenthq.ui

import com.superiorsoftllc.agenthq.ui.model.splitExtension
import kotlin.test.Test
import kotlin.test.assertEquals

/** GIT VIEW: a changed-file row cuts the stem, never the extension. */
class SplitExtensionTest {
    @Test fun extensionIsSplitOffWithItsDot() =
        assertEquals("a/b/sync-worktree-base" to ".sh", splitExtension("a/b/sync-worktree-base.sh"))

    @Test fun noExtensionKeepsTheWholePath() = assertEquals("Makefile" to "", splitExtension("Makefile"))

    @Test fun aDotfileHasNoExtension() = assertEquals(".gitignore" to "", splitExtension(".gitignore"))

    @Test fun onlyTheLastSegmentCounts() = assertEquals("a.b/c" to "", splitExtension("a.b/c"))
}
