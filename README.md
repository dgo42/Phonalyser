# Phonalyser

**Precision audio measurement workbench** — a desktop analyzer for THD/IMD, SNR/ENOB,
frequency response, and oscilloscope-style inspection, built around coherent FFT
averaging and bit-exact playback/capture.

![Phonalyser](docs/screenshots/phonalyser.png)

## Highlights

- **FFT analyzer** — THD, THD+N, IMD, SNR, ENOB, per-harmonic readout, coherent
  averaging, selectable windows, and live calibration (`.frc`).
- **Ultra-low-distortion measurements** — sub-ppm THD with a capable ADC/DAC
  (e.g. E1DA Cosmos), coherent averaging to pull the noise floor down.
- **Oscilloscope** — triggered time-domain view with Vpp/Vrms/period/frequency stats.
- **Frequency response** — log-sweep / multitone with deconvolution and calibration.
- **Signal generator** — sine, dual-tone (IMD), sweep, DDS, with DAC predistortion.
- **Multi-backend audio** — WASAPI & WDM-KS (Windows), CoreAudio (macOS),
  JavaSound (Linux); high sample rates and 16/24/32-bit.

![Ultra-low-distortion FFT](docs/screenshots/fft-ultralow-thd.png)

## Download & run

Each release offers two ways to run Phonalyser:

**Native installers** (recommended) — they bundle their own Java runtime, so
nothing else is needed:

| OS | Installer |
|----|-----------|
| Windows | `Phonalyser-<version>.exe` |
| Linux | `Phonalyser-<version>.deb` |
| macOS (Apple Silicon) | `Phonalyser-<version>-arm64.dmg` |
| macOS (Intel) | `Phonalyser-<version>-x64.dmg` |

**Platform JARs** — run on your own **Java 17+** runtime (no bundled JRE).
Download the JAR for your OS plus the matching launcher script, keep them in the
same folder, and run the script:

| OS | JAR | Launcher | Run guide |
|----|-----|----------|-----------|
| Windows | `phonalyser-<version>-windows.jar` | `Phonalyser-windows.bat` | `README-windows.txt` |
| Linux | `phonalyser-<version>-linux.jar` | `Phonalyser-linux.sh` | `README-linux.txt` |
| macOS | `phonalyser-<version>-macos.jar` (Intel: `…-macos-x64.jar`) | `Phonalyser-macos.sh` | `README-macos.txt` |

The JARs are platform‑specific because each bundles the SWT native for that
OS/arch. macOS additionally needs `-XstartOnFirstThread` (the launcher adds it).
To run without the script: `java -jar phonalyser-<version>-<os>.jar` — on macOS,
`java -XstartOnFirstThread -jar …`.

## Code signing

The installers and JARs are currently distributed **unsigned**, so the OS may
warn on first run — this is expected:

- **Windows** — SmartScreen "unknown publisher": click **More info → Run anyway**.
- **macOS** — Gatekeeper may block it: **right‑click → Open**, or clear the
  quarantine flag: `xattr -dr com.apple.quarantine <file>`.

Signed builds are planned via the **Microsoft Store (MSIX)**, which signs the
package for free at publish time — no per‑developer certificate required. Signing
direct (non‑Store) downloads would need a paid code‑signing certificate, which
only becomes worthwhile once there's a steady download volume (a certificate's
SmartScreen reputation builds with downloads regardless).

## Preferences & log place

Phonalyser never writes inside its install directory (a packaged `.app`,
`Program Files`, or a system package is read-only). Preferences and logs live in
the per-user location for each OS:

| OS | Preferences | Logs |
|----|-------------|------|
| **Windows** | `%APPDATA%\Phonalyser\` | `%APPDATA%\Phonalyser\logs\` |
| **macOS** | `~/Library/Application Support/Phonalyser/` | `~/Library/Application Support/Phonalyser/logs/` |
| **Linux** | `$XDG_CONFIG_HOME/Phonalyser` (or `~/.config/Phonalyser`) | `/var/log/phonalyser` when writable, otherwise `…/Phonalyser/logs` |

Override the base directory with `-Dapp.data.dir=<path>`.

## Translating the UI & help

On first run the packaged app seeds editable copies of the UI strings and the
help pages into the per-user data dir; edit them there and relaunch. The bundled
copies stay as a fallback, so a missing or incomplete file never loses the
built-in text. After an app upgrade, delete the dir to re-seed the new version.

| OS | UI strings (i18n) | Help pages |
|----|-------------------|------------|
| **Windows** | `%APPDATA%\Phonalyser\i18n\` | `%APPDATA%\Phonalyser\help\` |
| **macOS** | `~/Library/Application Support/Phonalyser/i18n/` | `~/Library/Application Support/Phonalyser/help/` |
| **Linux** | `$XDG_CONFIG_HOME/Phonalyser/i18n` | `$XDG_CONFIG_HOME/Phonalyser/help` |

(On Linux `$XDG_CONFIG_HOME` defaults to `~/.config`.) UI strings are
`messages_<lang>.properties` (e.g. `messages_de.properties`); help pages live
under `help/<lang>/`.

## Measurement bench

Wiring the bench so the cabling doesn't inject its own noise — a Faraday cage,
CAT.8 twisted pair with XLR connectors, single-point grounding, and the
resulting induced-noise budget — is described in
[docs/Faradey-Cage-bench.md](docs/Faradey-Cage-bench.md).

## Build & run

Requires JDK 17 and Maven. See [BUILD.md](BUILD.md) for build and run
instructions and [PACKAGING.md](PACKAGING.md) for per-platform packaging
(jpackage).

## License

GNU Affero General Public License v3.0 — see [LICENSE](LICENSE).
