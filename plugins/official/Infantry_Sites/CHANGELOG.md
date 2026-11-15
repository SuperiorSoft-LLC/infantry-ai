# Infantry_Sites changelog

## Unreleased (ENDPOINT-ENV-1)

- The hosting service address is no longer a user setting. `sites.apiUrl` and `sites.siteUrl` are gone from Settings; the
  addresses live in module.json `daemon.endpoints` (`api`, `site`) and the build environment picks one (dev / staging / prod).
  A value stored under the old keys is ignored.
- Developers override an address for local testing with `INFANTRY_ENDPOINT_INFANTRY_SITES_API` / `..._SITE`.
- The prod addresses (`api.superiorsoftllc.tech`, `superiorsoftllc.tech`) are placeholders until the owner confirms them.
- Needs daemon API 1.63.0.
