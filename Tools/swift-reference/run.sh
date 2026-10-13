#!/usr/bin/env bash
# Builds the reference tool from the current iOS sources and runs it.
#
#   Tools/swift-reference/run.sh generate    # rewrite android/core/src/test/resources/golden from the real iOS code
#   Tools/swift-reference/run.sh verify DIR  # decode a folder written by the Android app with the iOS models
#
# Needs a Swift 6 toolchain for Linux or macOS (SWIFT=/path/to/swift if it is not on PATH).
set -euo pipefail
here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
repo="$here/../.."
swift="${SWIFT:-swift}"
scratch="${STRIDE_REFERENCE_BUILD:-$here/.build}"

"$here/prepare.sh"
"$swift" build --package-path "$here" --scratch-path "$scratch" >&2
tool="$("$swift" build --package-path "$here" --scratch-path "$scratch" --show-bin-path)/StrideReference"

# The iOS code reads the user's Documents folder and the local calendar; pin both so output is reproducible.
home="$(mktemp -d)"
trap 'rm -rf "$home"' EXIT
export HOME="$home" TZ=UTC LANG=C.UTF-8 STRIDE_REFERENCE_DOCUMENTS="$home/Documents"

case "${1:-}" in
  generate)
    out="$repo/android/core/src/test/resources/golden"
    mkdir -p "$out"
    "$tool" generate "$out" "$repo/android/core/src/test/resources/fixtures"
    ;;
  verify)
    "$tool" verify "${2:?usage: run.sh verify DIR}"
    ;;
  *)
    echo "usage: run.sh generate | verify DIR" >&2
    exit 2
    ;;
esac
