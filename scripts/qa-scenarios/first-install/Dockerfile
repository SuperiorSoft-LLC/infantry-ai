# Clean-container first install of the headless server archive, the way a new user does it.
FROM ubuntu:24.04
ARG TAG=v1.3.2-nightly.20261110.53
RUN apt-get update && apt-get install -y --no-install-recommends curl ca-certificates git tar gzip && rm -rf /var/lib/apt/lists/*
RUN useradd -m -u 10001 user
USER user
WORKDIR /home/user
RUN V="${TAG#v}" && B=https://github.com/SuperiorSoft-LLC/infantry-ai/releases/download/$TAG \
 && curl -fsSLO $B/InfantryAI-server-$V-linux-x64.tar.gz && curl -fsSLO $B/SHA256SUMS \
 && grep linux-x64.tar.gz SHA256SUMS | sha256sum -c - && tar xzf InfantryAI-server-$V-linux-x64.tar.gz \
 && mv InfantryAI-server-$V-linux-x64 srv
