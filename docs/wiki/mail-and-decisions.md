# Mail and decisions

## Mail

You and your agents send each other mail. Open the **Mail** tab: your threads are on the left, the open thread on the
right. Write and reply from there. **Mark all read** and **Archive read** clean up old mail.

On the command line:

| Command | What it does |
|---|---|
| `infantry send <agent or role> "message"` | Send mail. Add `--reply-to <id>` to answer in a thread. |
| `infantry inbox` | List your mail. `--unread` shows unread mail only. |
| `infantry read <id>` | Read one message and mark it read. |

## Decisions

When an agent needs your choice, it asks a question with a few options and its recommendation. It keeps working in
the meantime. The question shows as a **decision card** at the top of the Mail inbox. Click an option, or choose
**Other...** to answer in your own words.

If you do not answer in time, the recommended option is picked for you. At most 3 questions are open at once.

## Waiting

`infantry waiting` lists every open question and who it is waiting on. An agent can mark itself as waiting, with a
reason, for a number of minutes (setting `limits.waiting.defaultMinutes`).

## Notifications

Notifications reach every device: desktop, phone and browser. You can pick a sound (chime, bell, alarm, or your own
`.wav`, `.mp3` or `.ogg`) and set quiet hours. A milestone ticket notifies you once when it is done.
