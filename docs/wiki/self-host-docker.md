# Run a runner in Docker

The Docker image runs a [remote runner](remote-runners.md) on a server. Your desktop app sends agents to it.

## Build and start

From the repository root:

```
docker build -f docker/Dockerfile.rust -t infantry-runner-rust .
docker run -d --restart unless-stopped --name infantry-runner \
  -e INFANTRY_RUNNER_HOST=<this server's address> \
  -p 8790:8790 -v infantry-runner:/data infantry-runner-rust
docker logs infantry-runner
```

Use `docker/Dockerfile.rust`. The other files in `docker/` belong to an older version of the app.

`docker logs` prints the **endpoint** and the **bearer**. Add them in **Settings > Runners > Add runner**.

## Things to know

- Set `INFANTRY_RUNNER_HOST` to an address your PC can reach. If you leave it out, the runner says `localhost` and
  prints a warning.
- If you publish another host port (for example `-p 9000:8790`), also set `INFANTRY_RUNNER_PUBLIC_PORT=9000`.
- Everything is kept in the `/data` volume: the bearer, the agents' logins and the installed tools.
- The image has no agent CLIs inside. Install them from the app, or build them in with
  `--build-arg PREINSTALL_HARNESSES=pi,claude`.
- The container runs as a normal user, not as root.

## Reaching a daemon from another machine

The daemon itself listens only on its own PC. To reach it from elsewhere, start it with a token:

| Variable | Meaning |
|---|---|
| `INFANTRY_HOST` | Address to listen on, for example `0.0.0.0`. Default `127.0.0.1`. |
| `INFANTRY_PORT` | Port. Default 18990. |
| `INFANTRY_TOKEN_FILE` or `INFANTRY_TOKEN` | The token, at least 32 characters. Needed for any address other than this PC. |

Every client then sends the token. For the phone app, use [Allow phone connections](phone.md) instead; it does this
for you.
