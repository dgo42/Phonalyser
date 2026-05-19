# Packaging

This document describes how to produce distributable artifacts of
Phonalyser on Windows, Linux and macOS.

Two output formats are supported on every OS:

* **Fat JAR** (`target/phonalyser-<version>-jar-with-dependencies.jar`) —
  cross-platform-buildable, requires a Java 17+ runtime on the user's machine.
* **Native installer** — `.msi` on Windows, `.deb` on Linux, `.dmg` on macOS.
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

[.github/workflows/release.yml](.github/workflows/release.yml) builds all
three OS installers on every tag push (`v*`).  The job matrix uses
`windows-latest`, `ubuntu-latest`, and `macos-latest` runners.  The Linux
and macOS jobs install PortAudio via the package manager; the Windows job
expects the binaries to be present in `lib/windows/` (commit them or
restore them from a private release / artifact store).

A draft GitHub release is created when the matrix finishes, with the MSI,
DEB, DMG and the platform fat JAR attached.

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
