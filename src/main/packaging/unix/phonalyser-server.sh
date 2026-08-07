#!/bin/sh
# ============================================================================
#  Phonalyser headless server - console launcher (Linux and macOS).
#
#  What it does : starts the server from the phonalyser-server-*.jar that sits
#                 next to this script.  When the zip carries a natives/ folder
#                 it is put on the native library search path - PortAudio and
#                 libusb are loaded by JNA from a directory, never from the
#                 class path.  The macOS zips carry one; the Linux zips carry
#                 none on purpose (ALSA comes from the JDK, libusb from the
#                 distribution package).
#  Requires     : a Java 17+ runtime on the PATH.
#                 Linux + QA40x also needs the udev rule; install it with
#                 ./phonalyser-server-service.sh install, or by hand from
#                 70-qa40x.rules next to this script.
#  Arguments    : passed straight through to the server, for example
#                   ./phonalyser-server.sh --name bench1 --port 8377
#                 Recognised: --name, --port, --bind,
#                 -d / --daemon.
#  Stop it      : Ctrl-C - that runs the shutdown hook, which closes the audio
#                 lines and parks a QA40x analyzer.
#  Log file     : phonalyser-server.log, under ~/.config/Phonalyser/logs on
#                 Linux and ~/Library/Application Support/Phonalyser/logs on
#                 macOS (or /var/log/phonalyser on Linux when that is writable).
# ============================================================================
set -e

dir=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)

command -v java >/dev/null 2>&1 || {
    echo "Java 17+ was not found on the PATH."
    exit 1
}

jar=$(ls "$dir"/phonalyser-server-*.jar 2>/dev/null | head -n 1)
[ -n "$jar" ] || {
    echo "No phonalyser-server-*.jar found next to this script."
    exit 1
}

# The three properties are spelled out rather than built into one variable so
# that a directory with spaces in its name still survives the shell.
if [ -d "$dir/natives" ]; then
    exec java "-Djava.library.path=$dir/natives" \
              "-Djna.library.path=$dir/natives" \
              "-Dlibusb.path=$dir/natives" \
              -jar "$jar" "$@"
fi

exec java -jar "$jar" "$@"
