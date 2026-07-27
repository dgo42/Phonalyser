# Building Phonalyser

Everything here is plain Maven — no external toolchain. For producing
distributable installers see [PACKAGING.md](PACKAGING.md).

## 1. One-time setup on a fresh machine

| Tool  | Version | Notes |
|-------|---------|-------|
| JDK   | 17+     | Temurin or Liberica; `jpackage` ships with it. |
| Maven | 3.8+    | Picks the right OS profile automatically (§3). |

### The vendored flac-library

Project Nayuki's FLAC library (the **decode** side of Play-from) is **not on
Maven Central**, so it is vendored in this repository as
[modules/flac-library-java/](modules/flac-library-java/). It is an ordinary
module of the build, listed in [modules/pom.xml](modules/pom.xml), so the
reactor compiles it before anything that depends on it — **no separate install
step, on any platform**. It keeps its own standalone POM rather than inheriting
`phonalyser-parent`, so this project's checkstyle and plugin policy are not
applied to third-party source.

> This is the **only** setup step. The native libraries — PortAudio, libusb and
> the csjsound WASAPI mixers, for both Windows architectures and both macOS
> ones — are committed under [lib/](lib/); there is nothing to download or
> compile. See [PACKAGING.md §2](PACKAGING.md).

## 2. Build

```pwsh
mvn -DskipTests package     # fat JAR for the machine you are sitting on
mvn package                 # same, running the unit tests first
```

Output: `target/phonalyser-<version>-<platform>.jar` plus a sibling
`target/i18n/` folder. Run it with `java -jar target/phonalyser-*.jar`.

## 3. Platform profiles

`pom.xml` carries one profile per target platform. Each selects that platform's
SWT artifact and native dependencies, and sets the installer type jpackage
produces:

| Profile | Activates on | Installer built by jpackage |
|---------|--------------|-----------------------------|
| `windows-x64` | Windows + amd64 | `.exe` |
| `windows-x86` | **never automatically** | **none — fat JAR only** |
| `linux-x64` | Linux + amd64 | `.deb` |
| `linux-aarch64` | Linux + aarch64 | `.deb` |
| `macos-x64` | macOS + x86_64 | `.dmg` |
| `macos-aarch64` | macOS + aarch64 | `.dmg` |

**jpackage runs for every target except `windows-x86`**, whose profile pins
`<skip>true</skip>` on the jpackage plugin — 32-bit Windows ships as a fat JAR
and nothing else.

Each installer is built **on its own OS**: jpackage bundles a JRE for the
machine it runs on and cannot emit a foreign installer type. That is why the
release workflow uses a runner per platform (§4).

### Deactivating the host profile

Every profile except `windows-x86` auto-activates on `<os>`, so asking for a
different one is not enough — you must switch the host's off as well, or Maven
activates both and pulls two conflicting SWT artifacts. The `!` prefix does it:

```pwsh
# Windows x86_64 — the host profile, nothing to override
mvn -DskipTests package

# Windows 32-bit — MUST deactivate the auto-activated x64 profile.
# Produces the fat JAR only; the profile skips jpackage.
mvn "-P!windows-x64,windows-x86" -DskipTests package
```

The profile you deactivate is whichever one **your build machine**
auto-activates:

| Building on | Auto-activated profile | Deactivate with |
|-------------|------------------------|-----------------|
| Windows x86_64 | `windows-x64` | `-P!windows-x64,<target>` |
| Linux x86_64 | `linux-x64` | `-P!linux-x64,<target>` |
| Linux ARM64 | `linux-aarch64` | `-P!linux-aarch64,<target>` |
| macOS Intel | `macos-x64` | `-P!macos-x64,<target>` |
| macOS Apple Silicon | `macos-aarch64` | `-P!macos-aarch64,<target>` |

Only one platform profile ever auto-activates, since each activation block
matches a single OS-family/arch pair — one `!` is always enough. On Windows a
second profile, `windows-installer-extras`, also activates; it only adds
jpackage installer options (the stable upgrade GUID and the shortcut prompts).

> Naming a target whose installer your host cannot build — e.g. `linux-x64`
> from Windows — builds the fat JAR correctly and then stops at jpackage with
> *"Invalid or unsupported type: [deb]"*. Build each platform on its own OS, or
> let the CI matrix do it (§4).

### Quoting the `!`

The quotes are **required**, for a different reason per shell:

- **PowerShell** — bare `!` is history expansion.
- **bash / zsh** — same; single quotes are safest (`'-P!linux-x64,windows-x64'`).
- **cmd.exe** — only special with delayed expansion enabled, but quoting is
  harmless and keeps one command working everywhere.

### 32-bit Windows

The x86 JAR is a genuinely separate artifact: 32-bit SWT and 32-bit natives.
Verify you built the right one before shipping — the two look identical from the
filename, but a JAR carrying 64-bit SWT dies on a 32-bit JVM with an
`UnsatisfiedLinkError`. The SWT native inside tells them apart:

```
phonalyser-<ver>-windows.jar      swt-win32-4965r11.dll      64-bit
phonalyser-<ver>-windows-x86.jar  swt-win32-4919.dll         32-bit
```

Run it on a 32-bit JVM with `-Xmx1200m`; see [README.md](README.md) for why that
ceiling matters.

## 4. Cross-platform fat JARs from one machine

Building all platform JARs on your own box covers anyone who already has a JRE.
Native **installers** cannot be cross-built — jpackage bundles a JRE matching
the build machine — so push a `v*` tag and let the GitHub Actions matrix
produce the EXE / DEB / DMGs. See [PACKAGING.md §4](PACKAGING.md).

## 5. App-image (portable executable folder)

`jpackage` can emit a runnable folder instead of an installer — one flag:

```pwsh
mvn package "-Djpackage.type=APP_IMAGE"
```

(`APP_IMAGE`, uppercase with an underscore: the panteleyev jpackage plugin takes
an `ImageType` **enum constant** — `APP_IMAGE`, `EXE`, `MSI`, `DEB`, `DMG` — and
rejects both the lowercase spelling and the `app-image` form the raw `jpackage`
CLI accepts. Same value in `make-windows.cmd`, `make-linux.sh`, `make-mac.sh`
and both CI workflows.)

Output lands in `target/installer/Phonalyser/`:

```
Phonalyser.exe          # the launcher
runtime/                # bundled JRE (~40 MB)
app/
  phonalyser-...windows.jar
  i18n/
  portaudio_x64.dll
  csjsound-provider.jar
  csjsound_amd64.dll
```

Double-clicking `Phonalyser.exe` launches it. The folder is self-contained —
copy it anywhere, no installer and no Java installation needed. ZIP it and you
have a portable distribution. The flag overrides whatever the active OS profile
set `${jpackage.type}` to, so the same command works on every OS; the launcher
is `Phonalyser` on Linux and `Phonalyser.app` on macOS.

## 6. Documentation PDF (Markdown → PDF)

Selected docs (currently `doc/ALGORITHMS.md`) render to PDF — internal
`§`/anchor links stay clickable and the maths glyphs are embedded — via the
opt-in `pdf` profile:

```pwsh
mvn -Ppdf process-classes
```

Output: `target/ALGORITHMS.pdf`.

- **Pure Java, no external tools.** flexmark renders Markdown → HTML and Open
  HTML to PDF renders HTML → PDF. No `pandoc`, no LaTeX — only Maven
  dependencies. The DejaVu fonts covering the maths glyphs (`θ Δ √ ⁻ᴺ µ …`) are
  vendored under `src/pdf-tool/fonts/` and embedded.
- **Only the designated files are converted** — the list is the `<argument>`
  lines of the `md-to-pdf` execution in the `pdf` profile, *not* a glob of every
  `*.md`. To convert another document, add an `<argument>` line there.
- **Internal links are validated.** Every `[…](#anchor)` is checked against the
  generated heading ids; a dangling link **fails the build** rather than
  producing a PDF with dead links.
- **Decoupled from the app build.** The profile compiles only the converter
  (`src/pdf-tool/java`), bound to `process-classes`, so it neither builds the
  app nor needs the application sources to compile — you can regenerate the PDF
  while the app is mid-refactor. A normal `mvn package` is unaffected.
