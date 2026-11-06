[← Wiki index](index.md)

# Settings

Settings are durable key/value pairs kept by the daemon. Read and write them in the Settings tab, with the CLI, or
with the MCP tools `infantry_settings_get` / `infantry_settings_set`.

```
infantry settings                                  # every setting, key = value
infantry settings get <key> [--project p] [--role r] [--session s]
infantry settings set <key> <value> [--scope app|project|role|session] [--project p] [--role r] [--session s]
infantry settings explain <key> [--project p] [--role r] [--session s]
infantry settings limits                           # every cap/threshold: value, default, range, env name
```

## Scopes

A value can be stored at four scopes. The most specific one that holds a value wins:

**session > role (in a project) > project > app > built-in default**

| Scope | Target | Use |
|---|---|---|
| app | the whole installation | the normal case; flat keys |
| project | a project id | one project spends differently |
| role | a role inside a project | e.g. a cheap role keeps less context |
| session | one running session | a one-off experiment |

`settings explain <key>` prints the winner and every layer that holds a value. Not every key allows every scope: a
key that is not listed below is **app-only**, and setting it at another scope is refused with the allowed list. The
app scope cannot be cleared, only set.

## Cost-saving defaults and what each opt-in costs

The default is the cheapest reasonable value. A costlier value is an opt-in. These keys allow all four scopes.

| Key | Default | What it does | What the costlier value costs |
|---|---|---|---|
| `related.brief` | `true` | Puts the related-tickets block in a started ticket's brief | Adds to every worker's first turn |
| `brief.related.mode` | `files-command` | `files-command` (one line naming the command), `files-inline`, `tickets` | `files-inline` puts every related path in each brief, re-read every turn; `tickets` adds ids, titles, statuses and snippets: the most tokens |
| `related.files.format` | `paths` | What `ticket files <id> --related` prints: `paths` or `detailed` | `detailed` adds ids, status and counts per path: several times the output |
| `related.descriptionBlock` | `true` | Pastes the related-files block into a new ticket's description | Longer descriptions |
| `related.briefInstruction` | built-in line | The instruction line above the list | none |
| `related.suggestNumber` | `true` | Suggests the next number of a ticket family | none |
| `mail.wakeMode` | `real` | Which mail wakes a resting session: `real`, `never`, `all` | `all` wakes on every automation mail; each wake re-reads the whole context |
| `mail.deliveryMode` | `notify` | What a wake injects: `notify`, `truncate`, `full` | `full` injects every body, re-read on every later turn |
| `limits.session.contextCapTokens` | `100000` | Past this context size, a resting session is restarted with a short handoff when mail wakes it; `0` = off | A higher cap or `0` keeps longer context; each turn costs more |
| `session.keepAliveMinutes` | `30` | Minutes a resting harness process is kept alive; `0` = one-shot | A longer keep-alive holds more RAM |
| `session.resumeAfterRestart` | `true` | A session whose turn a daemon restart cut is prompted to continue at the next start; its starter (the ticket's notify list) and the owner (an attention item) are told either way | Each resumed session spends one more turn |
| `session.resumeAfterRestartPrompt` | `""` | The prompt such a session gets; `""` = "The daemon restarted and your last turn was cut. Continue where you left off." | none |
| `context.includeLinkedTasksContext` | `true` | Linked-tickets section in the brief | Longer brief when on |
| `context.includeSkillsContext` | `true` | Skills section in the brief | Longer brief when on |
| `context.includeFinishInstructions` | `true` | Report-instruction lines in the brief | Turn off when a script or role note owns the finish step |

`mail.wakeMode` and `mail.deliveryMode` also take per-role and per-session variants as keys with a suffix
(`mail.wakeMode.<role>`).

## Other settings worth knowing

| Key | Default | Meaning |
|---|---|---|
| `tickets.weightExecutors` | (unset) | Provider and model per ticket weight 1 to 5 |
| project `directory` (`infantry_project_config_set`) | (unset) | The project's folder. The Run tab's commands belong to one project each and run relative to this folder; see [Plugins](Plugins.md#run-tab-commands-as-tools-infantry_run) |
| `scripting.*` | see [Scripting](Scripting.md) | Run limits, hook guards, the process bridge (off by default) |
| `processes.autoReap` | off | Reap rogue background processes (Gradle/Kotlin daemons, node) left by ended sessions |
| `processes.reapKinds` | `[]` (every kind) | The kinds a session's close reaps: tool profile names (`gradle`, `kotlin`, ...) or `other`; a process of an unlisted kind is spared |
| `limits.processes.stopGraceSeconds` | 10 | How long a build daemon gets after its profile's graceful `stop` (run in the session's worktree) before it is killed by pid; 0 = no graceful step |
| `limits.processes.stuckMinutes` | 15 | A profile's `stuck` client (a CLI call) with no CPU for this long is reported stuck in its session; 0 = off |
| `nudges.deferToHooks` | `[]` | Built-in notices a script replaces (for example `notice.ticket_finished`) |
| `harness.claude.mode` | `interactive` | How Claude agents run. `interactive`: the real `claude` CLI in its own terminal, as you run Claude Code yourself, so usage behaves like normal Claude Code use. `headless`: `claude -p` stream-json (SDK), a machine-readable stream that may be metered differently. Switch in Settings (Plugins tab, Harnesses > Claude Code, field "Mode") or `infantry settings set harness.claude.mode headless`; it applies at the next session launch. See [Harnesses](Harnesses.md#claude-code-the-real-cli-by-default) |

## Secrets

A secret is a named set of fields, for example `android-signing` with `storeFile`, `storePassword`, `keyAlias` and
`keyPassword`. The daemon keeps them in its own encrypted keystore (`keystore.json` in the state folder, each field
sealed with AES-256-GCM under a key from `INFANTRY_KEYSTORE_KEY`, the OS vault, or a key file, in that order). A value
is never stored in clear, never written to the repository, never shown again after you save it (the UI shows only a
masked tail) and never logged.

**Two scopes.**

| Scope | Where you manage it | Who gets it |
|---|---|---|
| App | Configuration > Secrets | Every project's run commands |
| Project | Project settings > Secrets (the project picker's menu) | Only that project's run commands |

Secrets saved before scopes existed are app secrets; nothing moves on its own. Each card shows its scope (App or
Project) and a button that moves it to the other scope: an app secret to the project on screen, a project secret to the
app. A move re-seals every field under the new place in one write; it is refused when the other scope already holds a
secret of that name. The built-in secrets (for example the update token) are the app's own and never go to a run.

**Resolution order.** A run command of a project that names a secret gets **that project's secret first, else the app
secret** of the same name. So a project overrides a shared secret by creating its own with the same name; the app card
then says it is overridden in that project. A name found in neither scope fails the run before it starts.

**How a run command uses a secret.** A saved command lists secret names in its `secrets` (the Run tab's editor, or the
commands file). When it starts, every field of each named secret becomes an environment variable of the child process,
the field name as the variable name (`storePassword=...`). Any printed value is masked in the run's output, its replay
and its log lines.

**Tools.** Agents never hold the owner credential, so they cannot read or change a secret. They can see which names a
project's commands can use and where each resolves: `infantry run-cmd secrets [project|app|all]` or the MCP tool
`infantry_run_secrets` (names and scopes only, the caller's project by default; another project needs
`allowCrossProject`). The wire frames that change secrets (`secret_field_set`, `secret_field_delete`, `secret_delete`
with an optional `project`, and `secret_move`) are owner-only.

## Domains and URLs

Every domain, URL and repository the product reaches is one entry of `EndpointRegistry`
(`superiorSoftLibrary/infantryCore/src/main/kotlin/com/superiorsoftllc/infantry/settings/EndpointRegistry.kt`), the one
place their defaults live. Each resolves in this order:

**environment variable > saved setting > built-in default**

The environment wins so a deployment (`docker run -e`, compose `.env`) repoints an image without touching its data. A
blank value counts as unset; a value that does not fit its kind (URL, `owner/repo`, image name) is refused on
`settings set` and ignored (reported at daemon start) when it comes from the environment. `settings set <key> ""`
resets a key to its default. These keys are app-scope only. Daemon code reads them with `SettingsService.endpoint(def)`.

| Env var | Setting | Default | What reads it |
|---|---|---|---|
| `INFANTRY_SITE_URL` | `urls.site` | `https://github.com/SuperiorSoft-LLC/infantry-ai` | The product home page clients link to (About, help menus) |
| `INFANTRY_DOCS_URL` | `urls.docs` | `https://github.com/SuperiorSoft-LLC/infantry-ai/tree/main/docs/wiki` | The documentation root clients link to (Help, wiki links) |
| `INFANTRY_DOWNLOAD_URL` | `urls.downloads` | `https://github.com/SuperiorSoft-LLC/infantry-ai/releases` | The page installers are downloaded from by hand |
| `INFANTRY_SOURCE_URL` | `urls.sourceRepo` | `https://github.com/SuperiorSoft-LLC/infantry-ai` | The source repository page: About > Contribute (its `CONTRIBUTING.md`), Report a problem (its new-issue form), View on GitHub |
| `INFANTRY_UPDATE_REPO` | `urls.releasesRepo` | the Updater manifest's `update.repo` (baked at build time); a developer override | `owner/repo` whose releases hold the update files (the updater) |
| `INFANTRY_UPDATE_API` | `urls.releasesApi` | `https://api.github.com` | The releases API base the updater asks (a mirror or a fake server in tests) |
| `INFANTRY_DOCKER_IMAGE` | `urls.dockerImage` | `infantryai/infantry-ai` | The image a Docker install reports for `docker pull` |

Not in the registry, because they are not the product's own addresses: `INFANTRY_WEB_HOSTS` (the host names the
browser client may use), `INFANTRY_DOMAIN` (Caddy's site address in `docker/compose.yaml`'s `tls` profile) and
`INFANTRY_IMAGE` (the image compose runs). The browser client dials the host that served it, so the web demo needs no
daemon URL setting. Build-time links (`gradle/sdk-publish.gradle.kts`: `-Pinfantry.url`, `-Pinfantry.scm.url`) are
Gradle properties; the CI workflows use `${GITHUB_REPOSITORY}`.

Plugins add their own keys through `config` cards in their `module.json` (see [Plugins](Plugins.md)). The full list
for your build: `infantry settings`, and the Settings tab (searchable). Environment variables (`INFANTRY_*`, also read
as `INFANTRY_*`) set machine-level values such as the port, state dir and token; see the README.
