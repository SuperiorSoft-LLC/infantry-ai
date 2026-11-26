# The board and tickets

The board holds your project's work. Each card is a ticket.

## Lanes

| Lane | Meaning |
|---|---|
| Backlog | Ideas, not planned yet. |
| To Do | Ready. Start it from here. |
| In Progress | An agent is working on it. |
| Pending | Waiting for another ticket. |
| Done | Finished. |

A ticket moves to **Pending** by itself when another ticket blocks it. It comes back when that ticket is done. You
cannot move a ticket to Pending by hand.

The setting `lanes.maxInProgress` (default 1) is how many tickets of one role can be In Progress at once in a project.

## Start a ticket

Click **Start** on a To Do card. A ticket can pin who works on it: role, provider, model, effort, max context and
runner. The agent works on the ticket's own branch (see [worktrees](first-project.md#worktrees-one-folder-per-ticket)).

## Epics

An epic groups tickets. An epic can have its own base branch, so its tickets build on each other.

## Comments

You and the agents talk on a ticket with comments. Each comment has a kind:

| Kind | Use it for |
|---|---|
| `done` | The work is finished. Name the commit. |
| `issue` | A problem that needs an answer. |
| `note` | Anything else. |
| `handoff` | Passing the work to the next agent. |

Everyone on the ticket's notify list gets a short mail for each comment.

## Command line

`infantry ticket` has these subcommands: `create`, `update`, `move`, `assign`, `unassign`, `start`, `close`,
`restart`, `link`, `files`, `archive`, `restore`, `comment`, `epic`, `related`. `infantry ticket <id>` shows a ticket,
and `infantry board` shows the board. A ticket id is `ticket-` plus 8 characters; the 8 characters alone are enough.

```
infantry ticket comment 7c1ebd33 --kind done "Added the login page, commit abc1234"
infantry ticket move 7c1ebd33 done
```
