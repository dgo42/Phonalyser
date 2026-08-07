# QA40x USB Protocol Reference

**Purpose.** This document is the basis for a future cross-platform Phonalyser
backend that talks to the QuantAsylum QA40x family of audio analyzers directly
over **libusb-1.0**, without the vendor Windows software. It collects the wire
protocol (USB identity, endpoints, register map, streaming format, on-device
calibration, init/teardown sequencing, and hardware quirks) as **facts learned
by reading open-source projects** - PyQa40x, ASIO401, and
raph29's `virtual-qa40x-rs` - cross-checked against an **RT1062 QA403 simulator**
(the `QA40x` simulator subproject, a sibling repository). Every
statement carries its source. Items
the sources disagree on, or that only one knows, are in §9 CONFLICTS /
UNVERIFIED; the cal-page CRC/format, live status registers, and extended register
map (§4, §6) come from **raph29's public USB capture of a real unit** and are
confirmed on the simulator, but two things still need a **genuine QA403** and are
called out in §9 item 15 (the balanced level/clip convention, and whether real
hardware gaps at 192 k / 1 M-FFT).

## Sources

| Short name | Project | License | Copyright | Role here |
|---|---|---|---|---|
| **PyQa40x** | <https://github.com/QuantAsylum/PyQa40x> | MIT (`doc/licences/PyQa40x-LICENSE.txt`) | © 2026 QuantAsylum | **Authoritative** - the vendor's own Python driver. Only source for on-device calibration + raw↔volts math. Covers QA402/QA403 only. |
| **ASIO401** | <https://github.com/dechamps/ASIO401> | MIT (`doc/licences/ASIO401-LICENSE.txt`) | © 2018 Etienne Dechamps | Windows ASIO driver, developed with QuantAsylum. **Only its QA40x USB transport layer + docs are used here** (all ASIO-SDK material is ignored). Authoritative on the full-duplex streaming discipline and on the QA401. |
| **QA blog** | <https://quantasylum.com/blogs/news/qa401-headless-linux> | vendor web page (referenced, nothing copied) | QuantAsylum | Vendor post on headless QA401 under Linux: QA401 VID/PID, the udev rule, confirms libusb, and states the app configures the QA401's **FPGA** at startup (30-60 s). |
| **RT1062 sim bench** | `QA40x` simulator (sibling repository) | - | - | An RT1062 firmware QA403 simulator; running the QA40x GUI against it **confirmed** the register / cal / telemetry facts end-to-end (valid cal, live telemetry, clean captures) and surfaced the connect **handshake** + streaming behavior (§5/§6). |
| **virtual-qa40x-rs** | <https://github.com/GarageDeveloper/virtual-qa40x-rs> (v0.5.0) | MIT | © 2026 Raphaël Enrici (raph29) | A Rust **USB/IP virtual QA402/QA403** built from a **wire capture of a real unit** (for I2S-connector support). **Authoritative on the registers the GUI reads that PyQa40x/ASIO401 never touch**: firmware version (0x10), capability (0x1B/0x1C), serial (0x1D), I2S (0x0A/0x0B + a 3rd EP pair), firmware trace (0x14/0x15), stream status (0x1E), bootloader (0x0F) - including the connect-probe reads §9 item 14 had left "purpose unknown". Cited `[raph <file>:<line>]` against `crates/vqa40x-core/src/`. |

**Vendor-authority rule.** PyQa40x is QuantAsylum's
own code - a fact whose only source is PyQa40x is treated as
**vendor-authoritative**, not as "unverified single-source". §9 keeps only
genuine conflicts, ASIO401-only empirical values, and hardware-dependent
unknowns; formerly single-source-PyQa40x items are marked RESOLVED under this
rule (numbering kept stable for cross-references).

### What's new: raph29's USB capture + the RT1062 sim bench

New facts from **raph29's `virtual-qa40x-rs`** - a public USB capture of a real
QA402/QA403 (MIT) - cross-checked on the RT1062 sim. A **libusb backend needs
none of them to stream** (they're for GUI-parity / richer UI), but they're now
known:

- **Live status registers the GUI polls (~2 Hz), read via `0x80|reg`, BE**
  (§6): `0x11` = USB bus **voltage in mV**, `0x12` = USB **current in mA**,
  `0x16` = **temperature in 0.1 °C**, `0x13` = ISO current mA (QA402-only)
  [raph analyzer.rs:241-254]. Phonalyser could surface these; harmless to ignore.
- **Extended register map** (§4): the registers the GUI reads that PyQa40x /
  ASIO401 never touch - firmware version `0x10`, capability `0x1B`/`0x1C`,
  serial `0x1D`, I2S `0x0A`/`0x0B`, firmware trace `0x14`/`0x15`, stream status
  `0x1E`, bootloader `0x0F` - are all mapped from raph29's capture. This resolves
  the connect-probe reads (`0x0A/0x10/0x1B/0x1D`) §9 item 14 had left "unknown".
- **Cal-page format is a self-validating page with a CRC** (§6): version/flags
  @0, block markers `0x0023`/`0x0011` + `0xDEAD` sentinels @12/@168, the `'<hf'`
  records at the known offsets, and a **CRC-16/BUYPASS** (poly 0x8005) over bytes
  [0,510) stored big-endian at [510/511] [raph calpage.rs:8-24,79-97].
  **PyQa40x/ASIO401/a libusb backend read only the records and can skip the CRC**
  (as Phonalyser already does).
- **Streaming reality check** (§5): a real-time 192 k loopback with no analog
  buffer between DAC and ADC must NOT emit silence when the host stalls -
  silence >~6800 frames corrupts the host FFT. (Sim-specific, but the lesson -
  the host can stall mid-capture during heavy FFTs - is real.)
- **Still needs a genuine QA403** (§9 item 15): (a) is the −6 dB ADC term a
  balanced-vs-single-ended offset (so the input range clips at the dBV label,
  Vrms) or literal (clips 9 dB low, at Vpp)? (b) does real hardware gap at
  192 k / 1 M-FFT? Community measurement requested.

All three code sources (PyQa40x, ASIO401, raph29's `virtual-qa40x-rs`) are MIT;
copies of the PyQa40x + ASIO401 licenses live in `doc/licences/`, and raph29's is
in [`doc/licences/virtual-qa40x-rs-LICENSE.txt`](licences/virtual-qa40x-rs-LICENSE.txt)
(© 2026 Raphaël Enrici). Phonalyser's runtime
USB library, **libusb-1.0**, is a dependency (not a protocol source) and is
**LGPL-2.1** - a copy lives in
[`doc/licences/libusb-COPYING`](licences/libusb-COPYING). Citations are inline as
`[PyQa40x <path>:<line>]` / `[ASIO401 <path>:<line>]` / `[raph <file>:<line>]`.
The cited `<path>` is a **bare basename** (e.g. `qa403.cpp`, `analyzer.py`);
resolve it against these base directories:

- **ASIO401 sources** live under `tmp/qa40x-refs/ASIO401/src/asio401/ASIO401/`
  (e.g. `[ASIO401 qa403.cpp:8]` -> `.../ASIO401/src/asio401/ASIO401/qa403.cpp`).
  The Markdown docs `CONFIGURATION.md` / `FAQ.md` / `README.md` are at the
  ASIO401 clone root `tmp/qa40x-refs/ASIO401/`.
- **PyQa40x sources** live under `tmp/qa40x-refs/PyQa40x/src/PyQa40x/`
  (e.g. `[PyQa40x analyzer.py:56]` -> `.../PyQa40x/src/PyQa40x/analyzer.py`).
- **raph29 sources** are cited by basename from `virtual-qa40x-rs`
  (`crates/vqa40x-core/src/`)
  (e.g. `[raph analyzer.rs:240]` -> `.../vqa40x-core/src/analyzer.rs`); `options.rs`
  and `calpage.rs` are in the same directory.

Line numbers in the cites are against these files.

The GPL `QA40x-ALSA-plug` project is deliberately **not** referenced.

---

## 2. Device family overview

Three models. Only PyQa40x's authoritative register semantics (regs 5/6/8/9/0xD/
0x19) are for **QA402 + QA403**, which share one identical protocol
[ASIO401 qa403.h:11-13]. The **QA401 is a different protocol** on almost every
axis (different pipes, endianness, register map, ranges) and must never be driven
with QA402/QA403 semantics.

### USB identity

PyQa40x enumerates by **VID/PID** [PyQa40x analyzer.py:56-60]; ASIO401 enumerates
by **Windows device-interface GUID** (SetupAPI), not VID/PID
[ASIO401 asio401.cpp:270-273]. These are two views of the same devices.

| Model | VID | PID | Device-interface GUID (Windows) | Protocol class |
|---|---|---|---|---|
| QA401 | `0x16C0` | `0x4E27` [QA blog] | `{FDA49C5C-7006-4EE9-88B2-A0F806508150}` [ASIO401 asio401.cpp:270] | own (qa401.cpp) |
| QA402 | `0x16C0` | `0x4E37` [PyQa40x analyzer.py:56] | `{2232825C-1E52-447A-83BD-C84DA7C18859}` [ASIO401 asio401.cpp:271] | QA403 (identical) [ASIO401 asio401.cpp:278-281] |
| QA403 | `0x16C0` | `0x4E39` [PyQa40x analyzer.py:58] | `{5512825C-1E52-447A-83BD-C84DA7C18213}` [ASIO401 asio401.cpp:272] | QA403 |

- The `0x16C0` VID is the shared Van Ooijen Technische Informatica (V-USB /
  LibUSB) VID - consistent with a libusb-class device.
- **Single device only:** ASIO401 refuses to run if more than one QA40x (across
  all three GUIDs) is present [ASIO401 asio401.cpp:273].
- **Exclusive access:** the device is opened exclusively - only one application
  at a time. ASIO401 uses `CreateFileA(..., GENERIC_READ|GENERIC_WRITE,
  dwShareMode=0, OPEN_EXISTING, FILE_FLAG_OVERLAPPED)` then `WinUsb_Initialize`
  [ASIO401 winusb.cpp:47-64]; PyQa40x calls `resetDevice()` + `claimInterface(0)`
  [PyQa40x analyzer.py:61-62].

### USB speed

**UNKNOWN / not stated** by either source. Neither reports `bcdUSB` or a
device-descriptor speed. All transfers are **bulk** (§3), which is available at
full/high/super speed. Determine empirically from the descriptor.

### Driver story per OS

| OS | Driver | Notes |
|---|---|---|
| Windows | **WinUSB**, bound by QuantAsylum software (not by ASIO401/PyQa40x) [ASIO401 FAQ.md:46-51]. ASIO401 ships **no INF and no VID/PID literal** - it only opens an already-installed device-interface GUID [ASIO401 devices.cpp:28-66]. | For the **QA401 only**, the QuantAsylum app must run once **per power cycle** to configure endpoints; before that the interface reports 0 endpoints [ASIO401 qa40x.cpp:45-50]. QA402/QA403 work out of the box [ASIO401 FAQ.md:46-51]. |
| Linux | **libusb userspace** (PyQa40x uses `libusb1`, module `usb1`) [PyQa40x analyzer.py:2]; the vendor confirms "a cross-platform USB solution called LibUSB" [QA blog]. | Needs a **udev rule** granting non-root access. Vendor-documented QA401 rule (adapt the PID per model) [QA blog]: `SUBSYSTEM=="usb", ATTRS{idVendor}=="16c0", ATTRS{idProduct}=="4e27", MODE=="0666"` in `/etc/udev/rules.d/51-qa401.rules`. |
| macOS | **libusb**, no kernel driver needed for a vendor bulk device. | Same `libusb1`/`usb1` path as Linux. |

PyQa40x is pure Python-over-libusb1 and thus already cross-platform on
QA402/QA403; ASIO401 is Windows/WinUSB-only but its transport logic is
OS-agnostic once mapped onto libusb async transfers.

**Phonalyser uses libusb-1.0 on every OS - Windows included.** On Windows,
libusb's WinUSB backend drives the QA402/QA403 that QuantAsylum's software has
already bound to WinUSB, so no extra driver install is needed; Phonalyser
**bundles `libusb-1.0` (x64 + x86)** in its Windows package. macOS needs
`libusb-1.0.dylib` (no kernel driver - a vendor bulk device); Linux uses the
system `libusb` plus the udev rule above. libusb is LGPL-2.1
([`doc/licences/libusb-COPYING`](licences/libusb-COPYING)).

---

## 3. Endpoint map

Interface **0**, alternate setting **0**, is the only interface used
[PyQa40x analyzer.py:62; ASIO401 qa40x.cpp:37-64]. All pipes are **BULK**
[ASIO401 FAQ.md:68-71] (the device "uses bulk, not isochronous"; ASIO401 does not
assert pipe type - `winusb.cpp:20-39` only *logs* the WinUSB-reported `PipeType`,
descriptively, with no Bulk check or branch). Direction is encoded in bit
`0x80` of the endpoint address: set = IN, clear = OUT (standard USB)
[ASIO401 winusb.cpp:16].

### QA402 / QA403

| Endpoint addr | Dir | Type | Role |
|---|---|---|---|
| `0x01` | OUT | Bulk | Register writes (and read-request writes) [PyQa40x registers.py:16; ASIO401 qa403.cpp:8] |
| `0x81` | IN | Bulk | Register read replies (PyQa40x only; ASIO401 never reads registers) [PyQa40x registers.py:15] |
| `0x02` | OUT | Bulk | Audio stream **DAC** (playback) [PyQa40x stream.py:23; ASIO401 qa403.cpp:8] |
| `0x82` | IN | Bulk | Audio stream **ADC** (record) [PyQa40x stream.py:22; ASIO401 qa403.cpp:8] |

Note the audio DAC (`0x02` OUT) and ADC (`0x82` IN) **share endpoint number 2**;
registers use endpoint 1.

**Third (front-panel I2S) endpoint pair - `0x03` OUT / `0x83` IN.** raph29's
capture shows the real unit also exposes a bulk EP-3 pair for the front-panel
I2S generator (register `0x0A` starts it, §4): the host streams sample blocks on
`0x03` OUT (one 2048-frame block ≈ every 42.7 ms at 48 kHz), and `0x83` IN is
opened by the app but never observed to carry data [raph analyzer.rs:596-744].
It is **independent of the analyzer's DAC/ADC loopback** and irrelevant to a
Phonalyser capture backend - listed here only for completeness.

### QA401 (different pipes)

ASIO401 constructs the QA401 with exactly **three** pipe IDs
(`registerPipeId=0x02`, `writePipeId=0x04`, `readPipeId=0x88`)
[ASIO401 qa401.cpp:8]:

| Endpoint addr | Dir | Type | Role |
|---|---|---|---|
| `0x02` | OUT | Bulk | Register writes [ASIO401 qa401.cpp:8] |
| `0x04` | OUT | Bulk | Audio stream DAC [ASIO401 qa401.cpp:8] |
| `0x88` | IN | Bulk | Audio stream ADC [ASIO401 qa401.cpp:8] |

There is **no separate register-read reply pipe**: `0x88` is the single
audio-ADC read pipe. ASIO401's register channel is **write-only by construction**
on every model (the `REGISTER` channel only ever builds a Write buffer; there is
no register-read method) [ASIO401 qa40x.cpp:94-99]. So the QA401 register-**read**
framing/endpoint is simply **unknown** - no source reads a QA401 register (§9
item 5), not because a phantom reply pipe exists.

ASIO401 validates at init that the three expected pipe IDs
(`{registerPipeId, writePipeId, readPipeId}`) all appear among the interface's
endpoints via `WinUsb_QueryPipe`; `bNumEndpoints == 0` on the QA401 means the
vendor app was not run [ASIO401 qa40x.cpp:37-64].

**No pipe policies are set.** ASIO401 uses no `RAW_IO`, no `PIPE_TRANSFER_TIMEOUT`
policy, no `AUTO_CLEAR_STALL`, and deliberately **never** issues
`WinUsb_ResetPipe` - resetting the pipe on abort made the *next* session hang on
its first read [ASIO401 qa40x.cpp:87-90]. On stop it uses `WinUsb_AbortPipe`
only. For libusb, mirror this: cancel transfers, do **not** clear-halt/reset the
endpoint on stop.

---

## 4. Register protocol (QA402 / QA403)

### Wire framing - WRITE

A single **5-byte** bulk packet on the register OUT pipe (`0x01`):

```
byte[0] = registerNumber        (8-bit address)
byte[1] = (value >> 24) & 0xFF   ── 32-bit value, BIG-ENDIAN (MSB first)
byte[2] = (value >> 16) & 0xFF
byte[3] = (value >>  8) & 0xFF
byte[4] = (value >>  0) & 0xFF
```

Both sources agree exactly (no conflict):
- PyQa40x: `struct.pack('>BI', reg, val)` then `bulkWrite(ep=0x01, buf, 1000ms)`
  [PyQa40x registers.py:42-43].
- ASIO401: `{ byte(reg), byte(value>>24), byte(value>>16), byte(value>>8),
  byte(value>>0) }` [ASIO401 qa40x.cpp:97].

The register **value** is big-endian even though the audio **samples** are
little-endian (§5) - two independent endiannesses; do not conflate them.

### Wire framing - READ (PyQa40x only)

Register reads are exercised by **exactly one source (PyQa40x)** on **exactly two
models (QA402/QA403)**, and in practice only for the **calibration page** (reg
`0x19`, §6). ASIO401's register channel is **write-only by construction on every
model** - the `QA40x::Channel<REGISTER>` type only ever builds a Write buffer and
there is no register-read method [ASIO401 qa40x.cpp:94-99; qa40x.h:26 (REGISTER
`Pending` takes only a `(reg, value)` write)]. The **QA401 has no known
register-read at all** (cal or otherwise). PyQa40x reads
[PyQa40x registers.py:29-32]:

1. WRITE address `(0x80 | reg)` with value `0` - a normal 5-byte write with the
   **MSB of the address byte set** to request a read.
2. `bulkRead(ep=0x81, 4 bytes, 1000ms)`.
3. Decode the 4 bytes **big-endian**: `struct.unpack('>I', data)`.

### Register map (QA402 / QA403)

| Reg | Name | Access | Encoding | Notes |
|---|---|---|---|---|
| `0x05` | Full-scale **input** range | W | code 0-7 (see below) | attenuator; `<+24 dBV` disengages HW attenuator [ASIO401 CONFIGURATION.md:56-58] |
| `0x06` | Full-scale **output** range | W | code 0-3 (see below) | |
| `0x08` | **Stream / run control** | W | `0x05`=start, `0x00`=stop/reset-to-idle | writing 0 first also recovers from an unclean prior state [ASIO401 qa403.cpp:15] |
| `0x09` | **Sample-rate** | W | code 0-3 (see below) | undocumented by QuantAsylum; from private correspondence [ASIO401 qa403.h:32-37] |
| `0x0D` | **Calibration-page select** | W | write `0x10` to select the cal page | precedes cal reads [PyQa40x control.py:58] |
| `0x19` | **Calibration data read port** | R | 32-bit word per read | read 128× for the 512-byte page [PyQa40x control.py:62-65] |

Sources: reg 5/6 [PyQa40x control.py:28,38], reg 8 [PyQa40x stream.py:40,48;
ASIO401 qa403.cpp:15,28], reg 9 [PyQa40x control.py:48], reg 0xD/0x19
[PyQa40x control.py:58-65]. Registers not in the extended map below are unknown.

#### Extended register map - raph29 USB capture (wire-confirmed)

**Source: raph29's `virtual-qa40x-rs` (MIT), built from a USB capture of a real
unit.** These are the registers the QA40x GUI reads/writes that PyQa40x and
ASIO401 never touch - **a libusb capture backend needs none of them**, but they
are what a device must answer to look real to the vendor app (and they resolve
the connect-probe reads §6/§9-item-14 previously called "unknown"). All reads use
the `0x80|reg` path of §4, big-endian. `[raph ...]` cites
`crates/vqa40x-core/src/`.

| Reg | Name | Access | Value / meaning |
|---|---|---|---|
| `0x00` | **Link keepalive / echo** | R/W | write a nonce, read it back - the app's register-channel health probe; a device MUST echo it [raph analyzer.rs:227,299] |
| `0x0A` | **I2S control** | R/W | front-panel I2S: write `1` = start, `0` = stop; read = running flag. The app writes 0 at connect (safe init). Streams on the EP-3 pair (§3), not the analyzer loopback [raph analyzer.rs:360] |
| `0x0B` | **I2S frame width** | R/W | bit 6 set (`0x40`) = 32-bit frames, else 16-bit; boots `0x40` [raph analyzer.rs:378] |
| `0x0D` | **Page select** | W | `0x10 + 2·page` selects a flash page (page 0 = factory cal, the only one with data; others read zeros); `1` selects the firmware-trace buffer. Any write resets the `0x19`/`0x14` read pointer [raph analyzer.rs:384] |
| `0x0F` | **Bootloader entry** | W | two-magic unlock: write `0xDEADBEEF` then `0xCAFEBABE` -> reboot into the NXP KBOOT DFU bootloader (`1fc9:0022`) for a firmware flash [raph analyzer.rs:391] |
| `0x10` | **Firmware version** | R | build number; **real units report 60** [raph analyzer.rs:240] |
| `0x11` | USB bus voltage | R | mV (see §6 telemetry) [raph analyzer.rs:241] |
| `0x12` | USB bus current | R | mA (see §6) [raph analyzer.rs:242] |
| `0x13` | ISO-supply current | R | mA; **QA402-only** (QA403 shows `---`) [raph analyzer.rs:243] |
| `0x14` | **Firmware-trace read** | R | 4 bytes/word of the trace buffer selected by `0x0D`=1; a healthy unit's trace reads all zeros [raph analyzer.rs:250] |
| `0x15` | **Firmware-trace length** | R | trace byte length; a real unit answered **`0x418`** (empty trace) [raph analyzer.rs:249] |
| `0x16` | temperature | R | 0.1 °C (see §6) [raph analyzer.rs:254] |
| `0x1B` | **Capability word** | R | feature bits the app reads **at connect, before building its sample-rate menu**; real QA402 = `0x40000040` (a real QA403's value is not yet confirmed) [raph analyzer.rs:275; options.rs:76] |
| `0x1C` | **Capability word 2** | R | per-model; QA402 = `0x02A35B03` (wire-confirmed "[MATCH]"), QA403 = `0x7F31BD30` (expected, unconfirmed) [raph options.rs:47] |
| `0x1D` | **Serial number** | R | the serial's 8 hex digits packed as a u32 (e.g. "AB12_CD34" -> `0xAB12CD34`) [raph analyzer.rs:280; options.rs:201] |
| `0x1E` | **Stream status** | R | `0x40` for ~500 ms after a stream stop, `0x00` when idle - the app's stop/restart probe [raph analyzer.rs:281] |

The bootloader (`0x0F`) and I2S (`0x0A`/`0x0B` + EP-3) paths are documented for
completeness; a Phonalyser capture backend leaves them alone. The RT1062 sim
answers all of the **read** registers with these values so the vendor app sees a
fully-formed device.

#### Input full-scale range codes - reg 0x05

`code = dBV_full_scale / 6`. Eight ranges, 6 dB steps.

| dBV FS | 0 | +6 | +12 | +18 | +24 | +30 | +36 | +42 |
|---|---|---|---|---|---|---|---|---|
| code | 0 | 1 | 2 | 3 | 4 | 5 | 6 | 7 |

[PyQa40x control.py:17; ASIO401 qa403.h:16-25]. Values below +24 dBV disengage
the hardware input attenuator [ASIO401 CONFIGURATION.md:56-58].

#### Output full-scale range codes - reg 0x06

Four ranges.

| dBV FS | −12 | −2 | +8 | +18 |
|---|---|---|---|---|
| code | 0 | 1 | 2 | 3 |

[PyQa40x control.py:16; ASIO401 qa403.h:26-31].

#### No hardware power-on default - driver defaults DISAGREE

Neither source documents a hardware power-on default register value; both drivers
**always write** regs 5/6/9 at init [PyQa40x analyzer.py:72-74;
ASIO401 qa403.cpp:15-19]. "Default" therefore means "the driver's chosen
default," and the two drivers pick **opposite ends** - see §9 item 11:

| | Input FS (reg 5) | Output FS (reg 6) | Sample rate (reg 9) |
|---|---|---|---|
| **ASIO401** | **+42 dBV** (code 7) [asio401.cpp:206 `value_or(+42.0)`; CONFIGURATION.md:71] | **−12 dBV** (code 0) [asio401.cpp:226 `value_or(-12.0)`; CONFIGURATION.md:98] | host-selected (no fixed default) |
| **PyQa40x** | **0 dBV** (code 0) [analyzer.py:31] | **+18 dBV** (code 3) [analyzer.py:31] | **48000** via `init()` [analyzer.py:31] / 192000 via bare `AnalyzerParams()` [analyzer_params.py:5] |

An implementer copying "+42 / −12" gets PyQa40x-incompatible behaviour without
knowing PyQa40x picks 0 / +18. Choose your backend's default deliberately.

#### Sample-rate codes - reg 0x09

| Hz | 48000 | 96000 | 192000 | 384000 |
|---|---|---|---|---|
| code | 0 | 1 | 2 | **3 - QA403 only** |

Codes 0-2 are corroborated by both sources [PyQa40x control.py:18; ASIO401
qa403.h:32-37]. Code 3 (384 kHz) is **QA403-only** - the QA402 has no code 3.
PyQa40x cannot express it at all: its map is `samplerate2reg = {48000:0,
96000:1, 192000:2}` [PyQa40x control.py:18], so requesting 384000 raises
`KeyError`. After writing reg 9, insert a settling delay (§8, ABA hazard).

**Code 3 provenance - the ASIO401 "input-only" caveat is superseded.** ASIO401
documents code 3 as INPUT-only and claims that driving the *outputs* at 384 kHz
produces garbage [ASIO401 FAQ.md:104-107; qa403.h:32-37]. A QA40x user on the
QuantAsylum forum describes it instead as a plain rate selection, used in
practice, with no directional caveat at all:

> "384 k at the USB level: the sample rate is register 0x09, written the same
> way, with an index rather than Hz - 0/1/2/3 -> 48k/96k/192k/384k. So
> `09 00 00 00 03` selects 384 ksps (QA403 only)." - raph29,
> <https://forum.quantasylum.com/t/phonalyzer-for-qa40x/2343/6>

That is a field report from someone running the rate, against a driver comment
that QuantAsylum has never confirmed, so **the forum account is the one to
follow**: code 3 is a normal rate on the QA403, both directions. Phonalyser
exposes it accordingly (the backend runs one always-duplex session on this single
shared clock, so an input-only rate would not be representable in any case).

The 384 kHz path is exercised against the hardware simulator; no
QA403 is available for a direct bench check, so should output artefacts ever be
reported at this rate, revisit the ASIO401 claim and gate the generator lane
rather than withdraw the rate.

### Register map (QA401) - DIFFERENT, "black magic"

Do **not** apply the QA402/QA403 map to the QA401. From ASIO401 [qa401.cpp:14-46]:

| Reg | Role | Values |
|---|---|---|
| `0x04` | Run/reset state machine | reset dance `1,0,3,1,3,0`; then `5` = start (written during Reset, no separate Start) |
| `0x05` | Mode bits | `bit0(0x01)`=input HPF **engaged**; `bit1(0x02)`=attenuator **DISENGAGED**; `bit2(0x04)`=**48 kHz** select (clear => 192 kHz) |
| `0x06` | Output-init sequence | `4`, sleep 10 ms, `6`, `0` |
| `0x07` | Ping / keepalive | write `3` once per steady-state buffer to keep the "Link" LED lit |

Note reg 5's inverted polarity for QA401: the attenuator bit is set when
**disengaged**, and the SR bit marks **48 kHz** [ASIO401 qa401.cpp:27-31].

---

## 5. Streaming

### Start / stop (QA402 / QA403)

- **Start:** write reg 8 = `0x05` [PyQa40x stream.py:40; ASIO401 qa403.cpp:28].
- **Stop:** write reg 8 = `0x00` [PyQa40x stream.py:48].

QA401 has **no separate start** - `reg4=5` is written inside Reset and the device
only begins streaming on the first data write [ASIO401 qa401.h:27-28].

### Wire framing (audio samples)

| Property | QA402 / QA403 | QA401 |
|---|---|---|
| Sample type | signed **int32**, 4 bytes - **24-bit real precision: only the 24 MSBs carry signal, the low byte is zero padding, BOTH directions** [bench-confirmed] | signed **int32**, 4 bytes (24-bit real precision) |
| **Endianness** | **LITTLE** [PyQa40x analyzer.py:159,172; ASIO401 qa403.h:40] | **BIG** [ASIO401 qa401.h:17-18] |
| Channels | 2 (stereo), interleaved L,R | 2, interleaved |
| Frame | 2 ch × 4 B = **8 bytes** [ASIO401 asio401.cpp:107-109] | 8 bytes |

Frame byte offset for sample *n*, channel *c* = `(channelCount*n + c) *
sampleSizeInBytes`, i.e. `[ch0 s0][ch1 s0][ch0 s1][ch1 s1]...`
[ASIO401 asio401.cpp:107-109]. Channel 0 = Left, 1 = Right
[ASIO401 asio401.cpp:374-377].

> **QA402/QA403 endianness is LITTLE.** ASIO401's `qa403.h:39` inline comment
> says "32-bit big endian" but the constant on `:40` sets `Endianness::LITTLE`;
> the comment is a stale copy-paste from the QA401 header. The **constant is
> authoritative**, and PyQa40x independently confirms LITTLE (`struct '<i'`,
> `np.int32` host order). See §9. On a big-endian host the QA402/QA403 int32 must
> be read little-endian and the QA401 int32 big-endian.

**Output channels are SWAPPED** on both QA401 and QA403: ASIO out ch0 -> device
out ch1 and vice-versa. PyQa40x swaps L/R DAC before sending
[PyQa40x analyzer.py:115-116]; ASIO401 copies with
`channelOffset=(channelNum+1)%channelCount` [ASIO401 asio401.cpp:116].

### Transfer sizing

- PyQa40x streams DAC in **16384-byte (16 KB)** bulk chunks (= 4096 int32 = 2048
  stereo frames) and issues **16 KB** ADC reads, 1:1 per chunk
  [PyQa40x analyzer.py:143-146; stream.py:58,64].
- ASIO401 advertises buffer sizes **min 64 / preferred 1024 (scaled by
  rate/48000) / max 32768** frames; granularity = device write granularity
  [ASIO401 asio401.cpp:322-333].
- **Write granularity** (below which DAC output garbles): QA402/QA403 = **64
  frames**, QA401 = **32 frames** (both "measured empirically")
  [ASIO401 qa403.h:44; qa401.h:22]. Output buffers must be a multiple of this.
- **Hardware internal queue = 1024 frames** on both models
  [ASIO401 qa403.h:41]. Only ~2.7 ms at 384 kHz; must be refreshed at least every
  ~5 ms at 192 kHz / ~2.5 ms at 384 kHz [ASIO401 FAQ.md:75-78].
- **Exact-length transfer is an ASIO401 design choice, not a proven device
  requirement.** ASIO401 throws if a transfer moves fewer/more bytes than
  requested [ASIO401 winusb.cpp:111-114]. **PyQa40x does the opposite:** its ADC
  read callback appends whatever bytes arrived with no length check
  (`self.received_data.extend(transfer.getBuffer())`) [PyQa40x stream.py:83], and
  `analyzer.py` tolerates a mismatched count by `np.resize()`-ing to the expected
  length [PyQa40x analyzer.py:192-197]. So neither the exact-length invariant nor
  the "short transfer is fatal" recommendation (§10) is corroborated by both
  sources - it is ASIO401-only discipline.
- All PyQa40x bulk calls use a **1000 ms timeout** [PyQa40x registers.py:30,43;
  stream.py:58,64].

### Full-duplex discipline - attributed to **ASIO401** (the authority)

ASIO401 is the reference for continuous, sample-synchronous full duplex; PyQa40x
is a simpler batch model (see below). From ASIO401:

- **Two overlapped transfers in flight per direction** (double-buffered). With
  only one in flight, the sole underrun cushion is the 1024-frame HW queue; with
  two, the kernel/USB host can service the next without a user-space round-trip
  [ASIO401 asio401.cpp:667-678].
- **Priming / start ordering.** Reads are queued **first** but do not complete
  until the hardware actually starts. The hardware start threshold differs by
  model: **QA401 starts on the first frame written**; **QA403 starts only once
  its 1024-frame queue is filled**. Output writes are withheld and accumulated
  during priming until the threshold is crossed
  [ASIO401 asio401.cpp:645-648,652-662].
- **Cold-start size formulas (reproduce these exactly).** With `steadyRead` /
  `steadyWrite` = the steady-state per-buffer read/write size in frames
  [asio401.cpp:650-651]:
  - `outputQueueStartThreshold` = **1** frame (QA401) or **1024** frames (QA403 =
    `hardwareQueueSizeInFrames`) [asio401.cpp:645-648].
  - `initialInputGarbage` = **1088** frames (QA401) or **0** (QA403)
    [asio401.cpp:632-644].
  - `firstWriteSize` = `(duplex ? initialGarbageToSkip : 0) + steadyWrite`, then
    padded so the initial playback queue (`firstWriteSize + steadyWrite`) reaches
    the start threshold: if `threshold > firstWriteSize + steadyWrite`, add
    `threshold − (firstWriteSize + steadyWrite)` [asio401.cpp:652-662]. (This is
    what actually fills the QA403's 1024-frame queue so it starts.)
  - `firstReadSize` = `max(initialInputGarbage + steadyRead, duplex ? firstWriteSize : 0)`
    [asio401.cpp:663]. Both first sizes are ≥ their steady-state sizes
    [asio401.cpp:664-665].
  - In **output-only** mode there is no priming (steady-state from iteration 1)
    but one write is still issued to start the hardware [asio401.cpp:855-866].
- **Output-only mode** (`forceRead=false`, no input): you must still issue **at
  least one write** to start the hardware, or the first read hangs forever; that
  write is left pending until stop [ASIO401 asio401.cpp:859-866].
- **Clock/sync.** In full duplex the **read-completion event is the master
  timing reference** (timestamp taken right after a read completes); the first
  read is sized to consume the initial input garbage plus one steady-state buffer
  and padded up to the first write size for frame alignment. Output-only mode
  uses write backpressure as the clock instead
  [ASIO401 asio401.cpp:663-772].
- **Underrun/overrun = no auto-recovery.** A fatal error in the streaming thread
  (including a short/failed transfer, which throws) is caught and triggers a reset
  request to the host [ASIO401 asio401.cpp:900-907] - issued **indirectly** via
  `requestReset()` -> `PreparedState::RequestReset()`, which sends
  `kAsioResetRequest` (not inline at 900-907) [ASIO401 asio401.cpp:1040-1044].
  Glitches short of a fatal error are otherwise simply **accepted as buffer
  overflow (input) / underrun (output)** - there is no recovery logic
  [ASIO401 FAQ.md:62-66].
- **Thread priority.** ASIO401 runs the streaming thread at MMCSS "Pro Audio"
  (`AvSetMmThreadCharacteristicsA("Pro Audio", ...)` [ASIO401 asio401.cpp:52]) at
  `AVRT_PRIORITY_CRITICAL` (`AvSetMmThreadPriority(..., AVRT_PRIORITY_CRITICAL)`
  [asio401.cpp:58]) with `timeBeginPeriod(1)` [asio401.cpp:36]; these mechanisms
  live in the `Win32HighResolutionTimer` / `AvrtHighPriority` class definitions
  (asio401.cpp:32-68) and are instantiated on the streaming thread at
  [asio401.cpp:729,731]. A Java backend should give its transfer/event thread the
  highest practical priority.

**PyQa40x batch model** (contrast): a worker thread drives libusb
`handleEvents()`; DAC queue caps at **5 in-flight** buffers (blocks the producer
beyond that), ADC queue unbounded; on `TRANSFER_COMPLETED` for the read endpoint
the bytes are appended. This is a **finite-length capture**, not continuous
streaming: queue all DAC chunks, then drain remaining ADC reads
[PyQa40x stream.py:24-27,32-48,68-99].

---

## 6. Calibration data (PyQa40x only)

**ASIO401 does no calibration** and warns its dBV level settings are "NOT
calibrated" and may deviate several dB [ASIO401 CONFIGURATION.md:50-54]. For
absolute-voltage accuracy, use PyQa40x's on-device calibration.

### Location + read procedure

Factory cal is a **512-byte page** on the device
[PyQa40x control.py:51-67]:

1. Write reg `0x0D` = `0x10` (select cal page).
2. Read reg `0x19` exactly **128 times** (512 / 4). Each read yields one 32-bit
   word, packed **little-endian** (`struct.pack('<I', d)`) into the growing byte
   array.
3. Result: a 512-byte `bytearray`.

(Reg 0x19 reads go through the standard register-read path of §4: write
`0x80|0x19` with 0, then bulkRead 4 bytes big-endian; the returned word is then
re-serialized little-endian into the blob.)

**The 512-byte page is a self-validating structure with a CRC [raph
calpage.rs:8-97 - from a public USB capture of a real unit, confirmed on the
RT1062 sim].** The official app rejects a page whose CRC or block markers are
wrong as "invalid calibration data" (non-fatal - it still connects and streams).
Neither PyQa40x nor ASIO401 checks any of this (PyQa40x's `load_calibration()`
only `struct.unpack`s the records [PyQa40x control.py:51-67]), so a **libusb
backend is unaffected** - but the sim reproduces the full structure so the app
shows a valid, calibrated device.

Page layout (host byte order - the wire carries each 4-byte word reversed; all
fields LE except the CRC; 6-byte records = `int16 level + float32 value`, i.e.
PyQa40x's `'<hf'`) [raph calpage.rs:8-24]:

| offset | size | field | value |
|---|---|---|---|
| 0   | 4 | version / flags | **0** |
| 4   | 4 | payload length (16-bit words) | 76 (CRC-covered) |
| 8   | 4 | schema-id constant | 50 (CRC-covered) |
| 12  | 4 | block marker + sentinel | **`23 00` + `AD DE`** = `0x0023`, `0xDEAD` |
| 16  | 4 | AdcRanges | 8 |
| 20  | 4 | DacRanges | 4 |
| 24  | 8×12 | ADC records | L@24+12·i, R@30+12·i; level = 0,6,...,42 dBV |
| 120 | 4×12 | DAC records | L@120+12·j, R@126+12·j; level = −12,−2,8,18 dBV |
| 168 | 4 | end marker + sentinel | **`11 00` + `AD DE`** = `0x0011`, `0xDEAD` |
| 172...509 | 338 | padding | zero (CRC-covered) |
| 510 | 2 | CRC-16, **big-endian** | high @510, low @511 |

(Read as LE u32s the two markers are `0xDEAD0023` @12 and `0xDEAD0011` @168.)
The `level` int16 is the app's cal lookup key, so it must equal the exact range
dBV values. `value` (dB->linear `10^(dB/20)`) is not range-checked on load.

**CRC = CRC-16/BUYPASS** (aka CRC-16/UMTS: width 16, poly **0x8005**, init 0,
refin/refout false, xorout 0) over the **first 510 bytes**, stored **big-endian**
at `[510]`=high, `[511]`=low [raph calpage.rs:79-97]. A page whose last two bytes
don't equal this CRC of the preceding 510 is rejected as invalid calibration.

**Full connect handshake** - the register traffic the app issues right after
opening the interface, in order (observed on the wire, confirmed on the sim):
- reg `0x00` written then read back with **random 32-bit values** (echo probe -
  the app writes a nonce and reads it back to confirm the register channel
  works). A conformant device must echo reg 0x00 [raph analyzer.rs:227,299].
- reg `0x08` = 0 (stop/reset to idle).
- reads of regs `0x10` (firmware version), `0x1D` (serial), `0x1B` (capability),
  and a write to reg `0x0A` = 0 (I2S off, safe init) - **now mapped from raph29's
  capture** (§4 extended register map). Returning 0 for them does not stop the
  app connecting, but the real values are known.
- reg `0x0D` = 0x10 then 128× reg `0x19` - the cal-page read of this section.

**Steady-state poll loop + live status registers.** Once connected, the app
repeats this loop continuously (~500 ms timer): write reg `0x00` = a nonce, read
reg `0x00` back (echo keepalive), then read **regs `0x11`, `0x12`, `0x16`** in
order (all via the normal `0x80|reg` big-endian read path) [raph
analyzer.rs:241-254]. These are live status telemetry the app displays:

| reg | meaning | raw units | app formula / display |
|---|---|---|---|
| `0x11` (17) | USB bus voltage | **millivolts** | `raw/1000` -> `USB Voltage: x.xxxV`; `<4.6 V` triggers a "USB voltage is low" warning |
| `0x12` (18) | USB bus current | **milliamps** | `raw/1000` -> `USB Current: x.xxxA` |
| `0x13` (19) | ISO-supply current | milliamps | **QA402 only**; the QA403 never reads it (shows `ISO Current: ---`) - consistent with the observed 0x11/0x12/0x16-only loop |
| `0x16` (22) | temperature | **tenths of °C** | `raw/10` -> `xx.xC` |

A device returning 0 shows `0 V / 0 A`. A libusb backend ignores all of these.

### Blob layout

Records are `(int16 level, float32 value)` little-endian - `'<hf'`, **6 bytes
each**. Per range: **Left at offset**, **Right at offset+6**. The float `value`
is a dB correction -> linear multiplier `10 ** (value / 20)`. This identical
record layout + conversion is used on **both** paths: ADC
[PyQa40x control.py:85-92] and DAC [PyQa40x control.py:115-119].

**ADC cal offsets** (keyed by input dBV FS; right = left+6; step 12 bytes)
[PyQa40x control.py:80]:

| input dBV | 0 | 6 | 12 | 18 | 24 | 30 | 36 | 42 |
|---|---|---|---|---|---|---|---|---|
| L offset | 24 | 36 | 48 | 60 | 72 | 84 | 96 | 108 |

**DAC cal offsets** (keyed by output dBV FS; right = left+6)
[PyQa40x control.py:107]:

| output dBV | −12 | −2 | 8 | 18 |
|---|---|---|---|---|
| L offset | 120 | 132 | 144 | 156 |

### Raw ↔ volts math (per channel)

Let `MAXINT = 2**31 − 1 = 2147483647` [PyQa40x analyzer.py:140].

**ADC (raw int32 -> volts)** [PyQa40x analyzer.py:179-190]:

```
adc_volts = (raw_int32 / MAXINT)                       # dBFS-normalised to [-1, 1]
          * cal_adc_channel                            # linear cal = 10^(dB/20)
          * 10 ** ((max_input_level - 6) / 20)         # dBFS->dBV; -6 dB: ADC is differential
```

**DAC (volts -> raw int32)** [PyQa40x analyzer.py:121-141]:

```
dac_int32 = round( wave_volts_PEAK                     # PEAK amplitude, NOT RMS (see below)
                 * cal_dac_channel                     # linear cal = 10^(dB/20)
                 * 10 ** ( -(max_output_level + 3)/20) # -3 dB net: dBFS is peak, dBV is RMS
                 * MAXINT )                             # cast to int32
```

**Load-bearing convention: `wave_volts` on the DAC side must be PEAK amplitude.**
PyQa40x generates the DAC wave buffer in **peak volts**: `gen_sine_dbv` converts
dBV->linear via `dbv_to_linear_pk = 10^(dBV/20) * sqrt(2)` (RMS × √2 ≈ +3.01 dB)
[PyQa40x helpers.py:81-93; wave_sine.py:24]. The `+3` term in the exponent
(`-(max_output_level + 3)/20`) closes the dBFS-peak-vs-dBV-RMS gap **only because
the incoming buffer is already peak-scaled by √2**. A Java backend that feeds
RMS-scaled samples into this formula will be **~3 dB off** - it must scale its
generated tone to peak amplitude first, or drop the `+3`.

`max_input_level` / `max_output_level` are the selected full-scale range in dBV
(the same values that map to reg-5 / reg-6 codes). Remember DAC L/R are swapped
before scaling (§5). The `−6` on the ADC side is the differential-ADC factor, not
a peak/RMS term.

### Levels cheat-sheet - the input "FS dBV" is really dBFS (bench-confirmed)

The two "dBV" scales in QA40x are **not the same unit**, which is the classic
source of confusion:

- **Output "dBV" = genuine RMS dBV.** Gen "0 dBV" = 1 Vrms per leg; balanced
  (Out+ − Out−) = 2 Vrms = **6 dBV = 5.65 Vpp**.
- **Input "full-scale dBV" is effectively a dBFS / peak-to-peak reference**, not
  RMS dBV. The "N-dBV" input range clips (0 dBFS) at `10^(N/20)` **Vpp**
  differential - i.e. the label is `N = 20·log₁₀(Vpp_clip)`. Converting to true
  RMS: **RMS dBV at clip = N − 9 dB**, a *constant* 9 dB offset across every
  range = **+3 (peak/RMS, √2)** + **+6 (differential ×2 / the vendor `−6`
  term)** = `20·log₁₀(2√2)`.

| Input FS "dBV" (really dBFS) | 0-dBFS clip | RMS dBV at clip |
|---|---|---|
| 12 | ±2 V / 4 Vpp | +3 dBV |
| 18 | ±4 V / 8 Vpp | +9 dBV |

So a balanced 0-dBV-gen -> 6-dBV-differential signal (2 Vrms, 5.65 Vpp) is clean
on the 18 range (−3 dBFS) but clipped on the 12 range (+3 dBFS). All of this
falls straight out of the vendor ADC formula above; the surprising part (a
"12 dBV" range clipping at +3 dBV RMS) is the only bit still wanting a genuine
QA403 sanity-check (§9 item 15a). The RT1062 sim reproduces this exactly,
including the **+6 dB balanced/differential doubling** (In+ − In− = 2·Out+),
which the vendor `−6` does NOT cancel - a balanced loopback reads generated
**+6 dB** (0 dBV out -> +6 dBV measured), matching the QA doc's −10->−4 example.

---

## 7. Init / teardown - end-to-end sequence

One ordered sequence for **QA402/QA403**, noting where the two sources differ.
QA401 differences are in §8.

### Open -> configure -> calibrate

1. **Enumerate & open.** libusb: open VID `0x16C0` PID `0x4E37` (QA402) else
   `0x4E39` (QA403) [PyQa40x analyzer.py:56-60]. (Windows/WinUSB path opens by
   device-interface GUID instead [ASIO401 asio401.cpp:270-273].) Open
   **exclusive**.
2. **Reset device + claim interface 0.** PyQa40x: `resetDevice()` then
   `claimInterface(0)` [PyQa40x analyzer.py:61-62]. No explicit
   `SetConfiguration` - relies on the default active config.
3. **Validate the interface descriptor + pipes** (ASIO401 does this before any
   I/O; a libusb backend should replicate it or later failures are opaque). Query
   interface 0's descriptor and require `bNumEndpoints != 0`; then `QueryPipe`
   every endpoint and confirm **all three** expected bulk pipe addresses are
   present (`{registerPipeId, writePipeId, readPipeId}`), throwing if any is
   missing [ASIO401 qa40x.cpp:37-64]. On the **QA401** (`requiresApp=true`) a
   **zero-endpoint** interface is the specific signal that the vendor app was not
   run this power cycle [ASIO401 qa40x.cpp:44-50].
4. **Load calibration** (PyQa40x, before ranges): reg `0x0D`=`0x10`, then 128×
   read reg `0x19` [PyQa40x analyzer.py:69; control.py:51-67].
5. **Configure ranges + rate.** Two source orderings:
   - PyQa40x: `set_input`(reg5) -> `set_output`(reg6) -> `set_samplerate`(reg9) +
     **100 ms sleep** [PyQa40x analyzer.py:72-74; control.py:48-49].
   - ASIO401 `Reset()` (recommended clean init): **reg8=0 first** (stop/recover
     from unclean state), then reg5, reg6, reg9, then **50 ms sleep**
     [ASIO401 qa403.cpp:10-22]. The reg8=0-first step matters after an unclean
     stop; the sleep is mandatory to avoid the ABA "skip past zero" hazard (§8).
   - **Recommended:** follow ASIO401's order (reg8=0 -> 5,6,9 -> sleep), it is the
     safer superset.

### Stream (per capture / continuously)

6. **Start:** reg 8 = `0x05` [PyQa40x stream.py:40; ASIO401 qa403.cpp:28].
   - **Batch (PyQa40x):** the exact ordered DAC pipeline is
     [PyQa40x analyzer.py:112-146]: (1) **swap** L/R; (2) multiply each channel by
     `dac_dbfs_adjustment` **and** per-channel `cal` - i.e. **scale before
     interleave**, not after; (3) cast to `float32`; (4) **interleave** L,R into
     one `float32` array; (5) `× MAXINT` and cast to `int32`; (6) little-endian
     `struct.pack('<%di', ...)` into 16 KB chunks. Then start the worker; for each
     16 KB chunk queue a DAC write + a 16 KB ADC read; stop; drain remaining ADC
     reads. (`left_peak = np.max(...)` at analyzer.py:118 is computed but **never
     used** - dead code, not part of the protocol; do not replicate it.)
   - **Continuous (ASIO401):** the §5 full-duplex discipline (2 in flight,
     priming, read-completion clock).
7. **Stop:** set a stop flag, **cancel/abort** in-flight transfers (WinUSB
   `AbortPipe`; libusb `libusb_cancel_transfer`), join the thread, then write reg
   8 = `0x00` [PyQa40x stream.py:42-48; ASIO401 asio401.cpp:588-598]. Do **not**
   reset/clear-halt the pipe (§3).

### Teardown -> close

8. **Safe-state Reset (ASIO401).** Re-engage max attenuation:
   `Reset(input DBV42, output DBVn12, 48 kHz)` for QA403
   [ASIO401 asio401.cpp:948-959]. A Java backend should reset ranges to safest
   (max attenuation) on close.
9. **Release + close.** PyQa40x: `releaseInterface(0)` then `context.close()`
   [PyQa40x analyzer.py:87-99]. PyQa40x registers this via `atexit`
   [PyQa40x analyzer.py:83]; it does **not** stop the stream in cleanup -
   streaming is stopped per-capture.

---

## 8. Quirks & model differences

### General (both / QA402+QA403)

- **Bulk, not isochronous** - no reserved bandwidth; sensitive to bus contention.
  Keep the device on a dedicated USB root [ASIO401 FAQ.md:68-71].
- **Sample-rate register is fragile (ABA hazard).** After writing reg 9, wait
  before touching it again or the hardware "skips past" the zero state. PyQa40x
  sleeps 100 ms, ASIO401 50 ms [PyQa40x control.py:48-49; ASIO401 qa403.cpp:20-22].
- **Input is AC-coupled** in the QA40x hardware itself on all models - DC input
  offsets cannot be measured [ASIO401 FAQ.md:138-140]. **Output is DC-coupled
  end-to-end** - any DC in the sample stream appears at the output
  [ASIO401 FAQ.md:117-124].
- **Sample rate changeable only on reset**, not while streaming. The QA401 "only
  on reset" fact is a QuantAsylum note in a code comment [ASIO401 qa401.cpp:26].
  On **QA402/QA403 a live rate change is not attempted at all**: the device rate
  (reg 9) is only ever written inside `Reset()` before `Start()`
  [ASIO401 qa403.cpp:16-19]. What ASIO401 does when the host asks to change rate
  *while streaming* is a **driver/host concern, not a device reset**: `SetSampleRate`
  issues an ASIO **host** reset request (`preparedState->RequestReset()`)
  [ASIO401 asio401.cpp:413-417], and `RequestReset()` sends `kAsioResetRequest`
  [ASIO401 asio401.cpp:1040-1044] - after which the new rate is applied through the
  normal `Reset()` path on restart.
- **Levels are uncalibrated approximations** unless you apply the PyQa40x
  on-device cal (§6) [ASIO401 CONFIGURATION.md:50-54].
- PyQa40x snaps generated tones to the nearest FFT bin by default
  (`round(f/binRes)*binRes`) [PyQa40x wave_sine.py:73-76], and pads captures with
  `pre_buf + fft_size + post_buf` (default 2048+16384+2048), analyzing only the
  central `fft_size`; it `np.resize()`s ADC data to the expected length if it
  differs - implying returned sample count can differ from what was sent
  [PyQa40x wave.py:20,59-68; analyzer.py:192-197].

### Ranges & rates by model

| | QA401 | QA402 / QA403 |
|---|---|---|
| Sample rates (Hz) | 48000, 192000 [ASIO401 qa401.h:15] | 48000, 96000, 192000, **384000 (input-only)** [ASIO401 asio401.cpp:176-183; FAQ.md:104-107] |
| Input FS (dBV) | +6 (atten disengaged) / +26 (engaged) - ASIO401 default +26 [ASIO401 CONFIGURATION.md:60-62,71] | 0...+42 in 6 dB steps [PyQa40x control.py:17]; **no HW default** - ASIO401 defaults +42, PyQa40x defaults 0 (§9 item 11) |
| Output FS (dBV) | fixed **+5.5** [ASIO401 CONFIGURATION.md:90] | −12 / −2 / +8 / +18 [PyQa40x control.py:16]; **no HW default** - ASIO401 defaults −12, PyQa40x defaults +18 (§9 item 11) |
| Stream endianness | **BIG** int32 | **LITTLE** int32 |
| Register pipes | reg 0x02 / write 0x04 / read 0x88 | reg 0x01 / write 0x02 / read 0x82 |
| Write granularity | 32 frames (align **start** to 64) [ASIO401 qa401.h:22; asio401.cpp:638-641] | 64 frames [ASIO401 qa403.h:44] |
| Control registers | 4 / 5 / 6 / 7 | 5 / 6 / 8 / 9 (+0xD/0x19 cal) |

### QA401-only quirks (do NOT apply to QA402/QA403)

- Requires the **QuantAsylum app once per power cycle** to expose endpoints;
  0 endpoints otherwise [ASIO401 qa40x.cpp:45-50; FAQ.md:46-51]. The vendor's
  headless-Linux post explains why: the app **configures the FPGA inside the
  QA401** at startup (takes 30-60 s) [QA blog] - i.e. the QA401 needs an FPGA
  bitstream load after every power cycle, which no third-party source implements.
  Full standalone QA401 support would mean replicating that load; QA402/QA403
  need nothing (work from cold).
- **Decision: the initial Phonalyser backend targets
  QA402/QA403 only; QA401 is out of scope.** If QA401 is ever added, the FPGA
  init is handled at PROCESS level - detect the 0-endpoint state and prompt the
  user to run their installed QuantAsylum app / QA401H once (optionally
  auto-launching it as a separate process), then attach via libusb. Embedding or
  redistributing `qa401h.dll` / the bitstream is ruled out (proprietary freeware,
  no redistribution rights; CLR-in-JVM linking not worth it for a
  once-per-power-cycle step).
- **Input channels swapped** (QA403 not) [ASIO401 asio401.cpp:822-824].
- **Output polarity inverted** (whole output buffer negated); QA403 not
  [ASIO401 asio401.cpp:794-797].
- **~1088 initial garbage input frames** to skip (64 replayed + ~1000 silence,
  aligned to 64); QA403 = 0 [ASIO401 asio401.cpp:632-644].
- **DC-latch on abrupt stop:** output latches the last sample as constant DC until
  reset/power-off; QA403 unaffected - hence the clean-teardown Reset for QA401
  (HPF + attenuator engaged) [ASIO401 FAQ.md:125-137; asio401.cpp:948-959].
- **reg-7 keepalive ping** (write 3 per steady-state buffer) keeps the Link LED on
  [ASIO401 qa401.cpp:42-46].
- Init is the fixed reg-4 "black magic" dance (§4) - no separate Start
  [ASIO401 qa401.cpp:14-46].

---

## 9. CONFLICTS / UNVERIFIED - items needing hardware verification

(Resolved items keep their numbers - cross-references elsewhere use them.)

1. **[RESOLVED] QA402/QA403 stream endianness = LITTLE.** ASIO401 `qa403.h:39`
   comment says "32-bit **big** endian" but its `qa403.h:40` constant is
   `Endianness::LITTLE`, and the vendor's PyQa40x confirms **LITTLE** (`struct
   '<i'`, host-order `np.int32`) [PyQa40x analyzer.py:159,172] - settled per the
   vendor-authority rule; the ASIO401 comment is stale. (QA401 is BIG
   [ASIO401 qa401.h:18] - out of scope.)

2. **[RESOLVED] VID/PID vs GUID.** All three models' VID/PIDs are now
   vendor-known (QA402/QA403 from PyQa40x [analyzer.py:56-58], QA401 from the
   vendor blog [QA blog]) - and a libusb backend never needs the GUIDs at all;
   they matter only when reading ASIO401's WinUSB code. No open question left.

3. **[RESOLVED] QA401 VID/PID = `0x16C0:0x4E27`.** Neither code source
   gives it (ASIO401 finds the QA401 only by GUID `{FDA49C5C-...}`, PyQa40x doesn't
   support QA401 [ASIO401 asio401.cpp:270; PyQa40x analyzer.py:56-60]), but the
   vendor's headless-Linux post states it outright, including the udev rule with
   `idVendor=="16c0", idProduct=="4e27"` [QA blog]. Vendor-authoritative; still
   confirm by enumeration on real hardware as usual.

4. **[UNKNOWN values, KNOWN fields] USB speed / descriptor details.** Neither
   source hardcodes `bcdUSB`, endpoint `wMaxPacketSize`, `bInterfaceNumber` beyond
   "index 0", or the configuration number - read them from the real descriptor.
   But note ASIO401 **does read** `MaximumPacketSize` and `Interval` **per pipe**
   at runtime via `WinUsb_QueryPipe` and logs them
   [ASIO401 winusb.cpp:29-39; qa40x.cpp:53-60]. The libusb backend gets the same
   fields from the endpoint descriptor (`wMaxPacketSize`, `bInterval`, or
   `libusb_get_max_packet_size`): the values are device-dependent (unknown here),
   but the fields to read are known.

5. **[UNKNOWN] QA401 register-read reply endpoint / read protocol.** No source
   reads a QA401 register at all: ASIO401's register channel is **write-only by
   construction on every model** [ASIO401 qa40x.cpp:94-99] and PyQa40x doesn't
   support the QA401. So the QA401 register-read pipe and framing are simply
   unknown - there is no phantom `0x88` register-read reply pipe (the QA401 has
   only three pipes: reg `0x02` OUT, DAC `0x04` OUT, ADC `0x88` IN
   [ASIO401 qa401.cpp:8]). Register reads in general are a **PyQa40x-only,
   QA402/QA403-only** path, exercised only for the cal page (reg `0x19`, §6).

6. **[INFERRED, possible ASIO401 bug] Right-input polarity inversion not
   device-gated.** ASIO401 negates the **right input** channel unconditionally in
   `PostProcessASIOInputBuffers` (`channelNum==1`), for **all** devices
   [ASIO401 asio401.cpp:262-263] - yet it cross-references QA401 issue #14, and
   the sibling **output** polarity inversion **is** QA401-only
   [ASIO401 asio401.cpp:794-797]. So the code inverts right-input even on QA403,
   which looks unintended (an oversight, not a documented QA403 requirement). A
   Java backend should treat right-input inversion as **QA401-only** unless
   hardware shows QA403 also needs it. Verify.

7. **[RESOLVED] Sample-rate register (reg 9) semantics.** Codes for
   48/96/192 kHz are vendor-authoritative [PyQa40x control.py:18]. **Code 3 =
   384 kHz** is corroborated by a second, independent source - the QuantAsylum
   forum thread quoted in §4 - which pins it as **QA403-only** and, unlike
   ASIO401, attaches **no input-only caveat**; it is a field report from someone
   actually running the rate. ASIO401's "outputs garble" claim
   [qa403.h:32-37; FAQ.md:104-107] is treated as superseded.

   *Phonalyser:* exposes **48/96/192 kHz on both models and 384 kHz on the
   QA403** (`Qa40xProtocol.SAMPLE_RATE_HZ` + `sampleRatesHz(model)`), for both
   lanes - the backend runs one always-duplex session on the single shared reg-9
   clock, so an input-only rate is not representable anyway. Verified against the
   hardware simulator; no QA403 on hand for a direct bench check.

8. **[RESOLVED - vendor-authoritative]** Calibration blob format (512-byte page,
   `'<hf'` records, per-range offsets, raw↔volts formulas, §6) is PyQa40x-only
   but that IS the vendor's own code - settled per the vendor-authority rule.

9. **[RESOLVED delay / OPEN timing constants].** Rate-write settle delay:
   use the vendor's own **100 ms** [PyQa40x control.py:49] (ASIO401's 50 ms
   merely shows the true minimum is lower - irrelevant). Still OPEN: the
   full-duplex timing constants (2 in flight, 1024-frame threshold, 1088-frame
   QA401 skip, granularities) are ASIO401 "measured empirically" values -
   re-verify on hardware.

   *Phonalyser:* keeps the 100 ms settle; for output pacing it uses its own
   bench-tuned **write-debt** model (repaid, not forfeited; `MAX_WRITE_DEBT = 32`)
   with ≥2 reads in flight per direction, rather than adopting ASIO401's empirical
   in-flight constants. The 1024-frame output start threshold is honoured.

10. **[PARTLY MOOT] Output-channel swap + QA401 quirks.** Output L/R swap is
    corroborated by both [PyQa40x analyzer.py:115-116; ASIO401 asio401.cpp:116]
    - settled. The QA401 input-swap, garbage-frame count, DC-latch, and reg-7
    ping are ASIO401-only, but **QA401 is out of scope** (§8 decision).

11. **[CONFLICT: default ranges/rate] No documented HW power-on default; drivers
    disagree.** Neither source documents a hardware default for regs 5/6/9 - both
    always write them at init. The two drivers pick opposite ends:
    **ASIO401** input **+42** dBV / output **−12** dBV [asio401.cpp:206,226;
    CONFIGURATION.md:71,98], rate host-selected; **PyQa40x** input **0** dBV /
    output **+18** dBV / rate **48000** via `init()` (or 192000 via bare
    `AnalyzerParams()`) [PyQa40x analyzer.py:31; analyzer_params.py:5]. A backend
    copying "+42 / −12" is PyQa40x-incompatible. Pick your default deliberately.

12. **[RESOLVED - vendor-authoritative, load-bearing] DAC input is PEAK volts.**
    The DAC formula's `wave_volts` term is **peak** amplitude, not RMS (PyQa40x
    pre-scales by √2: `dbv_to_linear_pk = 10^(dBV/20)·√2`
    [PyQa40x helpers.py:81-93; wave_sine.py:24]) - settled per the
    vendor-authority rule. Feeding RMS-scaled samples is ~3 dB wrong; keep this
    in mind in the Phonalyser generator mapping (§10).

13. **[DESIGN CHOICE, not device-proven] Exact-length transfers.** ASIO401 treats
    a short/over transfer as a fatal error [ASIO401 winusb.cpp:111-114]; PyQa40x
    tolerates it (appends actual bytes [PyQa40x stream.py:83], then `np.resize` to
    expected [analyzer.py:192-197]). So "short transfer is fatal" (§5, §10) is
    ASIO401 discipline, not a proven device requirement - the two sources
    disagree on strictness.

    *Phonalyser:* took the **PyQa40x-tolerant** path - the transport consumes the
    actual transferred length (`LibUsbQa40xTransport`) and never treats a short
    read as fatal.

15. **[OPEN - awaiting real-QA403 measurement] Two
    questions the RT1062 simulator can't answer without genuine hardware:**
    (a) **Balanced level / clip convention - two candidate answers.** Does a
    balanced-out -> differential-in loopback on a real QA403 make the vendor GUI
    read the generated level **+6 dB** (In+ − In− = 2·Out+, a differential
    doubling the vendor's −6 dB ADC term does NOT cancel) or **+0 dB** (the −6
    IS the balanced-vs-single-ended offset, cancelling it)? The two independent
    reverse-engineers split: the **RT1062 sim adds the ×2 (+6)**, so 0 dBV out ->
    +6 dBV in (matching the QA doc's −10 -> −4 example); **raph29's
    `virtual-qa40x-rs` does NOT** (0 dBV out -> 0 dBV in) [raph
    analyzer.rs:479-492]. Both models are only self-consistent with the served
    cal page, not absolute-level measurements of real hardware, so neither
    settles it - only a **genuine QA403** does. Measure: gen 0 dBV, FFT
    balanced->diff, note the measured level and the clip voltage per input range.
    (b) **Gaps at 192 kHz / 1 M-point FFT.** The sim (with pause-on-underrun)
    shows NO gaps at any FFT size. Does a genuine QA403 also stay clean at
    192 k + 1024k FFT on the same host, or does its smaller (1024-frame) HW
    queue gap when the host stalls? This bounds how faithfully the sim's deep
    buffering should model real hardware.

14. **[RESOLVED - raph29 public capture + sim] Cal-page CRC, format,
    extended registers, and live status.** raph29's `virtual-qa40x-rs` (a USB
    capture of a real unit, MIT) documents: the cal page is a self-validating
    structure with block markers `0x0023`/`0x0011` (+`0xDEAD` sentinels) and a
    **CRC-16/BUYPASS** over bytes [0,510) big-endian at [510/511] (full layout in
    §6); the live poll reads USB voltage (0x11, mV), current (0x12, mA),
    temperature (0x16, 0.1 °C) + QA402-only ISO current (0x13); and the connect
    reads now map (§4 extended register map) - `0x10` firmware version, `0x1B`/
    `0x1C` capability, `0x1D` serial, `0x0A`/`0x0B` I2S. A **libusb backend needs
    none of it** (connects/streams without). Confirmed end-to-end on the RT1062
    sim - the connect-probe reads §6 previously called "unknown" are no longer
    open.

---

## 10. Implementation notes for Phonalyser (short)

**Status: IMPLEMENTED.** The QA40x backend described here is built
and lives in the tree under `sound/qa40x`; the notes below are the design as
realized.

- **Binding.** The `LibUsb` JNA binding to `libusb-1.0` mirrors
  `src/main/java/org/edgo/audio/measure/sound/PortAudio.java` - a `final` class
  with a nested `Library` interface, `NativeLong` for C `unsigned long` fields,
  platform library-name resolution (`libusb-1.0.dll` / `libusb-1.0.so.0` /
  `libusb-1.0.dylib`), and the same per-OS `lib/<os>/` drop-in convention. It
  binds only the calls actually needed: open by VID/PID, `claim_interface`, sync
  `bulk_transfer` (registers + cal + PyQa40x-style batch capture) and async
  `submit_transfer`/`handle_events` (ASIO401-style continuous duplex).
- **Backend family.** The backend mirrors the existing WDM-KS split
  (`WdmksDeviceManager` / `WdmksRecorder` / `WdmksGenerator`): a
  `Qa40xDeviceManager` enumerates/opens the device and reads the cal page
  once; a `Qa40xRecorder` (ADC IN `0x82`, little-endian int32, ch1 = calibrated
  channel per the ADC-channel memory) and `Qa40xGenerator` (DAC OUT `0x02`, L/R
  swap, granularity-64 buffers) sharing the same open handle and the §5
  full-duplex discipline. Heed the project rule: never hold the device line open
  idle across captures if it silently zeros (verify QA40x behaviour) - but note
  QA40x wants **priming writes** to start, so cold-start needs at least one write.
- **Session model: ONE duplex engine, clients attach/detach - the generator does
  NOT restart when a capture starts.**
  Input and output are one hardware streaming session (§5: reads only complete
  once output priming crosses the start threshold - on the QA403 the device does
  not stream until its 1024-frame output queue fills), so the two directions can
  NEVER be started independently. Instead of restarting the running direction
  when the other side starts, the backend owns a single always-duplex engine per
  open: the FIRST client (generator ON-AIR or capture acquire) starts the primed
  duplex stream - silence feeding the unused output lane, input discarded if
  unconsumed; a client arriving LATER attaches live (generator = swap
  zero-source -> real samples in the running output lane; scope/FFT = start
  consuming the running input lane). No restart, no glitch, no averaging reset.
  The engine tears down when the LAST client stops. Only a **sample-rate change
  restarts the session** - one shared rate register (reg 9) also means the app's
  separate input/output rate settings MUST be constrained equal for this
  backend. Bonus: one session = ADC and DAC sample-synchronous on one clock with
  a deterministic loopback offset (§5 cold-start formulas) - exactly what the
  coherent-averaging / pre-distortion / freq-resp paths want.
- **dBV / calibration plumbing.** The reg-5/reg-6 dBV full-scale ranges map
  directly onto Phonalyser's existing dBV calibration system: the selected
  input/output range's dBV is the `max_input_level` / `max_output_level` term in
  the §6 formulas, and the per-range on-device cal factor (`10^(dB/20)`) is an
  additional linear multiplier on top of any Phonalyser `.frc` correction. Keep
  the QA40x on-device cal and Phonalyser's own cal as **separate, composable**
  factors, not merged.
  **Levels (QuantAsylum-documented).** The FS range-switch
  labels are the **balanced** input/output full scale, in **dBV** (not dB); the
  **unbalanced** (single-ended) full scale is lower. Phonalyser drives a
  **balanced->balanced** software loopback, so `Qa40xLevels` treats the range label
  as the usable RMS full scale on BOTH directions (one shared `+3` dB peak-vs-RMS
  term, input mirroring the DAC). The `−6` dB the vendor ADC formula subtracts is
  exactly this **balanced-vs-unbalanced** offset, not an ADC-specific differential
  - so dropping it is correct for the balanced path (with the raw vendor math the
  input clipped ~9 dB below its label, breaking balanced-out->balanced-in
  correspondence). The DAC formula is unchanged (vendor math, already label-exact
  in RMS).
- **Thread priority + timing.** The transfer/event thread runs at max practical
  priority with ≥2 async reads in flight per direction (§5). Phonalyser takes the
  **PyQa40x-tolerant** path - a short/over transfer is consumed at its actual
  length, never fatal (§9 item 13). Output pacing is a **write-debt** model
  (repaid, not forfeited; `MAX_WRITE_DEBT = 32`), not ASIO401's empirical
  in-flight constants.
- **DAC amplitude convention.** Feed the §6 DAC formula **peak** volts, not RMS:
  PyQa40x pre-scales its generated tone by √2, which is why the formula's `+3` dB
  term closes (§6, §9 item 12). A Phonalyser generator emitting RMS-scaled samples
  must convert to peak first or it will be ~3 dB hot.
---

## 11. Measurement finding - QA40x software noise/SNR estimator

Cross-validation of Phonalyser against the vendor's QA40x application
(v1.220), both driving the **same hardware simulator** (differential loopback,
TPDF dither injected DAC-side, common to both apps), 48 kHz / FFT 64k /
input range 42 "dBV", generator 0 dBV and -60 dBV per leg.  The stimulus is
ideal and the reference is calculated independently of BOTH applications, so
each app can be graded against constructed ground truth.

### Ground truth method

A 5 s capture (24-bit stereo WAV, `doc/QA40x/QA40x_sim.wav`, recorded by the
Phonalyser capture path) evaluated offline (`doc/QA40x/analyze_wav.py`): DC removal,
least-squares sine fit at the refined tone frequency, subtraction, then a
single rectangular-window FFT of the residual and a one-sided Parseval power
integral over 20 Hz..20 kHz.  No analysis window, no averaging, no application
code in the loop.

- Tone: 999.7559 Hz at **-54.00 dBV** (= -60 dBV per leg, differential x2 -
  the level chain closes exactly in both apps).
- Noise 20 Hz..20 kHz: **-103.29 dBV**; spectral density flat at
  -179.3 dBFS/Hz in-band and 20..24 kHz (pure white dither, no shaping).
- True SNR at this level: **109.30 dB**.

### Readings against truth

| Reader                        | N (dBV)              | deficit vs truth |
|-------------------------------|----------------------|------------------|
| offline integral (reference)  | -103.29              | -                |
| Phonalyser (power avg, NENBW-corrected) | -103.46    | -0.17 dB         |
| QA40x sw, averages = 1        | -104.05 .. -104.11   | -0.79 dB         |
| QA40x sw, averages = 3        | -104.76 .. -104.79   | -1.48 dB         |
| QA40x sw, averages = 20       | -105.10              | -1.81 dB         |

Tone/RMS readouts are exact in BOTH applications at every setting; only the
noise quantities diverge.  The QA readings are window-independent (Hann,
flat-top, BH, rect all agree) and level-independent (0 / -60 dBV identical).

### Inference (black-box; source not available)

Averaging spectral **magnitudes** (|X|, then squaring) instead of **powers**
(|X|^2) biases Rayleigh-distributed noise bins by the estimator factor

    measured / true = pi/4 + (1 - pi/4) / M        (M = averages)

= 0 dB at M=1, -0.67 dB at M=3, -0.99 dB at M=20 - while coherent (tone) bins
are unaffected.  Subtracting this from the observed deficits leaves a
**constant -0.80 +/- 0.02 dB across all M**.  Conclusion: the QA40x software
noise path behaves exactly like **magnitude averaging plus a fixed ~0.8 dB
constant** (the constant possibly a definition artifact - its own N+D and N-D
tiles disagree by 0.64 dB on data whose distortion is ~30 dB below the noise).

Consequences for anyone comparing analyzers against the QA40x software: its
SNR / N readouts are flattered by 0.8..1.8 dB depending on the averaging
count; compare at averages = 1, or against an offline integral as above.

For symmetry: the same session found and fixed a Phonalyser defect of the
same family - the noise integral had omitted the analysis window's NENBW
division (SNR/SINAD/ENOB/THD+N pessimistic by 1.76 dB with Hann, up to
~5.8 dB with flat-top).  After the fix Phonalyser matches the constructed
truth within estimator scatter at every averaging count, per-bin and
integrated.

### Corroboration and re-test (added same day)

QuantAsylum's release notes confirm the averaging half of this finding: QA40x
**v1.221 (January 2026)** - "Fixed averaging bug where amplitude was being
used instead of power for averaging... an SNR or THD calculation would be
slightly better (by about 0.2 to 0.5 dB) than if averaging were disabled.
Thanks to user Hans Rosenberg for detecting this very subtle bug" (video:
"Are you measuring the right noise?", youtube.com/watch?v=1jTqRlIfdKY).  The
measurements above were taken with **v1.220**, the last version carrying that
bug.  Our measured magnitude of the bias (up to 1.0 dB at 20 averages,
following pi/4 + (1 - pi/4)/M) exceeds their 0.2..0.5 dB estimate.

**Re-test with v1.223** (same simulator, same settings, 14 averages) confirms
and localizes the remainder:

| v1.223 readout | dBV     | vs truth (-103.29) |
|----------------|---------|--------------------|
| N+D            | -103.47 | -0.18 - ON truth   |
| N-D            | -104.09 | -0.80              |
| SNR            | 110.09  | +0.79 flattered    |

The averaging dependence is gone (v1.223 at 14 averages equals v1.220 at
averages = 1), and N+D now matches the constructed ground truth - so the
total-noise integral is correct.  The residual ~0.8 dB lives entirely in
**N-D**, on a signal whose real distortion is ~30 dB below the integrated
noise (N-D should equal N+D here, but reads 0.6 dB lower): the "minus
distortion" step evidently discards whole exclusion ZONES around the
fundamental and harmonics - noise included, roughly 13 % of the band -
without rescaling the remaining sum.  SNR is built on N-D and inherits the
flattery.  Anyone comparing against the QA40x software should therefore
compare N+D (truth-accurate since v1.221/1.223), not SNR / N-D.
