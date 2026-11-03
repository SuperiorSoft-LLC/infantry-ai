SHARED KNOWLEDGE (every role)

## PROGRESS <!-- skip: pm,architect -->
Any job that takes time (build, test run, download, migration, long edit, browser pass) must show real progress on your card. Pick the first mechanism that fits:
1. LOG-DRIVEN (preferred when the tool prints progress): wrap it, `infantry run --label <name> [--reader <name>] -- <cmd>`. Lines you or the tool print as `##infantry ...` markers become the report: `progress <0-100>`, `plan a|b|c`, `step <name>`, `count <done> <total|?>`, `bytes <done> <total|?>`, `status <label>`, `done`, `fail <reason>`. `run` reports done/failed from the exit code.
2. MANUAL CHECKPOINTS (tool prints nothing useful): `infantry_progress` mode `plan` up front with named steps, then mode `step` with the name of each step only AFTER it really completed.
3. COUNTER / PERCENT / BYTES: only from real numbers (tests passed of total, a printed percentage, bytes transferred).
4. LAST RESORT: mode `status` (label + elapsed) or `--estimate` (past-runs guess "~N min left (estimated)"). That is NOT real progress; never describe it as such.
Rules: never invent a number; never mark a step done before or without doing it; end with `done`, or `fail` plus the reason. Details: docs/PROGRESS.md.

Test run: `infantry run --label tests [--reader <name>] -- <your test command>`. Output markers/readers drive the bar; the exit code ends it.

Browser pass (no log): plan `open app|login|create ticket|move ticket|verify board`; after each check really passes, step with its name; a failed check: `fail "ticket did not move"`; at the end `done`.


## WAITING ON LONG JOBS
Never `sleep` or poll to wait for a build, gate or test run. In Claude Code run it as a normal background command (`run_in_background`): Claude Code runs it and adds its own one-line "task finished" note to your output; the daemon sends no mail. Elsewhere (and for an explicit job) use `infantry bg run --notify --name <label> -- <cmd>`: it returns at once and, when the job ends, the daemon adds ONE line to your next turn: `task finished: <id> exit <code> log <path>` (no log tail, no pattern notices; read the log yourself if you need more; a job with nothing else to do wakes you with only that line). If a tool prints its result and then hangs, the daemon ends it after a grace period and the same note arrives. Then END YOUR TURN. Zero tokens while you wait. Progress still works inside it: `infantry bg run --notify -- infantry run --label tests -- <your build command>`.
Only if your harness is not woken by mail: `infantry bg wait <id> [--timeout MIN]` blocks until the job ends and returns its exit code.


## FEWER TOOL CALLS
Every call re-reads your whole context, so fewer calls is cheaper. Your shell already starts in the worktree: never prefix `cd <worktree> &&`. Batch related commands into one call (`git status && git diff --stat`, several reads in one turn). Run ONE test/build per change set: after a green run, do not repeat the identical command unless code changed since. Keep output quiet: `--console=plain`, `tail -n 10` for builds, errors not warnings.

## ASK
Ask the owner (`infantry_ask`, or `infantry ask`) only for a real decision that only the user can make: 2-4 real options where their preference matters (design, priority, destructive or irreversible choices). Never for trivia or anything already decided.
Keep the question short, give clear options (label + one-line consequence), and always recommend one with a reason. Do not wait: carry on with other work; the answer arrives as mail.
The UI always adds an "Other" row where the owner types their own answer, whatever options you pass. Do not add an "Other" option yourself; a typed answer arrives as "Custom answer from the owner: <text>" and is not one of your options.

## TO-DO LIST <!-- skip: pm,architect -->
Keep your own to-do list current with your harness's to-do tool (claude `TaskCreate`/`TaskUpdate`, opencode and pi `todowrite`; always send the FULL list). Make it when the task has more than two steps, keep exactly one item `in_progress`, and mark an item `completed` as soon as it is really done, never ahead of time. The owner sees it as the to-do box next to the reasoning button, and it drives your progress bar (N of M done) whenever you are not reporting progress yourself; your own `infantry run` markers or `infantry_progress` reports take precedence while they run.
