# The Phonalyser server

The machine your measurement hardware is plugged into does not have to be the
machine you sit at. Run **Phonalyser server** on the one that *is* wired to the
converters and it becomes a **bench**: it owns the sound cards and the QA40x
analyzer and serves them over the network to Phonalyser clients elsewhere. It
has no window and needs no desktop - a Raspberry Pi or any headless box will do.

This document is self-contained: everything needed to get a server running and
keep it running on a machine with no GUI is on this page. The **client** side -
finding a bench, connecting and measuring through it - is operated from the
desktop or web application, which carries its own help. What happens on the
wire is specified in [NET-PROTOCOL.md](NET-PROTOCOL.md).

## What it is, and what you need

The server ships as its own download, one ZIP per platform - Windows x64 and
x86, Linux x86_64 and aarch64, macOS Intel and Apple Silicon - beside the
desktop application's installers. Unpack it anywhere and run it; there is no
installer and nothing is written outside the folder until the server starts.

Inside each ZIP is **one fat JAR** plus the launchers for that platform. What
you must provide is a **Java 17 or newer runtime on the PATH** - and nothing
else: no JRE is bundled.

| Platform | What comes with it |
|---|---|
| **Windows** | `phonalyser-server-x64.exe` (double-click launcher), `phonalyser-server.cmd` (the same in a console you can read), `install-service.cmd` / `uninstall-service.cmd`, and a `natives\` folder carrying PortAudio (the WDM-KS path), libusb (the QA40x) and csjsound (the WASAPI-exclusive JavaSound mixers). **Keep `natives\` next to the launcher** - it is the only place searched. Without csjsound the server still runs but offers shared-mode mixers only, so the `EXCL:` devices and the high sample rates that depend on them disappear from the list. |
| **Linux** | `phonalyser-server.sh`, `phonalyser-server-service.sh` and the QA40x udev rule `70-qa40x.rules`. There is deliberately no `natives/`: ALSA is reached through the Java runtime, and libusb - needed only for a QA40x - comes from your distribution. The JAR is architecture-neutral - the x86_64 and aarch64 ZIPs differ in name only. |
| **macOS** | `phonalyser-server.sh`, `phonalyser-server-service.sh` and `natives/` with `libportaudio.dylib` (CoreAudio) and `libusb-1.0.dylib`. The Intel and Apple Silicon ZIPs carry different dylibs - take the one that matches your Mac. A QA40x needs no kernel driver here. |

> **Windows: the `-x64` / `-x86` suffix names what the bundle *targets*** -
> which JRE the launcher looks for and which architecture the DLLs in
> `natives\` are - not the launcher's own bitness. The 32-bit bundle insists on
> a 32-bit JVM, because a 64-bit one would quietly find none of its DLLs, and
> it caps the heap at 1200 MB, as much as a 32-bit process can reliably
> reserve. A QA40x must already be bound to the WinUSB driver, which happens
> once QuantAsylum's own software has been run on that machine.

## Running it in a console

    phonalyser-server-x64.exe
    phonalyser-server.cmd --name bench1 --port 8377

    ./phonalyser-server.sh
    ./phonalyser-server.sh --name bench1 --port 8377

Every launcher takes the same four arguments:

| Flag | Meaning |
|---|---|
| `--name <text>` | The name clients see in their server list and in the *in use by* note beside a device someone else holds. Defaults to the machine's host name. |
| `--port <port>` | **One port carries everything** - the control channel, the audio stream, the informational endpoints, the file upload and the web bundle. Default **8377**. |
| `--bind <address>` | Listen on one interface only. Default: all of them. This is the one knob that limits who can reach the bench - see *There is no authentication* below. |
| `-d`, `--daemon` | Accepted, and every service definition passes it - but it changes nothing today: the server writes no console output in any mode, and the log file is written the same way with or without it. |

**Ctrl-C stops it cleanly.** The shutdown hook closes the audio lines and
**parks a QA40x analyzer at a safe attenuator setting** - the same protection
the desktop application performs on the way out. That is why a server is
stopped rather than killed: an End Task leaves the analyzer at whatever
sensitive input range the last measurement selected.

> **macOS asks once.** The first time the server opens an input, macOS asks for
> microphone access. Grant it, or every capture stays silent
> (System Settings ▸ Privacy & Security ▸ Microphone).

## Discovery and the firewall

Clients find a server by **UDP multicast on 239.255.83.77, port 8377/udp** -
the same number the server listens on, because UDP and TCP are separate port
spaces. The announcement is **link-local**: routers do not pass it, so a bench
on another subnet is never discovered and is added by its address in the client
instead.

Allow that one port through the firewall, **udp and tcp**, or skip discovery
altogether and type the address into the client by hand.

## Running it as a service

### Windows - a real service

From an **elevated** command prompt:

    install-service.cmd                     run as LocalSystem
    install-service.cmd /user BENCH\alex    run as a real account
    install-service.cmd /user BENCH\alex --name bench1

Extra arguments are baked into the service. It is named **PhonalyserServer**
and points at *that* folder, so the folder must not be moved or deleted
afterwards. It is a genuine Service Control Manager service, hosted by WinSW:
**stopping it is a request, not a kill** - WinSW sends a Ctrl+C and waits up to
20 seconds, so the shutdown hook runs and the analyzer is parked, which a
scheduled task or an End Task would not do.

    start   : sc start PhonalyserServer
    stop    : sc stop PhonalyserServer
    remove  : uninstall-service.cmd
    settings: services.msc, entry "Phonalyser server"

> **Which account, and why it matters.** A Windows service always runs in
> **session 0**, never in your desktop session - a property of the operating
> system, not of this server. A **QA40x** is reached over USB and does not
> care, so LocalSystem is fine. **Sound cards do care**: the endpoints an
> application sees, and which one is the default, come from the per-user part
> of the registry, and LocalSystem has its own, effectively empty view. Install
> with `/user <account>` so the service loads that user's profile and sees the
> same devices that user does. Windows needs the account's password before such
> a service can start and the script deliberately does not handle passwords:
> after installing, open `services.msc` ▸ *Phonalyser server* ▸ Properties ▸
> Log On ▸ This account, type it, then Start. If even that does not show the
> card you expect, do not fight the service - put a shortcut to the launcher in
> `shell:startup` and it starts at logon inside your own desktop session, where
> the audio stack is unambiguous.

### Linux - a systemd unit

    sudo ./phonalyser-server-service.sh install
    sudo ./phonalyser-server-service.sh activate
    sudo ./phonalyser-server-service.sh install --name bench1 --bind 192.168.1.20

**install** writes `/etc/systemd/system/phonalyser-server.service` pointing at
that folder, creates the system user `phonalyser`, creates
`/var/lib/phonalyser` and `/var/log/phonalyser`, and installs the QA40x udev
rule; **activate** enables the unit and starts it. Extra arguments given to
install are baked into the unit, and the folder must not be moved afterwards -
the unit points straight at it.

    status  : systemctl status phonalyser-server
    stop    : sudo systemctl stop phonalyser-server
    remove  : sudo ./phonalyser-server-service.sh uninstall

The service user is given the `audio` and `plugdev` groups by the unit itself,
so nothing in `/etc/group` is edited. `systemctl stop` sends `SIGTERM`, so the
shutdown hook runs and the analyzer is parked. **uninstall** leaves
`/var/lib/phonalyser`, `/var/log/phonalyser` and the account alone: your
calibration is data, not packaging.

### macOS - a per-user launch agent

    ./phonalyser-server-service.sh install
    ./phonalyser-server-service.sh activate

**Do not use `sudo`.** install writes a LaunchAgent at
`~/Library/LaunchAgents/org.edgo.phonalyser.server.plist` pointing at that
folder; activate loads it, and from then on it starts at every login.

    status  : launchctl print gui/$(id -u)/org.edgo.phonalyser.server
    stop    : launchctl bootout gui/$(id -u)/org.edgo.phonalyser.server
    remove  : ./phonalyser-server-service.sh uninstall

> **A LaunchAgent, not a LaunchDaemon - and that is not laziness.** CoreAudio
> is **per session**, and the microphone-consent prompt comes from the window
> server: a system daemon would enumerate no inputs at all and could never
> answer the prompt. The price of doing it correctly is that the server runs
> only while you are logged in. `bootout` terminates normally, so the shutdown
> hook runs and the analyzer is parked.

## Where things are

| Platform | Log, cards and identity |
|---|---|
| **Windows** | One place, however it was started - console, LocalSystem service or `/user` service alike: `%ProgramData%\Phonalyser\logs\phonalyser-server.log`, `%ProgramData%\Phonalyser\devices.yaml`, `%ProgramData%\Phonalyser\server-id`. WinSW keeps its own record of service starts and stops in the same `logs` folder, beside the application's log rather than instead of it. |
| **Linux** | From a terminal: `~/.config/Phonalyser/logs/phonalyser-server.log` (or `/var/log/phonalyser` when that is writable). As the service: `/var/log/phonalyser/phonalyser-server.log` and `/var/lib/phonalyser` for `devices.yaml` and `server-id` - the `phonalyser` account has no home directory of its own, so the unit names those paths. |
| **macOS** | Both ways of starting it use one directory: `~/Library/Application Support/Phonalyser/` - `logs/phonalyser-server.log`, `devices.yaml`, `server-id`. |

On Windows that single machine-wide home is deliberate: **a server is a machine
installation**. The per-user `%APPDATA%\Phonalyser` belongs to the desktop GUI,
and a server sharing it would edit the desktop's own card store - while the two
service forms would each get a *different* `%APPDATA%` and disagree with the
console run.

> **The consequence to remember:** the desktop application's own calibration is
> **not** what the server serves. Give the server its own - calibrate through a
> connected client, which writes straight into the bench's store, or copy
> `devices.yaml` across. On Linux the same applies between a terminal run and
> the systemd service, which have separate data directories.

## There is no authentication, on purpose

A Phonalyser server does not ask for a password, and nothing on the wire is
encrypted. That is a decision, not an omission: **a bench instrument belongs on
a network you trust** - the room your measurement equipment is in - and adding
credentials to a LAN instrument protocol would buy nothing the network does not
already provide. Anyone who can reach the port can list the devices and take
one.

So do not expose the port to the open internet. If the machine has more than
one interface, `--bind` the server to the one your bench lives on and leave the
rest unreachable.

## Measuring through it

With a server running, the whole client side is three steps and it is all in
Preferences ▸ Audio:

1. **Find the bench.** *Servers...* opens the list. It usually fills by itself
   the moment it opens; a bench on another subnet is added by its address.
   **Connect** - or a double-click on the row - opens the session.
2. **Pick a backend.** The bench's backends join the Backend list beside the
   local ones, written `<server> -> <backend>`. Browsing only previews; **OK**
   commits.
3. **Measure.** From there the device combos, the card section, the range
   radios, **QA40x preferences**, the generator, the sweeps and *Play from...* all
   behave exactly as they do for local hardware. Calibration belongs to the
   bench, so a crosshair calibration writes into *its* store.

The finer client-side details - what is staged and what takes effect at once,
the *in use by* note on a device another client holds, the granted-rate rule,
the file upload, the five-second reconnect and the reasons a session can end -
are documented in the client applications themselves.

## While it runs

- **Hardware is re-enumerated every two seconds**, so a device plugged in or
  pulled out on the bench is noticed without restarting anything, and every
  connected client is told. Two seconds rather than continuously on purpose:
  enumeration talks to drivers and to USB, and nothing on a bench appears so
  urgently that the delay costs a measurement.
- **One client at a time per device.** The server holds a lock per device, so
  two operators cannot talk over each other's measurement; the lock names its
  holder, which is what the client's *in use by* note reads.
- **It serves every backend it has, concurrently.** There is no global "active
  backend" on a server - each session picks its own, so one client can be on
  the QA40x while another is on a sound card.
- **Shutting down parks the hardware**, the QA40x's input attenuator included,
  exactly as the desktop application does on exit.
- If a folder named `web` sits next to the JAR, the server also serves it on
  the same HTTP port.

## See also

- [NET-PROTOCOL.md](NET-PROTOCOL.md) - the wire: the ports, the beacon, the
  locks, the keepalive and what the audio stream actually carries.
- The per-platform `README-server-*.txt` inside each server ZIP - the shortest
  quick-start for that platform.
