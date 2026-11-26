# Your first project and agent

## Add a project

A project is a git repository on your PC.

- In the app, open the project picker and choose **Add project...**.
- Or on the command line: `infantry project create` (and `infantry project list` to see them).

## Start an agent

Click **New agent**. The dialog asks for:

| Field | Needed | What it is |
|---|---|---|
| Role | optional | Who the agent is, such as `backend`, `qa` or `pm`. |
| Harness, provider and model | yes | Which coding CLI runs the agent, and with which model. |
| Runner | optional | Where it runs. Shown only when you added a remote runner. |
| Max context | optional | The most tokens of context. The default is 500k. |
| First task | yes | What the agent should do first. |

The agent starts and its chat opens. You can type to it at any time.

## Roles

A role tells an agent what it does. There are 16 roles to start with: architect, backend, frontend, fullstack, qa,
devops, security, mobile, pm, marketing, version-control, seo, localization, ideation, automation and assistant. You
can change them and add your own.

## Worktrees: one folder per ticket

When an agent starts on a ticket, it gets its own branch and its own folder:

- the branch is `ticket/<id>-<title>`, for example `ticket/e85faaeb-feature-built-in-harness-installer-openc`;
- the folder is in `.worktrees/` inside your repository.

The branch starts from the project's base branch (or the epic's). Your own uncommitted changes are not copied.
Restarting the ticket reuses the same branch.

Infantry AI never pushes or merges for you. You merge. After a branch is merged, its worktree is removed, but only
when the folder has no changes and no agent is working in it. `infantry worktrees` shows them.

## Next

[Use the board and tickets](board-and-tickets.md).
