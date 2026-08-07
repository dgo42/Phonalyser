# Roadmap

Planned measurements, backends and architectural work for the versions after the
current one. **Nothing here is implemented** - unlike [ALGORITHMS.md](ALGORITHMS.md),
which documents what the code actually does, this is a list of intent. Items are
**sorted simple -> complex**: the early ones are mostly automation and plotting on
top of engines that already exist, the late ones need new native bindings, new
hardware classes, or cross-cutting changes to how the app is extended.

"Effort" is a rough size, not a schedule:

- **S** - new UI and orchestration over an existing engine; little or no new DSP.
- **M** - genuinely new DSP or a new measurement module, but built from parts
  that are already in the codebase.
- **L** - new native binding, new hardware class, or an architectural change
  that touches many modules.

| # | Item | Effort | Rests on |
|---|---|---|---|
| 1 | [Stepped-level and stepped-frequency sweeps](#1-stepped-level-and-stepped-frequency-sweeps) | S | FFT engine, generator |
| 2 | [Per-harmonic impulse responses from the Farina sweep](#2-per-harmonic-impulse-responses-from-the-farina-sweep) | S | existing ESS deconvolution |
| 3 | [Stereo / interchannel tools](#3-stereo--interchannel-tools) | S | two-channel capture |
| 4 | [Standardized filter bank and loudness metering](#4-standardized-filter-bank-and-loudness-metering) | S-M | capture chain |
| 5 | [Offline file analysis](#5-offline-file-analysis) | M | WAV/FLAC reader, analyzer chain |
| 6 | [Digital-chain integrity](#6-digital-chain-integrity) | M | WASAPI-exclusive / WDM-KS backends |
| 7 | [Cross-spectrum noise measurement](#7-cross-spectrum-noise-measurement) | M | FFT engine, coherent averaging |
| 8 | [Wow and flutter](#8-wow-and-flutter) | M | analytic signal, FLL |
| 9 | [Impedance and output impedance](#9-impedance-and-output-impedance) | M | two-channel sweep |
| 10 | [Vinyl and tape suite](#10-vinyl-and-tape-suite) | M | RIAA/IEC overlays, sweep engine |
| 11 | [Jitter and phase noise](#11-jitter-and-phase-noise) | M-L | wow-and-flutter machinery (#8) |
| 12 | [ASIO driver support](#12-asio-driver-support) | L | backend SPI, native bridge |
| 13 | [NI DAQ support](#13-ni-daq-support) | L | backend SPI, NI-DAQmx |
| 14 | [Scripting and plugin SPI](#14-scripting-and-plugin-spi) | L | whole app surface |

---

## 1. Stepped-level and stepped-frequency sweeps

THD and THD+N versus amplitude - which exposes crossover distortion at the
bottom of the range and clipping at the top - plus gain linearity and
deviation-from-linear, and the 1 % and 0.1 % THD power points. A stepped-frequency
variant gives the same figures across the band.

Pure automation over the existing FFT engine: step the generator, settle, measure,
tabulate, plot. No new DSP at all, which is why it leads the list.

## 2. Per-harmonic impulse responses from the Farina sweep

The log-sweep deconvolution already places the *N*-th harmonic's impulse response
at a known negative delay ahead of the linear IR. Window each of those out
separately and you get **THD versus frequency, per harmonic, from one sweep that
is already being run** - essentially free, and far faster than a stepped-tone
sweep.

The deconvolution exists; the work is the windowing arithmetic (harmonic delays
from the sweep rate), a per-harmonic magnitude plot, and the UI to pick how many
harmonics to extract.

## 3. Stereo / interchannel tools

Goniometer, correlation meter, crosstalk versus frequency, interchannel delay
with sub-sample estimation, and round-trip latency measurement.

Small, well-understood DSP on capture buffers that are already two-channel; most
of the effort is in the displays rather than the maths.

## 4. Standardized filter bank and loudness metering

A/C/Z weighting, ITU-R 468 quasi-peak, psophometric, and the AES17 20 kHz
brickwall for THD+N; plus BS.1770 / EBU R128 LUFS, true-peak (4× oversampled) and
LRA.

Mostly plumbing - filter design and gating rules straight from the standards -
but it is what makes Phonalyser's numbers directly comparable to published
figures, so it is worth doing early.

## 5. Offline file analysis

Load a WAV or FLAC file, run the full analyzer chain over it, and batch a whole
directory. Adds lossy-transcode detection (spectral cutoff), and lets people
analyze captures made on other hardware.

The FLAC reader is already vendored and file playback exists; the work is
decoupling the analyzer chain from a live capture source and adding a batch
runner and report output.

## 6. Digital-chain integrity

Bit-transparency verification - proving the OS path applies no hidden
resampling, volume scaling or DSP - plus digital-black / dither word-length
detection and an SRC quality test (sweep through the converter and look for the
classic aliasing fan).

Phonalyser supports WASAPI exclusive and WDM-KS, so it is the natural tool to
*prove* a path is bit-exact rather than assume it.

## 7. Cross-spectrum noise measurement

Feed one DUT into two independent input channels and average the cross-spectrum:
uncorrelated ADC noise falls as √N while the DUT's own correlated noise stays.
That buys **10-20 dB below the single-channel floor**.

Given the sub-10⁻⁶ % ambitions and two-channel hardware, this is the
**highest-leverage measurement addition on the list**. Display coherence
alongside the result so users can see when it is trustworthy.

## 8. Wow and flutter

Recover the instantaneous frequency of a test tone and measure its modulation -
the classic turntable and tape-transport figure, with the standard weighting
curves and peak/RMS conventions.

New DSP (analytic signal, FM demodulation, weighting), but it also builds the
machinery that #11 then reuses, so its real cost is shared.

## 9. Impedance and output impedance

A two-channel sweep across a reference resistor yields |Z| and phase. That
unlocks loudspeaker impedance, Thiele-Small parameters (added-mass and
added-volume methods), headphone impedance, and amplifier output impedance and
damping factor via the two-load method.

Needs only a trivial jig on the user's side, and it opens up the whole
DIY-loudspeaker audience.

## 10. Vinyl and tape suite

The RIAA/IEC overlays are already there; this extends them into test-record
tooling: azimuth and channel-separation tracks, tracking distortion, eccentricity
from the 3150 Hz tone, and NAB/CCIR/IEC tape EQ curves with bias and level
alignment.

A small niche, but an intensely loyal one, and no free tool covers it well.

## 11. Jitter and phase noise

A J-test (AES17 / Dunn) stimulus plus sideband integration gives ps RMS; an
L(f) dBc/Hz plot comes from the analytic signal. ADEV as a companion tab would
suit oscillator work.

The analytic-signal and FLL machinery arrives with #8, so the incremental cost
here is mostly the plotting and getting the integration limits right.

## 12. ASIO driver support

An ASIO backend alongside the existing WASAPI, WDM-KS, CoreAudio, JavaSound,
QA40x and net backends.

The backend SPI and the per-backend settings mechanism already exist, so the
shape is known - the cost is the native bridge and the Steinberg SDK licensing
terms, not the app-side integration.

## 13. NI DAQ support

National Instruments DAQ hardware as a capture and generation source, via
NI-DAQmx.

Same backend SPI as #12, but a different *class* of hardware: DC-coupled,
arbitrary ranges, no soundcard sample-rate conventions. Expect assumptions in the
shared capture path to need loosening.

## 14. Scripting and plugin SPI

JSR-223 scripting (Groovy or JS) with access to the capture buffers and FFT
results, plus a `ServiceLoader`-based module SPI.

For a one-person project this is the **highest-leverage architectural move on
the list**: it lets other people add the niche measurement they need without anyone
having to write it for them. It is last not because it is least valuable but
because it touches the whole app surface - every internal API it exposes becomes
a compatibility promise.
