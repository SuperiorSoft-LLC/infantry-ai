# Calendar (Infantry_Calendar)

A planner for the user: tasks and reminders that the user and every agent can add. Plan: CAL-PLAN-1 (ticket 5190597d) and
CAL-PLAN-2 (ticket cc8207de). Code: `plugins/official/Infantry_Calendar` (UI half: the Calendar tab; daemon half: tables, store,
tools, frames, the reminder tick).

## For the user
- The **Calendar** tab shows a month grid with a marker on days that have items, a day panel, and an item dialog
  (create, edit, complete, snooze, delete). Items you save there are yours.
- A reminder rings at its time (or `remindAt` / `remindBefore`). You get a notification with Open, Snooze and Done. A reminder
  missed while the app was off (later than `calendar.maxLateMinutes`) is marked missed, not fired.
- **Delete all**: the button in the tab header asks to confirm, then removes every item, its history and the triggers the
  calendar owns. It cannot be undone.
- **Disabling the plugin keeps your data.** The tables stay; enabling it again restores the calendar. Only Delete all (or the
  tool below) wipes it.

## For agents
| Tool | Use |
|---|---|
| `infantry_calendar_add` | A `task` or `reminder`: `date` + `time`, or `inMinutes`, or `allDay`; `target` `role:<role>` / `session:<id>` reminds that agent (needs the Triggers plugin). |
| `infantry_calendar_timer` | A reminder `minutes` from now. |
| `infantry_calendar_list` / `infantry_calendar_get` | Read items (filters: `from`, `to`, `status`, `kind`, `projectId`, `createdBy`); `get` adds the history. |
| `infantry_calendar_update` / `infantry_calendar_complete` | Change an item; mark it done or cancelled. |
| `infantry_calendar_clear` | Delete ALL calendar data. Destructive (`calendar.delete`), needs `confirm: true`; only when the user asked. |

Agents are briefed about these tools through the plugin's `knowledge` entries ([Plugin knowledge](plugin-knowledge.md)).

## Settings
The `infantry.calendar.limits` card holds the title, note and list limits, the history kept per item, `calendar.zone`,
`calendar.tickSeconds` and `calendar.maxLateMinutes`.

## Policy
Actions: `calendar.read`, `calendar.write`, `calendar.delete` (destructive: one item, or the whole wipe).
