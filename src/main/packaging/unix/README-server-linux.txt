Phonalyser headless server - Linux
=================================

The server is the machine your measurement hardware is plugged into.  It owns
the sound cards and the QA40x analyzer and serves them over the network to
Phonalyser desktop clients.  It has no window and needs no X server - a
Raspberry Pi or any headless box will do.

What is in this folder
----------------------
  phonalyser-server-<version>.jar          the server itself (one fat jar, the
                                           same file in every bundle - it
                                           carries every platform's natives)
  phonalyser-server.sh                     run it in a terminal
  phonalyser-server-service.sh             install / activate / uninstall it
  70-qa40x.rules                           udev rule for the QA40x analyzer

There is no natives/ folder, and that is deliberate: ALSA reaches the JavaSound
backend through the JDK, and libusb comes from your distribution.

What you need
-------------
    A Java 17+ runtime on the PATH and, for a QA40x analyzer, libusb
    (Debian/Ubuntu: sudo apt install libusb-1.0-0).

libusb is only needed for a QA40x analyzer.  The jar is architecture-neutral;
the x86_64 and aarch64 zips differ in name only, so either runs on a Pi.

alsa-utils (Debian/Ubuntu: sudo apt install alsa-utils) is what the server reads
a USB card's jacks through, so it can name each port (Line, Mic, Headphone) and
list only the ones with a cable in them.  Without it every port of every card is
listed, named as ALSA names it - nothing is hidden on a reading nobody took.

A physical machine, not a virtual one.  Measured inside a virtual machine, the
server produced heavy periodic dropouts in both directions; the same hardware on
a physical machine measured cleanly.  The audio device is reached over USB, and
the guest's virtualised USB timing is what breaks - samples arrive late and in
bursts, which a measurement reads as gaps in the signal.

Run it in a terminal
--------------------
    chmod +x phonalyser-server.sh phonalyser-server-service.sh
    ./phonalyser-server.sh
    ./phonalyser-server.sh --name bench1 --port 8377

Ctrl-C stops it cleanly: the shutdown hook closes the audio lines and parks a
QA40x analyzer at a safe attenuator setting.

Flags
-----
    --name <text>        the name clients see          (default: host name)
    --port <port>        everything: info / upload / web bundle and the
                         control + audio WebSocket     (default: 8377)
    --bind <address>     listen on one interface only  (default: all)
    -d, --daemon         silence the console; the log file is written anyway

Clients discover the server by UDP multicast on 239.255.83.77 port 8377/udp -
the same number the server listens on, because UDP and TCP are separate port
spaces.  Allow that one port through the firewall, udp and tcp, or connect by
typing the address into the client by hand.

Run it at boot
--------------
    sudo ./phonalyser-server-service.sh install
    sudo ./phonalyser-server-service.sh activate

install COPIES the jar (plus natives/ and web/ when present) to
/opt/phonalyser-server and writes /etc/systemd/system/phonalyser-server.service
pointing there, creates the system user "phonalyser", creates
/var/lib/phonalyser and /var/log/phonalyser, and installs the QA40x udev rule.
activate enables the unit and starts it.
Extra arguments given to install are baked into the unit:

    sudo ./phonalyser-server-service.sh install --name bench1 --bind 192.168.1.20

The unit always runs with -d (daemon): a service has no console to print to.
The log file is written exactly as it is in a terminal run, so nothing is lost.

The service runs from its own copy in /opt/phonalyser-server, so this folder may
be moved or deleted afterwards.  The copy is made at install time only: after
unpacking a newer build, re-run install to update it.

The copy is not cosmetic.  The unit runs as the unprivileged "phonalyser" user,
which cannot traverse a 0750 home directory - a unit pointed at a ZIP unpacked
under /home would fail at chdir (status=200/CHDIR) before java ever started, and
so would leave no application log to explain itself.

    status  : systemctl status phonalyser-server
    stop    : sudo systemctl stop phonalyser-server
    remove  : sudo ./phonalyser-server-service.sh uninstall   (also removes /opt/phonalyser-server)

The service user is given the audio and plugdev groups by the unit itself, so
nothing in /etc/group is edited.  systemctl stop sends SIGTERM, so the shutdown
hook runs and the analyzer is parked.

Where things are
----------------
  run from a terminal, log file  ~/.config/Phonalyser/logs/phonalyser-server.log
                                 (or /var/log/phonalyser when that is writable)
  run as the service, log file   /var/log/phonalyser/phonalyser-server.log
  run as the service, data       /var/lib/phonalyser (devices.yaml, server-id)

The launcher overrides neither path - run from a terminal, the server writes
exactly where the desktop application does.  Only the systemd unit names them,
because the "phonalyser" account has no home directory of its own.  The two
therefore have separate data directories, so calibration you did by hand is not
what the service sees.  Copy devices.yaml across if you want both to agree.

uninstall leaves /var/lib/phonalyser, /var/log/phonalyser and the phonalyser
account alone - your calibration is data, not packaging.

If a folder named "web" sits next to the jar, the server also serves the browser
client on the HTTP port - open http://<server>:8377/ and measure from any
machine on the network without installing anything.  The service install copies
that folder to /opt/phonalyser-server along with the jar, so a service-installed
server serves it exactly as a terminal run does.
