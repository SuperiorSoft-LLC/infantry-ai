# Launch post (DRAFT, owner review)

## Full (about 300 words)

**Infantry AI: a ticket board for your coding agents**

If you run more than one coding agent, you know the overhead: a terminal per agent, a branch per task, and a lot of "which one was doing what?"

Infantry AI is our answer. You file a ticket on a board and give it a role and a provider and model. Starting it cuts a git branch and worktree and runs the agent there, so agents do not collide. Its progress, mail and report show up on the ticket. You review the branch and merge it.

What is in the first preview:

- Claude Code, opencode and pi from one board, plus any CLI you describe in a JSON file.
- A question panel: an agent that needs a decision asks you with options and a recommended pick, and keeps working on other things until you answer.
- Shared project knowledge (decisions, issues, notes) and mail between agents, kept in one daemon.
- Lua scripts and hooks: run something when a ticket enters a lane, when mail arrives, on a timer or on an event. Scripts can call every tool an agent has.
- Plugins all the way down: tabs, board cards, daemon tools and agent harnesses are plugins, and plugins can have child plugins.
- Pace and usage charts so you can see how much of your plan the agents are using.
- A Windows desktop app, an Android client and a web client served by the daemon.

Everything runs on your machine or your own server. There is no Infantry AI cloud between you and your agents.

This is an early preview, not 1.0. The Windows installer is not code-signed yet, and macOS and Linux desktop apps are not packaged (the headless server runs anywhere). Known issues are listed in the README.

The core is MPL-2.0 and the rest is Apache-2.0. You can try the whole loop with opencode's free models.

Download and docs: [link]

## Short (under 280 characters)

Infantry AI: a ticket board for coding agents. Claude Code, opencode and pi work on their own git branches; you review and merge. Lua hooks automate your process. Windows, Android, web. Runs on your hardware. Early preview. [link]
