[← Wiki index](index.md)

# Updates

Infantry AI has ONE update engine, in core. It manages the app itself and every installed plugin, official and
3rd-party alike. Each of them is a **component**. Every component names its own **source**, which says where its new
versions live. A plugin's vendor decides that, not us. A source can need a **credential**, such as a private GitHub
repo's read token or a paid plugin's license key. The engine looks the credential up by name, hands it only to that
component's source, and never prints it.

The engine knows no release host, no repo and no file format. Those come from plugins:

- A **source type** (`github`, `folder`, a vendor's own server) is a class a daemon plugin registers.
- An **applier** (how to install a staged file for one kind of component) is a class an official plugin registers.

Core does the generic part: it lists the components, picks the source type, resolves the credential, downloads into a
staging folder, and refuses the file unless its SHA-256 matches what the source announced.

| Piece | Where |
|---|---|
| Plugin interface (`Updates`, `UpdateSourceType`, `UpdateApplier`, `Credential`, `UpdateState`) | `superiorSoftLibrary/infantryDaemonApi/src/main/kotlin/com/superiorsoftllc/infantry/daemon/api/updates/Updates.kt` |
| The module.json `update` block (`UpdateDeclaration`, `UpdateSourceRef`, `UpdateCredentialRef`) | `superiorSoftLibrary/infantryPlugins/src/commonMain/kotlin/com/superiorsoftllc/infantry/plugin/api/UpdateDeclaration.kt` |
| The engine (check, fetch, verify, stage, apply) | `superiorSoftLibrary/infantry/src/main/kotlin/com/superiorsoftllc/infantry/update/UpdateEngine.kt` |
| The host rules (components, declarations, credential names) | `superiorSoftLibrary/infantry/src/main/kotlin/com/superiorsoftllc/infantry/daemon/plugins/HostUpdates.kt` |

## 1. Components

`api.updates.components()` lists, in order:

1. `core`: the app itself (kind `core`). Its version is the running build. Its source is not written in core; an
   official plugin sets it from its own settings with `setSource("core", ...)`.
2. Every resolved module (kind `plugin`), by its `name`, at its module.json `version`, with the source from its
   module.json `update` block. A module with no `update` block ships with the app and is not updated on its own.

## 2. Declare where your plugin's releases live

Add an `update` block to your module.json:

```json
{
  "name": "Acme_Notes",
  "version": "1.4.0",
  "update": {
    "source": {
      "type": "github",
      "location": "acme/notes-plugin",
      "channel": "production",
      "credential": { "env": "ACME_NOTES_TOKEN", "secret": "acme.notes-license" }
    }
  }
}
```

| Field | Meaning |
|---|---|
| `type` | the source type id; a daemon plugin must have registered it (`api.updates.sourceTypes()` lists them) |
| `location` | what that type needs to find your releases: `owner/repo`, a URL, a folder. The engine does not read it |
| `channel` | optional; a channel the type understands |
| `credential` | optional; the NAMES the secret is looked up under: env var `env` first, then the daemon keystore entry `secret` |

A secret value never goes in module.json, a settings file or a repo. The buyer of a paid plugin puts the vendor's key
in the keystore entry (or the env var) once, and the engine passes it to that plugin's source on every check.

A module may also change its own source at run time, for example from one of its own settings:
`api.updates.setSource(api.moduleId, UpdateSourceRef(...))`. Passing `null` clears it.

### Which credential names a component may use

So that one plugin cannot read another's key or the app's own token:

| Component | Names it may use |
|---|---|
| `core` | any (only an official module may set its source) |
| an official module | names it declared in `daemon.env` / `daemon.secrets`, or names with its vendor prefix (`INFANTRY_...` env, `infantry...` secret for `Infantry_Updater`) |
| a community module | names with its vendor prefix (`ACME_...` env, `acme...` secret for `Acme_Notes`), or names it declared AND was granted (`daemon.env:<NAME>`, `daemon.secret:<name>`). The `INFANTRY` / `infantry` prefix never counts for a community module |

Any other name is skipped silently: the source is called with no credential.

## 3. Write a source type

A source type answers two questions: what is the newest version, and where are its bytes.

```kotlin
class AcmeStoreSource : UpdateSourceType {
    override val type = "acme-store"

    override fun latest(request: SourceRequest): SourceOffer? {
        // request.location, request.channel, request.platform ("windows", "linux", "macos", "android", "any")
        // request.credential?.reveal(): the buyer's license key, or null. Never log it: Credential.toString() hides it.
        val newest = askTheStore(request.location, request.credential?.reveal()) ?: return null
        return SourceOffer(version = newest.version, artifact = "Acme_Notes-${newest.version}.zip", sha256 = newest.sha256, handle = newest.id)
    }

    override fun download(request: SourceRequest, offer: SourceOffer, into: File): File =
        File(into, offer.artifact).also { downloadTo(it, offer.handle, request.credential?.reveal()) }
}

class AcmeDaemon : DaemonPlugin {
    override fun start(api: DaemonApi) {
        api.updates.registerSource(AcmeStoreSource())
    }
}
```

Declare the type in the daemon half, or the registration is refused:

```json
"daemon": { "entry": "...", "jar": "...", "updateSources": ["acme-store"] }
```

A community module also needs the grant `daemon.updateSource:acme-store` in `modules.json` `allow`.

Rules the engine holds every source to:

- `version` must be `MAJOR.MINOR.PATCH`. An offer that is not newer than the installed version counts as up to date.
- `sha256` is required (64 hex characters). An offer without one is refused at check time, and a download whose
  SHA-256 differs is deleted and refused. Every update is verified.
- `artifact` must be a plain file name (letters, digits, `._-`), and `download` must write that file inside `into`.
- Throwing from `latest` or `download` is fine; the engine turns it into `UpdateState.Failed` with the message. Do not put
  a secret in an exception message.

## 4. Check, fetch, apply

| Call | Does | Answers |
|---|---|---|
| `check(id)`, `checkAll()` | asks the component's source for its newest version; nothing is downloaded | `NoSource`, `UpToDate`, `Available`, `Failed` |
| `fetch(id)` | downloads the found version into `<state dir>/updates/<id>/<version>/` and verifies it | `Staged` or `Failed` |
| `apply(id)` | hands the staged file to the applier of the component's kind | `Applied` or `Failed` |

Installing is per kind (`core`: the app's installer for this platform; `plugin`: replace the module's folder). An
applier is registered with `registerApplier` by an official module; a community module may check and fetch but may not
register an applier or call `apply`. With no applier for a kind, `apply` answers `Failed` and the staged file stays.

## 5. Status

This is the foundation. The interface, the engine and the rules above are in place and tested (`UpdateEngineTest`,
`HostUpdatesTest`). The official Infantry_Updater plugin still runs its own GitHub check and MSI / APK install. Moving it
onto this engine, as a `github` source type and the `core` applier, is the next step. Its settings stay the one config
point for the app's own source. See also [Daemon plugin API](daemon-plugin-api.md) and [Plugins](Plugins.md).
