[← Wiki index](../index.md)

# Recipe: add your service to the Services hub

The **Services** tab (module `Infantry_Account`) is a generic provider layer. A company plugs a service into it with
its own bridge plugin plus its own web API; the hub signs the user in, shows their plan and permissions, and gives the
service's plugin a short-lived token. The hub names no product and no permission. The app's own **Infantry AI Hosting**
(module `Infantry_Sites`) is used below only as an example of what a bridge can offer, not as code you reuse.

**The model.** A service is the vendor's own bridge plugin plus the vendor's own API. The end user only creates an account
and subscribes; the bridge does everything else, with zero setup by the user (no keys to paste, no server addresses, no
tools to install). That is the bar a third-party service should meet. Every vendor builds its own bridge for its own
service.

## What Infantry provides

Infantry gives a vendor generic, abstract tools. It names no product, permission or server:

- **The Services hub and provider layer** (module `Infantry_Account`): the Services list, the sign-in flow and the
  account document cache; your `describe` answer plugs a service in (steps 2 and 3 below).
- **Device-code sign-in and token storage**: the OAuth device-code flow against your URLs, refresh and revoke, and the
  refresh token kept in the keystore; your daemon half asks for a short-lived access token (step 4). See
  [Secrets](../Secrets.md).
- **Per-environment manifest endpoints**: `module.json` `endpoints` with `prod`, `staging` and `dev` addresses and a
  developer override (step 1). See [Daemon plugin API](../daemon-plugin-api.md).
- **The plugin APIs**: the UI half ([UI plugin API](../plugin-api.md)) and the daemon half ([Daemon plugin API](../daemon-plugin-api.md))
  with agent tools, routes and actions, the shared HTTP seam and storage. See [Make a plugin](../make-a-plugin.md).
- **Grants and permissions**: a community plugin asks for what it needs (`daemon.code`, `daemon.tool`,
  `services.provide`) and the user allows it. See [Plugins](../Plugins.md).
- **Publishing**: the `.ihq` build and the catalog. See [Publish a plugin](../publish-a-plugin.md).

That is the platform's whole part. The bridge, its API and any server access are the vendor's, built their own way.

This is not the same as [Add a provider](add-provider.md), which adds a group of models to a harness's picker.

## What a service is

Two parts you build, one part the hub gives you:

| Part | Where | What it does |
|---|---|---|
| Your web API (and site) | your servers | OAuth device-code sign-in, an account document, your resource routes, billing pages |
| A provider plugin, daemon half | `daemon/` of your plugin | publishes a `describe` answer to the hub, asks the hub for a token, calls your API, registers agent tools |
| A provider plugin, UI half (optional) | root of your plugin | the dashboard tab the Services list opens (`section`) |
| The hub | `Infantry_Account` | sign-in flow, token refresh and storage, account document cache, the Services list |

The user's tokens never reach your UI, an agent, a log or an answer: the hub keeps the refresh token in the keystore and
hands an access token only to the modules your `describe` lists as `consumers`.

Read first: [Make a plugin](../make-a-plugin.md), [Daemon plugin API](../daemon-plugin-api.md), [Plugins](../Plugins.md).
Design records (dated; the code wins): `docs/design/ACCOUNT-HOSTING.md`, `docs/PLUGIN-ENDPOINTS-DESIGN.md`.

## Step 1: declare your endpoints per build environment

Where your service lives is developer config in `module.json`, never a user setting. Hosting declares two named
endpoints, `api` (with its path prefix) and `site` (plans and billing pages), each with one address per environment
(`plugins/official/Infantry_Sites/module.json`, inside the `daemon` object):

```json
"endpoints": {
  "api": {
    "prod": "https://superiorsoftllc.tech/api/superiorsoft",
    "dev": "http://127.0.0.1:18953/api/superiorsoft",
    "staging": "http://127.0.0.1:18953/api/superiorsoft"
  },
  "site": { "prod": "https://superiorsoftllc.tech", "dev": "http://127.0.0.1:18953", "staging": "http://localhost:5173" }
}
```

The daemon reads an address with `api.endpoint("api")` and the build environment with `api.environment`. Resolution
(`ServiceEndpoints.resolve`), first hit wins:

1. the developer override variable `INFANTRY_ENDPOINT_<MODULE>_<NAME>` (upper case, every other character `_`, for example
   `INFANTRY_ENDPOINT_ACME_HOSTING_API`), if it is an http(s) address;
2. the exact environment (`prod`, `staging`, `dev`, or any name the build bakes in);
3. outside `prod`: the `dev` address;
4. the `prod` address.

A test build never falls through to your real service while you declare a `dev` address. At load, every address must be
http(s) and a `prod` address must be https, or the daemon half is refused with the reason. A token only travels over
https, or http to `127.0.0.1`, `localhost` or `[::1]` (`ProviderDescriptor.urlOk`). More:
[Daemon plugin API](../daemon-plugin-api.md), "Service endpoints per build environment".

## Step 2: publish the provider (`describe`)

Register a string endpoint under the prefix `account.provider/<id>`. The hub asks it for `describe` and reads the JSON. A
service with no usable `api` address publishes nothing, so no build calls a real server by accident. From
`SitesDaemonPlugin.kt`:

```kotlin
published = api.services.publishEndpoint("account.provider/${InfantryProvider.ID}",
    ServiceEndpoint { op, _ -> if (op == "describe") text else error("unsupported operation '$op'") })
```

The `describe` answer (hosting's, trimmed; `<api>` and `<site>` are the two endpoints from step 1):

```json
{
  "id": "infantry", "name": "Infantry AI Hosting", "icon": "cloud",
  "description": "Servers for your sites, managed on the Infantry AI Hosting website.",
  "signIn": {
    "method": "device_code",
    "deviceAuthorizationUrl": "<api>/auth/oauth/device/code",
    "tokenUrl": "<api>/auth/oauth/token",
    "revokeUrl": "<api>/auth/oauth/revoke",
    "clientId": "infantry-app",
    "scopes": ["account", "hosting"]
  },
  "accountUrl": "<api>/core/v1/account",
  "billingUrl": "<site>/account/billing",
  "signUpUrl": "<site>/plans",
  "consumers": ["Infantry_Sites"],
  "services": [
    { "id": "hosting", "name": "Hosting", "entitlement": "hosting.servers.read", "section": "infantry.sites" }
  ]
}
```

Rules the hub enforces (`Provider.kt`): `id` is lowercase letters, digits and `_`, starting with a letter, at most 40
characters; `signIn.method` must be `device_code` (the only method today); `clientId`, `deviceAuthorizationUrl` and
`tokenUrl` are required; every URL must pass the https rule above. `consumers` are the module ids that may be given the
access token. In each entry of `services`, `entitlement` is a permission id looked up in your account document, and
`section` is the id of the UI tab that is that service's dashboard (omit it for no dashboard).

## Step 3: browser sign-in and the token handoff (what your API implements)

The user presses Sign in on your service's card. The hub runs the OAuth device-code flow (RFC 8628) against the URLs
from `describe`; the user approves in their browser on your site. Your API must implement:

| Route | Request (form-encoded) | Answer |
|---|---|---|
| `deviceAuthorizationUrl` | `client_id`, `scope` (space separated), `device_name` | `device_code`, `user_code`, `verification_uri` or `verification_uri_complete` (preferred; must be https), `expires_in`, `interval` (seconds) |
| `tokenUrl`, polled | `grant_type=urn:ietf:params:oauth:grant-type:device_code`, `device_code`, `client_id` | 200 with `access_token`, `refresh_token`, `expires_in`; or an `error`: `authorization_pending`, `slow_down`, `access_denied`, `expired_token` |
| `tokenUrl`, refresh | `grant_type=refresh_token`, `refresh_token`, `client_id` | a new `access_token` (and a rotated `refresh_token`); `invalid_grant` signs the user out and posts a notification |
| `revokeUrl` (optional) | the token to revoke, best effort at sign-out | any 2xx |

The hub shows the `user_code`, opens the page, polls at `interval`, and stores the refresh token in the keystore (field
`acct_<id>_refresh`). Your verification page is a normal website login that ends in "approve this device". The first
token answer must include a refresh token, or the sign-in fails.

**Account document.** `accountUrl` is called with `Authorization: Bearer <access token>`; the hub caches the JSON for the
setting `account.cacheSeconds` (default 30 s). The fields it reads:

```json
{
  "accountId": "...", "email": "alice@example.com", "memberRole": "owner",
  "plan": { "status": "active" },
  "permissions": ["hosting.servers.read", "hosting.servers.ssh"],
  "limits": { "servers.max": 3 }, "usage": {},
  "reclaimableUntil": 0
}
```

A service is shown as **entitled** when the user is signed in, `plan.status` is `active` (or absent) and `permissions`
holds the service's `entitlement`. The email is masked (`a***@example.com`) before any client sees it. Other plugins can
ask the same facts through the capabilities `account.status` and `account.entitlement`.

## Step 4: call your API with the hub's token

Your daemon half asks the hub for a token as itself. The hub refuses everyone who is not in `consumers`, so an agent, a
script or the CLI can never read it (`AccountGate.tokenRefusal`). From `SitesDaemonPlugin.kt`:

```kotlin
val r = api.capabilities.call("account.token", buildJsonObject { put("provider", InfantryProvider.ID) })
// CapabilityResult.Ok -> value.accessToken ; Failed("... not signed in ...") -> "sign in from the Services tab"
```

Use it for one request and never store, log or return it. Put it in a secret header so the HTTP seam redacts it
(`SitesService.send`):

```kotlin
http.request(HttpSpec("$base$path", method = method, headers = headers,
    secretHeaders = mapOf("Authorization" to "Bearer $token"), body = body))
```

On a 401, ask for a token again once (the hub refreshes it). Map the other statuses to states the UI can draw, as
`SitesService.answer` does: 403 not entitled (or a named reason in `{"error": "...", "message": "..."}`), 404, 409 limit,
429 with `Retry-After`, 5xx unavailable. Use `api.http` (the shared HTTP seam), never a client of your own.

## Step 5: read-only resource listing

Hosting lists the user's servers with `GET <api>/hosting/v1/servers`, answering a JSON array or an object with an `items`
or `servers` array. The plugin keeps only the fields it draws:

```json
{ "id": "<uuid>", "name": "...", "status": "online", "ip": "...", "region": "...", "size": "...", "createdAt": "...", "destroyAt": "" }
```

A daemon route feeds the dashboard (`api.routes.handle("GET", "state") { ... }`, declared in `module.json` `routes` as
`GET /plugins/<Module>/state`). Answer the **state first** (signed out, not entitled, not configured, ready) and the list
only when ready, so the UI never guesses. No token is ever in that answer. Hosting's UI half shows the list read-only;
creating, changing and paying happen on your website, behind the manage link (step 7).

## Step 6: optional agent tools

A tool is declared in `module.json` `daemon.tools` and registered with `api.tools.register`. Hosting has a read tool and
an exec tool:

```json
"tools": [
  { "name": "infantry_sites_list", "action": "sites.servers.read", "kind": "read", "description": "List your hosting servers (...)." },
  { "name": "infantry_sites_ssh_exec", "action": "sites.servers.ssh", "kind": "write", "description": "Run a shell command on one of your hosting servers over SSH ..." }
]
```

A **community** tool name must start with your vendor prefix (the part of the module id before the first `_`, lower case,
then `_`: `acme_hosting_list` for `Acme_Hosting`), not `infantry_`. The token stays in the daemon half, and a tool answer
carries data, never credentials.

**Server access and agent tools are yours to build.** If your service gives agents a way to reach a server or another
resource, you build that in your own plugin, your own way, with your own API routes. The public layer has no shared
SSH or agent-access seam to reuse. Keep to the same rules as any tool: the token stays in the daemon half, a tool answer
carries data and never credentials, and ids from an agent are checked before they reach a URL or a command. Hosting's
`infantry_sites_ssh_exec` is one example of such a tool, not a template to copy.

## Step 7: billing, subscribe and manage links

`billingUrl` (manage plan) and `signUpUrl` (subscribe) in `describe` are shown on your service's card by the hub and open
in the user's browser. Both are plain https pages on your site; the hub takes no payment itself. When the plan lapses, put
`reclaimableUntil` (epoch ms) in the account document: the hub passes it on, and your UI can say how long the user's
resources are kept.

## Step 8: test locally against a staging stack

1. Run your API locally and point `dev` (and `staging`) at it, as hosting does with `http://127.0.0.1:18953/...`.
2. Or leave `module.json` alone and export `INFANTRY_ENDPOINT_<MODULE>_API=http://127.0.0.1:PORT/api` for the daemon you
   start; the override beats the manifest.
3. A plain local build and `./gradlew :superiorSoftLibrary:infantry:runDaemon` are `dev`. Pass `-PinfantryEnv=staging`
   (or `INFANTRY_ENV` while building) to bake `staging` in.
4. Open Services, press Sign in, approve on your local site, and watch the card turn signed in.
5. Fake the HTTP seam in your module tests; hosting's are `SitesTest.kt` and `SitesSshTest.kt`, and the hub's own flow
   tests are `AccountFlowTest.kt`.

## Step 9: publish

A third-party plugin is a **community** plugin: the user enables it and grants what it needs in `modules.json` `allow`.
Your daemon half needs `daemon.code`, `daemon.tool` if you declare tools, and `services.provide` to publish the
`account.provider/<id>` endpoint. Build the `.ihq`, tag, open the catalog PR: see [Publish a plugin](../publish-a-plugin.md).
Tell users which grants to expect, and state an `author` and a `license`.

## Related

[Make a plugin](../make-a-plugin.md), [Plugins](../Plugins.md), [Daemon plugin API](../daemon-plugin-api.md),
[Plugin lifecycle](../Plugin-lifecycle.md), [Secrets](../Secrets.md).
