# Mock `libusb-1.0` with an emulated QA403 (VS2015 native test library)

A drop-in `libusb-1.0.dll` (x64) that implements exactly the `libusb-1.0` subset
the Phonalyser JNA binding calls
(`src/main/java/org/edgo/audio/measure/sound/LibUsb.java`) and emulates **one
permanently-connected QA403** behind it, wired as if a loopback cable joined its
output to its input. Point the Java library search at this DLL and the entire
stack - JNA binding -> `Qa40xDeviceFinder` -> `LibUsbQa40xTransport` ->
`Qa40xDuplexEngine` -> recorder/generator -> the full app - runs unmodified,
with **no Java production-code changes**, so the QA40x backend can be tested end
to end without QA40x hardware.

The existing Java `FakeTransport` covers the engine unit tests *above* the
transport seam; this mock covers everything *below* it: JNA marshalling, struct
layout, the async transfer lifecycle, event-thread dispatch, and the
`doc/QA40X-PROTOCOL.md` sections 4/5/6 wire protocol.

## What it emulates

- **USB identity** VID `0x16C0` / PID `0x4E39` (QA403), bus 1, address 5.
- **Registers** (EP `0x01`/`0x81`, doc section 4): input/output full-scale
  ranges, sample rate, run control, and the 512-byte factory calibration page
  read via reg `0x0D`/`0x19`. The cal page is deterministic (documented in the
  integration test) so cal factors are exactly reproducible.
- **Audio streaming** (async EP `0x02`/`0x82`, doc section 5): a real-time
  sample-clock worker paces a 1024-frame DAC queue and the ADC ring, honours the
  QA403 start threshold (streams only once the output queue has filled once), and
  completes transfers in submission order per direction.
- **Loopback** (doc section 6): the ADC stream is the DAC stream fed back through
  the exact inverse of the host `Qa40xLevels` raw<->volts math, so whatever peak
  voltage the app asks the DAC for is what the app's ADC math reads back. The
  device output L/R swap is un-swapped by the "cable"; the ADC is not swapped.

## Exported surface - 21 functions

The export set must match the `Lib` interface in `LibUsb.java` EXACTLY: JNA
resolves each function by name on first call, so one the binding declares and
the mock omits fails at run time, in the middle of an open, as
`UnsatisfiedLinkError: Error looking up function '<name>'` - not at build time.
That is not hypothetical: `libusb_get_configuration` and
`libusb_set_configuration` were added to the binding for the macOS
select-the-configuration path and the mock went stale behind them, which broke
every mock-backed test until it was extended.

- Lifecycle / enumeration (8): `libusb_init`, `libusb_exit`,
  `libusb_error_name`, `libusb_get_device_list`, `libusb_free_device_list`,
  `libusb_get_device_descriptor`, `libusb_get_bus_number`,
  `libusb_get_device_address`.
- Handles (7): `libusb_open`, `libusb_close`, `libusb_reset_device`,
  `libusb_get_configuration`, `libusb_set_configuration`,
  `libusb_claim_interface`, `libusb_release_interface`.
- Sync bulk (1): `libusb_bulk_transfer`.
- Async (5): `libusb_alloc_transfer`, `libusb_free_transfer`,
  `libusb_submit_transfer`, `libusb_cancel_transfer`,
  `libusb_handle_events_timeout_completed`.

The device has ONE configuration, value 1, and is always in it, so
`libusb_get_configuration` always reports 1 and the binding's
`set_configuration` branch is never taken; `set_configuration` accepts 1 and
answers `ERROR_NOT_FOUND` for anything else. Verify the built DLL with
`dumpbin /exports x64\Release\libusb-1.0.dll` - all 21 names, undecorated.

Non-goals: no QA402/QA401, no multi-device/hot-plug, no isochronous/control
transfers, no noise/distortion modelling (the loopback is mathematically clean).

## Build - Visual Studio 2015 ONLY

This library is built with **Visual Studio 2015** (Platform Toolset **v140**),
x64 only. Do **not** build it with the VS2019/VS2022 MSBuild or a newer toolset.

From a plain shell:

```
src\test\lib\libusb\build.cmd            rem Release (default)
src\test\lib\libusb\build.cmd Debug      rem Debug
```

`build.cmd` calls
`"C:\Program Files (x86)\Microsoft Visual Studio 14.0\VC\vcvarsall.bat" x64`
(failing loudly if VS2015 is absent) and then the MSBuild (14.0) that
environment puts on `PATH`. Equivalently, open `libusb-mock.sln` in Visual
Studio 2015 and build the `Release|x64` configuration.

Plain C, compiled `/TC` at `/W4` with zero warnings; the CRT is linked
statically (`/MT`) so the DLL has no VC++ runtime redistributable dependency.
`winmm.lib` is linked for `timeBeginPeriod`.

### Output - two DLL names, both required

The build writes to `x64\<Configuration>\`:

- `libusb-1.0.dll` - the actual mock (`TargetName`).
- `libusb-1.0_x64.dll` - an identical copy made by the project's post-build step.

Both names exist because the Java binding's `candidateLibraryNames` tries the
**arch-suffixed** name (`libusb-1.0_x64` on a 64-bit JVM) **first**, then the
plain `libusb-1.0`. JNA searches the whole path for candidate 1 before trying
candidate 2, so if only `libusb-1.0.dll` were present here and a real
`libusb-1.0_x64.dll` existed anywhere on the system path, the real library would
win. Shipping both names in this directory guarantees the mock is what loads.
The post-build copy runs for both `build.cmd` and an IDE build.

## Run the Java integration test against the mock

The IT `Qa40xMockLoopbackIT` (tag `qa40x-mock`, excluded from the default
suite) drives the real transport against the built DLL. Point the loader at this
directory with **`-Dlibusb.path`** (an absolute path is recommended):

```
mvn -o test -Dtest=Qa40xMockLoopbackIT "-Dsurefire.excludedGroups=" ^
    "-Dlibusb.path=%CD%\src\test\lib\libusb\x64\Release"
```

Build the DLL first (`build.cmd`) or the test fails with a message telling you
to. Debug builds work too - pass the `x64\Debug` directory instead.

## Run the full app against the mock

Launch Phonalyser with the same flag so the QA40x backend attaches to the
emulated device:

```
-Dlibusb.path=D:\path\to\_Phonalyser\src\test\lib\libusb\x64\Release
```

`-Dlibusb.path` pins **only** where `libusb-1.0` loads (a JNA per-library search
path that wins over `jna.library.path` and the system path); PortAudio /
csjsound keep loading normally. Prefer it over overriding `jna.library.path`,
which would redirect *every* native and break the other backends in a full app
run.
