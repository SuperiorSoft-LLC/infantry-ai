#!/usr/bin/env bash
# SPDX-License-Identifier: Apache-2.0
# Copyright (c) SuperiorSoft LLC
#
# NIGHTLY-1: plans one nightly build (.github/workflows/nightly.yml; docs/RELEASING.md, "Nightly builds").
#
#   scripts/nightly-version.sh plan <ref> <run-number> [force]
#       In a checkout with full history and tags. Prints key=value lines for $GITHUB_OUTPUT:
#       build=true|false, reason=..., sha=..., version=..., package_version=..., tag=...
#       build=false when <ref> already carries a nightly tag (no new commit since the last nightly), unless force=true.
#   scripts/nightly-version.sh compute <declared> <latest-stable|none> <yyyymmdd> <run-number>
#       The pure version arithmetic (no git), so it can be checked by hand:
#       scripts/nightly-version.sh compute 1.0.7 1.0.7 20261107 12
#         -> version=1.0.8-nightly.20261107.12  package_version=1.0.40012  tag=v1.0.8-nightly.20261107.12
#
# Versions:
#   next      = the declared infantryVersion (gradle.properties) when it is above the newest stable tag, else that tag's
#               BUILD + 1. The nightly orders above the current stable and below the next one (SemVer pre-release).
#   version   = <next>-nightly.<yyyymmdd>.<run>   (the date first, so a later night always orders higher)
#   package   = <next MAJOR>.<next MINOR>.<NIGHTLY_MSI_BUILD_BASE + run>   the MSI/DMG version, which must be numeric and
#               UNIQUE per build: jpackage derives the MSI ProductCode from it, so two MSIs with one version collide.
#               Stable releases keep BUILD below NIGHTLY_MSI_BUILD_BASE (release.yml checks), so the two never meet.
set -euo pipefail

# The first MSI BUILD number a nightly uses; stable BUILD numbers stay below it. MSI allows BUILD up to MSI_BUILD_MAX.
NIGHTLY_MSI_BUILD_BASE="${NIGHTLY_MSI_BUILD_BASE:-40000}"
MSI_BUILD_MAX=65535
# The SemVer pre-release identifier of the nightly channel (Infantry_Updater module.json appUpdate.channels.nightly).
NIGHTLY_ID="${NIGHTLY_ID:-nightly}"

die() { echo "nightly-version: $*" >&2; exit 1; }
numeric() { [[ "$1" =~ ^[0-9]+\.[0-9]+\.[0-9]+$ ]]; }
# 0 when $1 > $2 (both MAJOR.MINOR.BUILD).
greater() {
  local a b; IFS=. read -r -a a <<<"$1"; IFS=. read -r -a b <<<"$2"
  for i in 0 1 2; do
    (( 10#${a[i]} > 10#${b[i]} )) && return 0
    (( 10#${a[i]} < 10#${b[i]} )) && return 1
  done
  return 1
}

compute() {
  local declared="$1" stable="$2" day="$3" run="$4" next
  numeric "$declared" || die "gradle.properties infantryVersion='$declared' is not MAJOR.MINOR.BUILD"
  [[ "$day" =~ ^[0-9]{8}$ ]] || die "date '$day' is not yyyymmdd"
  [[ "$run" =~ ^[1-9][0-9]*$ ]] || die "run number '$run' is not a positive whole number"
  if [ "$stable" = none ] || greater "$declared" "$stable"; then
    next="$declared"
  else
    numeric "$stable" || die "latest stable '$stable' is not MAJOR.MINOR.BUILD"
    IFS=. read -r ma mi bu <<<"$stable"; next="$ma.$mi.$((10#$bu + 1))"
  fi
  IFS=. read -r ma mi bu <<<"$next"
  (( 10#$bu < NIGHTLY_MSI_BUILD_BASE )) || die "next stable $next has BUILD >= $NIGHTLY_MSI_BUILD_BASE, the nightly MSI range"
  local pkg=$((NIGHTLY_MSI_BUILD_BASE + 10#$run))
  (( pkg <= MSI_BUILD_MAX )) || die "run $run gives MSI BUILD $pkg > $MSI_BUILD_MAX; lower NIGHTLY_MSI_BUILD_BASE or bump MINOR (docs/RELEASING.md)"
  local v="$next-$NIGHTLY_ID.$day.$((10#$run))"
  echo "version=$v"
  echo "package_version=$ma.$mi.$pkg"
  echo "tag=v$v"
}

plan() {
  local ref="$1" run="$2" force="${3:-false}" sha declared stable built
  sha="$(git rev-parse --verify "$ref^{commit}")" || die "no commit '$ref'"
  echo "sha=$sha"
  built="$(git tag -l "v*-$NIGHTLY_ID.*" --points-at "$sha" | head -n1)"
  if [ -n "$built" ] && [ "$force" != true ]; then
    echo "build=false"
    echo "reason=no new commit since the last nightly ($built is $sha)"
    return 0
  fi
  declared="$(git show "$sha:gradle.properties" | sed -n 's/^infantryVersion=\(.*\)$/\1/p' | tr -d '[:space:]')"
  stable="$(git tag -l 'v*' | sed -n 's/^v\([0-9]*\.[0-9]*\.[0-9]*\)$/\1/p' | sort -t. -k1,1n -k2,2n -k3,3n | tail -n1)"
  compute "$declared" "${stable:-none}" "$(date -u +%Y%m%d)" "$run"
  echo "build=true"
  echo "reason=${built:+forced rebuild of $built; }new nightly of $sha"
}

case "${1:-}" in
  compute) shift; [ $# -eq 4 ] || die "usage: compute <declared> <latest-stable|none> <yyyymmdd> <run>"; compute "$@" ;;
  plan) shift; [ $# -ge 2 ] || die "usage: plan <ref> <run-number> [force]"; plan "$@" ;;
  *) die "usage: $0 plan <ref> <run> [force] | compute <declared> <latest-stable|none> <yyyymmdd> <run>" ;;
esac
