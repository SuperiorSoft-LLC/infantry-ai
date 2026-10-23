# AgentHQ headless server in Docker

Build with the `Dockerfile` here and run it with `AGENTHQ_TOKEN` (and `AGENTHQ_HOST=0.0.0.0` to accept
remote clients); see `superiorSoftLibrary/agentHq/headless/README.txt` for every setting.

## Starting tickets

Starting a ticket cuts a git worktree, so the container needs a repository and a base branch:

    -e AGENTHQ_REPO=/work/repo        # a git checkout mounted into the container
    -e AGENTHQ_BASE_BRANCH=main       # the branch ticket branches are cut from

Without them the Start is refused: "this project has no directory configured" (no repo) or
"no base branch configured" (no base). A ticket also needs a role (`agenthq ticket update <id> --role <role id>`).
