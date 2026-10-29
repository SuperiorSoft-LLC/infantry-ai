# AgentHQ headless server in Docker

Build with the `Dockerfile` here and run it with `AGENTHQ_TOKEN` (and `AGENTHQ_HOST=0.0.0.0` to accept
remote clients); see `superiorSoftLibrary/agentHq/headless/README.txt` for every setting.

## Starting tickets

Starting a ticket cuts a git worktree, so the container needs a repository and a base branch:

    -e AGENTHQ_REPO=/work/repo        # a git checkout mounted into the container
    -e AGENTHQ_BASE_BRANCH=main       # the branch ticket branches are cut from

Without them the Start is refused: "this project has no directory configured" (no repo) or
"no base branch configured" (no base). A ticket also needs a role (`agenthq ticket update <id> --role <role id>`).

## Compose and smoke test

`compose.yaml` is the single-host deployment (loopback port, `/data` + `/work` + home volumes, secrets as files,
read-only root, no capabilities, optional Caddy TLS with `--profile tls`). `.env.example` lists its settings.

    mkdir -p secrets && openssl rand -hex 32 > secrets/agenthq_token && openssl rand -hex 32 > secrets/agenthq_keystore_key
    chmod 644 secrets/*          # uid 10001 in the container must read them
    docker compose -f docker/compose.yaml up -d

The daemon refuses to start without a token. `docker/smoke.sh [image]` checks: no token refuses, `/health`, an
authenticated route refuses without a token, non-root, a ticket started with a fake harness, and that the data survives
`down` / `up`. The `docker-smoke` workflow runs it for changes under `docker/`.

## Demo droplet (opencode + pi inside, token generated on first start)

The image now carries the opencode and pi CLIs (claude with `--build-arg INSTALL_CLAUDE=true`). With no token
configured, `entrypoint.sh` generates one into `/data/access-token` on first start (`AGENTHQ_TOKEN_AUTO=false` turns that
off). `docker-compose.yml` is the 2 GB droplet demo (limits as env settings, `mem_limit`, token in the volume); the full
DigitalOcean recipe, the measured memory and the Linux notes are in `docs/docker-deploy.md`.
