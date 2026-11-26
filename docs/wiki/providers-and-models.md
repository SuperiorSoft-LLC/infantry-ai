# Providers and models

## Harnesses

A **harness** is the coding CLI that runs an agent. Infantry AI supports:

| Harness | CLI |
|---|---|
| claude | Claude Code |
| codex | Codex |
| opencode | opencode |
| pi | pi |

The guided setup installs the one you pick. Each harness lists its own providers and models. You pick them in
**New agent** or on a ticket.

## Sign in

You sign in with the CLI's own login. For Claude Code the app runs `claude auth login`. You can also use a long-lived
token from `claude setup-token`; the app keeps it in your [secrets](knowledge-scripting-settings.md#secrets).

## Effort

Effort is how hard the model thinks. Set it on a ticket.

- Claude: `low`, `medium`, `high`, `xhigh` or `max`.
- Codex and pi: the value is passed to the CLI as it is.

## Max context

Every agent has a maximum context. The default is **500k** tokens, from the setting
`limits.session.defaultContextTokens`. You can write `500k`, `1m` or `off`. Change it in **New agent**, on a ticket,
or on a running session.

## Usage and pacing

Open **Stats** to see your use. It has three tabs:

- **Session**: this session's use.
- **Tokens by day/week**.
- **Pace**: are you using your plan faster or slower than it lasts?

On the command line: `infantry usage`.

Useful settings: `usage.pacing`, `usage.refreshMinutes`, `usage.poll.enabled`.
