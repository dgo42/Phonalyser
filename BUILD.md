# To build a cross-platform fat JAR from Windows

The auto-activation on `<os>` means a plain `mvn package` on Windows always
picks `windows-x64`.  To force a different profile, deactivate Windows +
activate the target:

```pwsh
# Build the Windows x86_64 fat JAR
mvn "-Pwindows-x64" -DskipTests package

# Build the Linux x86_64 fat JAR while sitting on Windows
mvn "-P!windows-x64,linux-x64" -DskipTests package

# Linux ARM
mvn "-P!windows-x64,linux-aarch64" -DskipTests package

# macOS Apple Silicon
mvn "-P!windows-x64,macos-aarch64" -DskipTests package
```

The quotes are needed because PowerShell would otherwise interpret `!` as a
history-expansion.  Copy the resulting
`target/phonalyser-*-<platform>.jar` plus `target/i18n/` to the target
machine and run with `java -jar ...`.

## Practical workflow

- **Local development / quick distribution**: build all four fat JARs on
  your Windows box — that covers anyone with a JRE installed on
  Win / Linux / macOS.
- **End-user installers (EXE/DEB/DMG)**: push a `v*` tag and let the GitHub
  Actions matrix produce them.  You don't need a Mac or Linux box yourself.

# App-image (portable executable folder)

`jpackage` already supports producing a runnable folder instead of a full
installer — one flag override:

```pwsh
mvn package "-Djpackage.type=app_image"
```

(`app_image` with underscore — the panteleyev jpackage plugin uses an
`ImageType` enum whose constants are `APP_IMAGE`, `MSI`, `DEB`, `DMG` …
not the `app-image` form the raw `jpackage` CLI accepts.)

Output lands in `target/installer/Phonalyser/` and contains:

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

Double-clicking `Phonalyser.exe` launches the app.  The folder is fully
self-contained — copy it anywhere, no installer / Java install needed.
ZIP it up and you have a portable distribution.

`-Djpackage.type=app_image` overrides whatever the active OS profile set
`${jpackage.type}` to (msi / deb / dmg), so the same command works on
every OS — the launcher file is named `Phonalyser` on Linux,
`Phonalyser.app` on macOS, `Phonalyser.exe` on Windows.

# Generate documentation PDF (Markdown → PDF)

Selected docs (currently `docs/ALGORITHMS.md`) can be rendered to PDF — with the
internal `§`/anchor links kept clickable and the maths glyphs embedded — via the
opt-in `pdf` profile:

```pwsh
mvn -Ppdf process-classes
```

Output: `target/ALGORITHMS.pdf`.

How it works and what to know:

- **Pure Java, no external tools.** flexmark renders Markdown → HTML and Open
  HTML to PDF renders HTML → PDF. No `pandoc` and no LaTeX — only Maven
  dependencies (resolved on first run). The DejaVu fonts that cover the maths
  glyphs (`θ Δ √ ⁻ᴺ µ …`) are vendored under `pdf-tool/fonts/` and embedded.
- **Only the designated files are converted** — the file list is the
  `<argument>` lines of the `md-to-pdf` execution in the `pdf` profile, *not* a
  glob of every `*.md`. To convert another document, add one more `<argument>`
  line there pointing at it.
- **Internal links are validated.** Every `[…](#anchor)` is checked against the
  generated heading ids; a dangling link **fails the build** rather than
  producing a PDF with dead links.
- **Decoupled from the app build.** The profile compiles only the converter
  (`pdf-tool/java`), bound to `process-classes` (before `package`), so it neither
  builds the app/installer nor requires the application sources to compile — you
  can regenerate the PDF while the app itself is mid-refactor. The profile is
  fully self-contained, so a normal `mvn package` is completely unaffected.
