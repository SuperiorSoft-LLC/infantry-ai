// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.daemon

import com.superiorsoftllc.infantry.session.PathEnv
import kotlin.test.Test
import kotlin.test.assertEquals

/** ROLE-NOTE-READ-1: the launcher directory stays first on a session's PATH when later env layers set their own PATH. */
class PathEnvTest {
    @Test
    fun laterPathLayerKeepsTheLauncherDirectory() {
        // childEnv (bin first) overlaid by a launch-env PATH that knows nothing of the bin dir: the old `+` dropped it.
        val env = mapOf("PATH" to "/bin:/sys", "INFANTRY_BIN" to "/bin") + mapOf("PATH" to "/shims:/sys")
        assertEquals("/bin:/shims:/sys", PathEnv.withBinOnPath(env, "ignored", ":")["PATH"])
    }

    @Test
    fun mixedCaseKeysCollapseToOneAndKeepTheirSpelling() {
        val out = PathEnv.withBinOnPath(mapOf("Path" to "/a", "PATH" to "/b", "INFANTRY_BIN" to "/bin"), null, ":")
        assertEquals(listOf("PATH"), out.keys.filter { it.equals("path", true) })
        assertEquals("/bin:/b", out["PATH"])
    }

    @Test
    fun missingPathStartsFromInheritedAndNoBinLeavesEnvAlone() {
        assertEquals("/x:/y", PathEnv.withBinOnPath(mapOf("INFANTRY_BIN" to "/x"), "/y", ":")["PATH"])
        val plain = mapOf("PATH" to "/y")
        assertEquals(plain, PathEnv.withBinOnPath(plain, "/z", ":"))
    }
}
