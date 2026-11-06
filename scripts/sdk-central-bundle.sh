#!/usr/bin/env bash
# SPDX-License-Identifier: Apache-2.0
# Copyright (c) SuperiorSoft LLC
# SDK-CENTRAL-1: builds the Maven Central bundle build/central-bundle.zip (SDK modules + the Gradle plugin) and checks it.
# Extra arguments go to every Gradle call. Without arguments it is the dry run: nothing signed, nothing uploaded.
# The publish job passes -Pinfantry.central.sign=true (with ORG_GRADLE_PROJECT_signingInMemoryKey[Password] set): then
# a missing key fails the build ("signing secrets missing") and the check refuses any file without its .asc.
set -euo pipefail
root="$(cd "$(dirname "$0")/.." && pwd)"
cd "$root"

rm -rf build/central-bundle build/central-bundle.zip
./gradlew publishAllPublicationsToCentralBundleRepository --console=plain "$@"
./gradlew -p build-logic publishAllPublicationsToCentralBundleRepository --console=plain "$@"
./gradlew sdkCentralBundle --console=plain "$@"
echo "sdk-central-bundle: ok ($root/build/central-bundle.zip)"
