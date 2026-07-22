# Offline noise-integral arbiter for the QA40x-sim WAV (24-bit PCM capture).
# Method: DC removal -> precise sine fit (frequency refined by golden search,
# amplitude/phase by least squares) -> subtract -> rectangular-window FFT of
# the residual -> one-sided band power 20 Hz..20 kHz.  No analysis window, no
# app code involved; Parseval arithmetic only.
import numpy as np
import os
import sys

PATH = sys.argv[1] if len(sys.argv) > 1 else \
    os.path.join(os.path.dirname(os.path.abspath(__file__)), "QA40x_sim.wav")
RANGE_DBV = 42.0                    # input range: Vpp differential at clip
FS_PEAK_V = 10 ** (RANGE_DBV / 20) / 2.0
FS_RMS_V  = FS_PEAK_V / np.sqrt(2.0)
FS_RMS_DBV = 20 * np.log10(FS_RMS_V)

raw = open(PATH, "rb").read()
# Minimal RIFF walk: find 'fmt ' and 'data' chunks.
def chunks(b):
    pos = 12
    while pos + 8 <= len(b):
        cid = b[pos:pos+4]; size = int.from_bytes(b[pos+4:pos+8], "little")
        yield cid, pos + 8, size
        pos += 8 + size + (size & 1)
fmt = data = None
for cid, off, size in chunks(raw):
    if cid == b"fmt ": fmt = raw[off:off+size]
    if cid == b"data": data = raw[off:off+size]
ch    = int.from_bytes(fmt[2:4],  "little")
rate  = int.from_bytes(fmt[4:8],  "little")
bits  = int.from_bytes(fmt[14:16], "little")
assert bits == 24 and ch == 2, (bits, ch)
frames = len(data) // 6
u = np.frombuffer(data, dtype=np.uint8).reshape(frames, 6)
left = (u[:, 0].astype(np.int64)
        | (u[:, 1].astype(np.int64) << 8)
        | (u[:, 2].astype(np.int64) << 16))
left = np.where(left >= 1 << 23, left - (1 << 24), left)
x = left.astype(np.float64) / (1 << 23)          # FS peak = 1.0
N = len(x)
print(f"frames={N}  rate={rate}  duration={N/rate:.3f}s  DC={x.mean():.3e}")
x = x - x.mean()

# --- tone frequency: coarse FFT peak, then golden-section on projection power
w = np.hanning(N)
X = np.fft.rfft(x * w)
fax = np.fft.rfftfreq(N, 1 / rate)
band = (fax > 900) & (fax < 1100)
f_coarse = fax[band][np.argmax(np.abs(X[band]))]
t = np.arange(N) / rate
def proj_pow(f):
    c = np.cos(2 * np.pi * f * t); s = np.sin(2 * np.pi * f * t)
    a = 2 * np.dot(x, c) / N; b = 2 * np.dot(x, s) / N
    return a * a + b * b
lo, hi = f_coarse - 1.0, f_coarse + 1.0
gr = (np.sqrt(5) - 1) / 2
c1, c2 = hi - gr * (hi - lo), lo + gr * (hi - lo)
p1, p2 = proj_pow(c1), proj_pow(c2)
for _ in range(60):
    if p1 < p2:
        lo, c1, p1 = c1, c2, p2; c2 = lo + gr * (hi - lo); p2 = proj_pow(c2)
    else:
        hi, c2, p2 = c2, c1, p1; c1 = hi - gr * (hi - lo); p1 = proj_pow(c1)
f0 = (lo + hi) / 2
c = np.cos(2 * np.pi * f0 * t); s = np.sin(2 * np.pi * f0 * t)
A = 2 * np.dot(x, c) / N; B = 2 * np.dot(x, s) / N
amp = np.hypot(A, B)                              # tone peak amplitude, FS units
tone_dbfs = 20 * np.log10(amp / np.sqrt(2) / (1 / np.sqrt(2)))   # RMS re FS_RMS
print(f"tone: f={f0:.4f} Hz  amp_peak={amp:.6e} FS  -> {tone_dbfs:+.2f} dBFS = {tone_dbfs + FS_RMS_DBV:+.2f} dBV")
resid = x - (A * c + B * s)

# --- residual band power, rectangular FFT (Parseval), one-sided
R = np.fft.rfft(resid)
Pbin = (np.abs(R) ** 2) / (N * N)                 # two-sided power per bin
Pbin[1:-1] *= 2.0                                 # one-sided fold
fax = np.fft.rfftfreq(N, 1 / rate)
for name, flo, fhi in (("20 Hz..20 kHz", 20.0, 20000.0),
                       ("20 kHz..24 kHz", 20000.0, rate / 2)):
    m = (fax >= flo) & (fax <= fhi)
    P = Pbin[m].sum()
    rms = np.sqrt(P)
    dbfs = 20 * np.log10(rms * np.sqrt(2))        # re FS_RMS
    print(f"noise {name}: {dbfs:+.2f} dBFS = {dbfs + FS_RMS_DBV:+.2f} dBV   "
          f"(density {dbfs - 10*np.log10(fhi-flo):+.1f} dBFS/Hz)")
# sanity: total residual RMS via Parseval vs time domain
print(f"check: time-RMS {20*np.log10(resid.std()*np.sqrt(2)):+.2f} dBFS")
