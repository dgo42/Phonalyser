# Windows x86_64 natives

Drop these files here before building / packaging:

| File                       | Purpose                                                          |
| -------------------------- | ---------------------------------------------------------------- |
| `portaudio_x64.dll`        | PortAudio shared library for the WDM-KS audio backend.           |
| `csjsound_amd64.dll`       | csjsound-provider JNI bridge to WASAPI exclusive (64-bit JVM).   |
| `csjsound_x86.dll`         | Same as above for 32-bit JVM; rarely needed.                     |
| `csjsound-provider.jar`    | JavaSound MixerProvider that surfaces WASAPI exclusive lines.    |

`csjsound-provider.jar` is referenced from `pom.xml` as a system-scoped dependency,
so the build will fail to resolve the SWT-based modules if it is missing.

Build csjsound-provider from source: https://github.com/pavhofman/csjsound-provider
Build / get PortAudio for Windows from: https://www.portaudio.com/download.html
