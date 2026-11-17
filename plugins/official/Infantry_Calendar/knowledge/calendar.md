# Calendar (Infantry_Calendar)

The user's personal planner. Every agent may add to it; the user sees it in the Calendar tab.

## Tools
- `infantry_calendar_add`: a `task` or a `reminder`. Give `date` (yyyy-MM-dd) and `time` (HH:mm), or `inMinutes`, or `allDay: true`.
  A reminder fires at `remindAt` (yyyy-MM-ddTHH:mm) or `remindBefore` minutes before its time; with neither it fires at its time.
  `target`: omit = remind the user; `role:<role>` or `session:<id>` = remind that agent.
- `infantry_calendar_timer`: a reminder `minutes` from now (a shortcut for a reminder with `inMinutes`).
- `infantry_calendar_list`: filter by `from`/`to` day, `status`, `kind`, `projectId`.
- `infantry_calendar_get`: one item with its history. `infantry_calendar_update`: change fields.
- `infantry_calendar_complete`: mark `done` (default) or `cancelled`.
- `infantry_calendar_clear`: delete ALL calendar data (needs `confirm: true`). Only when the user explicitly asks.

If your tool list does not show these yet, call them with `infantry tool <name>`.

## Rules
- Add what the USER asked for or would clearly want (a deadline they mentioned, a follow-up date). Do not fill their calendar with your own chatter.
- An item you create records you as its creator; the user sees who made it.
- Times use the user's zone unless you pass `zone` (an IANA name).
- When a reminder fires, the user gets a notification (Open, Snooze, Done). A `role:`/`session:` target also gets your reminder as mail
  through a one-shot trigger; that needs the Triggers plugin, and is refused without it. When a reminder reaches you, handle it,
  then `infantry_calendar_complete` it.

<!-- skip: assistant -->
## For workers
Pass the `projectId` of your project on tasks you add, so they show under that project's filter.
