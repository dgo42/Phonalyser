# macOS arm64 (Apple Silicon) native libraries

Drop an **arm64-capable** PortAudio dynamic library here:

    libportaudio.dylib

It powers the **CoreAudio** audio backend (stereo, 24/32-bit, high sample
rates) on Apple Silicon Macs — JavaSound on macOS is mono-only, so CoreAudio
replaces it. The macOS jpackage build (`-Pmacos-aarch64`) stages every
`*.dylib` in this folder into the app at `$APPDIR`, and PortAudio is loaded from
there via `-Djava.library.path=$APPDIR`.

A `.dylib` is compiled for a single CPU arch, so an x86_64 build will **not**
run natively on Apple Silicon.

## Option A — build a universal dylib (recommended; works on BOTH arches)

A *universal (fat)* dylib carries both `x86_64` and `arm64` slices in one file,
so the SAME file works for the Intel **and** the Apple-Silicon DMG. You can
build it on **any** Mac, including an Intel one (clang + the macOS SDK can
target arm64 from an Intel host) — no Apple Silicon machine required:

    brew install cmake
    git clone https://github.com/PortAudio/portaudio
    cd portaudio && cmake -B build \
        -DCMAKE_OSX_ARCHITECTURES="x86_64;arm64" \
        -DCMAKE_BUILD_TYPE=Release -DPA_BUILD_SHARED=ON
    cmake --build build
    lipo -info build/libportaudio.dylib        # → Architectures ...: x86_64 arm64

Then copy that one universal file into **both** native dirs:

    cp build/libportaudio.dylib lib/macos-arm64/
    cp build/libportaudio.dylib lib/macos-x64/

## Option B — native arm64 only

On an Apple Silicon Mac (or a CI arm64 runner such as GitHub `macos-14`):

    brew install portaudio
    cp -L "$(brew --prefix portaudio)/lib/libportaudio.dylib" lib/macos-arm64/

Homebrew installs under `/opt/homebrew` on arm64. `cp -L` dereferences the
`libportaudio.dylib` → `libportaudio.2.dylib` symlink so a real file is copied.

## Option C — fuse two single-arch dylibs

If you already have separate x86_64 and arm64 builds:

    lipo -create libportaudio_x64.dylib libportaudio_arm64.dylib \
         -output libportaudio.dylib

## Verify

    file lib/macos-arm64/libportaudio.dylib    # Mach-O ... arm64 (or "two architectures")

The dylib is a platform binary and is **not** committed. Without it, the
CoreAudio backend cannot open — and since JavaSound is disabled on macOS, the
app would have no working audio backend there. PortAudio links only macOS
system frameworks (CoreAudio / AudioToolbox / AudioUnit), so nothing else needs
bundling.
