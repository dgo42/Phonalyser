#!/bin/bash
# ----------------------------------------------------------------------------
# macOS build: fat jar + jpackage APP_IMAGE (no .dmg wrap).
#
#   ./make-mac.sh             build for THIS machine's architecture
#   ./make-mac.sh x64         Intel build
#   ./make-mac.sh arm64       Apple Silicon build
#   ./make-mac.sh both        both, one after the other
#
# jpackage CANNOT cross-build: it bundles the JDK it is running on, and the pom
# picks its profile from os.arch.  On macOS that is still enough to build both
# from ONE machine, because the architecture comes from the JDK rather than the
# hardware — an x86_64 JDK runs on Apple Silicon under Rosetta 2 and makes
# jpackage emit an Intel app.  Each build therefore selects its own JDK with
# /usr/libexec/java_home -a <arch>; a missing one is reported, not guessed at.
#
# Both profiles set the same platform id, so the two builds would overwrite each
# other's output — each is moved to target/dist-<arch>/ as soon as it is made.
#
# APP_IMAGE rather than DMG keeps the build quick and unsigned; the release
# pipeline builds the real .dmg (see PACKAGING.md).
# ----------------------------------------------------------------------------
set -euo pipefail

# Run from this script's own directory, so it works from anywhere.
cd "$(dirname "$0")"

host_arch() {
    case "$(uname -m)" in
        arm64|aarch64) echo arm64 ;;
        x86_64)        echo x64 ;;
        *) echo unsupported ;;
    esac
}

# Project Nayuki's flac-library is not on Maven Central, but it is vendored as the
# modules/flac-library-java module and the reactor builds it — no separate install
# step is needed any more.

build_arch() {
    local arch="$1" profile jdk_arch home out
    case "$arch" in
        x64)   profile=macos-x64;      jdk_arch=x86_64 ;;
        arm64) profile=macos-aarch64;  jdk_arch=arm64 ;;
        *) echo "Unsupported architecture: $arch" >&2; return 2 ;;
    esac

    # PROFILE=... still overrides, as it always did.
    profile="${PROFILE:-$profile}"

    if ! home=$(/usr/libexec/java_home -a "$jdk_arch" -v 17 2>/dev/null); then
        echo "No $jdk_arch JDK 17 found — install one to build $arch." >&2
        echo "  (/usr/libexec/java_home -a $jdk_arch -v 17 found nothing)" >&2
        return 1
    fi

    out="modules/phonalyser-app/target/dist-$arch"
    # jpackage refuses to overwrite an existing image; clear both it and the
    # destination before building.
    rm -rf modules/phonalyser-app/target/installer "$out"

    echo "=== Building Phonalyser ($profile, APP_IMAGE) with JDK $home ==="
    JAVA_HOME="$home" mvn -B -ntp clean "-P$profile" "-Djpackage.type=APP_IMAGE" -DskipTests package

    mkdir -p "$out"
    mv modules/phonalyser-app/target/installer/* "$out"/
    # The two profiles publish the same platform id, so the jars would collide;
    # keep each beside its own app image.
    mv modules/phonalyser-app/target/phonalyser-*.jar "$out"/

    echo
    for jar in "$out"/phonalyser-*.jar; do
        echo "Fat jar   ($arch): $jar"
    done
    echo "App image ($arch): $out/Phonalyser.app"
    echo
}

case "${1:-$(host_arch)}" in
    both)
        # Attempt both and report at the end, rather than letting a missing JDK
        # abort after a perfectly good first build.  On an INTEL Mac the arm64
        # leg cannot succeed at all — Rosetta runs x86_64 on Apple Silicon, not
        # the other way round — so that DMG has to come from CI.
        rc=0
        build_arch x64   || rc=1
        build_arch arm64 || rc=1
        exit $rc
        ;;
    x64)   build_arch x64 ;;
    arm64) build_arch arm64 ;;
    *) echo "Usage: $0 [x64|arm64|both]   (this machine is $(uname -m))" >&2; exit 2 ;;
esac
