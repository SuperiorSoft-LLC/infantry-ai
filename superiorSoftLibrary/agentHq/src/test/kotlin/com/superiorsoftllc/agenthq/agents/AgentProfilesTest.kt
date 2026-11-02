package com.superiorsoftllc.agenthq.agents

import com.superiorsoftllc.agenthq.persistence.SqlAgentHqStore
import com.superiorsoftllc.agenthq.protocol.AgentProfile
import com.superiorsoftllc.agenthq.protocol.SpawnOptions
import com.superiorsoftllc.agenthq.harness.AgentProvider
import com.superiorsoftllc.agenthq.harness.RunRequest
import com.superiorsoftllc.agenthq.runner.ProcessListener
import com.superiorsoftllc.agenthq.runner.ProcessRunner
import com.superiorsoftllc.agenthq.runner.ProcessSpec
import com.superiorsoftllc.agenthq.runner.RunningProcess
import com.superiorsoftllc.agenthq.session.JsonSessionStore
import com.superiorsoftllc.agenthq.session.SessionManager
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Contract 1.4.0 (story 9.1): agent profiles, over the real SQLite store.
 *
 * What a client sends is validated and normalised before it is stored, a profile the daemon could not
 * spawn from is refused at save time, and a spawn that names a profile resolves to the provider, model,
 * role and persona the session will run with -- the spawn's own choice first, the profile's next, the
 * default last, and never a model carried over to a provider it does not belong to.
 *
 * No run is ever started here: [AgentProfiles] only asks the [SessionManager] whether a provider and model
 * can be spawned, so the providers and the runner refuse to run at all.
 */
class AgentProfilesTest {

    /** A configured provider id, and nothing else: asking it to run is a test failure. */
    private class NamedProvider(override val id: String, private val configuredModel: String? = null) : AgentProvider {
        override fun spec(request: RunRequest): ProcessSpec = error("AgentProfiles must never start a run")
        override fun defaultModel() = configuredModel
    }

    /** A provider that knows its models (for model validation tests). */
    private class KnownModelsProvider(
        override val id: String,
        val knownModels: List<String>,
        private val configuredModel: String? = null
    ) : AgentProvider {
        override fun spec(request: RunRequest): ProcessSpec = error("AgentProfiles must never start a run")
        override fun defaultModel() = configuredModel
        override fun knownModels() = knownModels
    }

    /** A provider that explicitly returns null for knownModels (accepts any model). */
    private class UnknownModelsProvider(override val id: String, private val configuredModel: String? = null) : AgentProvider {
        override fun spec(request: RunRequest): ProcessSpec = error("AgentProfiles must never start a run")
        override fun defaultModel() = configuredModel
        override fun knownModels() = null
    }

    private object NoRunner : ProcessRunner {
        override val id = "no-runner"
        override fun start(spec: ProcessSpec, listener: ProcessListener): RunningProcess =
            error("AgentProfiles must never start a run")
    }

    private val dir: File = Files.createTempDirectory("agenthq-profiles").toFile()
    private val dbPath = File(dir, "agenthq.db").absolutePath
    private val opened = mutableListOf<SqlAgentHqStore>()

    @AfterTest
    fun cleanUp() {
        opened.forEach { runCatching { it.close() } }
        dir.deleteRecursively()
    }

    private fun openStore(): SqlAgentHqStore = SqlAgentHqStore.open(dbPath).also { opened += it }

    /** Creates a manager with the given default provider and additional providers. */
    private fun manager(
        defaultProvider: AgentProvider = NamedProvider("oc-fake", configuredModel = "free-model"),
        additional: List<AgentProvider> = listOf(NamedProvider("cc-fake")),
    ) = SessionManager(
        defaultProvider,
        NoRunner,
        JsonSessionStore(File(dir, "sessions.json")),
        additionalProviders = additional,
    )

    /** A clock that ticks once per save, and generated ids taken from [ids] in order. */
    private fun profiles(
        store: SqlAgentHqStore = openStore(),
        manager: SessionManager = manager(),
        ids: List<String> = (1..20).map { "agent-$it" },
        traits: Set<String> = emptySet(),
    ): AgentProfiles {
        val next = ids.iterator()
        var tick = 0L
        return AgentProfiles(store.agents, manager, clock = { ++tick }, idFactory = { next.next() }, traitExists = { it in traits })
    }

    private val backend = AgentProfile(
        id = "backend",
        name = "Backend",
        role = "backend",
        provider = "cc-fake",
        model = "sonnet",
        instructions = "You are the backend agent.",
        traitIds = emptyList(), // TRAITS-T3: a stored profile always carries its list
        skillIds = emptyList(), // P10-K12: and its skill list
    )

    // --- saving ----------------------------------------------------------------------------------

    @Test
    fun aProfileWithNoIdIsCreatedUnderAGeneratedIdAndNormalised() {
        val profiles = profiles()

        val saved = profiles.save(
            AgentProfile(
                name = "  Backend dev  ",
                role = " Backend ",
                provider = " CC-Fake ",
                model = " sonnet ",
                instructions = "\n  You are the backend agent.  \n",
            ),
        )

        assertEquals(
            AgentProfile("agent-1", "Backend dev", "backend", "cc-fake", "sonnet", "You are the backend agent.", traitIds = emptyList(), skillIds = emptyList()),
            saved,
            "trimmed; role and provider lowercased; the model id kept as written",
        )
        assertEquals(listOf(saved), profiles.list())
        assertEquals(saved, profiles.get("agent-1"))
    }

    @Test
    fun blankOptionalFieldsAreStoredAsNull() {
        val saved = profiles().save(AgentProfile(name = "QA", role = "qa", provider = " ", model = "", instructions = "  \n "))

        assertEquals(AgentProfile("agent-1", "QA", "qa", null, null, null, traitIds = emptyList(), skillIds = emptyList()), saved)
    }

    /** ORIGIN-SCRUB-1a: an older client saving a retired role id gets the profile stored under its replacement. */
    @Test
    fun aRetiredRoleIdIsSavedUnderItsReplacement() {
        val saved = profiles().save(AgentProfile(name = "Steward", role = "git"))

        assertEquals("version-control", saved.role)
    }

    @Test
    fun modelTrimmingWorks() {
        val saved = profiles().save(AgentProfile(name = "Trim", role = "qa", model = "  opencode/x  "))

        assertEquals("opencode/x", saved.model, "model is trimmed")
    }

    @Test
    fun modelWithWhitespaceIsRefusedWithExactMessage() {
        val profiles = profiles()

        val refusal = assertFailsWith<IllegalArgumentException> {
            profiles.save(AgentProfile(name = "Test", role = "qa", model = "a b"))
        }
        assertEquals("model contains whitespace or a control character", refusal.message, "exact message for whitespace in model")
    }

    @Test
    fun modelWithControlCharIsRefusedWithExactMessage() {
        val profiles = profiles()

        val refusal = assertFailsWith<IllegalArgumentException> {
            profiles.save(AgentProfile(name = "Test", role = "qa", model = "model\u0001with\u0002control"))
        }
        assertEquals("model contains whitespace or a control character", refusal.message, "exact message for control char in model")
    }

    @Test
    fun modelLongerThan200CharsIsRefusedWithExactMessage() {
        val profiles = profiles()
        val longModel = "m".repeat(201)

        val refusal = assertFailsWith<IllegalArgumentException> {
            profiles.save(AgentProfile(name = "Test", role = "qa", model = longModel))
        }
        assertEquals("model is longer than 200 characters", refusal.message, "exact message for >200 char model")
    }

    @Test
    fun savingAnExistingIdOverwritesItAndKeepsItsPlaceInTheList() {
        val profiles = profiles()
        profiles.save(AgentProfile(id = "a", name = "First", role = "qa"))
        profiles.save(AgentProfile(id = "b", name = "Second", role = "qa"))

        profiles.save(AgentProfile(id = "a", name = "First, renamed", role = "backend", provider = "cc-fake"))

        assertEquals(listOf("a", "b"), profiles.list().map { it.id }, "an overwrite keeps the profile's creation order")
        assertEquals(AgentProfile("a", "First, renamed", "backend", "cc-fake", null, null, traitIds = emptyList(), skillIds = emptyList()), profiles.get("a"))
        assertEquals(2, profiles.list().size, "overwritten, not duplicated")
    }

    @Test
    fun aClientChosenIdIsKeptAndAnIdThatIsNotOneIsRefused() {
        val profiles = profiles()
        assertEquals("backend", profiles.save(AgentProfile(id = " backend ", name = "B", role = "backend")).id)
        assertEquals("x".repeat(64), profiles.save(AgentProfile(id = "x".repeat(64), name = "Long", role = "qa")).id)

        val refused = listOf(
            "role:backend", // ':' is reserved for `role:<role>` (story 9.3)
            "-x",
            ".hidden",
            "has space",
            "a/b",
            "x".repeat(65),
        )
        for (bad in refused) {
            val refusal = assertFailsWith<IllegalArgumentException>("'$bad' must be refused") {
                profiles.save(AgentProfile(id = bad, name = "N", role = "qa"))
            }
            assertTrue(refusal.message!!.contains("not a profile id"), refusal.message)
        }
        assertEquals(listOf("backend", "x".repeat(64)), profiles.list().map { it.id }, "nothing refused was stored")
    }

    @Test
    fun aGeneratedIdThatIsAlreadyTakenIsSkipped() {
        val profiles = profiles(ids = listOf("dup", "fresh"))
        profiles.save(AgentProfile(id = "dup", name = "Taken", role = "qa"))

        assertEquals("fresh", profiles.save(AgentProfile(name = "New", role = "qa")).id)
        assertEquals("Taken", profiles.get("dup")!!.name, "the existing profile was not overwritten")
    }

    @Test
    fun invalidFieldsAreRefusedAndNothingIsStored() {
        val profiles = profiles()
        val refused = mapOf(
            "blank name" to AgentProfile(name = "   ", role = "qa"),
            "control character" to AgentProfile(name = "two\nlines", role = "qa"),
            "long name" to AgentProfile(name = "n".repeat(AgentProfiles.MAX_NAME_LENGTH + 1), role = "qa"),
            "role with a space" to AgentProfile(name = "QA", role = "Quality Assurance"),
            "role starting with a digit" to AgentProfile(name = "QA", role = "1qa"),
            "blank role" to AgentProfile(name = "QA", role = " "),
            "long role" to AgentProfile(name = "QA", role = "r".repeat(33)),
            "role:prefix" to AgentProfile(name = "QA", role = "role:qa"),
            "unconfigured provider" to AgentProfile(name = "QA", role = "qa", provider = "gemini"),
            "model read as a flag" to AgentProfile(name = "QA", role = "qa", provider = "cc-fake", model = "-x"),
            "model with a space" to AgentProfile(name = "QA", role = "qa", model = "two words"),
            "long instructions" to AgentProfile(
                name = "QA",
                role = "qa",
                instructions = "i".repeat(AgentProfiles.MAX_INSTRUCTIONS_LENGTH + 1),
            ),
        )
        for ((what, profile) in refused) {
            assertFailsWith<IllegalArgumentException>("$what must be refused") { profiles.save(profile) }
        }
        assertTrue(profiles.list().isEmpty(), "a refused save stores nothing: ${profiles.list()}")

        val unknownProvider = assertFailsWith<IllegalArgumentException> {
            profiles.save(AgentProfile(name = "QA", role = "qa", provider = "gemini"))
        }
        assertTrue(unknownProvider.message!!.contains("gemini"), unknownProvider.message)
    }

    @Test
    fun theLimitsThemselvesAreAccepted() {
        val saved = profiles().save(
            AgentProfile(
                name = "n".repeat(AgentProfiles.MAX_NAME_LENGTH),
                role = "r" + "-".repeat(30) + "1",
                instructions = "i".repeat(AgentProfiles.MAX_INSTRUCTIONS_LENGTH),
            ),
        )
        assertEquals(AgentProfiles.MAX_INSTRUCTIONS_LENGTH, saved.instructions!!.length)
        assertEquals(32, saved.role.length)
    }

    // --- deleting ----------------------------------------------------------------------------------

    @Test
    fun deleteRemovesAProfileAndAnUnknownOrBlankIdIsRefused() {
        val profiles = profiles()
        profiles.save(AgentProfile(id = "a", name = "A", role = "qa"))
        profiles.save(AgentProfile(id = "b", name = "B", role = "qa"))

        profiles.delete(" a ")
        assertEquals(listOf("b"), profiles.list().map { it.id })

        val unknown = assertFailsWith<IllegalArgumentException> { profiles.delete("a") }
        assertTrue(unknown.message!!.contains("no agent profile 'a'"), unknown.message)
        val blank = assertFailsWith<IllegalArgumentException> { profiles.delete("  ") }
        assertTrue(blank.message!!.contains("expects a profile id"), blank.message)
        assertEquals(listOf("b"), profiles.list().map { it.id })
    }

    @Test
    fun profilesSurviveADaemonRestart() {
        openStore().use { store -> profiles(store = store).save(backend) }

        openStore().use { store ->
            assertEquals(listOf(backend), profiles(store = store).list())
        }
    }

    // --- spawning from a profile ---------------------------------------------------------------------

    @Test
    fun aSpawnThatNamesNoProfileResolvesExactlyAsIn130() {
        val profiles = profiles()
        assertEquals(ResolvedSpawn(), profiles.resolveSpawn(null), "no options: the default provider and model")
        assertEquals(ResolvedSpawn(), profiles.resolveSpawn(SpawnOptions()))
        assertEquals(
            ResolvedSpawn(providerId = "cc-fake", model = "opus"),
            profiles.resolveSpawn(SpawnOptions(provider = "cc-fake", model = "opus")),
        )
    }

    @Test
    fun aProfileSuppliesItsProviderModelRoleAndPersona() {
        val profiles = profiles()
        profiles.save(backend)

        assertEquals(
            ResolvedSpawn(
                providerId = "cc-fake",
                model = "sonnet",
                agentId = "backend",
                role = "backend",
                instructions = "You are the backend agent.",
            ),
            profiles.resolveSpawn(SpawnOptions(agentId = " backend ")),
        )
    }

    @Test
    fun theSpawnOverridesTheProfileAndAModelNeverCrossesToAnotherProvider() {
        val profiles = profiles()
        profiles.save(backend) // cc-fake + sonnet
        profiles.save(AgentProfile(id = "qa", name = "QA", role = "qa", model = "free-model-2")) // the default provider

        // The spawn's model wins over the profile's.
        assertEquals(
            ResolvedSpawn("cc-fake", "opus", "backend", "backend", "You are the backend agent."),
            profiles.resolveSpawn(SpawnOptions(agentId = "backend", model = "opus")),
        )
        // Moved to another provider: the profile's model belongs to cc-fake, so it is dropped, and the
        // session runs on the new provider's own default. Profile, role and persona stay.
        assertEquals(
            ResolvedSpawn("oc-fake", null, "backend", "backend", "You are the backend agent."),
            profiles.resolveSpawn(SpawnOptions(agentId = "backend", provider = "oc-fake")),
        )
        // Naming the profile's own provider (in any case) is not a move.
        assertEquals("sonnet", profiles.resolveSpawn(SpawnOptions(agentId = "backend", provider = " CC-FAKE ")).model)

        // A profile with no provider runs on the default, so naming the default keeps its model...
        assertEquals("free-model-2", profiles.resolveSpawn(SpawnOptions(agentId = "qa")).model)
        assertEquals("free-model-2", profiles.resolveSpawn(SpawnOptions(agentId = "qa", provider = "oc-fake")).model)
        // ...and naming another provider drops it.
        assertEquals(
            ResolvedSpawn("cc-fake", null, "qa", "qa", null),
            profiles.resolveSpawn(SpawnOptions(agentId = "qa", provider = "cc-fake")),
        )
    }

    @Test
    fun anUnknownProfileIsRefused() {
        val refusal = assertFailsWith<IllegalArgumentException> {
            profiles().resolveSpawn(SpawnOptions(agentId = "nobody"))
        }
        assertTrue(refusal.message!!.contains("no agent profile 'nobody'"), refusal.message)
    }

    @Test
    fun aSpawnTheManagerCannotHonourIsRefusedEvenWithAProfile() {
        val profiles = profiles()
        profiles.save(backend)
        assertFailsWith<IllegalArgumentException> {
            profiles.resolveSpawn(SpawnOptions(agentId = "backend", provider = "gemini"))
        }
        assertFailsWith<IllegalArgumentException> {
            profiles.resolveSpawn(SpawnOptions(agentId = "backend", model = "--yolo"))
        }
    }

    @Test
    fun aProfileWhoseProviderIsNoLongerConfiguredIsRefusedAtSpawnNotSilentlyMoved() {
        val store = openStore()
        profiles(store = store).save(backend)

        // Restarted with AGENTHQ_PROVIDERS emptied: the profile is still stored, but cc-fake is gone.
        val restarted = profiles(store = store, manager = manager(additional = emptyList()))
        assertEquals(listOf(backend), restarted.list(), "the profile is kept, not dropped")
        val refusal = assertFailsWith<IllegalArgumentException> {
            restarted.resolveSpawn(SpawnOptions(agentId = "backend"))
        }
        assertTrue(refusal.message!!.contains("cc-fake"), refusal.message)
    }

    @Test
    fun theFirstPromptCarriesThePersonaAheadOfTheClientsPrompt() {
        assertEquals(
            "You are the backend agent.\n\nadd an endpoint",
            ResolvedSpawn(instructions = "You are the backend agent.").firstPrompt("add an endpoint"),
        )
        // BUG-808ce596: a spawn with a role (and agent) but no persona still opens with who it runs as.
        assertEquals(
            "You are running inside Infantry AI as role 'qa', agent id 'qa'. " +
                "Your environment carries the same identity (AGENTHQ_ROLE, AGENTHQ_AGENT_ID, AGENTHQ_SESSION_ID).\n\n" +
                "add an endpoint",
            ResolvedSpawn(agentId = "qa", role = "qa").firstPrompt("add an endpoint"),
        )
        assertEquals("add an endpoint", ResolvedSpawn().firstPrompt("add an endpoint"), "an anonymous spawn is left as typed")
    }

    // --- model validation (AGENT-UX-7) ---------------------------------------------------------

    @Test
    fun unknownModelIsRefusedWithClosestMatchSuggestion() {
        val models = listOf("opencode/space-bunny-free", "opencode/claude-sonnet-4", "opencode/gpt-4o")
        val defaultProvider = KnownModelsProvider("oc-fake", models, "opencode/space-bunny-free")
        val manager = manager(defaultProvider = defaultProvider)
        val profiles = profiles(manager = manager)

        val refusal = assertFailsWith<IllegalArgumentException> {
            profiles.save(AgentProfile(name = "Test", role = "qa", provider = "oc-fake", model = "space-bunny-freee"))
        }
        assertTrue(refusal.message!!.contains("unknown model 'space-bunny-freee' for oc-fake"))
        assertTrue(refusal.message!!.contains("did you mean 'opencode/space-bunny-free'"), refusal.message)
    }

    @Test
    fun knownModelIsAccepted() {
        val models = listOf("opencode/space-bunny-free", "opencode/claude-sonnet-4", "opencode/gpt-4o")
        val defaultProvider = KnownModelsProvider("oc-fake", models, "opencode/space-bunny-free")
        val manager = manager(defaultProvider = defaultProvider)
        val profiles = profiles(manager = manager)

        val saved = profiles.save(AgentProfile(name = "Test", role = "qa", provider = "oc-fake", model = "opencode/claude-sonnet-4"))
        assertEquals("opencode/claude-sonnet-4", saved.model)
    }

    @Test
    fun providerReturningNullAcceptsAnyModel() {
        val defaultProvider = UnknownModelsProvider("oc-fake", "free-model")
        val manager = manager(defaultProvider = defaultProvider)
        val profiles = profiles(manager = manager)

        val saved = profiles.save(AgentProfile(name = "Test", role = "qa", provider = "oc-fake", model = "completely-unknown-model"))
        assertEquals("completely-unknown-model", saved.model)
    }

    @Test
    fun modelValidationSkipsWhenProviderHasNoModelList() {
        // Provider is configured but returns null for knownModels -> accept any model
        val defaultProvider = UnknownModelsProvider("oc-fake", "free-model")
        val manager = manager(defaultProvider = defaultProvider)
        val profiles = profiles(manager = manager)

        val saved = profiles.save(AgentProfile(name = "Test", role = "qa", provider = "oc-fake", model = "any-model-at-all"))
        assertEquals("any-model-at-all", saved.model)
    }

    // --- TRAITS-T3: agent trait additions -----------------------------------------------------------

    private val library = setOf("report-back", "no-force-push", "tdd")

    @Test
    fun aNewProfileWithoutTraitIdsStoresAnEmptyList() {
        val saved = profiles(traits = library).save(backend.copy(traitIds = null))
        assertEquals(emptyList(), saved.traitIds)
    }

    @Test
    fun aSaveWithoutTheFieldKeepsTheStoredListAndEmptyClearsIt() {
        val profiles = profiles(traits = library)
        val first = profiles.save(backend.copy(traitIds = listOf(" tdd ", "report-back")))
        assertEquals(listOf("tdd", "report-back"), first.traitIds, "trimmed, order kept")

        val renamed = profiles.save(backend.copy(name = "Renamed", traitIds = null))
        assertEquals("Renamed", renamed.name)
        assertEquals(listOf("tdd", "report-back"), renamed.traitIds, "null/absent keeps the stored list")

        assertEquals(emptyList(), profiles.save(backend.copy(traitIds = emptyList())).traitIds, "[] clears it")
    }

    @Test
    fun aSwapKeepsTheTraitIds() {
        val profiles = profiles(traits = library)
        profiles.save(backend.copy(traitIds = listOf("no-force-push")))
        val swap = profiles.swap("backend", "oc-fake", null, null)
        assertEquals("oc-fake", swap.after.provider)
        assertEquals(listOf("no-force-push"), swap.after.traitIds)
        assertEquals(listOf("no-force-push"), profiles.get("backend")!!.traitIds)
    }

    @Test
    fun unknownTooManyAndDuplicateTraitIdsAreRefusedAndNothingIsWritten() {
        val profiles = profiles(traits = library + (1..9).map { "t$it" })
        profiles.save(backend.copy(traitIds = listOf("tdd")))
        listOf(
            listOf("nope"),
            (1..9).map { "t$it" },
            listOf("tdd", " tdd"),
        ).forEach { bad ->
            assertFailsWith<IllegalArgumentException>(bad.toString()) { profiles.save(backend.copy(name = "Changed", traitIds = bad)) }
        }
        assertEquals("Backend", profiles.get("backend")!!.name)
        assertEquals(listOf("tdd"), profiles.get("backend")!!.traitIds)
    }
}
