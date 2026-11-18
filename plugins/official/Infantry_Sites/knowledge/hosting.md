# Infantry AI Hosting (plugin Infantry_Sites)

The only service in the Services tab today. The Services tab (plugin Infantry_Account) lists services and holds the
user's sign-in; Infantry_Sites is its Hosting section.

## What it is
Servers for the user's sites, run by Infantry AI Hosting. In the app the servers are read-only (online / setting up /
offline, with ip, region, size, createdAt and a "will be destroyed on" date when one is set). Servers are created and
deleted on the Infantry AI Hosting website, not by an agent and not in the app.

## Plan and price
Plan and price are shown on the website plans page (the Subscribe button in Services opens it). The app does not carry
a price: never quote one from memory. Send the user to Services > Hosting > Subscribe to see it.

## The user's steps
1. Services tab: sign in to the Infantry AI account (device code, a browser page opens).
2. Subscribe (Stripe checkout on the website). Hosting section states: signed out, not entitled (no plan yet: Subscribe),
   email unverified (verify the email), at the plan's server limit, ready.
3. The server is created; it shows "Setting up..." until `sshReady` is true (1-3 minutes).
4. When ready, agents can use it with the tools below.

## Agent tools
- `infantry_sites_list`: list servers (id, name, status, sshReady, ip, region, size, createdAt, destroyAt). Call it first.
- `infantry_sites_ssh_exec {server?, command, timeoutSec?}`: run a shell command on a server over SSH. `server` is a name
  or id; optional when the account has one server. Returns exitCode, stdout, stderr (output is capped).
- `infantry_sites_ssh_upload {server?, path, content}`: write a small text file (parent folders created, file replaced).
  For big files, write a small script and have the server download it.
No SSH setup is needed: the PC makes its own key and uses a short-lived certificate per connect.

## States and fixes
- `sshReady=false` or status `new`: still being set up. Wait 1-3 minutes, list again. Do not retry in a tight loop.
- Tools refuse with signed out / not entitled / no servers: tell the user to open Services, sign in or subscribe. Do not
  work around it.
- Delete a server or change the plan: on the website (the Services section links to it). Agents never delete servers.
- Cancel the subscription: the user does it in the Stripe billing portal from Services ("Manage billing").
  A "past due" plan needs the payment method updated there; a cancelled server is destroyed on its destroyAt date.

## Never
- Never ask the user for card details, passwords, tokens or keys; payment happens only on Stripe's page.
- Never print, log or upload secrets (keys, certificates, tokens, .env contents). Run commands that report only names or
  lengths when you must check a secret.
- Never run destructive commands (rm -rf, wiping disks) on a server unless the user asked for that exact action.
