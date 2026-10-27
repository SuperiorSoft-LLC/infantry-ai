package com.superiorsoftllc.agenthq.worktree

import java.io.File
import java.util.Properties
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** A new ticket worktree gets the Android SDK location from the daemon; a missing SDK is one clear message. */
class AndroidSdkProvisionTest {

    private val git = testGit()

    private fun fakeSdk(): File =
        File(System.getProperty("java.io.tmpdir"), "fake-sdk-${UUID.randomUUID()}").apply { mkdirs() }

    private fun sdkDir(worktree: String): String? =
        File(worktree, "local.properties").takeIf { it.isFile }
            ?.let { f -> Properties().apply { f.inputStream().use { load(it) } }.getProperty("sdk.dir") }

    @Test
    fun worktreeCreation_writesSdkDirFromTheMainCheckoutsLocalProperties() {
        val repo = newGitRepo()
        val sdk = fakeSdk()
        File(repo, "local.properties").writeText("sdk.dir=${sdk.path.replace("\\", "\\\\")}\n")

        val cut = assertIs<TicketBranchOutcome.Ready>(
            TicketBranches(git, androidSdk = AndroidSdkProvision { null })
                .ensure(TicketBranchRequest("ticket-aaaa1111", "Sdk", repo, "HEAD")),
        )

        assertEquals(sdk.path, sdkDir(cut.worktree.path))
        assertFalse(cut.note.orEmpty().contains("no Android SDK"))
    }

    @Test
    fun theSettingIsUsedWhenTheMainCheckoutHasNoLocalProperties() {
        val repo = newGitRepo()
        val sdk = fakeSdk()
        val cut = assertIs<TicketBranchOutcome.Ready>(
            TicketBranches(git, androidSdk = AndroidSdkProvision { if (it == AndroidSdkProvision.SETTING) sdk.path else null })
                .ensure(TicketBranchRequest("ticket-bbbb2222", "Sdk", repo, "HEAD")),
        )
        assertEquals(sdk.path, sdkDir(cut.worktree.path))
    }

    @Test
    fun aMissingSdk_isOneClearMessageAndNoFile() {
        val repo = newGitRepo()
        val cut = assertIs<TicketBranchOutcome.Ready>(
            TicketBranches(git, androidSdk = AndroidSdkProvision { null })
                .ensure(TicketBranchRequest("ticket-cccc3333", "Sdk", repo, "HEAD")),
        )
        assertNull(sdkDir(cut.worktree.path))
        val note = assertNotNull(cut.note)
        assertEquals(1, Regex("no Android SDK found").findAll(note).count())
        assertTrue(note.contains(AndroidSdkProvision.SETTING))
    }

    @Test
    fun anExistingLocalProperties_isNeverOverwritten() {
        val repo = newGitRepo()
        val wt = newGitRepo()
        File(wt, "local.properties").writeText("sdk.dir=/mine\n")
        assertNull(AndroidSdkProvision { fakeSdk().path }.provision(repo, wt))
        assertEquals("sdk.dir=/mine\n", File(wt, "local.properties").readText())
    }
}
