HARNESS-AUDIT-1 probe. Do exactly these steps, briefly, no other work:
1. Run in your shell: `infantry bg run --notify --name probe -- git --version` and then END YOUR TURN immediately (do not wait or poll).
2. When the job-finished line arrives in a later turn, call the MCP tool infantry_inbox (or run `infantry inbox` if you have no MCP tools) and say in one line which you used.
3. Run in your shell `echo SECRET=[$AUDIT_SECRET]` (bash) or `echo "SECRET=[$env:AUDIT_SECRET]"` (PowerShell) and repeat its exact output.
4. Run `infantry ticket move <THIS TICKET ID> done`.
