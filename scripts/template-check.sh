#!/usr/bin/env bash
# TEMPLATE-REPO-1: proves templates/plugin-repo builds against the SDK as published (dry run into build/m2), so the template
# cannot rot. Steps: publish the SDK + the Gradle plugin into build/m2, then build the template with -Pinfantry.sdkRepo and `ihq`.
set -euo pipefail
root="$(cd "$(dirname "$0")/.." && pwd)"
cd "$root"
version="$(sed -n 's/^infantryVersion=//p' gradle.properties | tr -d '\r')"
[ -n "$version" ] || { echo "template-check: infantryVersion not found in gradle.properties" >&2; exit 1; }

rm -rf build/m2
./gradlew publishAllPublicationsToDryRunRepository --console=plain
./gradlew -p build-logic publishAllPublicationsToDryRunRepository --console=plain

cd templates/plugin-repo
./gradlew build ihq -Pinfantry.sdkRepo="$root/build/m2" -Pinfantry.sdk.version="$version" --console=plain

ihq="$(ls build/ihq/*.ihq)"
listing="$(unzip -Z1 "$ihq")"
echo "$listing"
echo "$listing" | grep -q '^lib/Example_Starter\.jar$' || { echo "template-check: UI jar missing from $ihq" >&2; exit 1; }
echo "$listing" | grep -q '^lib/Example_Starter-daemon\.jar$' || { echo "template-check: daemon jar missing from $ihq" >&2; exit 1; }
echo "template-check: ok ($ihq)"
