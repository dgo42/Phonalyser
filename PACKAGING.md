# Packaging

This document describes how to produce distributable artifacts of
Phonalyser on Windows, Linux and macOS.

Two output formats are supported on every OS:

* **Fat JAR** (`target/phonalyser-<version>-jar-with-dependencies.jar`) —
  cross-platform-buildable, requires a Java 17+ runtime on the user's machine.
* **Native installer** — `.exe` on Windows, `.deb` on Linux, `.dmg` on macOS.
  Bundles a JRE so the end user doesn't need Java installed.  Must be built
  on the target OS (jpackage can't cross-compile).

## 1. Prerequisites

| Tool      | Version | Notes                                                       |
| --------- | ------- | ----------------------------------------------------------- |
| JDK       | 17+     | Use Temurin or Liberica; jpackage ships with the JDK.       |
| Maven     | 3.8+    | The included `pom.xml` activates the correct OS profile automatically. |
| PortAudio | latest  | See the OS-specific section below.                          |

The Maven OS profiles in `pom.xml` (`windows-x64`, `linux-x64`,
`linux-aarch64`, `macos-x64`, `macos-aarch64`) auto-activate based on the
build machine and select the matching SWT artifact + extra Windows-only
dependencies.

## 2. Per-OS native libraries

**Linux and macOS need no native libraries.**  The JAVASOUND backend
talks to ALSA / CoreAudio through `javax.sound.sampled`, which ships
inside the JDK that jpackage bundles.  SWT brings its own native
widget bindings (`libswt-*.so` / `libswt-*.jnilib`) inside the
platform-specific Maven artifact.

**Windows** uses extra natives for the WASAPI exclusive / WDM-KS audio
paths.  Drop into [lib/windows/](lib/windows/):

* `portaudio_x64.dll` — from https://www.portaudio.com/download.html or vcpkg.
  Required for the WDM-KS backend.
* `csjsound-provider.jar` — from https://github.com/pavhofman/csjsound-provider.
  Registers a JavaSound MixerProvider that exposes WASAPI exclusive mode
  so the JAVASOUND backend can open hi-res rates (up to 768 kHz / 32-bit).
* `csjsound_amd64.dll` — built alongside the provider; loaded by the
  JAR above.

The build's `windows-x64` Maven profile references these as system-scoped
dependencies; the Linux/macOS profiles do not pull anything extra.

## 3. Build commands

### Fat JAR (any OS)

```
mvn -DskipTests package
```

Output: `target/phonalyser-<version>-jar-with-dependencies.jar` plus
a sibling `target/i18n/` folder containing the translation `.properties`
files (kept outside the JAR so users can add new languages without
rebuilding — see §6).  Run with:

```
java -Djava.library.path=lib/<os> -jar target/phonalyser-<version>-jar-with-dependencies.jar
```

`I18n` will auto-discover `i18n/` next to the JAR on disk.  To point at
a different folder, pass `-Di18n.dir=<path>`.  If neither is found the
app still starts in English (the default `messages.properties` stays
inside the JAR as a safety net — only the locale variants are external).

### Native installer (jpackage)

Stage the OS natives next to the fat JAR so jpackage picks them up into
`$APPDIR`:

```
# Windows (PowerShell)
Copy-Item lib/windows/* target/ -Force
mvn jpackage:jpackage

# Linux / macOS
cp lib/<os>/* target/
mvn jpackage:jpackage
```

The installer lands in `target/installer/`.  At runtime the bundled JRE
launches with `-Djava.library.path=$APPDIR`, so the staged natives are
found automatically.

## 4. CI/CD

[.github/workflows/release.yml](.github/workflows/release.yml) builds the
installers on every tag push (`v*` or `[0-9]*`).  The runners are
`windows-latest` (x64), `ubuntu-latest` (x64), and — because jpackage bundles
a JRE matching the build machine's CPU — **two** macOS runners:
`macos-13` (Intel x86_64) and `macos-14` (Apple Silicon arm64).  An
arm64-only DMG is rejected by Intel Macs (*"…is not supported on this Mac."*),
so both are built natively and shipped; each macOS DMG is tagged with its arch
(`Phonalyser-<ver>-x64.dmg` / `Phonalyser-<ver>-arm64.dmg`).  The Windows job
expects the binaries to be present in `lib/windows/` (commit them or restore
them from a private release / artifact store).

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

`org.edgo.audio.measure.sound.AudioBackendType` defines three backends:

| Backend     | Platform              | Notes                                                                                                |
| ----------- | --------------------- | ---------------------------------------------------------------------------------------------------- |
| `WASAPI`    | Windows only          | Default on Windows.  Exclusive-mode capture / shared-fallback; high rates supported.                 |
| `WDMKS`     | Windows only          | Lowest-latency path via PortAudio's WDM-KS host API.  Requires `portaudio_x64.dll`.                  |
| `JAVASOUND` | Windows / Linux / macOS | Cross-platform `javax.sound.sampled` route.  Default on Linux / macOS; available everywhere.       |

The Preferences dialog filters the backend dropdown to only those that
are usable on the running OS (`AudioBackendType.isAvailable()`), so a
Linux / macOS build never shows WASAPI / WDM-KS as options.

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
