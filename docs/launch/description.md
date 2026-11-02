# Descriptions (DRAFT, owner review)

## Short (about 50 words)

Infantry AI is a command post for coding agents. File tickets on a board, give each a role and a provider (Claude Code, opencode, pi or your own CLI), and an agent works on its own git branch. Watch and steer from Windows, a browser or Android. The daemon runs on your hardware.

## Long (about 200 words)

Running several coding agents at once usually means several terminals, several branches and a lot of remembering. Infantry AI puts that on one board.

You file a ticket, give it a role (engineer, QA, project manager) and a provider and model. Starting it cuts a git branch and worktree for the agent, so two agents never edit the same checkout. Progress, mail between agents and shared project knowledge (decisions, issues, notes) live in one daemon. When an agent needs a decision it asks you with options and a recommended pick, and carries on with other work until you answer.

Claude Code, opencode and pi work out of the box. Any other agent CLI can be described in a JSON file.

Your process lives in Lua scripts: run something when a ticket enters a lane, when mail arrives, on a timer or on an event. Scripts can call every tool an agent has.

The app is built from plugins, and plugins can have child plugins. Pace charts and usage tabs show how much of your plan you have used.

Clients: Windows desktop, Android, and a web client served by the daemon. Everything runs on your machine or server; nothing passes through an Infantry AI cloud. Early preview. Core is MPL-2.0, the rest Apache-2.0.
