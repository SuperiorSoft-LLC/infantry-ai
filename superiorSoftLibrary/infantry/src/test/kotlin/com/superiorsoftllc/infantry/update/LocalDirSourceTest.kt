// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.update

import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class LocalDirSourceTest {
    private val root: File = Files.createTempDirectory("lds").toFile()

    private fun release(version: String, bytes: String = "msi-$version", sum: String? = null): File {
        val d = File(root, version).apply { mkdirs() }
        val msi = File(d, "AgentHQ-$version.msi").apply { writeText(bytes) }
        File(d, ReleaseAssets.SUMS_FILE).writeText(ReleaseAssets.sumsLine(sum ?: ReleaseAssets.sha256(msi), msi.name) + "\n")
        File(d, ReleaseAssets.NOTES_FILE).writeText("notes $version")
        return msi
    }

    private fun source(current: String) = LocalDirSource(root, SemVer.parse(current)!!)

    @Test fun offersTheHighestNewerVersionAndVerifiesIt() {
        release("1.0.1"); release("1.0.5"); release("1.0.3")
        val a = assertIs<UpdateCheck.Available>(source("1.0.2").check()).update
        assertEquals(SemVer.parse("1.0.5"), a.latest)
        assertEquals("notes 1.0.5", a.notes)
        val got = assertIs<Fetched.Ok<VerifiedInstaller>>(source("1.0.2").download(a, File(root, "dl")))
        assertTrue(got.value.checksumVerified && got.value.file.isFile)
    }

    @Test fun upToDateWhenNothingIsNewer() {
        release("1.0.1")
        assertIs<UpdateCheck.UpToDate>(source("1.0.1").check())
    }

    @Test fun refusesATamperedInstallerAndLeavesNoFile() {
        release("1.0.2", sum = "0".repeat(64))
        val s = source("1.0.0")
        val a = assertIs<UpdateCheck.Available>(s.check()).update
        val dl = File(root, "dl")
        assertIs<Fetched.Failed>(s.download(a, dl))
        assertTrue(dl.listFiles().orEmpty().isEmpty())
    }

    @Test fun refusesAReleaseWithoutChecksums() {
        val d = File(root, "1.0.2").apply { mkdirs() }
        File(d, "AgentHQ-1.0.2.msi").writeText("x")
        assertIs<UpdateCheck.Failed>(source("1.0.0").check())
    }

    @Test fun missingFolderFailsCleanly() {
        assertIs<UpdateCheck.Failed>(LocalDirSource(File(root, "nope"), SemVer(1, 0, 0)).check())
    }

    @Test fun settingsPickTheLocalDirOverTheRepo() {
        val s = UpdateSettings.resolve(true, "1.0.0", null, setting = { if (it == UpdateSettings.DIR) root.path else null }, env = { null })
        assertEquals(root, (s as UpdateSettings.Enabled).config.localDir)
    }
}
