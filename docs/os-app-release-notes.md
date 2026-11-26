Infantry AI 0.8.7 as a BattalionOS app.

What changed for you:
- Sessions waiting on a tool approval or a question now count under "Needs input", so you see every session that is waiting on you.
- The CLI: `ticket create` refuses a project that does not exist, `ticket comment` takes `--kind` and `--state`, and `ticket move/show/get` accept a short ticket id.
- Chat images and notes reach sessions that have no project.
- The CLI and the daemon read one shared contract version.
