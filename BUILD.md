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
- **End-user installers (MSI/DEB/DMG)**: push a `v*` tag and let the GitHub
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
