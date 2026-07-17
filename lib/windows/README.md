# Windows x86_64 natives

Drop these files here before building / packaging:

| File                       | Purpose                                                          |
| -------------------------- | ---------------------------------------------------------------- |
| `portaudio_x64.dll`        | PortAudio shared library for the WDM-KS audio backend.           |
| `libusb-1.0_x64.dll`       | libusb-1.0 for the QA40x (QuantAsylum QA402/QA403) USB backend.  |
| `libusb-1.0_x86.dll`       | Same as above for the legacy 32-bit fat jar.                     |
| `csjsound_amd64.dll`       | csjsound-provider JNI bridge to WASAPI exclusive (64-bit JVM).   |
| `csjsound_x86.dll`         | Same as above for 32-bit JVM; rarely needed.                     |
| `csjsound-provider.jar`    | JavaSound MixerProvider that surfaces WASAPI exclusive lines.    |

`csjsound-provider.jar` is referenced from `pom.xml` as a system-scoped dependency,
so the build will fail to resolve the SWT-based modules if it is missing.

Build csjsound-provider from source: https://github.com/pavhofman/csjsound-provider
Build / get PortAudio for Windows from: https://www.portaudio.com/download.html

## libusb (QA40x backend) — optional drop-in

The QA40x backend loads libusb via the JNA binding
(`org.edgo.audio.measure.sound.LibUsb`), the same `lib/<os>/` +
`jna.library.path` convention PortAudio uses. The JVM-arch-suffixed name is
tried first — `libusb-1.0_x64.dll` on a 64-bit JVM, `libusb-1.0_x86.dll` on the
legacy 32-bit fat jar — then a plain `libusb-1.0.dll` as fall-back, so both
arch variants can coexist here (like `portaudio_x64` / `portaudio_x86`). It is
**optional**: without it the QA40x backend simply reports itself unavailable
(no error). The DLLs are **not** committed here.

Get prebuilt Windows DLLs from the libusb releases
(https://github.com/libusb/libusb/releases): rename `VS2015-x64/dll/libusb-1.0.dll`
→ `libusb-1.0_x64.dll` and `VS2015-Win32/dll/libusb-1.0.dll` → `libusb-1.0_x86.dll`.
Also install the vendor WinUSB driver per QuantAsylum's instructions so
`libusb` can bind the device.
