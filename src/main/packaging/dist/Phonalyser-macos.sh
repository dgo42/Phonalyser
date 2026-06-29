#!/bin/sh
# Run Phonalyser with your own Java 17+ runtime (no bundled JRE).
# macOS SWT REQUIRES -XstartOnFirstThread (added below).
# Place this next to phonalyser-<version>-macos*.jar, then:
#   chmod +x run-macos.sh && ./run-macos.sh
cd "$(dirname "$0")" || exit 1

command -v java >/dev/null 2>&1 || {
  echo "Java 17+ not found on PATH.  Install it from https://adoptium.net/"
  exit 1
}

# -macos.jar = Apple Silicon (aarch64), -macos-x64.jar = Intel; you have one of them.
jar=$(ls phonalyser-*-macos*.jar 2>/dev/null | head -n1)
[ -n "$jar" ] || { echo "No phonalyser-*-macos*.jar found next to this script."; exit 1; }

exec java -XstartOnFirstThread -jar "$jar" "$@"
