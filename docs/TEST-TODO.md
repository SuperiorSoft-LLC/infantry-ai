
## OWNER-PRIO-1 (ticket-08058fe7): owner prompt before mail wakes
- In the app: with the PM busy, send a worker mail to it, then type a message; when the turn ends the owner's message must run first and the mail after (not lost).
- The race at turn end (mail router `session_idle` vs queue drain) is covered by `owner_first::a_mail_wake_never_takes_the_turn_from_a_waiting_owner_prompt` only by timing; watch for it with `limits.mail.wakeDebounceSeconds` = 0.

## AR-6 (ticket-add961ba): communication vs board instructions
- MCP `initialize` against a live daemon: `instructions` = core text + the board paragraph (from `infantry_ticket_start`'s descriptor `instructions` key); `tools/list` carries no `instructions` key. Inputs: `infantry mcp serve` on a test daemon. Expected: one "Board:" paragraph after a blank line.
- A daemon composed without the board tools: no ticket text in `instructions` (unit-covered in `server::tests::no_board_tools_means_no_ticket_sentence`).

## AR-8 (ticket-08d927e5): per-session MCP tool allow-list from a role's `tools`
- Live `infantry mcp serve` inside a session whose role sets `tools: ["infantry_board"]`: `tools/list` = infantry_watch_file + infantry_board + the communication family (`COMMUNICATION_TOOLS` in `compose/tools.rs`); calling `infantry_ticket_create` answers "tool infantry_ticket_create is not enabled for this agent". Daemon side is covered by `tests/all/agent_tool_allow_list.rs`; the CLI's `plugin_tools` payload `{client:{sessionId, role}}` (`Tools::list_request`) is not covered end to end.
- The 17 bundled roles carry no `tools`: their sessions keep the full set (unchanged; worth one spot check in the app).
