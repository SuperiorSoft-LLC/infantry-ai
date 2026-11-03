# Contributing to Infantry AI

Thank you for wanting to help. Please read this page before you open an issue or a pull request.

> **Code contributions from outside SuperiorSoft LLC are on hold.** The license split is applied but still a
> DRAFT (see [LICENSE](LICENSE) and the per-directory map in [docs/LICENSE-MAP.md](docs/LICENSE-MAP.md): MPL-2.0 for the
> abstraction layer, Apache-2.0 for everything concrete). Until the CLA ([docs/legal/CLA.md](docs/legal/CLA.md)) is official
> we cannot accept outside code: there is no clear grant to relicense your changes. Every source file carries an SPDX header
> that `LicenseMapGuardTest` checks; `scripts/license-headers.sh` adds it.
> Bug reports, feature requests and documentation feedback are welcome now. This section will change once a license
> (and, if needed, a contributor agreement) is published.

<!-- Wording to use once the CLA bot is enabled (docs/legal/CLA.md is then official), replacing the notice above:
**Sign the CLA.** Before we can merge a pull request you sign the [Contributor License Agreement](docs/legal/CLA.md).
A bot comments on your first pull request; reply with the sentence it shows. Companies sign the corporate part.
You keep your copyright; the CLA lets SuperiorSoft LLC relicense a file when it moves between the MPL-2.0 core and
the Apache-2.0 rest.
-->

Everyone who takes part follows the [Code of Conduct](CODE_OF_CONDUCT.md).

## Filing an issue

1. Search the [existing issues](../../issues) first; add details to a match instead of opening a duplicate.
2. Open a new issue with the right template ([choose one](../../issues/new/choose)):
   - **Bug report**: the version (Configuration > About, or `infantry version`), the OS, which client (Windows, Android,
     server, CLI), the steps, what you expected and what happened. Attach `infantry.log` (Configuration > Logs > Open logs
     folder). Read the log first: it can hold project paths, prompts and agent output.
   - **Feature request**: the problem you want solved, not only the solution you have in mind.
3. **Security problems never go in a public issue**: follow [SECURITY.md](SECURITY.md).
4. Questions go to [Discussions](../../discussions), not issues.

## Building

Requirements:

- JDK 21 (the Gradle toolchain resolves it through foojay if you do not have one).
- An Android SDK, only for the `infantryAndroid` module.
- Git, and Node 20+ if you want the app to install agent CLIs for you.

Common tasks, from the repository root:

    ./gradlew :superiorSoftLibrary:infantry:compileKotlin :superiorSoftLibrary:infantryUi:compileKotlin
    ./gradlew :superiorSoftLibrary:infantry:runDaemon        # daemon on ws://127.0.0.1:8787/ws
    ./gradlew :superiorSoftLibrary:infantryUi:runUi          # desktop UI against it
    ./gradlew :superiorSoftLibrary:infantryUi:runSmoke       # UI against an in-process stub daemon (no real CLIs)
    ./gradlew :superiorSoftLibrary:infantry:installDist      # the infantry / infantry CLI

**Run one Gradle build at a time.** The test JVMs are memory hungry; two builds in parallel can exhaust the host's RAM
and crash. More detail: [QUICKSTART.md](superiorSoftLibrary/infantry/docs/QUICKSTART.md) (from source) and
[docs/INFANTRY-INSTALL.md](docs/INFANTRY-INSTALL.md) (installer).

## Branches

- `feature-agenthq-mvp` is the branch the app is built from.
- Work happens on a short-lived branch per change, named after its ticket or issue, e.g. `ticket/<id>-<short-slug>`
  or `fix/<issue-number>-<short-slug>`.
- Changes are batched into a `staging-agenthq*` branch, the full test gate runs once over the batch
  ([docs/GATE.md](docs/GATE.md)), and the batch is promoted to `feature-agenthq-mvp` only after it passes.
- Never force-push a shared branch.

## Testing

- Add or update tests with every behaviour change; a fix comes with a test that fails without it.
- Run the tests of the module you touched, for example `./gradlew :superiorSoftLibrary:infantry:test`.
- The full gate is `scripts/infantry-gate.sh <branch-or-sha>` ([docs/GATE.md](docs/GATE.md)). It takes tens of minutes.
- Tests never bind the real ports 8787 or 8788 (a guard test fails the build); use port 0.
- Do not edit a test just to make it pass. If a test is wrong, say why in the commit message.
- Documentation-only changes need no test run.

## Commits

- One logical change per commit. Keep unrelated formatting out of it.
- Subject line: short, imperative, and specific. Either `area: what changed` or a conventional prefix
  (`feat(ui): ...`, `fix(mailbox): ...`, `docs: ...`). Add the ticket or issue id when there is one.
- The body says **why**, and anything a reviewer cannot see from the diff (what you tested, what you did not).
- Never commit secrets: keys, keystores, tokens and passwords belong in `secrets.local.properties` (gitignored) or
  environment variables. `./gradlew verifySecretsNotTracked` fails if one is tracked.
- Wire-protocol changes update `CONTRACT.md` and its version in the same commit.

## Pull requests

Once outside contributions open: fork, branch from `feature-agenthq-mvp`, keep the PR small and focused, fill in the
pull request template, and link the issue it closes. A maintainer reviews it and merges it through the staging gate.

## Code style

Follow the style of the code around you: Kotlin with the official code style, small functions, names that say what a
thing is. Comments explain why, not what.
