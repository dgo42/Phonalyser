# Phonalyser device scanner

A standalone console diagnostic, attached to each release as a single jar that
runs on every supported OS (all platforms' natives ride inside it), separate
from the application and the server. It enumerates every
audio backend present on the machine (WASAPI, WDM-KS, JavaSound including the
csjsound WASAPI-exclusive mixers, CoreAudio on macOS, and the software
loopback, which is present everywhere) and writes each device
with its supported sample rates and bit depths to a text file for a bug
report.

Cache-free by construction: the tool builds fresh device managers from their
service providers - never through the application's backend singleton - and a
fresh manager starts with empty format caches, so every figure was probed live
by the run that wrote the file. No streams are opened.

## Build

A reactor module: the normal build from the repo root covers it. To build just
this tool and the backends it needs:

    mvn -B -DskipTests package -pl modules/device-scanner -am

## Run

    java -jar device-scanner-<version>.jar

The report lands as `device-scan-<timestamp>.txt` in the working directory and
mirrors to the console. Backend log lines (WARN/ERROR from the probes) appear
on the console only, so the report stays clean scan output.

Notes for reading a report:

- The JavaSound provider roster line answers whether csjsound is registered
  (the `EXCL:` mixers exist only when it is).
- A digital endpoint (SPDIF/ADAT) in exclusive mode typically offers only the
  rate the device is currently clocked at - set the rate in the vendor panel
  and re-run to see it move.
- On such endpoints "32 bits" commonly carries 24 valid bits in a 32-bit
  container; a missing plain 24 is the driver refusing the 3-byte spelling,
  not a missing capability.
