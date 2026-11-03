# Recipe: add a provider (a model group)

A provider is a group of models in a harness's picker. It is data on a harness; there is no separate provider
registry to edit. Pick the route that matches how the harness was added.

## Route 1: a descriptor harness (no code)

File to touch: your descriptor, for example `~/.infantry/harnesses/mycli.json`
(see [Add a CLI harness](add-cli-harness.md)). Add a `models` section, either a fixed list:

```json
"models": { "list": [{ "provider": "openai", "id": "openai/gpt-5" }] }
```

or a command that prints one `provider/model` per line (argv only, no shell, 20 second timeout):

```json
"models": { "modelsCommand": ["my-cli", "--list-models"] }
```

## Route 2: a plugin harness

Files to touch: your harness class (the `MyHarnessPlugin.kt` of [Add a CLI harness](add-cli-harness.md)).
`Harness.models()` (declared in
`superiorSoftLibrary/infantryHarnessApi/src/main/kotlin/com/superiorsoftllc/infantry/harness/api/HarnessApi.kt`) returns
`List<HarnessModels>?`; return one `HarnessModels(group, label, models, note, unavailable)` per group. `null` (the
default) means the harness contributes no models.

## Route 3: the CLI already knows the provider

For a CLI that already has the provider (pi, opencode): configure it in that CLI. Its model listing appears in the
picker with no change to Infantry AI.

## Routing is data, not code

Which provider and model a ticket gets, by weight, type or role, is a project routing rule list:

```
infantry project routing <projectId> --file rules.json
```

Rules are `{name, weights?, ticketTypes?, role?, provider, model, effort?}`, first match wins; a provider or model
the harness does not list is refused. Pins on a single ticket or role use `--provider` and `--model`.

## Check

`infantry harnesses` lists the harnesses and their models. For the model-catalog code paths in core, see
`superiorSoftLibrary/infantryCore/src/main/kotlin/com/superiorsoftllc/infantry/provider/HarnessModelCatalogs.kt` and
`superiorSoftLibrary/infantryCore/src/main/kotlin/com/superiorsoftllc/infantry/settings/ModelAllowlist.kt`.

## Related

[Harnesses](../Harnesses.md) ("Add a new provider").
