# Packaging

This document describes how to produce distributable artifacts of
Phonalyser on Windows, Linux and macOS.

Two output formats are supported on every OS:

* **Fat JAR** (`target/phonalyser-<version>-<platform>.jar`) —
  cross-platform-buildable, requires a Java 17+ runtime on the user's machine.
* **Native installer** — `.exe` on Windows, `.deb` on Linux, `.dmg` on macOS.
  Bundles a JRE so the end user doesn't need Java installed.  Must be built
  on the target OS (jpackage can't cross-compile).

## 1. Prerequisites

| Tool      | Version | Notes                                                       |
| --------- | ------- | ----------------------------------------------------------- |
| JDK       | 17+     | Use Temurin or Liberica; jpackage ships with the JDK.       |
| Maven     | 3.8+    | The included `pom.xml` activates the correct OS profile automatically. |

That is the whole list. **The native libraries are committed to the repository**
(§2) — nothing to download, nothing to build.

### Install the vendored flac-library first (once per machine)

Project Nayuki's FLAC library — the decode side of Play-from — is **not on
Maven Central**. It is vendored under `deps/flac-library-java/` and has to be
installed into your local `~/.m2` before the main project can resolve
`io.nayuki:flac-library:1.1.0`:

```bash
mvn -B -ntp -f deps/flac-library-java/pom.xml install
```

```pwsh
# equivalent, from the directory itself
cd deps\flac-library-java
mvn clean install
cd ..\..
```

Every CI job does this before building; skip it on a fresh clone and the build
fails during dependency resolution. See [BUILD.md](BUILD.md) §1.

### Platform profiles

The Maven OS profiles in `pom.xml` — `windows-x64`, `windows-x86`, `linux-x64`,
`linux-aarch64`, `macos-x64`, `macos-aarch64` — select the matching SWT artifact
plus any platform-only dependencies. **All auto-activate on `<os>` except
`windows-x86`**, which has no activation block and must always be requested by
hand, with the host profile deactivated:

```pwsh
mvn "-P!windows-x64,windows-x86" -DskipTests package
```

The same `-P!<host>,<target>` form cross-builds any other platform's fat JAR;
[BUILD.md §3](BUILD.md) has the full matrix and the per-shell quoting rules.
Native **installers** cannot be cross-built (jpackage bundles a JRE matching the
build machine), so those come from the CI matrix — §4.

## 2. Native libraries — already in the repository

**Nothing to fetch or compile.** Every native the app needs is committed under
[lib/](lib/), so a fresh clone builds on any platform straight away:

| Folder | Contents |
| ------ | -------- |
| [lib/windows/](lib/windows/) | `portaudio_x64.dll`, `portaudio_x86.dll` (WDM-KS backend) · `csjsound-provider.jar` + `csjsound_amd64.dll`, `csjsound_x86.dll` (WASAPI-exclusive JavaSound mixers) · `libusb-1.0_x64.dll`, `libusb-1.0_x86.dll` (QA40x backend) |
| [lib/macos-x64/](lib/macos-x64/) · [lib/macos-arm64/](lib/macos-arm64/) | `libportaudio.dylib`, `libusb-1.0.dylib` |

Both Windows architectures are covered, so the `windows-x86` build finds its
32-bit natives just as `windows-x64` finds the 64-bit ones. The Maven profiles
reference them as system-scoped dependencies and jpackage stages them into the
installer; each folder's `README.md` records where its binaries came from.

**Linux ships no natives here.** ALSA reaches the JAVASOUND backend through
`javax.sound.sampled` inside the bundled JDK, and the QA40x backend uses the
distribution's own libusb — which is why the `.deb` declares it as a package
dependency instead. SWT always brings its own widget bindings
(`libswt-*.so` / `.jnilib` / `.dll`) inside its platform Maven artifact.

## 3. Build commands

### Fat JAR (any OS)

```
mvn -DskipTests package
```

For a platform other than the one you are building on — including **32-bit
Windows**, whose profile never activates by itself — deactivate the host profile
and name the target (see [BUILD.md §3](BUILD.md)):

```pwsh
mvn "-P!windows-x64,windows-x86"   -DskipTests package   # Windows 32-bit
mvn "-P!windows-x64,linux-x64"     -DskipTests package   # Linux x86_64
mvn "-P!windows-x64,macos-aarch64" -DskipTests package   # Apple Silicon
```

Output: `target/phonalyser-<version>-<platform>.jar` — where `<platform>` is
`windows`, `windows-x86`, `linux` or `macos`, so several OS builds can sit in
one release folder — plus
a sibling `target/i18n/` folder containing the translation `.properties`
files (kept outside the JAR so users can add new languages without
rebuilding — see §6).  Run with:

```
java -Djava.library.path=lib/<os> -jar target/phonalyser-<version>-<platform>.jar
```

`I18n` will auto-discover `i18n/` next to the JAR on disk.  To point at
a different folder, pass `-Di18n.dir=<path>`.  If neither is found the
app still starts in English (the default `messages.properties` stays
inside the JAR as a safety net — only the locale variants are external).

### Native installer (jpackage)

Nothing to stage by hand — one command does it all:

```
mvn -DskipTests package                       # installer for the host OS
mvn -DskipTests package "-Djpackage.type=APP_IMAGE"   # portable folder, no installer
```

The `package` phase builds the fat JAR, then copies the JAR, the external
`i18n/` and `help/` folders and the OS natives into `target/jpackage-input/`,
and only then runs jpackage against that directory.  An earlier version of this
page told you to `Copy-Item lib/windows/* target/` and call
`mvn jpackage:jpackage` on its own — both are wrong now: the goal reads
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
`windows-latest` (x64), `ubuntu-latest` (x64), and — because jpackage bundles
a JRE matching the build machine's CPU — **two** macOS runners:
`macos-13` (Intel x86_64) and `macos-14` (Apple Silicon arm64).  An
arm64-only DMG is rejected by Intel Macs (*"…is not supported on this Mac."*),
so both are built natively and shipped; each macOS DMG is tagged with its arch
(`Phonalyser-<ver>-x64.dmg` / `Phonalyser-<ver>-arm64.dmg`).  The natives the
Windows and macOS jobs need are committed under `lib/` (§2), so the runners just
check out the repository — there is nothing to restore from an artifact store.

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

1. **Reserve the app** in **Partner Center** (Microsoft Store) → that gives you
   the package **Identity Name** and **Publisher** (`CN=…`).
2. Add the Store **logo PNGs** under
   [src/main/packaging/msix/assets/](src/main/packaging/msix/assets/) (sizes listed in its
   `README.txt`).
3. Add repository **variables** (Settings → Secrets and variables → Actions →
   *Variables*):

   | Variable | Value |
   |----------|-------|
   | `MSIX_IDENTITY_NAME` | Partner Center *Package/Identity/Name* (e.g. `12345Edgo.Phonalyser`) |
   | `MSIX_PUBLISHER` | Partner Center *Publisher* (`CN=…`) |
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
| `QA40x`     | any, USB-gated        | A QuantAsylum QA402 / QA403 analyzer driven directly over USB — not a sound-card path.  Offered whenever libusb is present (§2), so it is gated on the library rather than on the OS; on Linux it also needs the udev rule from README. |

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
named by the `-Di18n.dir=...` system property — the jpackage launcher
sets this to `$APPDIR/i18n` so the installer payload is self-contained).

To add a new language to an installed copy of the app:

1. Locate the install dir's `app/i18n/` folder
   (`C:\Program Files\Phonalyser\app\i18n\` on Windows,
   `/opt/phonalyser/lib/app/i18n/` on Linux,
   `/Applications/Phonalyser.app/Contents/app/i18n/` on macOS).
2. Copy an existing file (e.g. `messages_en.properties`) to
   `messages_<lang>.properties` — `<lang>` is a BCP-47 tag like `ja`,
   `pt_BR`, etc.
3. Translate the values (keep the keys).
4. Restart the app.  The new language appears in **Tools → Language**
   automatically — discovery walks the same folder on startup, so no
   menu changes are needed.

The English default (`messages.properties`) lives inside the application
JAR.  When a translated key is missing, Java's ResourceBundle falls back
to it automatically.
