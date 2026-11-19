# App preview of a branch

> **legacy-java:** this section describes the Kotlin app. It lives on the `legacy-java` branch. The Rust framework on main is described in [Architecture](Architecture.md).

## Legacy (Kotlin) reference


PREVIEW-BRANCH-1. Glance at a branch's app without making a build. In the Run tab, pick the **Preview app** command and press
**Start on branch...**, then pick a branch (or a ticket's branch). The app of that branch opens in its own window, through a
Gradle run with no packaging, against the live endpoints.

## Branch commands

A saved run command is a **branch command** when its cwd or argv names `{worktree}` or `{branch}`. Each start then names a
branch, and the command runs in that branch's checkout:

| Variable | Becomes |
|---|---|
| `{worktree}` | The branch's checkout. In the cwd it must come first (`{worktree}` or `{worktree}/<path>`, no `..`). |
| `{branch}` | The branch name. |
| `{root}` | The project folder (the main checkout), absolute. Use it in the argv to reach a file a branch cut earlier does not have. |

The checkout is chosen like this:

- A worktree that already has the branch checked out (a ticket's) is used as it is, and it stays after the run.
- Otherwise a detached worktree at the branch tip is made at `<project>/.worktrees/preview-<branch>`, and it is removed when the
  run ends (stopped, failed or finished). A leftover from a daemon that died is removed by the next preview of that branch.
- The main checkout (your working tree) is never checked out over, used or removed.

The Run tab lists the branches, and the run's pill shows the branch ("Preview app (ticket/x)"). Stop ends the run by its process
tree only. From an agent or the CLI, `infantry_run_branches` lists the branches and `infantry_run_start` takes `branch`; the
client frame is `run_branches`.

## The Preview app command

```
name:  Preview app
cwd:   {worktree}
argv:  cmd
       /c
       gradlew.bat
       --no-daemon
       --console=plain
       --init-script
       {root}/scripts/preview-app.init.gradle.kts
       -PpreviewBranch={branch}
       :superiorSoftLibrary:infantryUi:runRealDaemon
```

`scripts/preview-app.init.gradle.kts` keeps the preview away from your own app and daemon. Right before the window starts it:

- drops every `INFANTRY_*`, `AGENTHQ_*` and `CLAUDE*` variable it inherited;
- uses a fresh state folder, `<checkout>/build/preview-state` (never `~/.infantry`);
- binds the daemon to a free port in 18900-18999 (`INFANTRY_PORT_STRICT=true`) and the runner link to another free one;
- uses the live endpoints (`INFANTRY_ENV=prod`);
- labels the footer's build line and the window title `PREVIEW <branch>`.

A preview in a ticket's worktree runs Gradle there beside the ticket's worker, and it shares that worktree's `build/` folder.
