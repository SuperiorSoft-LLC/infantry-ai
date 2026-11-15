# Microsoft Store (STORE-1)

Infantry AI ships to the Microsoft Store as an **MSIX** package, built from the same jpackage app image as the GitHub
MSI. Every stable tag (`vX.Y.Z`, never a nightly) builds it and, once the owner's Store credentials exist, submits it.

## Why MSIX, not an MSI/EXE submission

| | MSIX (chosen) | MSI/EXE submission |
|---|---|---|
| Code-signing certificate | none needed: the Store signs the package it distributes | required: the installer must be signed by a CA certificate you buy (about $200-400 a year) |
| Install warning | none (Store-signed, installed by the Store) | none only with that certificate |
| Updates | the Store installs them; the in-app updater turns itself off | the app's own updater (ours) |
| Install / uninstall | clean, per user, no leftovers | our MSI |
| Hosting | the package is uploaded to Partner Center | the installer must sit at a versioned HTTPS URL |

MSIX gives the owner's goal (no "unsafe" warning) without buying a certificate, and a jpackage app image is a plain
folder with an exe, which is what an MSIX of a full-trust desktop app wraps.

## What is built

- `superiorSoftLibrary/infantryUi/msix/msix.json`: the package identity (developer data, not a user setting). It holds
  **placeholders** until Partner Center gives the real values.
- `superiorSoftLibrary/infantryUi/msix/AppxManifest.template.xml`: the MSIX manifest (full-trust desktop app,
  `runFullTrust`, internet + local network for the in-process daemon on 8797).
- `scripts/msix/package-msix.ps1`: copies the app image, adds `-Dinfantry.package=msix` to the launcher's `.cfg`, draws
  the logos from `icons/infantry.ico`, fills the manifest, runs `makeappx pack` (Windows SDK). Unsigned by design.
- Locally: `./gradlew :superiorSoftLibrary:infantryUi:packageMsix -PinfantryVersion=1.5.3 -Pihq.devSign=true` ->
  `superiorSoftLibrary/infantryUi/build/msix/InfantryAI-1.5.3.msix`.
- CI (`.github/workflows/release.yml`): the `windows` job packs it on stable tags (artifact `msstore`, never attached to
  the GitHub Release), then the `msstore` job, after the Release is published, submits it with the Microsoft Store
  Developer CLI (`msstore publish`). With any secret missing, or the placeholder identity still in the package, that job
  prints a notice and succeeds: it never fails or holds back a release.

## In-app updates on a Store install

`StoreInstall` (Infantry_Updater) detects a Store install by `-Dinfantry.package=msix` or a launcher under
`\WindowsApps\`. The window's updater is then off: no update pill, no toast, no MSI download; a manual "Check for
updates" says the Store installs the updates. The GitHub MSI install keeps the in-app updater.

## Owner-only steps

1. **Partner Center account.** Sign up at <https://partner.microsoft.com/dashboard/registration> as a company
   (SuperiorSoft LLC). The company account needs the business verification Microsoft asks for (a few days).
2. **Reserve the name.** Apps and games > New product > MSIX or PWA app > "Infantry AI".
3. **Copy the identity.** Product > Product management > Product identity: `Package/Identity/Name`,
   `Package/Identity/Publisher` (`CN=<GUID>`), `Package/Properties/PublisherDisplayName`. Either put them in
   `msix.json` (commit; they are not secret) or set the repository **variables** `MSSTORE_IDENTITY_NAME`,
   `MSSTORE_PUBLISHER`, `MSSTORE_PUBLISHER_DISPLAY_NAME`. Also note the **Store ID** (12 characters, e.g. `9NXXXXXXXXXX`).
4. **The submission app (Microsoft Entra ID).** Partner Center > Account settings > User management > Microsoft Entra
   applications > Create application (role **Manager**). Then in the Azure portal, that app > Certificates & secrets >
   New client secret. Note the tenant id, the client (application) id and the secret value. The seller id is on
   Partner Center > Account settings > Legal info > Developer tab.
5. **Repository secrets** (Settings > Secrets and variables > Actions > Secrets):

   | Secret | Value |
   |---|---|
   | `MSSTORE_TENANT_ID` | the Entra tenant id |
   | `MSSTORE_SELLER_ID` | the Partner Center seller id |
   | `MSSTORE_CLIENT_ID` | the Entra application (client) id |
   | `MSSTORE_CLIENT_SECRET` | the client secret value (it expires: renew it before then) |
   | `MSSTORE_PRODUCT_ID` | the Store ID from step 3 |

6. **The first submission by hand.** The Store API updates an existing product, so the first submission (store
   listing text, screenshots, age rating, pricing, privacy policy URL, and the `runFullTrust` justification: "a
   desktop app that runs a local daemon and starts developer tools such as git and agent CLIs") is made in Partner
   Center, uploading the `msstore` artifact of a stable release run (or a local `packageMsix` with the real identity).
   Every later stable tag then submits on its own.

### GitHub MSI without SmartScreen warnings

The Store does not help the MSI on the GitHub Release: it stays unsigned and SmartScreen warns until it is signed. That
needs an **OV or EV code-signing certificate** in the company name (DigiCert, Sectigo, SSL.com; EV clears SmartScreen at
once, OV after the file builds reputation), or **Azure Trusted Signing** (about $10 a month, an identity check for the
company). Once one exists, signing the MSI in the `windows` job is a separate ticket (`signtool sign` with the
certificate from a secret, or the Trusted Signing action).

## Local test install (sideload)

An unsigned MSIX does not install. To try one on a test machine (never the owner's main install):

```
New-SelfSignedCertificate -Type Custom -Subject "<publisher from msix.json>" -KeyUsage DigitalSignature `
  -CertStoreLocation Cert:\CurrentUser\My -TextExtension @("2.5.29.37={text}1.3.6.1.5.5.7.3.3","2.5.29.19={text}")
signtool sign /fd SHA256 /n "<the CN>" InfantryAI-1.5.3.msix
```

then trust that certificate under Local Machine > Trusted People and open the `.msix`. Uninstall it afterwards.

## Known differences of the MSIX install (check in the first sideload)

- Writes to `%LOCALAPPDATA%` (the state dir `SuperiorSoft\InfantryAI`) are redirected by Windows into the package's
  private folder. The app reads them back the same way, but they vanish on uninstall and are not shared with an MSI
  install on the same machine.
- The `infantry` CLI launcher sits under `C:\Program Files\WindowsApps\...` and that path changes with each version;
  the daemon rewrites the user PATH entry at every start (CLI-PATH-1), so a new terminal finds the current one.

## Store-review risks and their disposition (STORE-PKG-1)

The owner's choice is MSIX (above). Privacy policy: [docs/legal/PRIVACY.md](legal/PRIVACY.md); its public URL for Partner
Center is `https://github.com/SuperiorSoft-LLC/infantry-ai/blob/main/docs/legal/PRIVACY.md`.

| # | Risk | Disposition |
|---|---|---|
| 1 | Self-updater downloads an MSI | Fixed (STORE-1): `StoreInstall` turns the updater off for `-Dinfantry.package=msix`. |
| 2 | harness-install runs npm packages; Node.js is separate | Accepted: allowed for full-trust apps; disclosed in the privacy policy and the `runFullTrust` justification. |
| 3 | Agents spawn shells and child processes | Accepted: declared by `runFullTrust`. |
| 4 | Loopback port 8797 | Accepted: loopback only, no firewall prompt. To check in the first sideload: web/Android pairing against the packaged daemon. |
| 5 | HKCU PATH write is virtualized under MSIX | Open: the `infantry` CLI is not on PATH in a Store install; needs an App Execution Alias (separate ticket). |
| 6 | State dir redirected to the package LocalCache | Accepted: data lives in the package folder and goes with uninstall; no migration from an MSI install. |
| 7 | `devTrust` launcher flag | Fixed: `package-msix.ps1` refuses a launcher .cfg that sets `infantry.plugins.devTrust` unless `-AllowDevTrust` (local dev-signed sideload only). |

Open: an in-app link to the privacy policy (About/Settings). The config UI has no link field type, so it needs a
small UI seam the owner has to approve.
