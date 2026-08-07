Phonalyser headless server - macOS
=================================

The server is the machine your measurement hardware is plugged into.  It owns
the sound cards and the QA40x analyzer and serves them over the network to
Phonalyser desktop clients.  There is no application bundle and no installer -
the server is a jar and two scripts, on purpose.

What is in this folder
----------------------
  phonalyser-server-<version>-macos*.jar   the server itself (one fat jar)
  phonalyser-server.sh                     run it in a terminal
  phonalyser-server-service.sh             install / activate / uninstall it
  natives/libportaudio.dylib               CoreAudio path, loaded from here
  natives/libusb-1.0.dylib                 QA40x analyzer, loaded from here

The Intel and Apple Silicon zips carry different dylibs - take the one that
matches your Mac.  A QA40x needs no kernel driver on macOS.

What you need
-------------
A Java 17+ runtime on the PATH - and nothing else.
No JRE is bundled.

Run it in a terminal
--------------------
    chmod +x phonalyser-server.sh phonalyser-server-service.sh
    ./phonalyser-server.sh
    ./phonalyser-server.sh --name bench1 --port 8377

Ctrl-C stops it cleanly: the shutdown hook closes the audio lines and parks a
QA40x analyzer at a safe attenuator setting.

The first time it opens an input, macOS asks for microphone access.  Grant it,
or every capture stays silent (System Settings > Privacy & Security >
Microphone).

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

Run it at login
---------------
    ./phonalyser-server-service.sh install
    ./phonalyser-server-service.sh activate

Do NOT use sudo.  install writes a LaunchAgent at
~/Library/LaunchAgents/org.edgo.phonalyser.server.plist pointing at THIS folder;
activate loads it, and it starts at every login from then on.  Extra arguments
given to install are baked into the agent:

    ./phonalyser-server-service.sh install --name bench1

The agent always runs with -d (daemon): a service has no console to print to.
The log file is written exactly as it is in a terminal run, so nothing is lost.

Do not move or delete this folder afterwards - the agent points straight at it.

A LaunchAgent, not a LaunchDaemon, and that is not laziness: CoreAudio is
per-session and the microphone consent prompt comes from the window server, so
a system daemon would enumerate no inputs and could never answer the prompt.
The price is that the server runs only while you are logged in.

    status  : launchctl print gui/$(id -u)/org.edgo.phonalyser.server
    stop    : launchctl bootout gui/$(id -u)/org.edgo.phonalyser.server
    remove  : ./phonalyser-server-service.sh uninstall

bootout terminates normally, so the shutdown hook runs and the analyzer is
parked.

Where things are
----------------
  log file      ~/Library/Application Support/Phonalyser/logs/phonalyser-server.log
  device cards  ~/Library/Application Support/Phonalyser/devices.yaml
  server id     ~/Library/Application Support/Phonalyser/server-id

Both ways of starting it - terminal and LaunchAgent - use that one directory:
neither the launcher nor the agent overrides where the application puts its log
and its data, so what you read there is always the whole story.  launchd's own
capture of anything printed before logging starts lands beside it as
phonalyser-server.out / .err.

If a folder named "web" sits next to the jar, the server also serves the browser
client on the HTTP port - open http://<server>:8377/ and measure from any
machine on the network without installing anything.  The LaunchAgent runs from
this same folder, so it serves the client exactly as a terminal run does.
