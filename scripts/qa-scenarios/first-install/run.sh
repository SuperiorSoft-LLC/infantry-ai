#!/usr/bin/env bash
# Re-runnable: builds the image, starts the daemon in a fresh container, walks the first-run steps, logs to scratch/first-install/.
set -u
cd "$(dirname "$0")"; OUT=../../../scratch/first-install; mkdir -p $OUT
C=infantry-qa-first-install
docker rm -f $C >/dev/null 2>&1
docker build -t $C . 2>&1 | tail -n 15 | tee $OUT/00-build.log
docker run -d --name $C $C bash -c 'openssl rand -hex 32 > tok 2>/dev/null || head -c32 /dev/urandom | od -An -tx1 | tr -d " \n" > tok; INFANTRY_TOKEN_FILE=$PWD/tok INFANTRY_HOST=127.0.0.1 srv/bin/infantry-server.sh > daemon.log 2>&1' >/dev/null
sleep 25
x(){ n=$1; shift; echo "\$ $*" | tee $OUT/$n.log; docker exec $C bash -c "$*" 2>&1 | tee -a $OUT/$n.log; }
x 01-ls 'ls srv srv/bin; cat srv/README.txt | head -40'
x 02-daemonlog 'tail -n 30 daemon.log'
x 03-health 'curl -s localhost:8787/health'
x 04-cli 'export INFANTRY_TOKEN_FILE=$PWD/tok; srv/bin/infantry --help | head -30; srv/bin/infantry board 2>&1 | head -20'
# Second half: the CLI is bin/agenthq (bin/infantry is the supervisor) and needs sh + JAVA_HOME; see README.md.
J='cd srv/bin; export JAVA_HOME=/home/user/srv/runtime INFANTRY_TOKEN_FILE=/home/user/tok; I="sh ./agenthq"'
x 05-supervisor "cd; srv/bin/infantry --help; echo exit=\$?"
x 06-cli "$J; \$I board; \$I projects; \$I plugin list; \$I plugin search; \$I harness-status; \$I health | head -20"
x 07-plugin "$J; \$I plugin install infantry-community:Infantry_Mascot; \$I plugin list"
docker exec -u root $C bash -c 'apt-get update -qq >/dev/null 2>&1; apt-get install -y -qq nodejs npm >/dev/null 2>&1; node -v'
x 08-harness "$J; \$I harness-install opencode"   # ~3 min on Node 18, no progress
x 09-project "cd; mkdir -p repo && cd repo && git init -q -b main && git -c user.email=a@b -c user.name=q commit -q --allow-empty -m init; $J; P=\$(\$I project create qa /home/user/repo | awk '/created/{print \$3}'); \$I project base \$P main; \$I ticket create --title second --desc hello --project \$P --provider opencode; T=\$(\$I tickets --project \$P | awk 'NR==2{print \$1}'); \$I ticket start \$T; \$I ticket update \$T --role fullstack; \$I ticket start \$T; sleep 60; \$I board"
docker rm -f $C >/dev/null   # by name only
