# Phonalyser FAQ

Answers to questions that reach the project repeatedly. The in-app help
carries the same FAQ (Help -> FAQ) in every UI language, with a search box.

Questions:

- [The scope and FFT report frequent "time discontinuity" events - on every
  backend](#the-scope-and-fft-report-frequent-time-discontinuity-events---on-every-backend)
- [My interface offers only one sample rate
  everywhere](#my-interface-offers-only-one-sample-rate-everywhere)
- [The capture will not open - the device is in
  use](#the-capture-will-not-open---the-device-is-in-use)
- [Why does measuring change my hardware volume (Linux /
  macOS)](#why-does-measuring-change-my-hardware-volume-linux--macos)
- [What should I attach to a bug
  report](#what-should-i-attach-to-a-bug-report)
- [Does the Phonalyser server need
  authentication](#does-the-phonalyser-server-need-authentication)

## The scope and FFT report frequent "time discontinuity" events - on every backend

**Symptoms.** The FFT discards windows with "Found signal discontinuity -
re-synced", the scope's glitch trigger fires, the capture rate drops - and it
happens with WASAPI, WDM-KS and JavaSound alike, while the analog output,
checked with an external oscilloscope, is perfectly continuous.

**What it is.** Genuine sample loss in the OS/USB stack below the
application. Phonalyser's capture path cannot create gaps by design - the
ring buffer accepts every delivered sample and each consumer keeps its own
overrun-protected cursor - and both detectors (the scope's glitch trigger
and the FFT's time gate are the same detector class) fire on the signal
itself, so what they report is a real splice in the stream. The same loss is
known to affect other measurement software on the same machines.

**Typical cause.** An overloaded or degraded USB host controller: several
high-traffic devices sharing one controller, a hub shared with disks or
cameras, or controller/driver state that has degraded since boot - a reboot
often clears the problem for a while before it returns.

**Remedies.**
- Connect the audio interface to a dedicated USB controller - its own PCIe
  USB card, or at least a port group no other high-traffic device shares.
- Avoid hubs, especially hubs shared with storage, cameras or network
  adapters.
- A reboot resets the controller state and buys clean time; if the problem
  creeps back, the controller load is the thing to fix.

**Why it looks worse at high frequencies.** A splice of n lost samples steps
the waveform by roughly A * 2*pi*f/fs * n - proportional to the signal's
derivative. The same loss rate that is barely visible on a 2 kHz tone fires
the detectors constantly on a 5 kHz tone at the same amplitude. When
comparing before/after a hardware change, compare at the same tone
frequency.

## My interface offers only one sample rate everywhere

**Symptoms.** The device offers a single sample rate - in this application
and in every other one - and the rate list cannot be changed from the
operating system's sound settings either.

**What it is.** Some interfaces do not take their clock from the host at
all: the rate is selected in the manufacturer's own control utility, and the
driver then presents that one rate to the system. Audient-class interfaces
work this way. Nothing above the driver can override it - the operating
system and every application can only use the rate the utility has selected.

**Remedy.** Open the manufacturer's control utility, set the rate there,
then rescan devices in Preferences so the new rate is picked up.

## The capture will not open - the device is in use

**Symptoms.** Starting a capture fails and the device is reported as
unavailable or already in use.

**What it is.** An exclusive-mode conflict. The measurement paths take the
device exclusively, so exactly one application may hold it at a time - and
the holder is not always an obvious one: another audio application, or the
system's own sound panel while it is showing a level meter for that device.
The application itself never holds a line across captures: a device it is
not measuring with is released, so a previous measurement of its own is not
what is blocking the device.

**Remedies.**
- Close the application that holds the device, and the sound panel if it is
  open on that device's page.
- Check the device's exclusive-mode allowances in the system sound settings -
  a device that forbids exclusive use cannot be measured bit-exactly.
- Retry.

## Why does measuring change my hardware volume (Linux / macOS)

**What happens.** When a measurement starts, the card's hardware volume
controls are set to unity gain, and they are put back to their previous
positions when it ends.

**Why.** A measurement states levels in volts and in dBV, which is only
meaningful when digital full scale maps to a known analog level. A hardware
volume anywhere in the path scales that mapping without appearing in the
data, so a figure measured at one slider position would not match the same
measurement made at another. Pinning the controls to unity gain removes that
unknown, and restoring them afterwards leaves the card as it was found.

**The broad case.** The controls are matched by port name. On cards whose
controls match no port name, every volume control of the card is pinned -
and every one of them is restored afterwards.

## What should I attach to a bug report

**The device scanner's report.** The project ships a standalone diagnostic
beside the application: a self-contained jar, one jar for every operating
system, attached to each release. Run it from a terminal:

    java -jar device-scanner-<version>.jar

**What it does.** It enumerates every audio backend present on the machine
cache-free - it builds fresh device managers, so every figure in the report
was probed by that run rather than read from a cache - and writes a
human-readable `device-scan-<stamp>.txt` in the working directory, listing
every device with its supported sample rates and bit depths. No stream is
opened.

**Attach that file.** It answers, in one place, which backends the machine
has, which devices each of them sees, and what formats those devices really
accept.

## Does the Phonalyser server need authentication

**No, and that is by design.** The server is a bench instrument for a
trusted network, which is what the server documentation states as well. It
carries no user accounts, no passwords and no tokens.

**What that means in practice.** Anyone who can reach the server on the
network can use the bench: list its devices, drive its generator and take
its measurements.

**How to run it.** Keep it on the trusted segment it was meant for, and do
not expose it beyond that - no port forwarding to the internet, and no
untrusted network. Where the reach has to be wider, put the access control
in front of it, in the network rather than in the instrument.
