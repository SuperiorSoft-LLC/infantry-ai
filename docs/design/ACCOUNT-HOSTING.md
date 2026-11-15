# ACCOUNT-HOSTING: the Account hub, the website deployer, and our hosting backend

Ticket HOSTING-1 (f9b08f4a), 2026-11-14; updated by HOSTING-2 (ceab264b) the same day with the owner's decisions
(app names, server side as closed-source SuperiorSoft plugins on the existing Ktor daemon, reusable collections,
decoupled auth/roles/billing; see section 3 and the "HOSTING-2 changes" list at the end). Design only, no code. The owner signs this off before any build ticket starts.
Core and SDK seams in section 2 need his OK first (abstract tooling rule). Target: plugin of the week, shippable by the
weekend of 2026-11-21/22. Related: PLUGIN-TIER-1 (8df4341d, the Official tier by publisher, which these plugins rely on),
BROWSER-1 (62dd3b3b, the latest official installable plugin, a model for packaging), STORE-SETUP-1 (abc76a76, the Store
launch that comes first). No other ticket touches this work.

Owner, 2026-11-14 (short form; the full quotes are in the ticket):
- "we need a account plugin, this can manage any services we put under it, it needs to be seperate from all other plugins"
- "we create a website deployer. it will talk to our site and relay creating a droplet stop it etc."
- "account could be a place where developers can add their services and ours can just be one of them"
- "they can stop or start or destroy their own droplet we just limit how many they can do"
- "if we can sign up, deploy a site within 10 to 15 minutes we have a success" / "the key for the service is convenience"

## 0. The picture

```
 Infantry app (desktop / web / phone client)          Infantry daemon (user's PC or a droplet)
 ─────────────────────────────────────────            ───────────────────────────────────────────
 Account tab  (Infantry_Account UI half)               Infantry_Account daemon half
   ├ Overview   accounts, plans, services     <-route-  · provider registry  (endpoint prefix account.provider/)
   ├ Billing    "Manage" opens provider site            · generic sign-in engines: device code, OAuth+PKCE, API key
   └ Sites      (Infantry_Sites UI half)                · refresh tokens in the keystore (plugin.infantry_account)
       └ Servers: list, create, start/stop/destroy      · capabilities account.status / account.entitlement / account.token
                                                              ^ token (short-lived, per caller)
                                                        Infantry_Sites daemon half
                                                          · registers provider "infantry" + service "hosting"
                                                          · calls OUR backend with the token, never DigitalOcean
                                                                  │ HTTPS
                                                                  v
 Our side (private repo SuperiorSoft-LLC/       ┌──────────────────────────────────────────────┐
   webservices) + site: subscribe page (Stripe │ superiorsoft-core + superiorsoft-hosting jars │
   Checkout), device-confirm page, devices     │ loaded as plugins into the EXISTING remote   │
   page, "Manage billing" (Stripe portal)      │ Ktor daemon (no new droplet), routes via S2  │
                                               │ · device-code + token issuer (auth module)   │
                                               │ · users <-> subscriptions <-> droplets (DB)  │
                                               │ · limits, rate limits, teardown job          │
                Stripe ── webhooks ──────────> │ · the ONLY holder of the DO API token        │
                                               └──────────────────┬───────────────────────────┘
                                                                  v
                                                  DigitalOcean (OUR one token, droplets
                                                  tagged owner:<userId>)
```

## 1. Two plugins, one hub

### 1.1 Infantry_Account (the hub)

- Official, signed, installable from the catalog, **not bundled** (so it is labelled Official, not Core, by
  PLUGIN-TIER-1). Separate from every other plugin: it depends on nothing but the SDK, and no plugin is a dependency of it.
- It owns the top-level **Account** tab and two sections of its own: **Overview** and **Billing**.
- It knows nothing about hosting, SuperiorSoft or DigitalOcean. Everything specific arrives through providers (1.3).
- It holds every credential. Other plugins never see a password, a payment detail or a refresh token; they ask
  "signed in? entitled to X?" and get yes/no, and, if they are an allowed consumer, a short-lived access token.

### 1.2 Infantry_Sites (the website deployer)

- Official, signed, installable, not bundled. `dependencies: [{ "id": "Infantry_Account", "version": "^1.0" }]`.
- It registers our provider `infantry` (shown as "Infantry AI Hosting") and the service `hosting`, and adds the **Sites** section to the Account tab.
- MVP content of Sites: **Servers** (droplets: status, IP, create up to the limit, start, stop, destroy).
  Later sub-sections: Sites, Domains, Activity.
- It talks ONLY to our backend, with the token Account gives it. It never holds a DigitalOcean key.

Names (owner, 2026-11-14, decided): everything the app user sees is Infantry-branded: `Infantry_Account`, `Infantry_Sites`,
provider id `infantry`, display name "Infantry AI Hosting". "SuperiorSoft LLC" appears only on Stripe (checkout, receipts,
statement descriptor). Server-side code uses SuperiorSoft names (section 3).

### 1.3 The provider abstraction (what a third-party developer implements)

A provider is a company account (Infantry AI Hosting, Acme). A service is something under that account (hosting, backups).
A plugin registers a provider by publishing a string endpoint under the prefix `account.provider/<providerId>`
(`api.services.publishEndpoint`, the same extension-point pattern `scripting.engine/<lang>` uses today). Operations are
JSON in, JSON out. The minimum is ONE operation, `describe`; everything else is optional because the hub does the work.

```jsonc
// op "describe" -> the provider descriptor (static data)
{
  "id": "infantry",                           // stable, lowercase; the keystore field prefix
  "name": "Infantry AI Hosting",
  "icon": "cloud",                            // a generic icon key the hub draws (vector, never a glyph)
  "signIn": {                                 // ONE of the supported methods (1.4)
    "method": "device_code",
    "deviceAuthorizationUrl": "https://api.<our domain>/oauth/device/code",
    "tokenUrl": "https://api.<our domain>/oauth/token",
    "revokeUrl": "https://api.<our domain>/oauth/revoke",
    "clientId": "infantry-app",
    "scopes": ["account", "hosting"]
  },
  "accountUrl": "https://api.<our domain>/v1/account",   // standard account document (below), called with the token
  "billingUrl": "https://<our domain>/billing",          // "Manage" opens it in the browser
  "signUpUrl": "https://<our domain>/hosting",           // "Subscribe" opens it
  "consumers": ["Infantry_Sites"],            // modules that may receive an access token for this provider
  "services": [
    { "id": "hosting", "name": "Hosting", "entitlement": "hosting.servers", "section": "infantry.sites" }
  ]
}
```

The standard account document every provider's `accountUrl` returns (the hub caches it, refreshes on open and every
`account.refreshMinutes`, a setting):

```jsonc
{
  "user": { "id": "u_123", "email": "a***@gmail.com", "name": "..." },   // shown masked
  "plan": { "id": "hosting-basic", "name": "Hosting Basic", "status": "active" },  // active | past_due | canceled | none
  "entitlements": [
    { "id": "hosting.servers", "limit": 1, "used": 0, "attrs": { "size": "s-1vcpu-2gb", "regions": ["nyc3"] } }
  ]
}
```

Optional operations a provider may answer instead of the hub's default: `account` (build the account document itself,
for a provider whose API does not speak the standard shape), `signOut` (extra cleanup). A provider that answers only
`describe` and points at standard URLs is pure data. A second plugin that registers an id already taken may only add
`services` (merged by service id); its `signIn` is ignored, and the hub logs it.

### 1.4 Sign-in methods the hub supports

| Method | How | Where it works | MVP |
|---|---|---|---|
| `device_code` | RFC 8628: the hub asks for a code, shows `user_code` and a button that opens `verification_uri`; the user confirms on the site (already signed in there after Checkout); the hub polls `tokenUrl` at `interval` until approved, denied or expired. Like `gh auth login`. | every client, including the phone (the daemon polls, the phone only shows the code) | yes |
| `oauth_pkce` | RFC 8252: loopback redirect on the daemon's machine, PKCE, no client secret | desktop only (the browser must reach the daemon's loopback port) | later |
| `api_key` | the user pastes a key in an `AhqAlertDialog`; stored in the keystore; no refresh | everywhere | later (cheap, likely first third-party ask) |

Tokens: access token short (our backend: 1 hour), refresh token rotating (our backend: 60 days, revocable on the site's
Devices page). The hub refreshes before expiry; a refresh that fails with `invalid_grant` marks the account
"signed out" and posts one notification on the bus (`dedupeKey` per provider) with a "Sign in" action.

### 1.5 The query other plugins call (entitlement and token)

Declared by Infantry_Account as daemon capabilities (PLUGIN-BRIDGES-1, `runs: daemon`), so a daemon plugin calls them with
`api.capabilities.call`, a UI half through its own daemon half, and a Lua script through the capability bridge.
`CapabilityCall.source` says who asks (`plugin:<module>`, `session:<id>`, `cli`), which is what least privilege needs.

| Capability | Args | Answer | Who may call |
|---|---|---|---|
| `account.status` | `provider?` | per provider: `signedIn`, `user` (masked), `plan.status`, `services[]` with `entitled` | anyone (no secrets in it) |
| `account.entitlement` | `provider`, `entitlement` | `{ entitled, limit, used, attrs }` | anyone |
| `account.token` | `provider`, `scope?` | `{ accessToken, expiresAt }` | only `plugin:<module>` listed in the provider's `consumers`; refused for sessions, CLI and Lua (an agent never holds a customer token) |
| `account.signIn` / `account.signOut` | `provider` | the device-code state (`userCode`, `verificationUri`, `expiresAt`) / ok | the Account UI (through its own routes) and the CLI `infantry account login <provider>`; refused to agent sessions |

An agent (the future web developer role) asks `account.status` and the Sites tools; it never gets a token. Every
customer-affecting action (create, destroy) is a Sites tool with `kind: write` / `destructive`, so policy presets and the
approval gate already apply.

### 1.6 The Account tab and its sections

Everything here is existing UI seams; nothing new in core:

- `Infantry_Account` registers `TabProvider(id = "infantry.account", container = CENTER, ...)` and two sub-tabs with
  `parent = "infantry.account"`: `infantry.account.overview`, `infantry.account.billing`. The tab body is
  `context.SubTabs("infantry.account")`.
- **A section from another plugin is a sub-tab with `parent = "infantry.account"`.** Cross-module sub-tabs work today:
  Infantry_Git, Infantry_Notes, Infantry_Run, Infantry_Triggers and Infantry_Profiles all hang their pages on
  `parent = "infantry.settings"` (SETTINGS-TAB-1, RUNTIME-5). Infantry_Sites adds `infantry.sites` that way. This answers
  design question 2: **yes, a plugin can add a top-level tab with sub-sections today, and other plugins can add sections
  to it, with no core change.** (The MCP Tools screen did the same.)
- Sub-sections inside a section (Sites > Servers / Sites / Domains / Activity) are `parent = "infantry.sites"` and the
  Sites body calls `context.SubTabs("infantry.sites")`. Two levels have not been drawn anywhere yet; ticket ACCOUNT-1
  proves it in a real window. If a nested strip does not draw, the minimal generic fix is in `SubTabs` only (draw any
  host id at any depth), not an Account-specific seam. For the MVP the Sites section can draw Servers directly and need
  no second level.
- **Overview**: one bordered card per registered provider (icon, name, signed-in user masked, plan + status chip,
  services with "Active" or "Subscribe"), the "Sign in" / "Sign out" action, and, while signing in, the device code card
  (big `user_code`, "Open sign-in page", a `ProgressIndicator`, expires-in). Reuses the plugin list card shape
  (`ModuleCard`) and the Secrets cards' badges, `AhqAlertDialog` for sign out, `ProgressViews.kt` for the wait.
- **Billing**: one card per provider: plan, status, "Manage" (opens `billingUrl` with Compose's `LocalUriHandler`, as
  `AboutSection.kt` and the setup wizard already do). No card or price data is ever shown or stored by the app.
- **A service section without the entitlement shows "Subscribe"** (opens `signUpUrl`) instead of controls. A signed-out
  provider shows "Sign in" in that section. Infantry_Sites draws this itself from `account.entitlement`.
- Compact / Android / web: the same cards in one column; sub-tabs use the existing compact sub-tab behaviour
  (`CompactNavRow` back). Web and Android compile the UI halves in (D6/D9), like every official plugin.
- Notices (signed out, payment past due, droplet created) go on the notification bus, never a plugin toast
  (`NotificationGuardTest`).

### 1.7 Data flow inside the app

- UI half -> its own daemon half through relative routes (`/plugins/Infantry_Account/...`, `/plugins/Infantry_Sites/...`)
  declared in `routes` and called with `Services.DAEMON`. The UI never holds a token; it gets display data only.
- Account daemon half -> provider endpoints (in-process) and the provider's URLs (HTTPS).
- Sites daemon half -> `account.token` -> our backend (HTTPS). Server list cached in its own storage table
  (`infantry_sites_server`) so the section draws at once and refreshes behind it; a state change polls the backend every
  `sites.pollSeconds` (a setting) only while an action is in flight.
- The phone: the phone client talks to a daemon (the user's PC, or later the droplet itself). The account lives in that
  daemon, so the phone needs nothing extra; device-code sign-in works there because the user confirms in the phone's own
  browser.

## 2. Core and SDK seams: the list for the owner's OK

The provider API, sections and entitlement query need **no new core seam**: they are built from the endpoint registry
(`api.services.publishEndpoint`), capabilities, relative routes, cross-module sub-tabs, plugin storage and the keystore.
Three seams are needed or must land first:

| # | Seam | Why | Size | Status |
|---|---|---|---|---|
| S1 | **Plugin secret write**: `DaemonApi.pluginSecrets` with `get(field)`, `set(field, value)`, `clear(field)`, `fields()`, limited to the module's own secret `plugin.<moduleId>` (the name `KeystoreHandler.pluginSecretName` already defines). Additive, `DaemonApi` minor bump, documented in `daemon-plugin-api.md`. | Today a plugin can only READ a secret the owner typed (`DaemonApi.secret`, `plugin_secret_get` for endpoints). The hub must store the refresh token it receives from a sign-in. Writing to its own keystore entry keeps it encrypted at rest (DPAPI on Windows) and out of plugin storage. | S | **needs owner OK** |
| S2 | **HTTP seam** (HTTP-SEAM-1 d2de18a0): a daemon HTTP client (`DaemonApi.http`) AND plugin-owned HTTP routes (a daemon plugin mounts its own paths, incl. unauthenticated ones such as a webhook, under its module prefix) | Client: both app daemon halves make HTTPS calls; `DownloadProgressGuardTest` forbids new raw clients. Routes: the closed-source server plugins (section 3) serve the public API from the existing Ktor daemon. Generic: nothing in it names hosting, Stripe or SuperiorSoft; it is the only server-side piece in the public repo. | M | **needs owner OK** for the routes half; first ticket |
| S3 | Nested sub-tabs (only if ACCOUNT-1 finds `SubTabs` does not draw a second level) | Sites > Servers / Domains | S | conditional, generic |

Nothing else: no plugin id, provider id or hosting term enters core (rules 1 and 2).

## 3. The backend (our side)

### 3.1 Where it runs (owner, 2026-11-14: decided)

- **No new droplet.** The backend is a set of **plugins loaded into the remote Ktor daemon that is already running** (the
  one the site uses). They are daemon plugins like any other, built against the public SDK, and they serve their HTTP
  API through S2 (HTTP-SEAM-1: a plugin adds its own routes). We use the resources we already pay for.
- **Closed source.** All server code lives in the private repo `github.com/SuperiorSoft-LLC/webservices`, is built there to
  jars and deployed only to our server. The public repo holds only the generic mechanism (S2, plus the existing plugin
  loader). The app plugins (`Infantry_Account`, `Infantry_Sites`) hold no secrets and stay public.
- **Server-side names are SuperiorSoft** (`superiorsoft-core`, `superiorsoft-hosting`, ...), built to host many services
  later: one shared base, one module per service, hosting is #1.
- **Tokens (decided):** the user signs in through us and gets an *Infantry token* (access + refresh) that is only valid
  against our server. Our server creates, starts, stops and destroys droplets with **our single DigitalOcean token** on
  the user's behalf. There is no per-user DigitalOcean token. We reuse the DO token and the Stripe test-mode sandbox the
  owner already has (used before only for testing on another project, never live). The owner injects both as server
  secrets (environment of the remote daemon / its secret store); **no value is ever written in a repo, ticket, doc or
  log.** A custom-scoped token and a separate customers team (HOSTING-1's idea) stay recommended before live mode, not
  before the MVP.
- Database: the remote daemon's own storage (plugin storage tables) for the MVP, behind the collection kit's storage
  interface (3.8) so a later move to Postgres touches one adapter.
- The API paths in 3.2 live under the module prefix (e.g. `/api/superiorsoft/auth/oauth/...`,
  `/api/superiorsoft/hosting/v1/servers`); the provider descriptor (1.3) carries the full URLs, so the app does not care.

**Risks of the shared process, and their answers:**

| Risk | Answer |
|---|---|
| A server plugin crash or leak takes down the site daemon | `PluginGuard` already isolates plugin faults; the hosting module does no heavy work in-process (DO calls are short HTTPS calls, the teardown job is a timer); a per-plugin request timeout and a body-size limit (settings) |
| The public Stripe webhook path behind the existing proxy | S2 lets a route opt out of daemon auth only when it declares its own check; the webhook route checks the Stripe signature before anything else and is rate limited; the proxy forwards only `/api/superiorsoft/**` to the daemon, nothing of the daemon's admin surface becomes public |
| Memory headroom on the existing droplet | measure the daemon's resident memory before and after loading the jars (HOSTING-E2E-1 records it); the jars carry no framework beyond what the daemon already ships (Ktor, kotlinx.serialization); if headroom is short, resize the droplet one step rather than add one |
| Secrets in a shared process | the DO and Stripe secrets are readable only by the modules that need them (`DaemonApi.secret` per module); never in a response, log line or `toString` |

### 3.2 API (v1, JSON, bearer access token unless noted)

| Method | Path | What |
|---|---|---|
| POST | `/oauth/device/code` (no auth) | RFC 8628 start: `{device_code, user_code, verification_uri, verification_uri_complete, interval, expires_in}`; `client_id` must be `infantry-app` |
| POST | `/oauth/token` (no auth) | `grant_type=device_code` (poll: `authorization_pending`, `slow_down`, `access_denied`, `expired_token`) and `grant_type=refresh_token` (rotates) |
| POST | `/oauth/revoke` | revoke a refresh token (sign out) |
| GET | `/v1/account` | the standard account document (1.3) |
| GET | `/v1/servers` | the caller's droplets: id, name, status (`new`, `active`, `off`, `archive`), ipv4, region, size, createdAt |
| POST | `/v1/servers` | create; body `{name, sshPublicKey?}`; header `Idempotency-Key` (required); refused 403 when not entitled, 409 when at the limit |
| GET | `/v1/servers/{id}` | one droplet (404 unless it belongs to the caller) |
| POST | `/v1/servers/{id}/actions` | `{type: "power_on" | "shutdown" | "power_cycle"}` |
| DELETE | `/v1/servers/{id}` | destroy (the app confirms first) |
| POST | `/webhooks/stripe` (Stripe signature, no bearer) | subscription lifecycle (3.4) |
| GET | `/healthz` (no auth) | liveness |

Every `{id}` is looked up in OUR table `servers(droplet_id, user_id, ...)` and checked against the token's user before a
DO call is made; the client's id is never passed to DO unchecked. Droplets are also tagged `owner:<userId>` as a second
line of defence and for the abuse kill switch.

### 3.3 Limits (all config on the backend, not code)

- Per plan: `max_servers` (MVP 1), one fixed size (Q4), allowed regions (Q5), one image (Ubuntu LTS).
- Rate limits: device-code start 5 / 10 min per IP; token polls obey `interval` (`slow_down` past it); create 3 / day and
  destroy 3 / day per user (a create-destroy loop costs us a billed hour each time); actions 30 / hour.
- A create needs: verified email, active subscription, under the limit.

### 3.4 Stripe and the teardown policy

- Stripe Checkout (subscription mode) on the site; the Customer Portal for "Manage billing". **Test mode** for the MVP.
- Webhooks (signature verified, each event id stored so a replay is a no-op):

| Event | Effect |
|---|---|
| `checkout.session.completed` | create or link the user, plan `active`, entitlement on |
| `customer.subscription.updated` | plan status follows Stripe (`active`, `past_due`, `canceled` at period end) |
| `invoice.payment_failed` | `past_due`: creates refused, existing droplet keeps running, email |
| `customer.subscription.deleted` | `canceled`: creates refused; droplet **powered off** now; **destroyed after the grace period**; emails at cancel and 48 h before destroy |

- DigitalOcean bills a powered-off droplet in full (its disk and IP stay reserved), so powering off protects access, not
  cost; destroy is the only cost stop. Recommended grace: 7 days after cancel, then destroy (Q3). No snapshots in the MVP
  (they cost storage); "download your data before the date" is in the cancel email.
- `past_due` longer than Stripe's retry window ends in `customer.subscription.deleted`, so one path covers both.

### 3.5 DigitalOcean terms and our abuse policy

- To verify before launch (owner / legal read): DO's Terms of Service and Acceptable Use Policy on running servers for
  our own customers. My understanding, not verified here: reselling is allowed, and the account holder (we) is responsible
  for every droplet's traffic, so DO's abuse reports and suspensions land on our team. That is the reason for the
  separate customers team.
- Our terms of use (the site): no spam or bulk mail, no open proxies / VPN resale, no port scanning or attacks, no
  illegal content, no cryptocurrency mining, no reselling. Breach = power off at once, destroy after notice, no refund.
- Controls: the rate limits above; DO's default SMTP block on new accounts left in place; a DO Cloud Firewall on every
  customer droplet (in: 22, 80, 443; out: all but 25); DO monitoring alerts on sustained 100 % CPU or outbound spikes to
  our ops mail; an admin kill switch (power off all droplets tagged `owner:<id>`); DO abuse reports forwarded to the
  user with a 24 h reply window (immediate power-off for active attacks).

### 3.6 Private repo layout (`SuperiorSoft-LLC/webservices`)

```
webservices/
  settings.gradle.kts            # includes every module below; pins the public Infantry SDK version (SDK BOM)
  superiorsoft-core/
    auth/          # identity, tokens, sign-in methods (device code; later email link, OAuth)
    roles/         # roles and permissions; a declarative rule language other modules reference
    collections/   # the collection kit (3.8): fields, access, CRUD routes, validation, paging, storage
    billing/       # Stripe adapter, plans, entitlements (maps a plan to roles and limits)
    limits/        # quotas and rate limits as data
    http/          # thin glue over S2: module route prefix, error shape, idempotency keys
  superiorsoft-hosting/          # service #1: collection `servers`, actions start/stop, DO client, teardown job
  superiorsoft-<next>/           # a future service: its collections and actions only
  deploy/                        # build + copy the jars to the server's plugin folder; no secrets in it
  docs/                          # the API reference
```

Each `superiorsoft-*` folder is a daemon plugin (its own `module.json`, routes declared). Core is a plugin too, so a
service declares `dependencies: [{"id": "superiorsoft-core", ...}]` and calls core through its published capabilities,
never through internal classes.

### 3.7 Module boundaries (decoupled; owner, 2026-11-14)

Dependency direction: `hosting -> billing, collections, roles, auth, limits`; `billing -> roles, limits`;
`collections -> roles, auth (only the Principal type)`; `auth`, `roles`, `limits` depend on nothing else.
**None of the core modules knows about hosting** (a guard test in the private repo greps core for `hosting`, `droplet`,
`digitalocean`).

```kotlin
// auth: who is calling. Knows nothing about billing, roles or any service.
interface Auth {
  fun authenticate(bearer: String): Principal?                   // validates our access token
  fun signInMethods(): List<SignInMethod>                        // device_code for the MVP
  fun issue(userId: UserId, scopes: Set<String>, device: String): TokenPair
  fun refresh(refreshToken: String): TokenPair                   // rotates
  fun revoke(refreshToken: String)
}
data class Principal(val userId: UserId, val scopes: Set<String>, val device: String)

// roles: what a principal may do. Rules are data; modules reference permissions by name.
interface Roles {
  fun rolesOf(userId: UserId): Set<RoleId>
  fun grant(userId: UserId, role: RoleId, source: String)        // source = "billing:<plan>", "admin"
  fun revoke(userId: UserId, role: RoleId, source: String)
  fun can(p: Principal, permission: String, resource: Resource? = null): Boolean
}
// permissions are strings such as "servers.create"; a role = a set of permissions, declared in JSON config.

// limits: how much. Pure data + counters.
interface Limits {
  fun quota(userId: UserId, key: String): Int?                   // e.g. "servers.max"
  fun rate(key: String, subject: String): RateDecision           // "servers.create", per user, per day
}

// billing: plans and entitlements. Maps a plan to roles and limits; knows no service.
interface Billing {
  fun planOf(userId: UserId): Plan?                              // id, status active|past_due|canceled|none
  fun onEvent(listener: (BillingEvent) -> Unit)                  // Activated, PastDue, Canceled(graceUntil)
}
// plans.json -> { "hosting-basic": { "roles": ["hosting.customer"], "limits": { "servers.max": 1 } } }
```

A service listens to `BillingEvent`s for its own side effects (hosting powers off on `Canceled` and destroys after
`graceUntil`). The standard account document (1.3) is assembled by core from `Auth` + `Billing` + `Limits`, plus usage
counts each service reports through one callback.

### 3.8 The collection kit (Payload-style; owner: "basic crud etc is a fast setup")

A service declares a collection once; the kit gives it list/get/create/update/delete routes, validation, paging,
storage and owner checks. Only special actions are hand-written.

```kotlin
val servers = collection("servers") {
  field("name", Text, required = true, maxLength = 63, pattern = "[a-z0-9-]+")
  field("dropletId", Long, readOnly = true)
  field("status", Enum("new", "active", "off", "archive"), readOnly = true)
  field("ipv4", Text, readOnly = true)
  field("sshPublicKey", Text, writeOnly = true)
  ownedBy("userId")                                    // every query filtered by the caller; another user's id = 404
  access {
    list("servers.read"); get("servers.read")
    create("servers.create", quota = "servers.max", rate = "servers.create")
    update(none)                                       // no generic update for this collection
    delete("servers.destroy", rate = "servers.destroy")
  }
  hooks {
    beforeCreate { ctx, doc -> doc + ("dropletId" to doClient.create(doc, tag = "owner:${ctx.user}")) }
    beforeDelete { _, doc -> doClient.destroy(doc["dropletId"]) }
  }
  action("start", "servers.power") { _, doc -> doClient.powerOn(doc["dropletId"]) }
  action("stop", "servers.power") { _, doc -> doClient.shutdown(doc["dropletId"]) }
}
```

Generated under the module prefix: `GET /servers?cursor&limit`, `GET /servers/{id}`, `POST /servers` (requires
`Idempotency-Key`), `PATCH /servers/{id}`, `DELETE /servers/{id}`, `POST /servers/{id}/actions/{name}`. One error shape
(`{error, message, field?}`), cursor paging with a max page size from a setting, validation from the field list, a
`Storage` interface (plugin storage for the MVP, Postgres later). `access` names permissions from `roles`, quotas and
rates from `limits`; the kit never hard-codes a role.

## 4. Security

| Concern | Design |
|---|---|
| Our DO key | one token of ours, only in the remote daemon's secret store (owner-injected); readable only by superiorsoft-hosting; rotated on any suspicion; never sent to the app |
| App tokens at rest | refresh token in the keystore entry `plugin.infantry_account`, field `<providerId>.refresh` (S1); encrypted with the keystore master key (DPAPI / Keychain / Secret Service, else the documented key file). Access token in memory only |
| Tokens in transit | HTTPS only; the hub refuses a provider URL that is not `https://` (except `http://localhost` for a developer's own test provider, behind a setting off by default) |
| Least privilege in the app | the UI never gets a token; `account.token` only to modules in the provider's `consumers`; never to agent sessions, CLI or Lua; tokens never in logs, transcripts, `toString`, notifications or the wire (the `PluginSecret` pattern of masking `toString`) |
| Token power | access token 1 h, scoped (`account`, `hosting`), audience = our API; refresh rotates and is revocable per device on the site |
| User-to-droplet scope | every backend call resolves droplets through our `servers` table by `user_id` from the token; 404 (not 403) for another user's id; DO tag as a second check |
| Device-code phishing | the confirm page shows the device name ("Infantry on DESKTOP-xxxx") and the code; codes are 8 characters, expire in 10 minutes, single use, rate limited |
| Stripe | webhook signature checked; events idempotent; the app never sees payment data |
| Customer droplet access | SSH key auth only (password login off via cloud-init); the user pastes a public key in the create form, or (later, web developer role) the app generates a key pair and keeps the private key in the keystore |
| Plugin trust | both plugins are signed by the Infantry key, so they show as Official (PLUGIN-TIER-1) and a look-alike community plugin cannot claim the `infantry.account` tab (a second tab with a taken id is skipped) |

## 5. MVP cut (weekend of 2026-11-21/22) vs later

**MVP: sign up -> server running in under 15 minutes, Stripe in test mode.**

1. User installs Infantry_Sites from the catalog (Infantry_Account comes with it as its dependency; ACCOUNT-1 checks
   the plugin manager installs dependencies, else the catalog lists both and Sites says "Install Account first").
2. Account tab > Sites shows "Subscribe" -> the site's hosting page -> Stripe Checkout (test card).
3. Account tab > Overview > "Sign in" -> device code -> confirm on the site -> signed in, plan Active.
4. Sites > Servers > "Create server" (name, optional SSH public key) -> droplet `new` -> `active` with its IP in about a
   minute; start, stop, destroy (destroy behind an `AhqAlertDialog` confirm that names the server).
5. Cancel in the Stripe portal -> plan canceled, server powered off, destroyed after the grace period.

**Later (in this order of value):** web developer role (an agent that SSHes into the droplet and builds a site; needs
the app-generated key pair and a terminal/run seam), Domains (point a domain, DNS through DO, TLS), Sites (a deployed
site per server), runner install on the droplet (cloud-init with the runner image + pairing, so the phone has a server
to run agents on), more sizes and regions, `api_key` and `oauth_pkce` sign-in for third-party providers, Activity log,
live mode billing.

## 6. Tickets (revised by HOSTING-2; small, ordered)

| # | Id | Repo / layer | What | Depends | Acceptance check |
|---|---|---|---|---|---|
| 1 | HTTP-SEAM-1 (d2de18a0, exists) | public core (SDK), owner OK | S2: `DaemonApi.http` client + plugin-owned routes (own prefix; opt out of daemon auth only with a declared check) | owner OK on S2 | an example plugin mounts a route reachable over HTTP in a test on port 0; a route with no auth and no declared check is refused at load; `DownloadProgressGuardTest` green; the seam names no `stripe` / `hosting` / `superiorsoft` |
| 2 | ACCOUNT-SEAM-1 | public core (SDK), owner OK | S1 `DaemonApi.pluginSecrets` (own entry only), tests, `daemon-plugin-api.md` | owner OK on S1 | a plugin writes, reads and clears its own field; writing another module's entry fails; the value never appears in a log or `toString` (test) |
| 3 | WS-CORE-1 | private `webservices` | `superiorsoft-core`: auth (device code, tokens, revoke), roles, limits, collection kit, billing (Stripe test-mode adapter, webhooks, plans.json), http glue | 1 | a sample collection of under 20 lines gets the five CRUD routes with paging, validation and owner checks (tests); another user's id returns 404; a replayed Stripe event is a no-op; the guard grep finds no `hosting` / `droplet` / `digitalocean` in core |
| 4 | WS-HOSTING-1 (replaces HOST-API-1) | private `webservices` | `superiorsoft-hosting`: `servers` collection, start/stop actions, DO client on the owner's token, teardown job on `Canceled` + grace, usage for `/v1/account`, deploy script to the existing daemon | 3 | on the sandbox, through the API with an Infantry token: create -> droplet active with IP, stop, start, destroy; a 2nd create at limit 1 returns 409; a canceled test subscription powers off at once and destroys after a shortened test grace; no secret in the repo (grep) |
| 5 | ACCOUNT-1 | public `Infantry_Account` (new) | tab + Overview / Billing, provider registry, device-code engine, keystore storage, `account.*` capabilities, CLI `infantry account status|login|logout`, notices; proves cross-module and nested sub-tabs | 1, 2 | in a real window: device-code sign-in against a stub provider ends signed in; the refresh token is in the keystore, not plugin storage; `account.token` is refused to a session and the CLI |
| 6 | SITES-1 | public `Infantry_Sites` (new) | provider `infantry` ("Infantry AI Hosting") + service `hosting`, Sites > Servers list / create / start / stop / destroy, Subscribe and Sign in states, cache, daemon tools (`write` / `destructive`) | 4 (or its stub), 5 | in a real window against the sandbox: list, create, stop, start, destroy (with confirm) work; no `SuperiorSoft` string anywhere in the app UI; signed out shows Sign in, unsubscribed shows Subscribe |
| 7 | SITE-HOST-1 (shrunk) | site | the subscribe/pricing page + Stripe Checkout (test mode, merchant "SuperiorSoft LLC"); the device-confirm and Devices pages are thin pages over the core auth API | 3 | a test card completes checkout and the account document then shows plan `active`; the confirm page approves a device code |
| 8 | HOSTING-E2E-1 | QA | a real window, a clean profile: install -> subscribe (test card) -> sign in -> create -> SSH in -> stop/start -> destroy -> cancel -> teardown; daemon memory before/after loading the server jars | 6, 7 | the whole flow under 15 minutes; memory headroom recorded; no secret in any log |

Order: 1 first; then 3 -> 4 (private repo) in parallel with 2 -> 5 -> 6 (public repo); 7 after 3; 8 last.
6 can start against a stub of the documented API and switch when 4 lands.

## 7. Owner decisions

Decided 2026-11-14 (HOSTING-2): app-side names Infantry-branded (SuperiorSoft LLC only on Stripe); server-side names
SuperiorSoft; backend = plugins on the existing Ktor daemon, no new droplet; server code closed source in
`SuperiorSoft-LLC/webservices`; one DO token of ours, users hold only Infantry tokens; reuse the existing DO token and
Stripe sandbox (the owner injects them as server secrets); core modules reusable for future services; collections via a
kit; auth, roles and billing decoupled. This replaces HOSTING-1's Q1, Q2, Q7 and Q8.

**OPEN (recommendations, waiting on the owner):**

1. **Teardown grace** after cancel: 7 days powered off, then destroy, emails at cancel and 48 h before.
2. **Droplet size**: Basic 1 vCPU / 2 GB.
3. **Region**: NYC3 for the MVP.
4. **Price**: a flat monthly price of roughly 2x the droplet's DO price; you set the number.
5. **Seams S1 and S2** (S2 now includes plugin-owned HTTP routes) for your OK.
6. **DO terms**: confirm DO's ToS / AUP allow hosting customer servers under our account before live mode (then consider
   the custom-scoped token + separate customers team).

## HOSTING-2 changes (2026-11-14)

- Names: provider `superiorsoft` -> `infantry`, shown "Infantry AI Hosting"; SuperiorSoft only on Stripe and server side.
- 3.1 rewritten: plugins on the existing Ktor daemon, closed source, one DO token, shared-process risks.
- New 3.6 (private repo layout), 3.7 (auth / roles / limits / billing interfaces), 3.8 (collection kit).
- S2 widened to plugin-owned routes. Tickets revised: HOST-API-1 replaced by WS-CORE-1 + WS-HOSTING-1, SITE-HOST-1
  shrunk, an acceptance check per ticket. Owner decisions split into decided and OPEN.
