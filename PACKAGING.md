# Packaging

This document describes how to produce distributable artifacts of
Phonalyser on Windows, Linux and macOS.

Two output formats are supported on every OS:

* **Fat JAR** (`target/phonalyser-<version>-<platform>.jar`) -
  cross-platform-buildable, requires a Java 17+ runtime on the user's machine.
* **Native installer** - `.exe` on Windows, `.deb` on Linux, `.dmg` on macOS.
  Bundles a JRE so the end user doesn't need Java installed.  Must be built
  on the target OS (jpackage can't cross-compile).

## 1. Prerequisites

| Tool      | Version | Notes                                                       |
| --------- | ------- | ----------------------------------------------------------- |
| JDK       | 17+     | jpackage ships with the JDK.                                |
| Maven     | 3.8+    | The included `pom.xml` activates the correct OS profile automatically. |

That is the whole list. **The native libraries are committed to the repository**
(§2) - nothing to download, nothing to build.

### The vendored flac-library

Project Nayuki's FLAC library - the decode side of Play-from - is **not on
Maven Central**, so it is vendored as the `modules/flac-library-java` module and
built by the reactor along with everything else. Nothing to install by hand.
See [BUILD.md](BUILD.md) §1.

### Platform profiles

The Maven OS profiles in `pom.xml` - `windows-x64`, `windows-x86`, `linux-x64`,
`linux-aarch64`, `macos-x64`, `macos-aarch64` - select the matching SWT artifact
plus any platform-only dependencies. **All auto-activate on `<os>` except
`windows-x86`**, which has no activation block and must always be requested by
hand, with the host profile deactivated:

```pwsh
mvn "-P!windows-x64,windows-x86" -DskipTests package
```

The same `-P!<host>,<target>` form cross-builds any other platform's fat JAR;
[BUILD.md §3](BUILD.md) has the full matrix and the per-shell quoting rules.
Native **installers** cannot be cross-built (jpackage bundles a JRE matching the
build machine), so those come from the CI matrix - §4.

## 2. Native libraries - already in the repository

**Nothing to fetch or compile.** Every native the app needs is committed under
[lib/](lib/), so a fresh clone builds on any platform straight away:

| Folder | Contents |
| ------ | -------- |
| [lib/windows/](lib/windows/) | `portaudio_x64.dll`, `portaudio_x86.dll` (WDM-KS backend) · `csjsound-provider.jar` + `csjsound_amd64.dll`, `csjsound_x86.dll` (WASAPI-exclusive JavaSound mixers) · `libusb-1.0_x64.dll`, `libusb-1.0_x86.dll` (QA40x backend) |
| [lib/macos-x64/](lib/macos-x64/) · [lib/macos-arm64/](lib/macos-arm64/) | `libportaudio.dylib`, `libusb-1.0.dylib` |

Both Windows architectures are covered, so the `windows-x86` build finds its
32-bit natives just as `windows-x64` finds the 64-bit ones. Each folder's
`README.md` records where its binaries came from.

**None of these is a Maven dependency.** No profile declares a native as a
system-scoped artifact - the only system-scoped entry in the whole build is
`csjsound-provider.jar`, in an IDE-only profile of `phonalyser-app`. The natives
reach a distribution two other ways:

* **Into the installer** - the `stage-jpackage-input` antrun execution in
  `modules/phonalyser-app/pom.xml` copies them into `target/jpackage-input/`,
  and jpackage packs that directory.
* **Into a JAR** - the copies under
  `modules/<module>/src/main/resources/win32-x86-64/` and `win32-x86/` ride
  inside the fat JARs as JNA resources, each in the module whose backend loads
  it: PortAudio in `phonalyser-core`, libusb in `backend-qa40x`, csjsound in
  `backend-javasound`. Those module copies are the authoritative ones -
  `lib/windows/portaudio_x64.dll` is a stale build with WDM-KS compiled out
  (`rc=-9979`) and must not be used.

**Linux ships no natives here.** ALSA reaches the JAVASOUND backend through
`javax.sound.sampled` inside the bundled JDK, and the QA40x backend uses the
distribution's own libusb - which is why the `.deb` declares it as a package
dependency instead. SWT always brings its own widget bindings
(`libswt-*.so` / `.jnilib` / `.dll`) inside its platform Maven artifact.

## 3. Build commands

### Fat JAR (any OS)

```
mvn -DskipTests package
```

For a platform other than the one you are building on - including **32-bit
Windows**, whose profile never activates by itself - deactivate the host profile
and name the target (see [BUILD.md §3](BUILD.md)):

```pwsh
mvn "-P!windows-x64,windows-x86"   -DskipTests package   # Windows 32-bit
mvn "-P!windows-x64,linux-x64"     -DskipTests package   # Linux x86_64
mvn "-P!windows-x64,macos-aarch64" -DskipTests package   # Apple Silicon
```

Output: `target/phonalyser-<version>-<platform>.jar` - where `<platform>` is
`windows`, `windows-x86`, `linux` or `macos`, so several OS builds can sit in
one release folder - plus
a sibling `target/i18n/` folder containing the translation `.properties`
files (kept outside the JAR so users can add new languages without
rebuilding - see §6).  Run with:

```
java -Djava.library.path=lib/<os> -jar target/phonalyser-<version>-<platform>.jar
```

`I18n` will auto-discover `i18n/` next to the JAR on disk.  To point at
a different folder, pass `-Di18n.dir=<path>`.  If neither is found the
app still starts in English (the default `messages.properties` stays
inside the JAR as a safety net - only the locale variants are external).

### Native installer (jpackage)

Nothing to stage by hand - one command does it all:

```
mvn -DskipTests package                       # installer for the host OS
mvn -DskipTests package "-Djpackage.type=APP_IMAGE"   # portable folder, no installer
```

The `package` phase builds the fat JAR, then copies the JAR, the external
`i18n/` and `help/` folders and the OS natives into `target/jpackage-input/`,
and only then runs jpackage against that directory.  An earlier version of this
page told you to `Copy-Item lib/windows/* target/` and call
`mvn jpackage:jpackage` on its own - both are wrong now: the goal reads
`target/jpackage-input`, not `target/`, so a bare goal invocation runs against
an unpopulated directory and the manual copy lands where nothing looks.

The result lands in `target/installer/`.  At runtime the bundled JRE launches
with `-Djava.library.path=$APPDIR` (`$APPDIR/lib/windows` on Windows, where the
natives are staged), so they are found automatically.

> On Windows the default type is `EXE`, which makes jpackage shell out to the
> WiX toolset.  The project does not use WiX: the release path builds
> `APP_IMAGE` and wraps it into an MSIX separately (§4b), and the `make-*`
> scripts pass `APP_IMAGE` for exactly this reason.

## 4. CI/CD

[.github/workflows/release.yml](.github/workflows/release.yml) builds the
installers on every tag push (`v*` or `[0-9]*`).  The runners are
`windows-latest` (x64), `ubuntu-latest` (x64), and - because jpackage bundles
a JRE matching the build machine's CPU - **two** macOS runners:
`macos-13` (Intel x86_64) and `macos-14` (Apple Silicon arm64).  An
arm64-only DMG is rejected by Intel Macs (*"...is not supported on this Mac."*),
so both are built natively and shipped; each macOS DMG is tagged with its arch
(`Phonalyser-<ver>-x64.dmg` / `Phonalyser-<ver>-arm64.dmg`).  The natives the
Windows and macOS jobs need are committed under `lib/` (§2), so the runners just
check out the repository - there is nothing to restore from an artifact store.

A draft GitHub release is created when the matrix finishes, with the EXE,
DEB, both DMGs and the platform fat JARs attached.

## 4b. Microsoft Store (MSIX)

jpackage cannot emit MSIX, so the Store package is built by wrapping the
jpackage **app-image** (a full-trust Win32 app) with `makeappx`. The big win:
**the Microsoft Store signs the MSIX for free at publish time**, so no
per-developer code-signing certificate is needed.

The release workflow has a **guarded** `Build MSIX` step (Windows job) that is
skipped unless configured, so it never affects the normal `.exe` / `.jar` build.
To enable it:

1. **Reserve the app** in **Partner Center** (Microsoft Store) -> that gives you
   the package **Identity Name** and **Publisher** (`CN=...`).
2. Add the Store **logo PNGs** under
   [src/main/packaging/msix/assets/](src/main/packaging/msix/assets/) (sizes listed in its
   `README.txt`).
3. Add repository **variables** (Settings -> Secrets and variables -> Actions ->
   *Variables*):

   | Variable | Value |
   |----------|-------|
   | `MSIX_IDENTITY_NAME` | Partner Center *Package/Identity/Name* (e.g. `12345Edgo.Phonalyser`) |
   | `MSIX_PUBLISHER` | Partner Center *Publisher* (`CN=...`) |
   | `MSIX_PUBLISHER_NAME` | your Store publisher display name |

On the next tag, the workflow renders [src/main/packaging/msix/AppxManifest.xml](src/main/packaging/msix/AppxManifest.xml)
(version filled from `project.version` as `major.minor.build.0`), packs
`Phonalyser-<ver>.msix`, and attaches it to the draft release. **Upload that
`.msix` to Partner Center**; the Store signs and distributes it.

Local build (for testing / sideloading) mirrors the CI step:

```bash
mvn -DskipTests package -Djpackage.type=APP_IMAGE          # -> target/installer/Phonalyser/
# copy a filled AppxManifest.xml + Assets\*.png into that folder, then:
makeappx pack /d target/installer/Phonalyser /p Phonalyser-1.0.0.0.msix /o
```

A locally-built MSIX is unsigned, so to *install* it outside the Store you must
sign it with a (self-signed, for testing) certificate and trust that cert. For
real distribution, submit the unsigned MSIX to the Store and let Microsoft sign.

## 5. Audio backends per OS

`org.edgo.audio.measure.enums.AudioBackendType` defines five backends:

| Backend     | Platform              | Notes                                                                                                |
| ----------- | --------------------- | ---------------------------------------------------------------------------------------------------- |
| `WASAPI`    | Windows only          | Default on Windows.  Exclusive-mode capture / shared-fallback; high rates supported.                 |
| `WDMKS`     | Windows only          | Lowest-latency path via PortAudio's WDM-KS host API.  Requires `portaudio_x64.dll`.                  |
| `COREAUDIO` | macOS only            | PortAudio's CoreAudio host API; replaces JavaSound there.                                            |
| `JAVASOUND` | Windows / Linux       | Cross-platform `javax.sound.sampled` route.  Hidden on macOS, where CoreAudio takes its place.       |
| `QA40x`     | any, USB-gated        | A QuantAsylum QA402 / QA403 analyzer driven directly over USB - not a sound-card path.  Offered whenever libusb is present (§2), so it is gated on the library rather than on the OS; on Linux it also needs the udev rule from README. |

The Preferences dialog filters the backend dropdown to only those that
are usable (`AudioBackendType.isAvailable()`), so a Linux / macOS build never
shows WASAPI / WDM-KS, a Windows or Linux build never shows CoreAudio, and
QA40x appears only where libusb loaded.

If the YAML-persisted backend choice is unavailable when the app starts
(e.g. preferences carried over from a Windows machine), the GUI silently
falls back to `JAVASOUND` and rewrites the preferences file.

## 6. Adding a translation without rebuilding

Translation bundles live as plain `messages_<lang>.properties` files in
an external `i18n/` folder next to the application JAR (or at the path
named by the `-Di18n.dir=...` system property - the jpackage launcher
sets this to `$APPDIR/i18n` so the installer payload is self-contained).

To add a new language to an installed copy of the app:

1. Locate the install dir's `app/i18n/` folder
   (`C:\Program Files\Phonalyser\app\i18n\` on Windows,
   `/opt/phonalyser/lib/app/i18n/` on Linux,
   `/Applications/Phonalyser.app/Contents/app/i18n/` on macOS).
2. Copy an existing file (e.g. `messages_en.properties`) to
   `messages_<lang>.properties` - `<lang>` is a BCP-47 tag like `ja`,
   `pt_BR`, etc.
3. Translate the values (keep the keys).
4. Restart the app.  The new language appears in **Tools -> Language**
   automatically - discovery walks the same folder on startup, so no
   menu changes are needed.

The English default (`messages.properties`) lives inside the application
JAR.  When a translated key is missing, Java's ResourceBundle falls back
to it automatically.

## 7. The headless server

The server - the machine the measurement hardware is plugged into, serving it
over the network - ships apart from the workbench: **one fat JAR plus one ZIP
per platform, and no installer anywhere**, not even on macOS where the desktop
build produces a DMG. It needs a Java 17+ runtime on the target machine; no JRE
is bundled.

### Building it

`modules/server-net` carries an **opt-in** `server-dist` profile. It is off by
default because that module is a library the desktop reactor compiles on its way
to `phonalyser-app` - an unconditional assembly would write a 16 MB JAR on every
`mvn package` for nobody.

```pwsh
mvn -DskipTests package -P server-dist -pl modules/server-net -am
```

`-pl ... -am` builds only the server's own subtree (core, the wire format, the
backends) instead of the whole desktop reactor. Output in
`modules/server-net/target/`:

| Artifact | Contents |
| -------- | -------- |
| `phonalyser-server-<version>-<platform>.jar` | the server fat JAR - no SWT, no LWJGL, no help bundle, no locale bundles |
| `phonalyser-server-<version>-<platform>.zip` | that JAR plus everything around it (below) |

`<platform>` is one of `windows-x64`, `windows-x86`, `linux-x64`,
`linux-aarch64`, `macos-x64`, `macos-aarch64` - six bundles, one per row of the
platform-profile table in [BUILD.md §3](BUILD.md).

### What is in a ZIP

The server JAR; the platform's natives under `natives/` (none on Linux); a
launcher; the service scripts; and a `README-server-<os>.txt` naming the ports,
the flags, the log file and the service verbs. The sources live under
[src/main/packaging/](src/main/packaging/) - `windows/` for the `.cmd` files and
the WinSW config template, `unix/` for the two POSIX scripts, which are
single-source for Linux and macOS (same shell, different service manager, so one
file branches on `uname -s`). The assembly descriptors are
`src/main/assembly/server-dist-<platform>.xml`.

Windows additionally carries two executables, neither of which is a bundled JRE:

* **`phonalyser-server-x64.exe`** / **`phonalyser-server-x86.exe`** - a ~430 KB
  [Launch4j](https://launch4j.sourceforge.net/) thin launcher wrapping the JAR,
  built by the `launch4j-maven-plugin` in the `server-dist` profile and named
  from `${server.exeName}`. It sets the same three library properties the `.cmd`
  does, requires a JRE 17+ and shows a dialog with the download page when none
  is found. `runtimeBits` is pinned per architecture - the x86 launcher must
  refuse a 64-bit JVM, because the JAR is architecture-neutral but the DLLs in
  `natives\` are not. **The suffix names the target, not the stub**: Launch4j
  emits a 32-bit PE head in both cases, and the x64 stub goes on to start a
  64-bit JVM. `phonalyser-server.cmd` ships alongside and stays the debuggable
  path.
* **`phonalyser-server-service.exe`** - [WinSW](https://github.com/winsw/winsw)
  v2.12.0 (MIT), vendored under `lib/windows/` per architecture and renamed by
  the assembly, since WinSW resolves its config from its own base name. These
  are the **self-contained** `WinSW-x64.exe` / `WinSW-x86.exe` assets, ~18 MB
  each because they carry their own .NET runtime - deliberately, so a bench
  machine needs nothing installed to host the service. (The ~400 KB
  `WinSW.NET4.exe` / `WinSW.NET461.exe` assets would need .NET Framework on the
  target instead.) That one file is why a Windows bundle unpacks to about 32 MB
  where Linux and macOS are about 13 MB.

### Natives, and why they are staged beside the JAR

The server JAR's **bytes are platform-independent**: nothing loads a native at
class-load time and all six backends probe lazily, so one JAR carries every
backend on every OS. What differs per platform is what sits next to it, because
neither library is loaded from the class path - PortAudio resolves through JNA
from `jna.library.path` / `java.library.path`, and libusb honours
`-Dlibusb.path`. The launchers point all three at the bundle's `natives/`.

* **Windows** - `portaudio_x64.dll`, `libusb-1.0_x64.dll` and
  `csjsound_amd64.dll` (32-bit: `portaudio.dll`, `libusb-1.0_x86.dll`,
  `csjsound_x86.dll`), from the module resources of §2, not from `lib/windows`.
  For the first two this is belt-and-braces - they are JNA libraries, and JNA
  falls back to the `win32-*` resources inside the JAR, so a deleted `natives/`
  still works. **`csjsound` is different and load-bearing**: it is loaded by
  `System.loadLibrary` through `CsjsoundNativePath`, which reads
  `java.library.path` and has no class-path fallback. A bare `java -jar` works
  because the Windows JVM appends `.` to that path, so the DLL can be staged
  into the working directory; the Launch4j launcher *sets* `java.library.path`
  explicitly, which replaces that default and removes `.`. Without the copy in
  `natives/` the packaged server silently loses its `EXCL:` mixers and drops to
  shared-mode JavaSound - the same regression the bare JAR already fixed - and
  writes a stray ~900 KB DLL into the bundle root on every start.
* **macOS** - `libportaudio.dylib` + `libusb-1.0.dylib` from `lib/macos-x64` /
  `lib/macos-arm64`. **Load-bearing**: no module carries `darwin/*` JNA
  resources, so a macOS bundle without them finds no CoreAudio devices at all.
* **Linux** - none, for the reason in §2.

### Cross-building

Every bundle except the two Windows ones can be produced from any machine, since
what varies is a file copy rather than a compiler target:

```pwsh
mvn -DskipTests package -P "server-dist,linux-aarch64,!linux-x64"  -pl modules/server-net -am
mvn -DskipTests package -P "server-dist,windows-x86,!windows-x64"  -pl modules/server-net -am
```

The same `-P!<host>,<target>` rule as [BUILD.md §3](BUILD.md) applies - the host
profile must be switched off, and the `!` must be quoted.

### Running it, and running it as a service

Unpack the ZIP anywhere and run the launcher (`phonalyser-server.cmd` /
`./phonalyser-server.sh`); Ctrl-C stops it through the JVM shutdown hook, which
closes the audio lines and parks a QA40x analyzer. To run it unattended:

| OS | Mechanism | Command |
| -- | --------- | ------- |
| Windows | SCM service hosted by WinSW | `install-service.cmd [/user DOMAIN\name]` |
| Linux | systemd system unit, user `phonalyser` | `sudo ./phonalyser-server-service.sh install` then `... activate` |
| macOS | per-user LaunchAgent | `./phonalyser-server-service.sh install` then `... activate` |

A plain Java process cannot be an SCM service by itself - it never calls
`StartServiceCtrlDispatcher`, so `sc create java.exe` is killed at start-up with
error 1053. WinSW supervises it instead, and the reason it is worth vendoring a
binary for is the **stop**: WinSW sends the console a Ctrl+C and waits
`<stoptimeout>`, so the JVM shutdown hook closes the audio lines and parks a
QA40x analyzer. A scheduled task, or any other terminate-the-process mechanism,
leaves the analyzer at full sensitivity with its output line open. macOS uses a
LaunchAgent rather than a LaunchDaemon because CoreAudio is per-session and the
microphone consent prompt comes from the window server, so a system daemon would
enumerate no inputs.

One Windows caveat the READMEs spell out: a service always runs in session 0, so
`LocalSystem` sees the USB analyzer but effectively no sound-card endpoints -
those come from the per-user registry. `install-service.cmd /user <account>`
registers the service under a real account for a sound-card bench; the surest
alternative is not to use a service at all and start the launcher from
`shell:startup` inside the desktop session.

The Linux install also writes the QA40x udev rule - the same
`src/main/jpackage/linux/70-qa40x.rules` the `.deb` ships - and gives the
service account `/var/lib/phonalyser` and `/var/log/phonalyser`.

**Log and data directories.** Every launcher - `.exe`, `.cmd`, `.sh` - and the
macOS LaunchAgent override *nothing*: `AppPaths` decides, so the server writes
the same `phonalyser-server.log` the desktop application does. Only the two
**system services** pin the paths, and only because their account has no usable
profile: the Linux unit sets `-Dapp.data.dir=/var/lib/phonalyser` and
`-Dapp.log.dir=/var/log/phonalyser`, and the Windows service does the same with
`%ProgramData%\Phonalyser` when it runs as `LocalSystem` - but not when
`/user <account>` gave it a real profile to use.
