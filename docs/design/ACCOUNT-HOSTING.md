# ACCOUNT-HOSTING: the Account hub, the website deployer, and our hosting backend

Ticket HOSTING-1 (f9b08f4a), 2026-11-14. Design only, no code. The owner signs this off before any build ticket starts.
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
                                                          · registers provider "superiorsoft" + service "hosting"
                                                          · calls OUR backend with the token, never DigitalOcean
                                                                  │ HTTPS
                                                                  v
 Our side (Site project)                       ┌──────────────────────────────────────────────┐
   superiorsoft site: subscribe page (Stripe   │ Hosting backend (api.<our domain>)            │
   Checkout), device-confirm page, devices     │ · device-code + token issuer                 │
   page, "Manage billing" (Stripe portal)      │ · users <-> subscriptions <-> droplets (DB)  │
                                               │ · limits, rate limits, teardown job          │
                Stripe ── webhooks ──────────> │ · the ONLY holder of the DO API token        │
                                               └──────────────────┬───────────────────────────┘
                                                                  v
                                                  DigitalOcean team "Superiorsoft Customers"
                                                  (droplets tagged owner:<userId>)
```

## 1. Two plugins, one hub

### 1.1 Infantry_Account (the hub)

- Official, signed, installable from the catalog, **not bundled** (so it is labelled Official, not Core, by
  PLUGIN-TIER-1). Separate from every other plugin: it depends on nothing but the SDK, and no plugin is a dependency of it.
- It owns the top-level **Account** tab and two sections of its own: **Overview** and **Billing**.
- It knows nothing about hosting, Superiorsoft or DigitalOcean. Everything specific arrives through providers (1.3).
- It holds every credential. Other plugins never see a password, a payment detail or a refresh token; they ask
  "signed in? entitled to X?" and get yes/no, and, if they are an allowed consumer, a short-lived access token.

### 1.2 Infantry_Sites (the website deployer)

- Official, signed, installable, not bundled. `dependencies: [{ "id": "Infantry_Account", "version": "^1.0" }]`.
- It registers our provider `superiorsoft` and the service `hosting`, and adds the **Sites** section to the Account tab.
- MVP content of Sites: **Servers** (droplets: status, IP, create up to the limit, start, stop, destroy).
  Later sub-sections: Sites, Domains, Activity.
- It talks ONLY to our backend, with the token Account gives it. It never holds a DigitalOcean key.

Name: "Infantry_Sites" reads like what the user sees (the tab section). Q8 lets the owner pick another name before code.

### 1.3 The provider abstraction (what a third-party developer implements)

A provider is a company account (Superiorsoft, Acme). A service is something under that account (hosting, backups).
A plugin registers a provider by publishing a string endpoint under the prefix `account.provider/<providerId>`
(`api.services.publishEndpoint`, the same extension-point pattern `scripting.engine/<lang>` uses today). Operations are
JSON in, JSON out. The minimum is ONE operation, `describe`; everything else is optional because the hub does the work.

```jsonc
// op "describe" -> the provider descriptor (static data)
{
  "id": "superiorsoft",                       // stable, lowercase; the keystore field prefix
  "name": "Superiorsoft",
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
    { "id": "hosting.servers", "limit": 1, "used": 0, "attrs": { "size": "s-1vcpu-1gb", "regions": ["nyc3"] } }
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
| S2 | **Daemon HTTP client** (`DaemonApi.http`, HTTP-SEAM-1 d2de18a0, already in the architecture audit) | Both daemon halves make HTTPS calls. `DownloadProgressGuardTest` forbids new raw HTTP clients; copying the Updater's `java.net.http` code would be a local copy (rule 9). | M | planned ticket; must land before ACCOUNT-1, or ACCOUNT-1 adds it |
| S3 | Nested sub-tabs (only if ACCOUNT-1 finds `SubTabs` does not draw a second level) | Sites > Servers / Domains | S | conditional, generic |

Nothing else: no plugin id, provider id or hosting term enters core (rules 1 and 2).

## 3. The backend (our side)

### 3.1 Where it runs

- Recommendation: one small service on its own droplet in **our** DigitalOcean team (the one the site uses), reachable at
  `api.<our domain>`, behind TLS. It is built by the Site project in that project's stack (the product stays stack-neutral;
  this page fixes the API, not the language). Database: managed Postgres (smallest plan) or SQLite with nightly off-box
  backup for the MVP (Q6).
- Customer droplets live in a **separate DO team** ("Superiorsoft Customers"), so a bug or leaked token cannot touch our
  own servers, and billing is separated.
- The DO token is a **custom-scoped** token of the customers team (droplet create/read/update/delete, ssh_key, tag,
  firewall; no billing, no other resource types), held only in the backend's environment / secret store, never in a repo,
  never in the app, never in a log.

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

## 4. Security

| Concern | Design |
|---|---|
| Our DO key | only in the backend's secret store; custom-scoped; customers team only; rotated on any suspicion; never sent to the app |
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

## 6. Tickets (small, ordered)

| # | Id | Project / layer | What | Depends |
|---|---|---|---|---|
| 1 | HTTP-SEAM-1 (d2de18a0, exists) | core (SDK) | `DaemonApi.http` | owner OK (already planned) |
| 2 | ACCOUNT-SEAM-1 | core (SDK), owner OK | S1 `DaemonApi.pluginSecrets` (own entry only), tests, `daemon-plugin-api.md` | - |
| 3 | HOST-API-1 | Site project | backend: device flow + tokens, `/v1/account`, servers CRUD against DO (customers team), `servers` table, limits, rate limits, Stripe test-mode webhooks, teardown job, health | owner decisions Q1-Q6 |
| 4 | SITE-HOST-1 | Site project | hosting page + Stripe Checkout (test), device-confirm page, Devices page (revoke), "Manage billing" (portal) | 3 |
| 5 | ACCOUNT-1 | Infantry_Account (new) | tab + Overview / Billing, provider registry (`account.provider/`), device-code engine, keystore storage, capabilities `account.*`, CLI `infantry account status|login|logout`, notices; proves cross-module and nested sub-tabs in a real window; checks dependency install | 1, 2 |
| 6 | SITES-1 | Infantry_Sites (new) | provider + service registration, Sites section, Servers list / create / start / stop / destroy, Subscribe and Sign in states, server cache, daemon tools `infantry_sites_servers` (read) and create/action/destroy (`write` / `destructive`) | 3, 5 |
| 7 | HOSTING-E2E-1 | QA | a real window, a clean profile: install -> subscribe (test card) -> sign in -> create -> SSH in -> stop/start -> destroy -> cancel -> teardown; timed against the 15-minute goal | 4, 6 |

3 and 4 run in the Site project in parallel with 1, 2 and 5. 6 needs a backend to call: it can start against a stub
of the documented API and switch to the real one when 3 lands.

## 7. Owner decisions

1. **Stripe account**: which business entity (Superiorsoft LLC?), test mode now; who creates it (you; a worker never
   handles payment setup).
2. **DigitalOcean**: create the separate team "Superiorsoft Customers" and a custom-scoped API token for it (you create
   it; it goes only into the backend's secret store).
3. **Teardown grace** after cancel: recommendation 7 days powered off, then destroy, with emails at cancel and 48 h before.
4. **Droplet size** (one fixed size for the MVP): recommendation Basic 1 vCPU / 2 GB (room for a site plus a runner; check
   the current DO price) over 1 GB (cheapest, tight for a runner).
5. **Region**: recommendation one region near most users (NYC3) for the MVP; more later.
6. **Price**: recommendation a flat monthly price that covers the droplet, Stripe fees and support margin (roughly 2x the
   droplet's DO price); you set the number.
7. **Backend hosting**: recommendation its own small droplet in our existing team, built by the Site project; database
   managed Postgres vs SQLite + backups.
8. **Names**: plugin names `Infantry_Account` and `Infantry_Sites`, provider id `superiorsoft`, API host `api.<domain>`.
9. **Seam S1** (plugin secret write) for the owner's OK; S2 is HTTP-SEAM-1, already planned.
10. **DO terms**: you (or counsel) confirm DO's ToS / AUP allow hosting customer servers under our account before live mode.
