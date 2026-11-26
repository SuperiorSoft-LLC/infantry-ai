# Infantry AI

<!-- shared:tagline -->Run a whole team of coding agents from one ticket board.<!-- /shared:tagline -->

Infantry AI is a command post for coding agents. You write tickets on a board. Each agent takes a ticket and works on
its own git branch, in its own folder. You review the work and decide what gets merged.

It runs on your own machine. There is no third-party cloud between you and your agents.

## What is in it

- **A ticket board** with the lanes To Do, In Progress, Pending and Done.
- **Agents** that run the coding CLIs you already use: Claude Code, Codex, opencode and pi.
- **Mail** between you and your agents, and decision cards when an agent needs your choice.
- **Shared knowledge**: notes, roles and skills that every agent of a project can read.
- **Lua scripts and hooks** that automate your own process, with no model and no tokens.
- **Plugins** that add tabs, panels and tools. Most of the app itself is plugins.

## Infantry AI and BattalionOS

**BattalionOS** is the core and shell: the daemon, the protocol, the runner and the desktop window. It is licensed
MPL-2.0. **Infantry AI** is an app that runs on BattalionOS. Apps and plugins are Apache-2.0.

You can install Infantry AI as its own desktop app, or install BattalionOS and add Infantry AI from the Armory.

## How it fits together

- The **daemon** is a small background program. It keeps the board, the mail, the agents and the plugins.
- The **apps** (desktop, phone, command line) talk to the daemon. Closing the window does not stop your agents.
- A **runner** is where agents run. Your own PC is the local runner. You can add remote runners.

## Getting started

1. [Install the desktop app](install-desktop.md), or [install BattalionOS](install-battalionos.md).
2. [Create your first project and agent](first-project.md).
3. [Use the board and tickets](board-and-tickets.md).
4. [Choose providers and models](providers-and-models.md).

## User guide

- [Mail and decisions](mail-and-decisions.md)
- [Knowledge, scripting, settings and secrets](knowledge-scripting-settings.md)
- [Use the phone app](phone.md)
- [Services and Hosting](services-and-hosting.md)
- [Remote runners](remote-runners.md)
- [Run a runner in Docker](self-host-docker.md)

## Status

Infantry AI is in Beta. It works and we use it every day, but not every part is fully tested yet. Please report
anything that breaks on [GitHub](https://github.com/SuperiorSoft-LLC/infantry-ai/issues).
