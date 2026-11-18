# Vault: the OS master key, reveal, copy and backup

Infantry AI keeps your secrets in the daemon's keystore, so no value has to live in a plain file. This page says how the
keystore is protected, how you read a value back (only you, only after Windows checks it is you), and how a passphrase
backup keeps your secrets safe if the PC is lost.

## The OS master key

Each secret is sealed with AES-256-GCM under one master key. The master key is held by the operating system's credential
facility (Windows DPAPI, macOS Keychain, Linux `secret-tool`), tied to your OS user on this PC. Configuration > Secrets
shows which protection is in use. Nothing on disk holds a value in clear, and the master key never leaves the OS vault.

Because the key is tied to one user on one PC, a lost or wiped PC loses the keystore with it. That is what the backup is for.

## Reveal and Copy (owner window only)

Each field row in Configuration > Secrets has **Reveal** and **Copy value**. Both ask the daemon, which first asks the person
at the daemon's computer to prove it is the signed-in Windows user:

1. **Windows Hello** (PIN, fingerprint or face, through the Windows UserConsentVerifier API), when it is set up.
2. Otherwise the **Windows credential prompt**, opened on the current user; the password is checked by Windows (the LogonUser API)
   and never leaves the helper process.

The prompt appears on the daemon's PC (for a remote window, the person there must answer it).

- **Reveal** shows the value for `limits.secrets.revealSeconds` (30 by default), then masks it again. **Hide** masks it at once.
- **Copy value** puts the value on the clipboard and clears it after `limits.secrets.clipboardClearSeconds` (30), unless you
  copied something else since.
- `limits.secrets.verifyCacheSeconds` (0 = ask every time) lets one verification cover the next reveals for that many seconds.
- `limits.secrets.verifyTimeoutSeconds` (120) is how long the prompt may wait for you.

Every reveal, copy, export and import is written to `vault-audit.jsonl` in the daemon's state folder: the time, the action,
the secret's name and field, the scope, the verification method and the outcome. Never the value.

**macOS and Linux:** the verifier is a port (`OsUserVerifier`); Touch ID and polkit are not built yet, so there Reveal, Copy and
Export answer "not available yet" instead of showing anything.

### Never for agents

Reveal, copy and backup are three owner-only frames (`secret_reveal`, `secret_backup_export`, `secret_backup_import`). An agent
connection does not hold the owner credential and is refused; a restricted policy refuses them too. No CLI command, MCP tool,
script API or plugin API sends them (`VaultNoAgentPathGuardTest` fails the build if one appears). Agents use the
`{{secret:NAME}}` placeholder ([Secrets](Secrets.md)) and never see a value.

## Backup

The **Vault backup** card writes every secret (app and project scopes) to one new file, encrypted with a passphrase you choose:

- the key comes from the passphrase through PBKDF2-HMAC-SHA256 (`limits.secrets.backupKdfIterations`, 600 000 by default),
- the secrets are sealed with AES-256-GCM, the file's header (format, count, salt, nonce) bound into the seal,
- the passphrase needs at least `limits.secrets.backupMinPassphrase` (12) characters and cannot be recovered.

Export asks Windows Hello first and never overwrites a file. Paths are on the daemon's computer; `~` is your home folder.
Without the passphrase the file is useless, so it may sit on a USB stick or in cloud storage.

**Restore** merges a backup into the secrets here. A secret that is new is added, one with the same values is left alone, and
one that exists with other values is a conflict: the card lists each one with **Keep mine** / **Use backup's**, and nothing is
written until every conflict is answered. A wrong passphrase, or a changed file, is refused. Files over
`limits.secrets.backupMaxBytes` are refused.

## How the owner tests Windows Hello (on a TEST instance)

Test on a scrubbed test daemon, never on your own app or keystore:

1. Build the CLI: `./gradlew :superiorSoftLibrary:infantry:installDist`.
2. Start a test daemon with its own state folder: `scripts/testdaemon.sh up --name vault`. It prints its port and token.
3. Point a desktop window at it (`./gradlew :superiorSoftLibrary:infantryUi:runUi`, then connect to that port with the token).
4. In Configuration > Secrets, add a dummy secret (e.g. `test.reveal` = `not-a-real-value`).
5. Press **Reveal**: Windows Hello (or the password prompt) appears. Cancel once (the row says it was not passed), then pass it:
   the value shows and masks again after 30 s. Press **Copy value**, paste somewhere, wait 30 s, paste again: it is empty.
6. Export a backup to a temp path with a test passphrase, delete the dummy secret, import the file: it comes back. Import it
   again after changing the value: the conflict list appears.
7. Read the test daemon's `vault-audit.jsonl` (its state folder): one line per action, no value.
8. `scripts/testdaemon.sh down --name vault`.
