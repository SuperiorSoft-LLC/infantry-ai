[← Wiki index](index.md)

# Board and epics

The board (the Infantry_Board plugin) holds a project's tickets in columns: Backlog, To do, In progress, Done. An
**epic** groups tickets inside one project (for example "Launch" and "Post-launch"); the Epics tab lists them, with
each epic's ticket counts, and the board's Epic filter shows one epic's tickets at a time.

## Moving tickets between epics

A move changes only the ticket's epic. Its status, its place in its column, its comments, its branch and its worker
are kept. Moving to **No epic** (`none`) takes the ticket out of every epic. Naming an epic that does not exist yet
creates it in the tickets' project (only when at least one ticket moves). Names match without regard to case.

### On the board

- **Many tickets:** tick the small box at the left of each card. A bar under the filter row shows "N selected",
  **Move to epic...** and **Clear**. Move to epic... opens a dialog: pick an existing epic, **No epic**, or type a
  **New epic name**, then **Move**. The dialog shows the answer (what moved, what was already there, what was not
  moved and why). The cards and the Epics tab counts update at once.
- **One ticket:** the card's **More** menu has **Move to epic...**, the same dialog for that ticket.

### From an agent (MCP)

- `infantry_ticket_epic` moves one or many tickets: `tickets` (a list of ids, full or 8-char), or `ticketId`, plus
  `epic` (an epic id, a name, or `none`) and an optional `force`. The answer is JSON: `epicId`, `epicName`, `created`,
  `moved`, `unchanged`, `refused` (each with a `reason`), `warnings` and a one-line `message`.
- `infantry_ticket_update` takes the same `epic` (and `force`) for a single ticket, next to its other fields.

### From the CLI

```
infantry ticket epic <id>... <epic id|epic name|none> [--force]
infantry ticket epic 826c1d6d 5e9e2a01 "Post-launch"
infantry ticket epic 826c1d6d none
```

### In-progress tickets and the epic base

An epic can choose its own base branch (`infantry_epic_base`); a ticket's branch is cut from it when the ticket starts.
A ticket that is **in progress** already has its branch. When the new epic cuts from a different base than the old
one (the project's base counts as one base), the ticket is **not moved**, and the answer says why: finish or unassign
it first. With `force` (the dialog's **Move anyway**, `--force`, `"force": true`) it moves anyway and the answer warns
that its branch keeps its old base. Tickets in other columns, and in-progress tickets between epics on the same base,
always move. In a bulk move the other tickets still move; one unknown ticket or epic id refuses the whole call and
changes nothing.
