package com.superiorsoftllc.infantry.worktree

import java.io.File
import java.util.Properties
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
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

    /** A Gradle project: only those are provisioned. Untracked is enough, the check reads the main checkout. */
    private fun gradleRepo(): File = newGitRepo().also { File(it, "settings.gradle.kts").writeText("rootProject.name = \"sdk\"\n") }

    @Test
    fun worktreeCreation_writesSdkDirFromTheMainCheckoutsLocalProperties() {
        val repo = gradleRepo()
        val sdk = fakeSdk()
        File(repo, "local.properties").writeText("sdk.dir=${sdk.path.replace("\\", "\\\\")}\n")

        val cut = assertIs<TicketBranchOutcome.Ready>(
            TicketBranches(git, provisioners = WorktreeProvisions(listOf(AndroidSdkProvision { null })))
                .ensure(TicketBranchRequest("ticket-aaaa1111", "Sdk", repo, "HEAD")),
        )

        assertEquals(sdk.path, sdkDir(cut.worktree.path))
        assertNull(cut.warning)
    }

    @Test
    fun theSettingIsUsedWhenTheMainCheckoutHasNoLocalProperties() {
        val repo = gradleRepo()
        val sdk = fakeSdk()
        val cut = assertIs<TicketBranchOutcome.Ready>(
            TicketBranches(git, provisioners = WorktreeProvisions(listOf(AndroidSdkProvision { if (it == AndroidSdkProvision.SETTING) sdk.path else null })))
                .ensure(TicketBranchRequest("ticket-bbbb2222", "Sdk", repo, "HEAD")),
        )
        assertEquals(sdk.path, sdkDir(cut.worktree.path))
    }

    @Test
    fun aMissingSdk_isOneClearMessageAndNoFile() {
        val repo = gradleRepo()
        val cut = assertIs<TicketBranchOutcome.Ready>(
            TicketBranches(git, provisioners = WorktreeProvisions(listOf(AndroidSdkProvision { null })))
                .ensure(TicketBranchRequest("ticket-cccc3333", "Sdk", repo, "HEAD")),
        )
        assertNull(sdkDir(cut.worktree.path))
        val warning = assertNotNull(cut.warning)
        assertEquals(1, Regex("no Android SDK found").findAll(warning).count())
        assertTrue(warning.contains(AndroidSdkProvision.SETTING))
        assertEquals("cut from HEAD ${cut.worktree.base.take(8)}", cut.note, "the warning stays out of the cut note")
    }

    @Test
    fun aRepositoryThatIsNoGradleProject_getsNoFileAndNoWarning() {
        val repo = newGitRepo()
        val cut = assertIs<TicketBranchOutcome.Ready>(
            TicketBranches(git, provisioners = WorktreeProvisions(listOf(AndroidSdkProvision { fakeSdk().path })))
                .ensure(TicketBranchRequest("ticket-dddd4444", "Sdk", repo, "HEAD")),
        )
        assertNull(sdkDir(cut.worktree.path))
        assertNull(cut.warning)
    }

    @Test
    fun anExistingLocalProperties_isNeverOverwritten() {
        val repo = gradleRepo()
        val wt = newGitRepo()
        File(wt, "local.properties").writeText("sdk.dir=/mine\n")
        assertNull(AndroidSdkProvision { fakeSdk().path }.provision(repo, wt))
        assertEquals("sdk.dir=/mine\n", File(wt, "local.properties").readText())
    }
}
