[← Wiki index](index.md)

# Runner pairing: one link from the client (RUNNER-PAIR-UX-1, 2026-11-08)

Design record (dated), ticket d723fb7b. Owner 2026-11-08: "what can we do to make pairing a remote runner to client
easier?? this setup we have is too complicated." No product code ships with this page; the steps are backlog tickets
(section 6). Builds on [RUNNER-POOL](../RUNNER-POOL.md) (sections 6 and 7) and does not repeat it. Words: the
**orchestrator** is the daemon with the board (the owner's PC); the **runner** is the daemon on the server.

## 1. How the headless runner works today (as built)

* **Mode.** `infantry daemon --mode runner` (or `INFANTRY_MODE=runner`, the Docker image) runs sessions, harnesses and
  worktrees and refuses board frames ([Run a remote environment](run-a-remote-environment.md)).
* **Transport.** The link (WebSocket, TLS) is a plugin, `Infantry_RemoteRunner`, behind `RunnerLinkTransport`. The core
  side is `RunnerLinkService` (`superiorSoftLibrary/infantry/src/main/kotlin/com/superiorsoftllc/infantry/runners/RunnerLinkService.kt`):
  owner-only `runner_admin` ops `pair`, `add`, `rotate`, `detach`, `list`, `pause`, `resume`.
* **Pairing.** `infantry runner pair --name N` on the runner makes a self-signed EC certificate on first use, opens the
  link port (`limits.runners.linkPort`, default 8790), and issues a one-time code (`PairCodes`: 80 bits, single use,
  10 minutes, 5 tries an hour kept on disk, burned past the limit). It prints the code and the SHA-256 fingerprint.
  `infantry runner add N wss://host:port --code C --fingerprint F` on the PC (`RunnerPeers.add`) checks the pin in the
  TLS handshake **before** it sends the code, then trades the code for a 32-byte per-pair credential (`RunnerLinkStore`:
  the PC keeps it in a keystore of its own, the runner keeps only its SHA-256). One orchestrator per runner.
* **Grants.** The runner keeps the final say: `runner.projects` and `runner.harnesses` (empty = none, `*` = any) gate every
  remote start. `runner pair --allow-projects/--allow-harnesses` writes them (RUNNER-DEFAULTS-1); nothing grants silently.
* **Starts and I/O.** A pinned ticket starts with `runner_link_start` (the runner clones the project's `origin`, cuts the
  ticket branch, pushes after each turn). HELLO reports harnesses and a `loggedIn` yes/no. Output is read on demand
  (`runner_link_transcript`); `infantry_prompt` is forwarded; agent tool frames are forwarded runner to orchestrator.
* **UI.** `Infantry_Runners` (`plugins/official/Infantry_Runners/src/RunnersCard.kt`) lists runners with Pause, Rotate,
  Detach, and draws nothing while `runners.enabled` is off. **There is no Add button**, although the quickstart says
  "Settings > Runners > Add does the same". The agent Runner picker exists (New agent > Advanced > Runner).
  *Landed in 75f0778e (RUNNER-ADD-UI-1):* the card always draws its heading and **Add runner**, which pairs from the
  pasted link and then shows the step-4 check rows with their fixes; each row's Check opens them again.

## 2. Every step the owner does today

| # | where | step | what is copied or typed | necessary? |
|---|---|---|---|---|
| 1 | server | start the runner (docker run or `--mode runner`) with the link port published | image, port | **necessary** (something must run there); can be one command |
| 2 | server | `infantry settings set runners.enabled true` | a setting | **accident**: on a runner nothing in the pair or link path reads it (only the orchestrator's card flag) |
| 3 | server | `runner pair --name N --allow-projects <id> --allow-harnesses <ids>` | runner name, a long project id from the PC | pairing: **necessary** (owner presence on the runner). Typing the PC's project id on the server: **accident** of where the grant is made (owner decision, section 5) |
| 4 | server to PC | copy the code | 19 characters | the secret itself is **necessary**; carrying it separately is accident |
| 5 | server to PC | copy the fingerprint | 95 characters | the pin is **necessary**; carrying it separately is accident |
| 6 | PC | build `wss://host:port` by hand | host, port | **accident**: the runner knows its port and most of its addresses |
| 7 | PC | `infantry runner add N <url> --code C --fingerprint F` in the owner's own shell | all of the above | owner-only is **necessary**; the long command is accident; no UI exists |
| 8 | PC | `infantry settings set runners.enabled true` | a setting | **accident**: adding a runner is already the consent |
| 9 | PC | pin the runner per ticket or agent | runner name | **necessary** (placement is a choice); the picker already makes it one click |
| 10 | server | `claude login` (or the harness's login) | an interactive login | **necessary**: logins never travel. Today it fails **silently** (the session starts, the model never answers) |
| 11 | server | give the server access to the git remote (deploy key or token) | a key | **necessary**; today it fails only at the first ticket start |
| 12 | network | firewall or tailnet rule for the link port | a rule | **necessary**; today the failure is a bare connect error |

Docs drift found on the way: the quickstart uses port 8934 and `INFANTRY_LIMIT_RUNNERS_LINK_PORT`; the default is 8790
and `docker/runner-compare.sh` spells the variable `INFANTRY_LIMIT_RUNNERS_LINKPORT`. RUNNER-UP-1 fixes it.

So of 12 steps, 5 are accident (2, 4-6 as separate values, 8) and 3 are necessary but silent (10-12).

## 3. Options weighed

| option | removes | effort | security | verdict |
|---|---|---|---|---|
| **A. One pairing link** `infantry://runner?v=1&name=&host=..&port=&code=&fp=` printed by the runner, pasted into the client | steps 4, 5, 6 (three values become one string) | S | unchanged: the code and the pin travel together through the owner's own copy (out of band), as today. The pin is still checked before the code is sent | **yes** |
| **B. QR of the same link** | typing on a phone client | S once A exists (the PAIR-1 scanner and `qrMatrix` exist) | same as A | later; text paste first |
| **C. One server command** `infantry runner up --name N` / Docker env `INFANTRY_RUNNER_NAME` prints the link on start | steps 1-3 become one | S | must not loop codes (each issue is a fresh guessing window); the link in a container log is a 10-minute secret | **yes** |
| **D. Client turns `runners.enabled` on at first add** | step 8 | XS | adding is already owner-only consent | **yes** |
| **E. Health check from the client** (link, contract, harness logged in, project and harness granted, `git ls-remote`, disk) with the fix command per row | makes 10-12 loud and early | M (one new link frame) | read only; git stderr scrubbed of credentials; no clone, no write | **yes** |
| **F. "Test this runner" turn** (echo, or one tiny turn of the chosen harness) | proves the CLI really answers | M | goes through the runner's normal grants; costs one small turn on a model harness | **yes**, after E |
| **G. Grant at pair time from the client** (code-scoped, one project, once) | step 3's project id | M | only safe if the authority comes from the runner console (the code carries it) and dies with the code; post-pair grants stay runner-only | **yes, owner D24** |
| **H. Client SSHes to the server and does everything** | steps 1-3 and the copy | L (ssh keys, host keys, Windows ssh variance, OS detection, sudo/docker rights) | the client would hold server shell credentials: a much bigger key than a runner credential | **no** for now |
| **I. Reverse dial** (runner connects out to the orchestrator; no inbound port) | step 12 | L (RUNNER-POOL step 9; the PC then needs a reachable address instead, or a relay) | moves the exposed port to the PC | **no** for now; tailnet covers it |
| **J. curl-pipe-to-shell installer** | install on a bare box | S | pipes remote code into a root shell; the image or the release zip already covers it | **no** |

## 4. Recommendation: the one-link flow

```
server:  docker run -d -e INFANTRY_MODE=runner -e INFANTRY_RUNNER_NAME=server-1 \
           -e INFANTRY_RUNNER_HOST=<tailnet or public address> -p 8790:8790 -v infantry-data:/data <image>
         docker logs infantry-runner        -> ==== PAIRING LINK ==== infantry://runner?v=1&name=server-1&host=...
   (or, from an install:  infantry runner up --name server-1)
PC:      Settings > Runners > Add runner > paste the link > Connect
         (or  infantry runner add '<link>')
         -> paired; runners.enabled turned on; the check rows appear:
            link ok · contract ok · claude logged in: FAIL  fix: claude login (on the runner)
            project granted: FAIL  fix: infantry settings set runner.projects '[...]' · git reachable ok · disk ok
         -> fix what is red on the server, Check again, then Test (one echo or tiny turn, reply shown)
```

From 12 steps with 3 copied values to: one server command, one paste, then only the steps that are truly manual, each
named by the check with its exact fix.

What stays manual, on purpose: the harness login on the server, git access for the server, the network path (firewall or
tailnet), and, without `--grant-on-pair`, the project grant on the runner.

Host candidates in the link: `--host` flags first, then the setting `runners.advertiseHosts`, else auto-detected
interface addresses (tailnet 100.64.0.0/10 first, then private, then the rest). The client tries them in order; a host
whose certificate does not match the pin is skipped without sending the code, so offering several hosts is safe.
Inside a container the detected addresses are container-internal, so the Docker start warns until a host is given.

## 5. Security: what must not be simplified

* **The code is born on the runner's console.** Owner presence on the runner is the root of trust. No client-side "request a
  code" call, no code minted from the orchestrator, no endless re-issue loop.
* **Single use, 10-minute expiry, attempt limit kept on disk.** Unchanged (`PairCodes`).
* **The pin is checked in the TLS handshake before the code is sent.** The link carries the fingerprint; the client never
  learns it from the network (no trust-on-first-use). A link without `fp` is refused unless it says `plain=1` and the PC
  also has `runners.allowPlainTailnet` on.
* **The link is a secret for 10 minutes.** Its `toString` never shows the code, errors never echo it, the UI masks the field,
  the CLI takes `--link -` from stdin. Once redeemed or expired it is worthless (no credential is ever in the link).
* **`runner_admin` stays owner-only.** An agent cannot add a runner, paste a link or run a check that changes anything.
* **The runner keeps the final say on grants.** No link frame widens `runner.projects` / `runner.harnesses` after pairing.
  Option G (owner D24) lets a code minted on the runner console carry a one-time grant, at most one project,
  never `*`, audited.
* **The check and test go through the same gates** as a real start; the test never bypasses `runner.harnesses`.

### Owner decisions (answered 2026-11-08)

1. **May pairing auto-grant the project it is added from?** Owner 2026-11-08 (D24): **code-scoped grant**. A code minted on the
   runner console with `--grant-on-pair` may grant at most one project and installed harnesses, once, at pairing, audited; never
   `*`; grants never widen after pairing. RUNNER-GRANT-AT-PAIR-1 builds it.
2. **Where does the site PM's runner live?** Owner 2026-11-08 (D25): **a separate small VPS**, not the website host and not the
   owner's PC (a runner is a remote-code-execution channel; keep it off the public site box). It is the first live target of
   the one-link flow once steps 1-5 land.

### Expensive to reverse

* **The link format** is a contract between machines on different versions: `v=1` from day one; an unknown `v` is refused
  with "update this app".
* **Grant at pair time** (if chosen): once runners accept grants in PAIR, taking that away breaks the documented flow.

## 6. Tickets (backlog, dependency order)

| step | ticket | what | needs |
|---|---|---|---|
| 1 | 5791dde2 RUNNER-LINK-1 | the `infantry://runner?v=1` codec; `runner pair` prints the link; host candidates and `--host` | none |
| 2 | f9139b7b RUNNER-ADD-LINK-1 | `runner add '<link>'` and op `add {link}`; try each host with the pin; first add turns `runners.enabled` on | 1 |
| 3 | 0ae6d0dd RUNNER-UP-1 | `infantry runner up` and the Docker env that prints the link on start; fix the port drift in the docs | 1 |
| 4 | 678c33e1 RUNNER-CHECK-1 | `runner_link_check` frame, op `check`, `infantry runner check`: rows with fixes | none |
| 5 | 75f0778e RUNNER-ADD-UI-1 | Runners card: Add runner (paste link), check rows, always visible | 2, 4 |
| 6 | 2bf027f5 RUNNER-TEST-1 (done) | op `test`, `infantry runner test`, Test button: one throwaway turn, reply shown; the turn's session is stopped and removed (`runner_link_remove`, contract 1.5.0) | 4 (5 for the button) |
| 7 | 546262c8 RUNNER-GRANT-AT-PAIR-1 (built) | code-scoped grant at pairing: a code issued with `--grant-on-pair` lets the first pairing add one project and installed harnesses, once, audited (owner D24) | only a grant-flagged code, at pairing |

Every ticket carries its own live repro on two test daemons on 189xx ports with their own state dirs (never the owner's
8787/8788/8797/8798), using the Echo harness (`plugins/examples/Acme_EchoHarness`) so no login is needed.
