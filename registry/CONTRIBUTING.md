# Contributing a plugin

## Submit

Open a PR that adds `community/<Id>.json`. Start from the `catalog-entry` your plugin repo's Gradle `catalogEntry`
task prints (it fills `versions`), then add `id`, `description`, `tags`, `license` and `repo` (`owner/name`).
The shape is `schema/catalog-entry.schema.json`.

## New version

Append an object to `versions` (never edit an old one's `url` or `sha256`). Its `url` must be under
`https://github.com/<repo>/releases/`.

## Yank

Set `"yanked": true` and `"yankedReason": "..."` on the version. Do not delete it.

## Checks

CI builds the catalog, then downloads each version and checks sha256, size and grants. A mismatch fails the PR.
A missing `license` is a warning, not a failure. Entries tagged `example` (such as `Example_Starter`) have no
versions, so there is nothing to download; they are skipped by the asset check.
