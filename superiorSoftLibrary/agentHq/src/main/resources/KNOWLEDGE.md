SHARED KNOWLEDGE (every role)

## PROGRESS
Any job that takes time (build, test run, download, migration, long edit, browser pass) must show real progress on your card. Pick the first mechanism that fits:
1. LOG-DRIVEN (preferred when the tool prints progress): wrap it, `agenthq run --label <name> [--reader gradle|junit|gate] -- <cmd>`. Lines you or the tool print as `##infantry ...` markers become the report: `progress <0-100>`, `plan a|b|c`, `step <name>`, `count <done> <total|?>`, `bytes <done> <total|?>`, `status <label>`, `done`, `fail <reason>`. `run` reports done/failed from the exit code.
2. MANUAL CHECKPOINTS (tool prints nothing useful): `agenthq_progress` mode `plan` up front with named steps, then mode `step` with the name of each step only AFTER it really completed.
3. COUNTER / PERCENT / BYTES: only from real numbers (tests passed of total, a printed percentage, bytes transferred).
4. LAST RESORT: mode `status` (label + elapsed) or `--estimate` (past-runs guess "~N min left (estimated)"). That is NOT real progress; never describe it as such.
Rules: never invent a number; never mark a step done before or without doing it; end with `done`, or `fail` plus the reason. Details: docs/PROGRESS.md.

Gradle test run: `agenthq run --label tests --reader gradle --reader junit -- ./gradlew test --console=plain --no-daemon`. Output markers/readers drive the bar; the exit code ends it.

Browser pass (no log): plan `open app|login|create ticket|move ticket|verify board`; after each check really passes, step with its name; a failed check: `fail "ticket did not move"`; at the end `done`.

## TO-DO LIST
Keep your own to-do list current with your harness's to-do tool (claude `TodoWrite`, opencode and pi `todowrite`; always send the FULL list). Make it when the task has more than two steps, keep exactly one item `in_progress`, and mark an item `completed` as soon as it is really done, never ahead of time. The owner sees it as the to-do box next to the reasoning button, and it drives your progress bar (N of M done) whenever you are not reporting progress yourself; your own `agenthq run` markers or `agenthq_progress` reports take precedence while they run.
