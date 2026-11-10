# first-install QA walkthrough (QA FIRST-INSTALL-DOCKER-1)
`./run.sh` builds a clean ubuntu:24.04 image holding the latest nightly Linux server archive (SHA256 checked), runs the
daemon, and walks CLI, plugin install, harness install, project, ticket start. Logs go to `scratch/first-install/`.
Needs Docker and network. Tag: `--build-arg TAG=...` in the Dockerfile.
