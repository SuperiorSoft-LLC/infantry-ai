#!/usr/bin/env bash
# SPDX-License-Identifier: Apache-2.0
# Copyright (c) SuperiorSoft LLC
# pid.sh: PID of the isolated QA window = the process holding the daemon port 18841.
netstat -ano | grep -E "127.0.0.1:18841 +[0-9.:]+ +(ESTABLISHED|LISTENING)" | awk '{print $NF}' | sort -u | head -1
