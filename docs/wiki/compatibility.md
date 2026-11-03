[← Wiki index](index.md)

# Compatibility and deprecation policy

> **DRAFT. Not yet a promise.** The owner makes this official at the public launch. Numbers marked *proposed* are for
> the owner to decide (see the end of this page). The mechanisms in the table are in the code today.

The core is the foundation: apps and plugins stand on it, so a plugin that loads today should keep loading. The
license cannot promise that. This policy does. See [Licensing](licensing.md) for why the core is MPL-2.0.

## 1. What "backward compatible" means

For a surface with a version number: **a module built against version X.Y keeps loading and working on any later
version with the same major number.** A major bump may break it, and only after the notice in section 3.

| Surface | Version today | Where it is checked | Compatible means |
|---|---|---|---|
| Wire protocol | `EventVocabulary.VERSION` (semver, `1.x`) in `superiorSoftLibrary/infantryProtocol` | Both ends; payloads decode with `ignoreUnknownKeys` | An older client ignores new frames and fields; a newer client accepts a daemon of the same major. A new frame or field is a minor bump. Removing or retyping one is a major bump. |
| UI plugin API | `module.json` `apiVersion`, an integer, exact match today | `ModuleResolver` in `superiorSoftLibrary/infantryPlugins` | Today only an equal `apiVersion` loads. Additive changes go under `PLUGIN_API_REVISION` and do not break older modules. Range scheme: section 5. |
| Daemon plugin API | `DaemonApi.VERSION` (semver) and `BOARD_API_VERSION` | `DaemonPluginHost`, through `daemon.apiAtLeast` | A plugin declaring `apiAtLeast` X.Y loads on every daemon of the same major at or above X.Y. A field with a default is a minor bump. |
| Harness API | `HARNESS_API_VERSION` (semver), `contractVersion` in the plugin | `HarnessRegistry` | Same rule as the daemon plugin API. |

Not covered: anything outside these APIs. Daemon internals, the database layout, the CLI's human-readable output and
Lua helpers not documented in [Scripting](Scripting.md) may change in any release.

## 2. How long an old version keeps loading (proposed)

- **Within a major:** forever. A minor or patch never removes what an older module uses.
- **After a major bump:** the previous major keeps loading for **12 months** or **two minor releases of the new
  major**, whichever is longer. During that time the host runs the old major through a compatibility layer where one
  is feasible and marks the module "deprecated" in the plugin list. After it, the host refuses the module with a
  message naming the new version and the migration page.
- **Wire protocol:** a daemon accepts clients of the previous major for the same period, so a phone or browser tab on
  an old build is not locked out the day the daemon updates.

## 3. Notice for a breaking change (proposed)

1. **Announce** in the release notes at least **6 months** before the major release that removes something (3 months
   for optional protocol frames).
2. **Mark** the item `@Deprecated` in the API, with the replacement in the message, and log a warning when a loaded
   plugin uses it. A deprecation never ships in the same release that removes the item.
3. **Document** the migration: a recipe or a section here, with before and after.
4. **Guard**: a non-additive API change fails a test until the version is bumped (a proposed ticket).
5. **Security fixes** are the exception: a breaking fix may ship at once, with the reason in the notes.

## 4. Deprecation announcements

A deprecation is announced in the release notes, the plugin list in the app (a "deprecated" mark with the removal
version), the log, and the table below. Each entry gives what, since which version, removed in which version, and what
to use instead.

| Item | Deprecated in | Removed in | Use instead |
|---|---|---|---|
| (none yet) | | | |

## 5. Proposal: a range scheme for the UI plugin API

Today a module with a different `apiVersion` is refused, so every host bump forces every plugin to republish even when
nothing it uses changed. Proposed, and filed as its own ticket rather than coded here:

- `module.json` keeps `apiVersion` as the major built against and gains an optional `apiRevisionAtLeast`.
- A module loads when its `apiVersion` equals the host's major (or is inside the deprecation window of section 2) and
  `apiRevisionAtLeast` is at most the host's `PLUGIN_API_REVISION`.
- Adding to the API bumps the revision only; removing or changing bumps the major.
- A module that has only `apiVersion` keeps working, read as "this major, revision 0".

## Decisions the owner must make

1. Support period for an old major: 12 months or two minors (proposed), longer, or shorter?
2. Notice before a removal: 6 months (proposed)?
3. Does the wire protocol get a shorter window than the plugin APIs?
4. Is the range scheme of section 5 accepted so it can be built before launch?
5. Does a security fix override the policy (proposed: yes)?
