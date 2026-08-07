# QA40x over WebUSB

The web app talks to a QuantAsylum QA402 / QA403 through **`navigator.usb`** (WebUSB): full duplex,
no native code, no plugin, no extension. `web/js/qa40x/` is a port of the desktop's `sound.qa40x`
package with `webusb-qa40x-transport.js` in place of the libusb transport; everything above the
transport - protocol, levels, calibration, duplex engine, device manager - is the same design.

WebUSB, not WebAssembly, is what makes this possible. WASM is compiled code in the same sandbox and
gains no I/O capability the page lacks; compiling libusb to WASM would still need `navigator.usb`
underneath. For register access and bulk streaming the API is a handful of calls, so nothing is
gained by wrapping it.

## Requirements and limits

* **Chromium-based browsers only.** The web app targets Chrome / Edge; Firefox and Safari do not
  implement WebUSB. `navigator.usb` is a W3C specification, so portability across Chromium builds
  and platforms follows from the standard rather than from per-machine testing.
* **Secure context** - `https://` or `http://localhost`. `file://` does not work.
* **A user gesture for first contact.** `requestDevice()` is refused without one, so the chooser
  opens from an explicit action: selecting QA40x in the backend selector, or the Scan devices click.
  An analyzer this origin has already been granted is found without prompting. The desktop needs no
  equivalent step - `libusb_get_device_list` sees every attached device.
* **Windows: WinUSB binding.** The analyzer must be bound to a WinUSB-class driver. This is a
  per-machine setup step a web page cannot perform for the user.
* **Exclusive access.** Interface 0 is claimed exclusively, so the browser and the desktop app (or
  the vendor software, or a second tab) cannot hold the analyzer at the same time. The web backend
  therefore parks the analyzer at its safe ranges and **releases it whenever nothing is using it** -
  a deliberate divergence from the desktop, which holds its libusb claim until the app exits.

## Transport

* **Identity** - VID `0x16C0`, PID `0x4E37` (QA402) / `0x4E39` (QA403); **interface 0**.
* **Registers** - 5-byte **big-endian** frame on EP 1 OUT; a read is that frame with the address MSB
  set (`0x80|reg`, value 0) and a 4-byte big-endian reply on EP 1 IN.
* **Audio** - EP 2 OUT / EP 2 IN, interleaved **little-endian int32 stereo** (8 B/frame). The two
  endiannesses are independent: registers big, audio little.
* **Endpoint numbering** - WebUSB addresses endpoints by number and infers direction from the call,
  so `0x01`/`0x81` are both endpoint 1 and `0x02`/`0x82` both endpoint 2.
* **Start order** - `RUN_STOP` (recover / idle) -> `INPUT_FS` -> `OUTPUT_FS` -> `SAMPLE_RATE` -> I2S
  frame width -> I2S control -> **100 ms settle** (the rate-write ABA guard) -> `RUN_START` -> then
  prime the output past the 1024-frame threshold.
* **Teardown** - the mirror image: cancel the outstanding transfers **before** `RUN_STOP`, then park
  the ranges at their safe state (input +42 dBV, output −12 dBV) and stop the I2S port.
* **Always duplex** - the ADC does not stream unless the DAC is being fed, so a capture-only client
  still writes (silence is fine) and must pre-queue output frames before `RUN_START`.

### Two disciplines the transport depends on

**Consume by awaiting the head of the queue and resubmitting one per completion**, rather than
racing the outstanding promises. USB bulk completes FIFO, but concurrent `transferIn` promises carry
no ordering guarantee; racing them scrambles the sample stream, and the FFT is the only place it
shows - as unexplained noise. Keeping the queue in submission order preserves contiguity while
holding the depth constant.

**WebUSB has no per-transfer timeout.** After `RUN_STOP` the device sends nothing and an outstanding
`transferIn` never settles; `releaseInterface()` is the only thing that aborts pending transfers, and
a `.catch()` must already be attached to every queued promise before releasing, or the rejections
surface as unhandled. Register transfers impose the desktop's 1000 ms deadline in JS instead.

## Throughput

Stereo int32: **1.536 MB/s at 192 kHz**, **3.072 MB/s at 384 kHz**. Steady state is **2 transfers of
2048 frames** in flight per direction, matching the desktop.

## Probe pages

`htmls/qa40x-webusb.html` (registers and status) and `htmls/qa40x-webusb-stream.html` (full-duplex
streaming, scope + FFT, stream-health counters, an edge-probe matrix over rate × queue depth ×
frames-per-transfer). They are diagnostic tools, not the reference: their start sequence primes the
output *before* `RUN_START` and omits the I2S writes and the settle. **`Qa40xDuplexEngine` is the
reference implementation.**

Run them with:

```
python -m http.server 8799 -d doc/htmls
```

then open <http://localhost:8799/qa40x-webusb-stream.html>, with Phonalyser and the vendor software
closed. A loopback cable DAC -> ADC makes the tone visible.
