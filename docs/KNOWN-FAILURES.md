# Known failures on dev

A test class on this list fails on dev too. Do not chase it unless your ticket is about it. Report it in your
ticket as "known failure, see docs/KNOWN-FAILURES.md" and move on. A failure NOT on this list is new: it is yours
(or a regression) and gets a bug ticket with the evidence.

## Source and date

- **Base list:** the QA base file `base-infantry.txt` (62 classes, last changed 2026-11-03), the list QA's
  `qa-run.ps1` compares each full run against. It lives in the QA work folder on the owner's machine, outside the repo.
- **Cross-check:** full QA run on dev `5f5b974e` (2026-11-05 09:35 -0500): `infantry` `test` 2705 tests,
  120 failed, 2 skipped; `infantryUi` `test` 785 tests, 8 failed; 60 failing classes. Column "Fails at 5f5b974e" shows it.
- The later run on `581c5ee6` (2026-11-05 10:46) did not finish (Gradle daemon disappeared), so it is not used for the cross-check.
- **Reason column:** the first failing test of the class and the assertion it stopped at, from the 5f5b974e log.
  None of these classes has a ticket of its own yet; the Ticket column is for the fix ticket when one is filed.

Failing at 5f5b974e but not in the base list (so NOT a known failure; treat as new): `LicenseMapGuardTest`
(infantry, `everySourceFileHasTheSpdxIdOfItsPath`). The unfinished 581c5ee6 run also failed `CoreShellTreeTest`
(infantryUi, `the core tab trees keep every node id, in order, unique across the window tree`), not in the base either.

## How to regenerate

1. Run the full suite on dev: the QA script (`qa-run.ps1`), or the bulk gate `scripts/infantry-gate.sh --full dev`
   ([GATE.md](GATE.md)); the gate writes each stage's Gradle output to `$TEMP/infantry-gate-<sha8>-<pid>/<stage>.log`.
2. Take the failing classes: every log line ending in ` FAILED` has the form `<Class> > <test> FAILED`; the line
   after it is the assertion.
3. Update the table: one row per base class, the Fails column from that run, and the commit and date above.
   A class that passes on two full runs in a row comes off the base list (tell QA).

## The list

| Module | Class (`com.superiorsoftllc.infantry.` +) | Fails at 5f5b974e | Reason: first failing test, where it stops | Ticket |
|---|---|---|---|---|
| infantry | `agents.SharedKnowledgeTest` | yes | `knowledge is shipped and names every progress mechanism`: AssertionError at SharedKnowledgeTest.kt:24 | |
| infantry | `agents.registry.AgentRegistryLayerGuardTest` | yes | `the agent layer names no orchestration code`: AssertionError at AgentRegistryLayerGuardTest.kt:47 | |
| infantry | `cli.AgentTicketStartE2eTest` | yes | `MCP - an agent pins role, provider and model on a ticket, then ticket_start runs the worker exactly so`: AssertionError at AgentTicketStartE2eTest.kt:257 | |
| infantry | `cli.CliAcceptanceTest` | yes | `watchStreamsOutputAndExitsCleanlyWhenThePodConverges`: AssertionError at CliAcceptanceTest.kt:228 | |
| infantry | `cli.CliDecisionTest` | yes | `refusalsPrintTheCodeAndExitOne`: AssertionError at CliDecisionTest.kt:91 | |
| infantry | `cli.CliIssueTest` | yes | `refusalsPrintTheCodeAndExitOne`: AssertionError at CliIssueTest.kt:154 | |
| infantry | `cli.CliSkillTest` | yes | `usageSlipsExit2BeforeConnecting`: AssertionError at CliSkillTest.kt:120 | |
| infantry | `cli.SessionKillScopeTest` | yes | `aPmRemovesAStaleBusySessionWithoutForceOverTheCli`: IllegalStateException at SessionKillScopeTest.kt:57 | |
| infantry | `cli.SessionRemoveTest` | yes | `theMcpToolRemovesAnEndedSessionOfAnyOriginAndRefusesALiveOne`: IllegalStateException at SessionRemoveTest.kt:80 | |
| infantry | `cli.mcp.BacklogLaneE2eTest` | yes | `backlogHasParityAcrossCliAndMcpAndRefusesEveryStart`: ComparisonFailure at BacklogLaneE2eTest.kt:115 | |
| infantry | `cli.mcp.McpMailToolsE2eTest` | yes | `aWorkerMailsItsPmAndThePmReadsItThroughMcp`: ComparisonFailure at McpMailToolsE2eTest.kt:98 | |
| infantry | `cli.mcp.McpOrchestrationE2eTest` | yes | `anAgentOrchestratesAnotherThroughMcpServe`: ComparisonFailure at McpOrchestrationE2eTest.kt:109 | |
| infantry | `cli.mcp.McpServeConfigTest` | yes | `spawnWithoutAProjectIdUsesTheConfiguredDefault`: ComparisonFailure at McpServeConfigTest.kt:174 | |
| infantry | `cli.mcp.McpServeTest` | yes | `rolesToolsComeFromInfantryRolesUnchanged`: AssertionError at McpServeTest.kt:730 | |
| infantry | `cli.mcp.McpTicketAnswerRaceE2eTest` | yes | `ticketCreateAndUpdateAnswerWithTheirOwnTicketWhileAStartedTicketIsBeingPushed`: ComparisonFailure at McpTicketAnswerRaceE2eTest.kt:116 | |
| infantry | `cli.mcp.McpTicketImagesE2eTest` | yes | `ticketsCarryImagesThroughMcpAndTheWorkerBriefListsThem`: AssertionError at McpTicketImagesE2eTest.kt:152 | |
| infantry | `cli.mcp.McpTicketToolsTest` | yes | `update changes only the fields given, description verbatim`: AssertionError at McpTicketToolsTest.kt:155 | |
| infantry | `cli.mcp.McpWriteToolsTest` | yes | `aKilledSessionRevivedToIdleDoesNotHoldASlotAgain`: ComparisonFailure at McpWriteToolsTest.kt:96 | |
| infantry | `daemon.AgentProfileDaemonTest` | yes | `theProfileIsReadWhenNewArrivesBecauseTheSessionIsBoundThen`: ComparisonFailure at AgentProfileDaemonTest.kt:200 | |
| infantry | `daemon.AgentSwapDaemonTest` | yes | `mailAfterACrossHarnessSwapMidRunStartsAFreshSessionOnTheNewHarness`: AssertionError at AgentSwapDaemonTest.kt:295 | |
| infantry | `daemon.BuiltinToolsListGuardTest` | yes | `roles ask and project config tools are not wired in core and their modules declare them`: AssertionError at BuiltinToolsListGuardTest.kt:74 | |
| infantry | `daemon.DaemonPluginMechanismTest` | yes | `a community plugin without its grants is refused`: AssertionError at DaemonPluginMechanismTest.kt:127 | |
| infantry | `daemon.DaemonPortFallbackTest` | yes | `strictDaemonProcessExitsWithTheReadableLine`: AssertionError at DaemonPortFallbackTest.kt:165 | |
| infantry | `daemon.DaemonShutdownTest` | yes | `stopWaitsForTheTicketOutcomeItsOwnKillQueued`: AssertionError at DaemonShutdownTest.kt:77 | |
| infantry | `daemon.DaemonThreadLeakTest` | yes | `twentyDaemonsStartedAndStoppedLeaveNoDaemonThreadsBehind`: AssertionError at DaemonThreadLeakTest.kt:36 | |
| infantry | `daemon.DescriptorHarnessDaemonTest` | yes | `theDescriptorShowsInTheProvidersFrameAndAPromptRunsThroughIt`: AssertionError at DescriptorHarnessDaemonTest.kt:47 | |
| infantry | `daemon.FrameGateDaemonTest` | yes | `an owner connection passes the owner gate, an agent connection on the same daemon does not`: IllegalStateException at FrameGateDaemonTest.kt:161 | |
| infantry | `daemon.HarnessWiringDaemonTest` | yes | `providersListContainsClaudeAndPiFromTheLoadedHarnessPlugins`: AssertionError at HarnessWiringDaemonTest.kt:29 | |
| infantry | `daemon.InterruptDaemonTest` | yes | `aMessageSentToABusyAgentIsQueuedAndDeliveredWhenItRests`: AssertionError at InterruptDaemonTest.kt:123 | |
| infantry | `daemon.KnowledgeFirstTurnTest` | yes | `aNeverRunSessionRendersTheCountsOfItsDeliveryNotOfItsSpawn`: AssertionError at KnowledgeFirstTurnTest.kt:105 | |
| infantry | `daemon.MailFrameDocTest` | yes | `theDocumentedAnswerFramesAreTheOnesTheDaemonSends`: AssertionError at MailFrameDocTest.kt:126 | |
| infantry | `daemon.NewsAgentPluginTest` | yes | `the news agent is listed, fetches its feed, carries the headlines and can be disabled`: AssertionError at NewsAgentPluginTest.kt:100 | |
| infantry | `daemon.ReportBackDaemonTest` | yes | `aReportToAnotherRoleIsNotAReport`: IllegalStateException at ReportBackDaemonTest.kt:137 | |
| infantry | `daemon.RoleInstanceSpawnTest` | yes | `aDeliveredFirstTurnUsesTheFrozenInstanceAfterAProfileEdit`: AssertionError at RoleInstanceSpawnTest.kt:180 | |
| infantry | `daemon.RoleSpawnIdentityTest` | yes | `aRoleOnlySessionIsRegisteredAtNewAndMailToItsSessionIdCarriesItsRole`: AssertionError at RoleSpawnIdentityTest.kt:138 | |
| infantry | `daemon.SkillsFirstTurnTest` | yes | `aNeverRunSessionNamedByIdRendersItsSkills`: ComparisonFailure at SkillsFirstTurnTest.kt:87 | |
| infantry | `daemon.StateDirLockTest` | yes | `aSecondDaemonOnTheSameStateDirExitsTwoNamingTheFirst`: AssertionError at StateDirLockTest.kt:100 | |
| infantry | `daemon.StubDaemonContractTest` | yes | `readyFrameReportsContract132`: ComparisonFailure at StubDaemonContractTest.kt:52 | |
| infantry | `daemon.TicketContextDaemonTest` | yes | `anUnknownSettingKeyOrAWrongTypeIsRefused`: AssertionError at TicketContextDaemonTest.kt:64 | |
| infantry | `daemon.plugins.BoardApiImportGuardTest` | yes | `no api type imports or names a class of the daemon`: AssertionError at BoardApiImportGuardTest.kt:29 | |
| infantry | `daemon.plugins.HookKindsGuardTest` | yes | `every official module json hook kind validates`: AssertionError at HookKindsGuardTest.kt:35 | |
| infantry | `executables.CliLocationsWireTest` | yes | `aHarnessWhoseExecutableIsGoneIsListedUnavailableWithTheReasonAndTriedPath`: NoSuchElementException at CliLocationsWireTest.kt:213 | |
| infantry | `experiments.pitoolbridge.PiToolBridgeIsolationTest` | yes | `onlyTheRegistrationLineReferencesThePackage`: AssertionError at PiToolBridgeIsolationTest.kt:33 | |
| infantry | `policy.ActionCatalogCoverageTest` | yes | `the parser sees the dispatch`: AssertionError at ActionCatalogCoverageTest.kt:38 | |
| infantry | `policy.DemoPresetParityTest` | yes | `the demo preset allows exactly the old allow-list`: AssertionError at DemoPresetParityTest.kt:24 | |
| infantry | `policy.PolicyActionParityTest` | yes | `every declared frame maps to the declared action`: AssertionError at PolicyActionParityTest.kt:45 | |
| infantry | `protocol.ContractFrameDocTest` | yes | `everyEnvelopeFrameHasATableRowOrIsKnownUndocumented`: AssertionError at ContractFrameDocTest.kt:55 | |
| infantry | `provider.ProviderSeamTest` | yes | `blankConfigDefaultsToOpenCode`: ComparisonFailure at ProviderSeamTest.kt:122 | |
| infantry | `provider.ProviderSpecTest` | yes | `factoryHasNoOpenCodeDefaultAnyMore`: ComparisonFailure at ProviderSpecTest.kt:21 | |
| infantry | `settings.LimitSettingsTest` | yes | `theCatalogDocumentsEveryLimitWithItsValueInForce`: AssertionError at LimitSettingsTest.kt:99 | |
| infantry | `stats.StatsConsistencyTest` | yes | `sameRequestSequenceGivesIdenticalRecordsOnEveryHarness`: NullPointerException at StatsConsistencyTest.kt:87 | |
| infantryUi | `ui.AgentsListGroupsUiTest` | yes | `theSelectedCardHasAnOrangeBorderAndTheOthersDoNot`: AssertionError at Assert.java:89 | |
| infantryUi | `ui.AutomationVisUiTest` | yes | `theSidebarRowShowsTheBadgeOnlyWhileMarkedAndTheLastWakeReason`: AssertionError at SemanticsNodeInteraction.kt:178 | |
| infantryUi | `ui.EpicsPaneUiTest` | yes | `Add task sends ticket_save with project and epic, the card lists the epic's tickets in 4 columns, and a move sends ticket_move`: ComposeTimeoutException at ComposeUiTest.skiko.kt:397 | |
| infantryUi | `ui.ProfileHarnessChangeUiTest` | no | not failing in the 5f5b974e run (base entry kept) | |
| infantryUi | `ui.RoleEditUiTest` | yes | `editRoleFromTheRowMenuChangesTitleAndInstructionsAndTheRowShowsTheNewTitle`: ComparisonFailure at RoleEditUiTest.kt:72 | |
| infantryUi | `ui.RoleIconUiTest` | yes | `anIconPickedInEditRoleShowsOnTheRoleCardAndInTheNewAgentPicker`: ComparisonFailure at RoleIconUiTest.kt:81 | |
| infantryUi | `ui.SwitchAgentUiTest` | no | not failing in the 5f5b974e run (base entry kept) | |
| infantryUi | `ui.TicketCommentsPanelUiTest` | yes | `threadPostResolveAndNotifyPicker`: AssertionError at Assertions.kt:264 | |
| infantryUi | `ui.TicketModalUiTest` | yes | `Open shows the ticket modal over the board, Back and Esc close it, the inline editor still works`: AssertionError at SemanticsNodeInteraction.kt:178 | |
| infantry | `daemon.InfantryBinShellTest` | no | not failing in the 5f5b974e run (base entry kept) | |
| infantry | `cli.mcp.McpTicketImagesE2eTest` | yes | CI-TESTS-5: answer carries no `images` field after plugins wired | e9b33f6b |
| infantry | `cli.mcp.McpMailToolsE2eTest` | yes | CI-TESTS-5: NullPointerException after plugins wired | e9b33f6b |
| infantry | `daemon.ReportBackDaemonTest` | yes | CI-TESTS-5: "run 2 to start" times out at 60 s (4 tests) | e9b33f6b |
| infantry | `cli.CliAcceptanceTest` | yes | CI-TESTS-5: 7 tests (inbox/threads/report handoff) fail after plugins wired | e9b33f6b |
| infantry | `daemon.RoleSpawnIdentityTest` | yes | CI-TESTS-5: mail to a role-only session assertion fails | e9b33f6b |
| infantry | `daemon.AgentSwapDaemonTest` | yes | CI-TESTS-5: mail after a cross-harness swap assertion fails | e9b33f6b |
| infantry | `daemon.DaemonShutdownTest` | yes | CI-TESTS-5: shutdown queued no ticket_runs write | e9b33f6b |
| infantry | `daemon.DaemonPluginMechanismTest` | yes | CI-TESTS-5: 6 tests fail after Settings wired and built-in tools filtered | e9b33f6b |
