#!/bin/bash
# ----------------------------------------------------------------------------
# Linux build: fat jar + jpackage APP_IMAGE (no .deb wrap).
#
#   ./make-linux.sh            build for THIS machine's architecture
#   ./make-linux.sh x64        force the x86_64 profile
#   ./make-linux.sh aarch64    force the aarch64 profile
#
# jpackage CANNOT cross-build — it bundles the JDK it is running on.  The arch
# argument therefore selects the profile, but the JDK you run this with has to
# match it; the check below refuses early rather than letting jpackage produce
# an image for the wrong architecture.  To get both, run this once on an x86_64
# machine and once on an aarch64 one (a Raspberry Pi will do).
#
# APP_IMAGE rather than DEB keeps the build free of fakeroot/dpkg-deb; the
# release pipeline builds the real .deb (see PACKAGING.md).
# ----------------------------------------------------------------------------
set -euo pipefail

# Run from this script's own directory, so it works from anywhere.
cd "$(dirname "$0")"

host_arch() {
    case "$(uname -m)" in
        aarch64|arm64) echo aarch64 ;;
        x86_64|amd64)  echo x64 ;;
        *) echo unsupported ;;
    esac
}

ARCH="${1:-$(host_arch)}"
case "$ARCH" in
    x64)     PROFILE=linux-x64 ;;
    aarch64) PROFILE=linux-aarch64 ;;
    *) echo "Usage: $0 [x64|aarch64]   (this machine is $(uname -m))" >&2; exit 2 ;;
esac

# Compare against the JDK's own architecture, not uname: jpackage bundles the
# running JDK, and a JDK can be running under emulation.
JVM_ARCH=$(java -XshowSettings:properties -version 2>&1 | sed -n 's/.*os\.arch = *//p')
case "$JVM_ARCH" in
    aarch64|arm64) JVM_ARCH=aarch64 ;;
    x86_64|amd64)  JVM_ARCH=x64 ;;
esac
if [ "$JVM_ARCH" != "$ARCH" ]; then
    echo "Refusing to build: asked for $ARCH but this JDK is $JVM_ARCH." >&2
    echo "jpackage bundles the running JDK, so run this with an $ARCH JDK." >&2
    exit 1
fi

# Project Nayuki's flac-library is not on Maven Central, but it is vendored as the
# modules/flac-library-java module and the reactor builds it — no separate install
# step is needed any more.

# jpackage refuses to overwrite an existing image; clear it before building.
rm -rf modules/phonalyser-app/target/installer

echo "=== Building Phonalyser ($PROFILE, APP_IMAGE) ==="
mvn -B -ntp clean "-P$PROFILE" "-Djpackage.type=APP_IMAGE" -DskipTests package

echo
# Globbed rather than spelled out: the jar carries the project version and the
# profile's platform id, and neither belongs hard-coded in this script.
for jar in modules/phonalyser-app/target/phonalyser-*.jar; do
    echo "Fat jar   : $jar"
done
echo "App image : modules/phonalyser-app/target/installer/Phonalyser/"
