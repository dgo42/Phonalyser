#!/bin/sh
# Run Phonalyser with your own Java 17+ runtime (no bundled JRE).
# Place this next to phonalyser-<version>-linux.jar, then:
#   chmod +x run-linux.sh && ./run-linux.sh
cd "$(dirname "$0")" || exit 1

command -v java >/dev/null 2>&1 || {
  echo "Java 17+ not found on PATH.  Install it (e.g. 'sudo apt install openjdk-17-jre' or https://adoptium.net/)."
  exit 1
}

jar=$(ls phonalyser-*-linux.jar 2>/dev/null | head -n1)
[ -n "$jar" ] || { echo "No phonalyser-*-linux.jar found next to this script."; exit 1; }

exec java -jar "$jar" "$@"
