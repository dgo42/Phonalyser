#!/bin/sh
# ============================================================================
#  Phonalyser headless server - service installer (Linux and macOS).
#
#  What it does : install | uninstall | activate the server as a background
#                 service.  One script, because the shell is the same on both
#                 systems; the service manager is not, so it branches on
#                 `uname -s` and carries both back-ends:
#
#                   Linux  -> a systemd SYSTEM unit
#                             (/etc/systemd/system/phonalyser-server.service),
#                             running as the dedicated system user
#                             "phonalyser", started at boot without anyone
#                             logging in.  ALSA is not session-bound, so this
#                             works headless.  install COPIES the jar (plus
#                             natives/ and web/ when present) to
#                             /opt/phonalyser-server
#                             and the unit runs from there - the service account
#                             cannot traverse a 0750 home directory, so a unit
#                             pointed at an extraction folder under $HOME dies
#                             at chdir before java starts and writes no log.
#                             Also installs the QA40x udev rule and the
#                             /var/lib + /var/log directories.
#
#                   macOS  -> a per-user LaunchAgent
#                             (~/Library/LaunchAgents/org.edgo.phonalyser.server.plist),
#                             started at login.  NOT a LaunchDaemon: CoreAudio
#                             is per-session and macOS asks for microphone
#                             consent through the window server, so a system
#                             daemon would enumerate no inputs and could never
#                             answer the consent prompt.
#
#  Verbs        : install [server arguments...]
#                     writes the unit / plist (plus, on Linux, the service user,
#                     the udev rule and the log directory).  Does not start
#                     anything.  Extra arguments are baked into the service
#                     command line, e.g.
#                         ./phonalyser-server-service.sh install --name bench1
#                 activate
#                     enables the service and starts it now.
#                 uninstall
#                     stops, disables and removes it.  Measurement data, device
#                     cards and logs are deliberately left behind.
#
#  Requires     : Java 17+ on the PATH; run as root on Linux (sudo), as your own
#                 user on macOS (NOT sudo).  On Linux the runtime is COPIED to
#                 /opt/phonalyser-server, so this folder may be moved or deleted
#                 afterwards; re-run install to pick up a new build.  On macOS
#                 the agent runs as you and points straight at THIS folder - do
#                 not move or delete it there.
#
#  Log location : the application decides it (AppPaths), and the launchers never
#                 override it.  The ONE exception is the Linux SYSTEM service
#                 below: it runs as the "phonalyser" account, which has neither
#                 the operator's home nor write access to it, so the unit names
#                 /var/lib/phonalyser and /var/log/phonalyser explicitly and the
#                 install step gives that account ownership of both.  The macOS
#                 agent runs as YOU and therefore overrides nothing - its log
#                 stays in ~/Library/Application Support/Phonalyser/logs, the
#                 same file the desktop application writes.
#
#  Stopping     : systemctl stop phonalyser-server / launchctl bootout send a
#                 normal termination signal, so the JVM shutdown hook runs and a
#                 QA40x analyzer is parked on the way out.
# ============================================================================
set -e

LABEL=org.edgo.phonalyser.server
UNIT_NAME=phonalyser-server
UNIT_FILE=/etc/systemd/system/phonalyser-server.service
SERVICE_USER=phonalyser
LINUX_DATA_DIR=/var/lib/phonalyser
LINUX_LOG_DIR=/var/log/phonalyser
# Where the Linux service RUNS FROM.  Deliberately not this folder: the unit runs
# as the unprivileged "phonalyser" account, and a ZIP unpacked in the operator's
# home sits under a 0750 home directory that account cannot traverse.  systemd
# then fails at chdir with status=200/CHDIR before java is ever executed - which
# is why that failure leaves no application log at all.  install copies the
# runtime pieces here, root-owned and world-readable, and uninstall removes it.
LINUX_INSTALL_DIR=/opt/phonalyser-server
UDEV_TARGET=/etc/udev/rules.d/70-qa40x.rules
PLIST="$HOME/Library/LaunchAgents/$LABEL.plist"
# Exactly what AppPaths resolves for this user on macOS.  Used ONLY to give
# launchd a place for the raw stdout/stderr capture - the application's own
# log4j file lands in the same directory by itself, unforced.
MAC_DATA_DIR="$HOME/Library/Application Support/Phonalyser"
MAC_LOG_DIR="$MAC_DATA_DIR/logs"

dir=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)

usage() {
    echo "usage: $(basename "$0") install [server arguments...] | activate | uninstall"
    exit 2
}

# The jar and the JVM are resolved once, here, so every back-end below writes an
# absolute command line - a service has no PATH worth relying on.
resolve_paths() {
    jar=$(ls "$dir"/phonalyser-server-*.jar 2>/dev/null | head -n 1)
    [ -n "$jar" ] || {
        echo "No phonalyser-server-*.jar found in $dir."
        exit 1
    }
    java_bin=$(command -v java || true)
    [ -n "$java_bin" ] || {
        echo "Java 17+ was not found on the PATH.  Install it first."
        exit 1
    }
}

require_root() {
    [ "$(id -u)" = "0" ] || {
        echo "This needs root on Linux - re-run it with sudo."
        exit 1
    }
}

refuse_root() {
    [ "$(id -u)" != "0" ] || {
        echo "Do NOT run this with sudo on macOS: the LaunchAgent belongs to your"
        echo "own login session, and as root it would be installed for root."
        exit 1
    }
}

# --------------------------------------------------------------- Linux -----

linux_install() {
    require_root
    resolve_paths

    if id "$SERVICE_USER" >/dev/null 2>&1; then
        echo "System user $SERVICE_USER already exists."
    else
        shell=/usr/sbin/nologin
        [ -x "$shell" ] || shell=/sbin/nologin
        [ -x "$shell" ] || shell=/bin/false
        useradd --system --no-create-home --home-dir "$LINUX_DATA_DIR" \
                --shell "$shell" "$SERVICE_USER"
        echo "Created system user $SERVICE_USER."
    fi

    mkdir -p "$LINUX_DATA_DIR" "$LINUX_LOG_DIR"
    chown "$SERVICE_USER:$SERVICE_USER" "$LINUX_DATA_DIR" "$LINUX_LOG_DIR"
    echo "Data directory $LINUX_DATA_DIR, log directory $LINUX_LOG_DIR."

    # Copy the runtime out of this folder and into a root-owned, world-readable
    # place.  The service account can neither traverse a 0750 home nor read a jar
    # inside one, so pointing the unit at the extraction folder makes the service
    # die at chdir before it starts.  Copying also means the operator may delete
    # or move the unpacked ZIP afterwards without breaking the service.
    mkdir -p "$LINUX_INSTALL_DIR"
    cp "$jar" "$LINUX_INSTALL_DIR/"
    installed_jar="$LINUX_INSTALL_DIR/$(basename "$jar")"
    if [ -d "$dir/natives" ]; then
        rm -rf "$LINUX_INSTALL_DIR/natives"
        cp -R "$dir/natives" "$LINUX_INSTALL_DIR/"
        echo "Copied natives/ to $LINUX_INSTALL_DIR."
    fi
    # The browser client, served at / on the same port.  The server looks for it
    # BESIDE THE JAR first and in the working directory second, and the unit runs
    # from this same directory - so one copy answers both.  Without it a service
    # install serves the API and answers 404 for the app itself, while the very
    # same ZIP run from a terminal serves it.
    if [ -d "$dir/web" ]; then
        rm -rf "$LINUX_INSTALL_DIR/web"
        cp -R "$dir/web" "$LINUX_INSTALL_DIR/"
        echo "Copied web/ to $LINUX_INSTALL_DIR - the browser client is served at /."
    fi
    [ -f "$dir/70-qa40x.rules" ] && cp "$dir/70-qa40x.rules" "$LINUX_INSTALL_DIR/"
    # Root-owned and readable by everyone, so the service account can read but
    # not rewrite what it executes.
    chown -R root:root "$LINUX_INSTALL_DIR"
    chmod -R a+rX "$LINUX_INSTALL_DIR"
    echo "Installed the runtime to $LINUX_INSTALL_DIR."

    # QA40x (USB 16c0:4e37 / 16c0:4e39) is opened through libusb, which needs
    # write access to the device node.  The rule shipped in this folder is the
    # same one the .deb installs.
    if [ -f "$dir/70-qa40x.rules" ]; then
        cp "$dir/70-qa40x.rules" "$UDEV_TARGET"
        chmod 0644 "$UDEV_TARGET"
        if command -v udevadm >/dev/null 2>&1; then
            udevadm control --reload-rules || true
            udevadm trigger --subsystem-match=usb || true
        fi
        echo "Installed the QA40x udev rule at $UDEV_TARGET."
    else
        echo "WARNING: 70-qa40x.rules is not in $dir - a QA40x analyzer will fail"
        echo "         to open with LIBUSB_ERROR_ACCESS until the rule is added."
    fi

    # The service user is not a member of these groups, it is GIVEN them by the
    # unit - nothing in /etc/group is edited.  Naming a group that does not
    # exist would make the unit refuse to start, so each one is checked first.
    supplementary=
    for group in audio plugdev; do
        if getent group "$group" >/dev/null 2>&1; then
            supplementary="$supplementary $group"
        fi
    done
    supplementary_line=
    [ -z "$supplementary" ] || supplementary_line="SupplementaryGroups=$(echo "$supplementary" | sed 's/^ //')"

    # No ProtectSystem / PrivateDevices hardening on purpose: reaching the sound
    # cards and the USB analyzer is this service's entire job.
    cat > "$UNIT_FILE" <<EOF
[Unit]
Description=Phonalyser headless measurement server
Documentation=https://github.com/dgo42/Phonalyser
After=network-online.target sound.target
Wants=network-online.target

[Service]
Type=simple
User=$SERVICE_USER
Group=$SERVICE_USER
$supplementary_line
WorkingDirectory=$LINUX_INSTALL_DIR
ExecStart=$java_bin -Dapp.data.dir=$LINUX_DATA_DIR -Dapp.log.dir=$LINUX_LOG_DIR -jar "$installed_jar" -d $*
Restart=on-failure
RestartSec=5
# SIGTERM, and enough time for it: the shutdown hook closes every session,
# releases the audio lines and parks a QA40x before the process exits.
KillSignal=SIGTERM
TimeoutStopSec=20

[Install]
WantedBy=multi-user.target
EOF
    chmod 0644 "$UNIT_FILE"
    systemctl daemon-reload
    echo "Wrote $UNIT_FILE (runs from $LINUX_INSTALL_DIR)."
    echo "Now run: sudo $0 activate"
}

linux_activate() {
    require_root
    [ -f "$UNIT_FILE" ] || {
        echo "$UNIT_FILE is missing - run 'sudo $0 install' first."
        exit 1
    }
    systemctl enable --now "$UNIT_NAME"
    echo "Enabled at boot and started."
    systemctl --no-pager --lines=0 status "$UNIT_NAME" || true
    echo "Check it with: curl http://localhost:8377/info"
    echo "Log file: $LINUX_LOG_DIR/phonalyser-server.log"
}

linux_uninstall() {
    require_root
    if [ -f "$UNIT_FILE" ]; then
        systemctl disable --now "$UNIT_NAME" || true
        rm -f "$UNIT_FILE"
        systemctl daemon-reload
        echo "Stopped, disabled and removed $UNIT_FILE."
    else
        echo "No $UNIT_FILE - nothing to remove."
    fi
    if [ -d "$LINUX_INSTALL_DIR" ]; then
        rm -rf "$LINUX_INSTALL_DIR"
        echo "Removed $LINUX_INSTALL_DIR."
    fi
    if [ -f "$UDEV_TARGET" ]; then
        rm -f "$UDEV_TARGET"
        if command -v udevadm >/dev/null 2>&1; then
            udevadm control --reload-rules || true
        fi
        echo "Removed $UDEV_TARGET."
    fi
    echo "Left in place: the $SERVICE_USER account, $LINUX_DATA_DIR (device cards"
    echo "and calibration) and $LINUX_LOG_DIR.  Delete them by hand if you mean to."
}

# --------------------------------------------------------------- macOS -----

mac_install() {
    refuse_root
    resolve_paths

    mkdir -p "$HOME/Library/LaunchAgents" "$MAC_LOG_DIR"

    # One <string> per argument: launchd does no word splitting, so the extra
    # server arguments are emitted one by one below the fixed ones.
    {
        cat <<EOF
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0">
<dict>
    <key>Label</key>
    <string>$LABEL</string>
    <key>WorkingDirectory</key>
    <string>$dir</string>
    <key>RunAtLoad</key>
    <true/>
    <key>KeepAlive</key>
    <dict>
        <key>SuccessfulExit</key>
        <false/>
    </dict>
    <key>StandardOutPath</key>
    <string>$MAC_LOG_DIR/phonalyser-server.out</string>
    <key>StandardErrorPath</key>
    <string>$MAC_LOG_DIR/phonalyser-server.err</string>
    <key>ProgramArguments</key>
    <array>
        <string>$java_bin</string>
        <string>-Djava.library.path=$dir/natives</string>
        <string>-Djna.library.path=$dir/natives</string>
        <string>-Dlibusb.path=$dir/natives</string>
        <string>-jar</string>
        <string>$jar</string>
        <string>-d</string>
EOF
        for arg in "$@"; do
            printf '        <string>%s</string>\n' "$arg"
        done
        cat <<'EOF'
    </array>
</dict>
</plist>
EOF
    } > "$PLIST"

    echo "Wrote $PLIST (runs from $dir)."
    echo "Now run: $0 activate"
}

mac_activate() {
    refuse_root
    [ -f "$PLIST" ] || {
        echo "$PLIST is missing - run '$0 install' first."
        exit 1
    }
    # bootstrap is the launchd of Yosemite and later; load -w is the fallback
    # for an older system, and for a second activate of an already-loaded agent.
    launchctl bootstrap "gui/$(id -u)" "$PLIST" 2>/dev/null \
        || launchctl load -w "$PLIST"
    echo "Loaded at login and started."
    echo "Check it with: curl http://localhost:8377/info"
    echo "Log file: $MAC_LOG_DIR/phonalyser-server.log - the same directory the"
    echo "desktop application logs into, because nothing here overrides it."
    echo "The FIRST capture makes macOS ask for microphone access - answer it, or"
    echo "the inputs stay silent (System Settings > Privacy & Security)."
}

mac_uninstall() {
    refuse_root
    if [ -f "$PLIST" ]; then
        launchctl bootout "gui/$(id -u)/$LABEL" 2>/dev/null \
            || launchctl unload -w "$PLIST" 2>/dev/null || true
        rm -f "$PLIST"
        echo "Stopped and removed $PLIST."
    else
        echo "No $PLIST - nothing to remove."
    fi
    echo "Left in place: $MAC_DATA_DIR - device cards,"
    echo "calibration and the log file are data, not packaging."
}

# ------------------------------------------------------------ dispatch -----

verb=${1:-}
[ -n "$verb" ] || usage
shift

system=$(uname -s)
case "$system" in
    Linux)
        case "$verb" in
            install)   linux_install "$@" ;;
            activate)  linux_activate ;;
            uninstall) linux_uninstall ;;
            *)         usage ;;
        esac
        ;;
    Darwin)
        case "$verb" in
            install)   mac_install "$@" ;;
            activate)  mac_activate ;;
            uninstall) mac_uninstall ;;
            *)         usage ;;
        esac
        ;;
    *)
        echo "$system is not a supported service platform (Linux and macOS only)."
        echo "Run the server directly with ./phonalyser-server.sh instead."
        exit 1
        ;;
esac
