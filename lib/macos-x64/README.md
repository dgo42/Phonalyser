# macOS x86_64 native libraries

Drop the **Intel (x86_64)** PortAudio dynamic library here:

    libportaudio.dylib

It powers the **CoreAudio** audio backend (stereo, 24/32-bit, high sample
rates) on Intel Macs — JavaSound on macOS is mono-only, so CoreAudio replaces
it. The macOS jpackage build (`-Pmacos-x64`) stages every `*.dylib` in this
folder into the app at `$APPDIR`, and PortAudio is loaded from there via
`-Djava.library.path=$APPDIR`.

## Where to get it

    brew install portaudio
    cp "$(brew --prefix portaudio)/lib/libportaudio.dylib" lib/macos-x64/

On an Intel Mac, Homebrew installs under `/usr/local`, so the file is
`/usr/local/lib/libportaudio.dylib` (a CoreAudio-backed build).

Verify the architecture (must be x86_64):

    file lib/macos-x64/libportaudio.dylib      # → Mach-O ... x86_64

The dylib is a platform binary and is **not** committed. Without it, the
CoreAudio backend cannot open — and since JavaSound is disabled on macOS, the
app would have no working audio backend there.
