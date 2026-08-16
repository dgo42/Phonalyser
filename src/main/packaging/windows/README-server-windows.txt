Phonalyser headless server - Windows
===================================

The server is the machine your measurement hardware is plugged into.  It owns
the sound cards and the QA40x analyzer and serves them over the network to
Phonalyser desktop clients.  It has no window and needs no desktop.

What is in this folder
----------------------
  phonalyser-server-<version>.jar           the server itself (one fat jar,
                                            the same file in every bundle -
                                            it carries every platform's natives)
  phonalyser-server-x64.exe                 double-click launcher
   (or phonalyser-server-x86.exe in the 32-bit bundle)
  phonalyser-server.cmd                     the same, in a console you can read
  phonalyser-server-service.exe             WinSW, the service host
  install-service.cmd                       run it as a Windows service
  uninstall-service.cmd                     undo that
  natives\                                  PortAudio, libusb and csjsound,
                                            all loaded from here

The three files in natives\ are the WDM-KS audio path (PortAudio), the QA40x
analyzer (libusb) and the WASAPI-exclusive JavaSound mixers (csjsound).  That
last one is what puts the "EXCL:" devices in the device list; without it the
server still runs, but it offers shared-mode mixers only, and with them you lose
the exclusive-mode capture path and the high sample rates that depend on it.
Keep the folder next to the launcher - it is where the launcher tells Java to
look, and nowhere else is searched.

Unpacked, this folder is around 32 MB, where the Linux and macOS bundles are
around 13 MB.  That is not a mistake: about 18 MB of it is
phonalyser-server-service.exe, which is a self-contained build of WinSW carrying
its own .NET runtime so the service host needs nothing installed on the machine.
You only pay for it if you install the service; the launcher and the jar do not
use it at all, and deleting it costs you nothing but install-service.cmd.

What you need
-------------
A Java 17+ runtime on the PATH - and nothing else.
No JRE is bundled: unpack this folder anywhere and run it.  The launcher is a
~430 KB stub, not a packaged runtime; if no Java 17+ is installed it says so in a
dialog.

About that -x64 / -x86 suffix: it names what the bundle TARGETS - which JRE the
launcher looks for and which architecture the DLLs in natives\ are - and not the
launcher's own bitness.  Both bundles ship a 32-bit launcher stub, because that
is the only kind the build tool emits; the x64 stub then goes and starts a
64-bit JVM.

The 32-bit bundle needs a 32-bit JVM, and its launcher insists on one: the jar
itself is architecture-neutral, but the DLLs in natives\ are 32-bit and a 64-bit
JVM would quietly find neither of them.  It also caps the heap at 1200 MB, as
much as a 32-bit process can reliably reserve.

A QA40x analyzer must already be bound to the WinUSB driver - that happens when
QuantAsylum's own software has been run once on this machine.  Nothing extra to
install for the sound cards.

A physical machine, not a virtual one.  Measured inside a virtual machine, the
server produced heavy periodic dropouts in both directions; the same hardware on
a physical machine measured cleanly.  The audio device is reached over USB, and
the guest's virtualised USB timing is what breaks - samples arrive late and in
bursts, which a measurement reads as gaps in the signal.

Run it in a console
-------------------
    phonalyser-server-x64.exe
    phonalyser-server.cmd --name bench1 --port 8377

Both take the same arguments and set the same properties; the .cmd is the one to
use when something is wrong, because it shows what it is doing.

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

Run it as a Windows service
---------------------------
From an elevated command prompt ("Run as administrator"):

    install-service.cmd                        run as LocalSystem
    install-service.cmd /user BENCH\alex       run as a real account

Extra arguments are baked into the service:

    install-service.cmd /user BENCH\alex --name bench1

A service always runs with -d (daemon): it has no console to print to.  The log
file is written exactly as it is in a console run, so nothing is lost.

The service is named "PhonalyserServer" and points at THIS folder, so do not
move or delete it afterwards.  It is a real Service Control Manager service,
hosted by WinSW: stopping it is a REQUEST, not a kill.  WinSW sends the server a
Ctrl+C and waits up to 20 seconds, so the shutdown hook runs and the analyzer is
parked - which a scheduled task or an End Task would not do.

    start it   : sc start PhonalyserServer
    stop it    : sc stop PhonalyserServer
    remove it  : uninstall-service.cmd
    settings   : services.msc, entry "Phonalyser server"

Which account, and why it matters
---------------------------------
A Windows service ALWAYS runs in session 0, never in your desktop session.  That
is a property of the operating system, not of this server, and it has one large
consequence for a bench:

  * A QA40x analyzer is reached over USB (WinUSB/libusb) and does not care about
    sessions.  LocalSystem is fine, and that is the default.
  * Sound cards do care.  The audio endpoints an application sees, and which one
    is the default, come from the per-user part of the registry; LocalSystem has
    its own, effectively empty, view.  Install with /user <account> so the
    service loads that user's profile and sees the same devices that user does.

Windows needs the account's password before such a service can start, and this
script deliberately does not handle passwords.  After install-service.cmd /user:

    1. run services.msc
    2. Phonalyser server > Properties > Log On > This account
    3. type the password, press OK
    4. press Start

If even that does not show the sound card you expect, do not fight the service:
run the launcher in your own session instead - put a shortcut to
phonalyser-server-x64.exe in shell:startup and it starts at logon, inside your
desktop session, where the audio stack is unambiguous.

Where things are
----------------
One place, however the server is started - console, LocalSystem service or
/user <account> service alike:

  log file      %ProgramData%\Phonalyser\logs\phonalyser-server.log
  device cards  %ProgramData%\Phonalyser\devices.yaml
  server id     %ProgramData%\Phonalyser\server-id

That is deliberate.  A server is a MACHINE installation: %APPDATA%\Phonalyser
belongs to the desktop GUI, and a server sharing it would edit the desktop's
own card store - while the two service forms would each get a different
%APPDATA% and disagree with the console run.  %ProgramData%\Phonalyser is the
machine-wide home, the server creates it, and every launch form reads and
writes the same files.  (An explicit -Dapp.data.dir on the java line still
overrides everything, which is what the automated tests use.)

The consequence to remember: the desktop GUI's own calibration lives in its
%APPDATA%\Phonalyser and is NOT what the server serves.  Give the server its
own calibration - calibrate through a connected client, or copy devices.yaml
across - if the two must agree.

WinSW also keeps its own record of service starts, stops and captured console
output in %ProgramData%\Phonalyser\logs, next to (not instead of) the
application's log file.

uninstall-service.cmd removes the service and its generated config and leaves
every one of those directories alone.

If a folder named "web" sits next to the jar, the server also serves the browser
client on the HTTP port - open http://<server>:8377/ and measure from any
machine on the network without installing anything.  The service runs from this
same folder, so it serves the client exactly as a console run does.
