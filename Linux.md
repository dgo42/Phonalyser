# Running Phonalyser on Linux

The JAVASOUND backend talks to ALSA via the JDK's bundled native bridge —
no extra packages are needed beyond a working sound stack.  The only
common gotcha is the **`audio` group**: many distros restrict raw audio
device access to members of that group, and a fresh user account isn't
in it by default.

## 1. Audio-group permissions

Check whether your user is in the `audio` group:

```bash
groups | tr ' ' '\n' | grep -x audio
```

If the command prints nothing, add yourself:

```bash
sudo usermod -aG audio "$USER"
```

Then **log out and log back in** (or reboot) — group membership is read
at login.  `newgrp audio` works as a one-shot for the current shell but
doesn't affect already-running desktop processes, so a full re-login is
simpler.

Verify the device files are now readable:

```bash
ls -l /dev/snd/
```

You should see entries owned by `root:audio` with mode `rw-rw----`.

## 2. PulseAudio / PipeWire and sample-rate caps

Most modern desktops (Ubuntu 22.04+, Fedora 36+, Arch with default
install) route audio through **PulseAudio** or **PipeWire** rather than
talking to ALSA directly.  Both transparently resample everything to a
single mix rate — typically 48 kHz / 24-bit — regardless of what the
hardware actually supports.

If you need bit-exact high-rate playback (96 / 192 / 384 / 768 kHz):

* **PipeWire**: edit `~/.config/pipewire/pipewire.conf.d/10-sample-rate.conf`:
  ```
  context.properties = {
      default.clock.allowed-rates = [ 44100 48000 88200 96000 176400 192000 ]
      default.clock.rate          = 192000
  }
  ```
  Restart pipewire: `systemctl --user restart pipewire pipewire-pulse`.

* **PulseAudio**: edit `/etc/pulse/daemon.conf`:
  ```
  default-sample-rate = 192000
  alternate-sample-rate = 96000
  ```
  Restart: `systemctl --user restart pulseaudio` (or `pulseaudio -k`).

* **Bypass entirely**: open the device's ALSA `hw:` mixer directly.
  Phonalyser's JavaSound backend lists every mixer reported by
  `AudioSystem.getMixerInfo()`, including raw ALSA ones if PulseAudio
  isn't intercepting them.

## 3. Real-time scheduling (optional)

For the smoothest playback under load, grant your user real-time
scheduling priority via the `audio` group's PAM limits (already
configured on most distros).  Check:

```bash
cat /etc/security/limits.d/audio.conf 2>/dev/null
```

Expected content:

```
@audio   -  rtprio     95
@audio   -  memlock    unlimited
```

If the file is missing, create it as root with the two lines above and
log out/in.

## 4. Backend selection

The Preferences dialog filters audio backends to those that work on the
running OS.  On Linux you'll only see **JAVASOUND** (WASAPI / WDM-KS are
Windows-only and hidden).  Once selected, the **Device** dropdown lists
every JavaSound mixer the JDK reports — pick the one whose name matches
your hardware.

If no devices appear, the most common causes are:

* User not in the `audio` group (see §1).
* The device is held exclusively by another application — close anything
  that might be capturing or playing audio and retry.
