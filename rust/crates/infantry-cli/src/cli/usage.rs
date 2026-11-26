// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
//! The `--help` text, word for word the Kotlin `usageText()` (scripts and the Assistant read it).

pub const USAGE: &str = r#"Infantry AI CLI - control the Infantry AI daemon (`infantry`; `agenthq` is a deprecated alias)

Usage: infantry [connection options] <command> [args...]

Tickets:
  tickets             List tickets from the daemon
  tickets --project <id>         Only that project's tickets
  board [--project <id>]         One-call summary: tickets by column, each with its worker, live state, branch@commit
  tickets --status <column>      Only that column: backlog, todo, in_progress, pending, done
  tickets --epic <id|prefix> --project <id>   Only that epic's tickets, in board order
  ticket show <id|prefix>        One ticket in full: title, status, agent, links, description
  ticket create --title <t> [--desc <d> | --desc-file <path> | --desc -] [--project <id>] [--epic <id|prefix>] [--role <id>] [--provider <id>] [--model <id>] [--weight 1-5] [--context-limit 200k|off] [--effort low|medium|high|xhigh|max|off] [--runner local|<name>|any|none] [--mcp <server>=<purpose>]... [--image <file>]... [--status todo|backlog]   Save a todo (or backlog) ticket; prints its id
  ticket update <id|prefix> [--title <t>] [--desc <d> | --desc-file <path> | --desc -] [--epic <id|prefix>] [--role <id>] [--provider <id>] [--model <id>] [--weight 1-5|0] [--context-limit 200k|off] [--effort low|medium|high|xhigh|max|off] [--runner local|<name>|any|none] [--mcp <server>=<purpose>|none]... [--image <file>]... [--remove-image <path>]...   Edit a ticket; flags left out keep their value
                      --weight: how likely the ticket is to come back wrong, not its size (ROUTING-1 rubric):
            4-5 touches the wire / contract, the daemon, auth or project scope, concurrency or SSR timing,
                an Android build, or more than one module
            3   gate fixes and bisects; UI that depends on daemon behaviour
            1-2 copy changes, icons, renames, docs: mechanical, one owner file
          An unweighted ticket is flagged; set a weight before it starts (project routing rules match on it).
          --image (repeatable) attaches a png/jpeg/gif/webp file (max 10 MB), uploaded to the
                      project's .infantry/uploads/ticket/; on update it is ADDED to the stored images.
                      --remove-image (repeatable) drops an attached image, named by the path `ticket show`
                      prints, its file name, or the original file. A missing, oversized or non-image file,
                      or an image the ticket does not have, is refused and nothing is saved
                      Multi-line description: use --desc-file <path> (or --desc - to read stdin). A multi-line
                      --desc is cut at its first line on Windows (the infantry.cmd launcher drops the rest).
  ticket move <id|prefix> <status>   Move one ticket to a column (backlog, todo, in_progress, pending, done); a move to
                      in_progress is a start (see ticket start): a refused or held one exits 1 with the reason
  ticket move <id|prefix> [<status>] --before <id|prefix> | --after <id|prefix> | --top | --bottom | --position <n>   Reorder: ticket order is priority
                      (the board and `tickets` list a column in that order; new tickets go to the bottom)
  agent queue <agent> [add <id> [--at n] | rm <id> | mv <id> <n> | clear]   An agent's persistent ticket queue (agent = profile id or unique name): lists it; its worker ending starts the first queued ticket
  ticket assign <id|prefix> <agent> [--queue] [--at n]   Give a todo ticket to an existing agent (start now, or --queue behind its work)
  agent switch <agent> <ticketId> [--now]   The agent leaves its ticket (handoff, todo) for another; the worker ends, the new ticket starts on exit
  ticket unassign <id|prefix> [--now] [--note <text>]   Take an in_progress ticket off its worker: back to todo (top), branch and work kept, with a handoff comment
  ticket restart <id|prefix> [--fresh]   Put a worker back on an in_progress ticket whose worker died: resumes the session; if that dies within board.restartResumeSeconds (or with --fresh)
                      a fresh session starts on the SAME worktree and branch with the brief and a continue-from-your-last-commit note
  ticket start <id|prefix>       (an id, full or the 8 chars `ticket create` prints; not a title) Start ticket work this way, never with a spawn: the worker runs the ticket's
                      role, provider and model on its own ticket/<short-id>-<slug> branch; the ticket goes
                      In Progress. A refused or held start exits 1 with the reason
  ticket link <id|prefix> <otherId|prefix> <type>   Link one ticket to another (see the link types)
  ticket epic <id|prefix>... <epic|none> [--force]   Move one or many tickets to an epic (id or name; a new name creates it) or
                      out of any epic (none). Status, order, branch and worker are kept; an in_progress ticket on another
                      base than the new epic's is not moved unless --force
  ticket files <id|prefix> [--related] | --path <glob> | --backfill   Files a ticket changed / tickets that changed a path / related tickets' files
  ticket related <id|prefix> [--suggest]   The ticket's RELATED (auto) block (series, linked, same-file tickets and their
                      files); --suggest rebuilds it first. ticket create/update --related-to <id|prefix> (repeatable) links one
  remote on|off|status  Let the daemon accept outside (Tailscale) connections; prints the ws address
                      and token. Takes effect on the next daemon restart.
  runner up --name <n> [--host <h>]... [--allow-projects <ids|*>] [--allow-harnesses <ids|*>]  Start a runner daemon here if none runs and print its pairing link (one command)
  runner bearer [--name <n>] [--host <h>] [--port <p>] [--allow-projects <ids|*>] [--allow-harnesses <ids|*>] [--rotate]
                      On a RUNNER machine: print its endpoint and long-lived bearer (made once, valid until --rotate);
                      the runner image prints them on every start. Empty grants become * (every project and harness)
  runner add <endpoint> --bearer <token|->  On the ORCHESTRATOR: pair with the endpoint and bearer the runner printed
                      (the bearer carries the runner's name and certificate pin; nothing else to type)
  runner connect --name <n> --via "<command>" [--host <h>] [--port <p>]  Pair a runner in one step: runs `runner pair` on it through <command> and adds it (owner only)
  runner pair --name <n> [--host <h>]... [--port <p>] [--allow-projects <ids|*>] [--allow-harnesses <ids|*>] [--grant-on-pair [--grant-harnesses-max <ids>]]
                      On a RUNNER machine: grant what the orchestrator may run (asked when omitted; nothing is granted
                      silently), print ONE pairing link (hosts, port, one-time code, fingerprint; the code is single use and expires after
                      limits.runners.pairCodeMin), then the code and fingerprint by hand; serve the link until paired.
                      --grant-on-pair lets the first pairing grant ONE project (chosen on the orchestrator, once) and the
                      installed harnesses (or only --grant-harnesses-max); audited as runner_link_pair_grant
  runner add <name> <wss-url> --code <c> --fingerprint <fp>  On the ORCHESTRATOR: pin the fingerprint, trade
                      the code for a per-pair runner credential (not the owner, remote or agent token)
  runner add '<infantry://runner?...>' [--name <n>] [--grant [--project <id>]] | runner add --link <link|-> [--name <n>] [--grant]
                      The one-paste form: the link carries name, hosts, port, code and fingerprint; each host is tried
                      in order and the first that answers pairs (a wrong certificate is skipped before the code is
                      sent). The first add turns runners.enabled on. When the link's code was issued with
                      --grant-on-pair, the CLI asks once whether to grant this project (--grant answers yes)
  runner list | rotate <name> | detach [<name>] | audit [--limit N]  Paired runners; in-band rekey; revoke
                      (a name: from the orchestrator; none: on the runner); the runner's log of remote operations
  runner check <name> [--project <id>] [--harness <id>]  The health check over the link: contract, harness installed and
                      signed in, project/harness granted, git remote reachable, disk, link; one line per row with the
                      fix under a failing one, exit 1 when any row fails (the project defaults to this one)
  runner test <name> [--harness <id>] [--model <id>]  One throwaway turn on the runner: prints the answer, its harness and
                      how long it took, then stops and removes the session; exit 0 only on a reply. A real model harness
                      spends one small turn of that account; echo spends nothing
  bg run [--name L] [--keep] -- CMD  Run CMD as a registered background job (session reads Running; --keep: survives the session closing); bg list|status|logs|stop ID
  bg run --notify [--notify-on RE]... [--notify-tail N] [--notify-timeout MIN] [--no-default-patterns] -- CMD
                         Start CMD detached; the daemon MAILS this session on exit, a pattern match or the timeout: end your turn, never sleep/poll
  bg wait ID [--timeout MIN]  Block until job ID exits (fallback when mail cannot wake the harness); prints its tail, returns its exit code
  run-watched [bg run options] -- CMD  Same as bg run --notify: watched (START/still running/END lines), you are mailed when it ends or goes quiet
  run-watched --follow FILE -- CMD  Also count FILE's growth as output (a build sent to a log file); watch.followRedirects=true does it for > / tee
  watch-file FILE [--until-pid N] [--idle-seconds S]  Follow a file another process writes: mailed when it goes quiet, matches, or is done
  unwatched --reason "WHY" -- CMD  Opt out of the watch for one command (reason required; logged, counted, shown, escalated by watch.escalateTo)
  progress <0-100> ["label"] | plan "a" "b" .. | plan N | step [name] | bytes D T | count D T | status "label" | done | fail | clear | show
                      Report your long job's progress, shown as a live bar on your card (progress --help: all forms)
  progress --watch [--reader <name>]   Copy stdin to stdout unchanged, reporting ##infantry marker lines
  run [--label L] [--reader R] -- CMD   Run CMD with its output and exit code unchanged; progress done on exit 0, failed otherwise
                      Harmless outside Infantry AI: no session or daemon = no report, exit 0 (run keeps CMD's code)
  harnesses           The harnesses (provider ids) a ticket can pin, and each one's models
  usage [show]        Plan usage per meter (claude, opencode-go, ...): percent used, reset, pace, and how many
                      ticket workers the pacing rule allows (default <60%: 3, <80%: 2, else 1) vs how many run
  usage set <meter> <pct> [<resetsInMin>] | clear <meter>   Type a percent for a meter with no readable usage
  usage override <meter> <n|none> [<hours>] | override <meter> clear   Allowance override (default: until reset, else 2h)
  usage rule <60:3,80:2,1|on|off|default>   The pacing rule: below:workers tiers, the last one workers alone
  settings [get [<key>]]   The daemon's durable settings, one `key = value` per line
  settings set <key> <value>   Store one (a number, true/false, JSON, or text); a refusal names the accepted range
  settings limits     Every limit (caps, thresholds, timeouts): value, default, range, what 0 means, its env name
  plugin <op> [id...] [--approve]   The plugin manager (owner): status, available, search, show, install, uninstall,
                      enable, disable, updates, update, sources, source-add, source-remove, source-refresh; --approve
                      accepts a module signed by a key that is not a trusted signer
  health [--json]     Daemon health and environment: up/down, pid, port, uptime, last restart and why, plugins (and any
                      PluginGuard disabled), MCP bridges, OS/shell/paths/off-limits ports. Works with the daemon or the MCP
                      bridge down (reads the state dir). Same report as the infantry_health MCP tool.
  processes [--all]   Rogue background processes (build daemons, node, JVMs) left by ended or idle sessions,
                      with MB, age and owner session; --all adds healthy ones and heavy processes that are not ours
  processes reap <pid> | --all   Kill one process (or every rogue one) that a session of this daemon started;
                      anything else is refused. Setting processes.autoReap (default off) reaps rogue ones on a timer
  agent-def list|show <id>|enable <id>|disable <id>   The agent registry: every agent (built-in roles, plugin
                      agents), one in full, or the user's enable/disable (refused for an agent caller)
  harness-status      Which harness CLIs are installed / missing / outdated; harness-install [id...] [--update] installs them via npm
  epics --project <id> List that project's epics, with each one's goal and story count
  epic show <id|prefix> [--project <id>]   One epic: its title, then its tickets as ID | STATUS | TITLE | AGENT
  epic create --title <t> [--desc <d>] [--project <id>]   Save an epic; prints its id
  epic base <epicId> [<branch>|--clear]   Read/set/clear the branch the epic's new tickets cut from (BASE-1c)
  stories              DEPRECATED: one notice, then the tickets filed under an epic (tickets --epic)
  stories --epic <id|prefix> --project <id>   The same rows as tickets --epic
  stories --status <column>      A ticket column; the old `doing` reads as in_progress
  project create "<name>" [<directory>]   Create a project (the directory: its git repository); prints its id
  project base <projectId> [<branch>|--clear]   The branch new ticket starts in the project cut from:
  project rename <projectId> "<name>"   Rename a project; its id, tickets, sessions and mail are untouched
                      read it, set it (refused unless it resolves where tickets are cut), or clear it
                      (then INFANTRY_BASE_BRANCH, else the start is refused). Tickets already started keep their base.
  project config get <projectId>   Each role's cap on concurrent agents (LANE-CAPS; unlisted roles run 1)
  project config set <projectId> <role> <n>   Set a role's cap (n >= 1); lowering never stops a running agent
  project config set <projectId> <dotted.key> <value>   Store generic project data (e.g. build.root); get lists it as 'key = value'
  project config unset <projectId> <dotted.key>   Remove a project data key
  project config set <projectId> auto-remove-done-workers on|off   End and remove a done ticket's worker session (default off)
  project routing <projectId> [--file <rules.json> | --clear]   The task routing rules (ROUTING-1): print them, replace
                      them with a JSON array ({name, weights?, ticketTypes?, role?, provider, model, effort?, setRole?},
                      first match wins, the last one a default row with no match field), or clear them. A rule naming a
                      provider / model its harness does not list is refused; Opus only as pi + anthropic/claude-opus-5-5
  project branches <projectId>   The project repo's local branches; * marks the checked-out one
  image upload <projectId> <file> [--purpose chat|ticket]   Store a png/jpeg/gif/webp (max 10 MB) in
                      the project's .infantry/uploads/<purpose>/ (default ticket) and print its absolute
                      path (same bytes and purpose = same path)

Agents and sessions:
  agents              Who is available: live agents (BUSY/IDLE/OFFLINE) plus saved profiles with no
                      live session yet (with a project: --project <id> or INFANTRY_PROJECT_ID; without
                      one, profiles only). A profile is a saved template; an agent is a running session.
  agents --profiles   Saved profiles only (templates, not running)
  agents --live --project <id>  Live agents only: a project's roster (who's here)
  ask --question <q> --option "label|description" [--option ...] [--multi] [--other] [--timeout <sec>]
                                 Ask the owner a choice for INFANTRY_SESSION_ID's session and wait;
                                 prints {"answers":[...],"otherText":...}. Exit 0 answered,
                                 3 no answer / timeout, 2 usage error or refused
  ask --question <q> --option "label|consequence" [--option ...] --recommend <label> --reason <why>
      [--no-other] [--default-after <min>]   (the owner may always type an own answer; --no-other forbids it)
                                 Ask the owner WITHOUT waiting (preferred): prints {"questionId":..}
                                 at once; the pick arrives as mail in thread question:<id>.
                                 Exit 0 asked, 2 usage error / refused / rate limited
  role traits <roleId> [--set a,b | --clear]   Show, replace (in order) or empty a role's traits
  trait save <id> [--name <n>] --body <text|-> [--report-to <role> --report-fields a,b]
                                 Save a trait (a re-save bumps its version); prints metadata
  trait list                     The trait library as ID | VERSION | NAME | CHECK
  trait show <id>                One trait in full
  trait delete <id>              Delete a trait; refused while a role or agent still binds it
  skill list                     The skills library as name: description lines (attached-only
                                 skills are left out)
  skill show <name|id>           One skill in full, by exact name or by id
  logs <sessionId>    A run's raw stdout / stderr as it is produced
  session rm <sessionId|prefix>   Remove an ENDED session (exited, failed, orphaned) from the
                      session list the sidebar shows; refused while it runs (kill it first).
                      Keeps the ticket, runs history, branch/worktree and mail; it can no longer be resumed

Mail:
  send <agentId|role> "message"  Message an agent, by id or role; print its message id
  send <agentId|role> "message" --reply-to <msgId>  Answer that message, in its thread (the 8-char id or the full one)
  inbox               The messages addressed to me (--unread: only the unread ones)
  read <msgId>        Print one message and mark it read; takes the 8-char id `inbox` shows
  threads             The conversations I am in: who else, how many, when it last moved
  thread <id>         One conversation, message by message, in thread order
  waiting             Every open question on this daemon, and who each one waits on
  mail, mailbox       Print only these mail commands
                      cmd.exe: wrap the message in "double quotes" (never 'single'), keep it on one
                      line, and quote a thread id: it contains |. See docs/AGENT-QUICKREF.md

Memory and decisions:
  knowledge                      The project's knowledge at a glance: counts, then the notes,
                                 the active decisions (<= 50) and the open issues (<= 30),
                                 each list capped with an "N more" line
  memory index [--folder <f>]    The project's memory notes as folder/name: description
                                 (above 60 notes: folders with counts, then root notes)
  memory show <name>             One memory note in full
  memory search <term>           Ranked memory hits with snippets
  memory save <name> [--description <d>] [--folder <f>] [--append | --edit --find <passage>]
              (--content <text> | --file <path> | -)
                                 Replace (default; needs --description), append (one blank line
                                 between) or edit one passage; prints metadata, never the body
  decision list [--all]          The project's active decisions as D<n> | summary;
                                 --all adds each superseded one under the decision that replaced it
  decision show <D7|7>           One decision in full: status, chain, author and body
  decision add <summary> [--body <text|-> | --file <path>] [--supersedes <D1|1>]
                                 Append a decision (never edited or deleted: supersede it instead);
                                 the author is INFANTRY_SESSION_ID's session
  issue list [--all] [--location <p>]
                                 The project's open issues as I<n> | status | summary (location);
                                 --all adds fixed ones, --location keeps those at or under <p>
  issue show <I7|7>              One issue in full, with every attempt and its fix
  issue open --summary <s> --location <p> [--ticket <id>]
                                 Open an issue; it becomes this session's active issue
  issue attempt [I<n>] --summary <s> [--outcome worked|failed|partial] [--location <p>]
                                 Record an attempt (default outcome failed) on I<n>, else on this
                                 session's active issue, else on a new implicit issue
  issue fix [I<n>] --summary <s> Close I<n>, else this session's active issue
  issue precheck <path>          The issues at or under <path>, open first, with their attempts;
                                 run it before changing a file

Chat cards (CARD-3):
  card list                      The card types the loaded plugins offer, one line each
  card show <type>               What to write for one: a ```card:<type> block and its JSON fields

Daemon and pods:
  submit "<goal>"     Create a pod, print the pod id (--project <id>: its project)
  submit "<goal>"     Create a pod, print the pod id
  watch <podId>       Stream a pod until it converges, fails or is cancelled
  pods                List the sessions the daemon knows about
  runs <podId>        List a pod's runs with outcome and session id
  cancel <podId>      Cancel a pod gracefully (--force to kill the trees)
  mcp serve           Serve Infantry AI tools (read + spawn/prompt/message/kill) as an MCP server on stdio
  mcp serve --max-depth <n> --max-spawns <n> [--allow-kill-any] [--mcp-depth <n>]
            [--project <id>]   defaults also read from ./.infantry/mcp.json (flags win), see docs/wiki/reference/mcp-tools.md
                      Narrow the daemon's spawn limits (--allow-kill-any is moot), see docs/wiki/reference/mcp-tools.md

This machine:
  login [--url <ws://host:port>] [--token <t>]     Ask for the server URL and token, check them, save as this CLI's default connection (not Claude's /login)
  logout | whoami                                  Forget the saved connection | show the saved server
  install-cli [--dir <launcher dir>] [--dry-run]   Put `infantry` on YOUR user PATH (idempotent; the system PATH is never touched)
  uninstall-cli [--dir <launcher dir>] [--dry-run]  Take it off again (only the entries that hold Infantry AI's launcher)
                      A running terminal keeps the PATH it started with: open a NEW one. The desktop app keeps
                      the entry up to date at every start unless `infantry settings set paths.addCli false`.

Where to connect (first one given wins):
  --url URL           A ws:// or wss:// daemon URL, e.g. ws://10.0.0.5:8787/ws
  --endpoint NAME     A daemon from the endpoints file
  --host HOST         Daemon host (default: 127.0.0.1)
  --port PORT         Daemon port (default: 8787)
  INFANTRY_URL         Environment fallback for --url

Credentials:
  --token-file FILE   Read the bearer token from FILE (never pass the token itself)
  --endpoints FILE    Endpoints JSON (default: INFANTRY_ENDPOINTS, else <state dir>/endpoints.json)
  INFANTRY_TOKEN_FILE  Token file, used when no endpoint is named
  INFANTRY_TOKEN       Token value, used when no endpoint is named

Options:
  --force             With cancel: kill the process trees outright
  --live              With agents: only the live agents (the project's roster)
  --profiles          With agents: only the saved profiles
  --project <id>      With tickets: only that project's tickets (default:
                      INFANTRY_PROJECT_ID, else every project)
                      With agents --live: the project to roster
                      REQUIRED with epics: epic_list matches the project exactly, so
                      there is no all-projects listing to fall back to
  --status <column>   With tickets: backlog, todo, in_progress, pending or done
                      With stories: the same, or the old doing (= in_progress)
  --reply-to <msgId>  With send: answer that message, in its thread and to its sender (8-char id from inbox, or the full id)
  --unread            With inbox: only the messages that have not been read yet
  INFANTRY_PROJECT_ID  Env fallback for --project (set by a spawned agent)
  INFANTRY_SESSION_ID  The session `send` speaks as, and the mailbox `inbox`, `read` and
                      `threads` read (set by a spawned agent); without it a message is
                      sent as "user" and `inbox` reads the "user" mailbox
  --help, -h          Show this help
  --version, version  Print this CLI's contract and build and the daemon's: infantry <cli> build <b> (daemon: <contract> build <b>|not running)"#;

/// MAIL-FOOTER-1: only the "Mail:" group.
pub fn mail_usage() -> String {
    USAGE.lines().skip_while(|l| *l != "Mail:").take_while(|l| !l.trim().is_empty()).collect::<Vec<_>>().join("\n")
}
