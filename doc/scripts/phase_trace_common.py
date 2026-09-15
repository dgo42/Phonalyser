"""Shared parsing for the phase-trace scripts: finds the newest Phonalyser
log, reads the harmonic phase trace lines and cuts out the LAST collection -
everything from the last "first accumulated frame" line to the end of the
file, in log order, so a collection that restarted mid-recording (frame
numbers continue counting) is still separated from the one before it.
"""
import datetime
import math
import os
import re

LOG_DIR = r"C:\Users\dgo\AppData\Roaming\Phonalyser\logs"
STAT_DIR = os.path.join(os.path.dirname(os.path.abspath(__file__)), "stat")
FRAME = re.compile(r"FFT harmonic phase trace, frame (\d+) \(([^)]*)\):(.*)$")
ACCUM = re.compile(r"FFT harmonic phase trace, accumulator after frame (\d+):(.*)$")
VALS = re.compile(r"H(\d)=(-?\d+\.\d+)")
STAMP = re.compile(r"^(\d{4}-\d{2}-\d{2}) (\d{2}):(\d{2})")


def output_path(base_name, collection_stamp):
    """Default picture path: <this folder>/stat/<base>-<YYYY-MM-DD_HH-MM>.svg,
    the stamp being the collection's start (the log time of its first
    accumulated frame), so a rerun on the same collection overwrites the
    same file and different collections keep their own pictures."""
    os.makedirs(STAT_DIR, exist_ok=True)
    return os.path.join(STAT_DIR, "%s-%s.svg" % (base_name, collection_stamp))


def newest_log(log_dir=LOG_DIR):
    """The most recently modified phonalyser*.log in the log directory."""
    candidates = [os.path.join(log_dir, f) for f in os.listdir(log_dir)
                  if f.startswith("phonalyser") and f.endswith(".log")]
    if not candidates:
        raise SystemExit("no phonalyser*.log in %s" % log_dir)
    return max(candidates, key=os.path.getmtime)


def parse_values(text):
    return {int(h): float(v) for h, v in VALS.findall(text)}


def wrap(a):
    return (a + 180.0) % 360.0 - 180.0


def unwrap_backwards(values):
    """Continuous series ending inside -180..180: walking from the last value
    backwards, each earlier value takes the 360-degree alias nearest its
    successor."""
    out = [0.0] * len(values)
    out[-1] = wrap(values[-1])
    for i in range(len(values) - 2, -1, -1):
        base = out[i + 1]
        out[i] = base + wrap(values[i] - base)
    return out


def last_collection(log_path):
    """Returns (singles, accums, first_frame, stamp): singles = [(frame,
    values)] of the single-frame lines AFTER the collection's first
    accumulated frame (that first line is traced on a different basis,
    phiF = 0, and is excluded), accums = [(frame, values)] of the
    accumulator lines of the collection, first_frame = the number of the
    first accumulated frame, stamp = the collection's start as
    YYYY-MM-DD_HH-MM from that line's log time (the current time when the
    line carries none)."""
    events = []
    with open(log_path, encoding="utf-8", errors="replace") as f:
        for line in f:
            m = FRAME.search(line)
            if m:
                events.append(("frame", int(m.group(1)), m.group(2), parse_values(m.group(3)), line))
                continue
            m = ACCUM.search(line)
            if m:
                events.append(("accum", int(m.group(1)), "", parse_values(m.group(2)), line))
    start = None
    for i, (kind, n, label, _, _) in enumerate(events):
        if kind == "frame" and label.startswith("first accumulated"):
            start = i
    if start is None:
        raise SystemExit("no collection (no 'first accumulated frame' line) in %s" % log_path)
    first_frame = events[start][1]
    m = STAMP.match(events[start][4])
    stamp = ("%s_%s-%s" % (m.group(1), m.group(2), m.group(3)) if m
             else datetime.datetime.now().strftime("%Y-%m-%d_%H-%M"))
    coll = events[start:]
    singles = [(n, v) for kind, n, _, v, _ in coll if kind == "frame" and n > first_frame]
    accums = [(n, v) for kind, n, _, v, _ in coll if kind == "accum"]
    if not accums:
        raise SystemExit("the last collection has no accumulator line in %s" % log_path)
    return singles, accums, first_frame, stamp


def circular_stats(angles_deg):
    """(mean_deg, std_deg, R) of a set of angles."""
    c = sum(math.cos(math.radians(a)) for a in angles_deg) / len(angles_deg)
    s = sum(math.sin(math.radians(a)) for a in angles_deg) / len(angles_deg)
    r = math.hypot(c, s)
    mean = math.degrees(math.atan2(s, c))
    std = math.degrees(math.sqrt(-2.0 * math.log(r))) if r > 0 else float("inf")
    return mean, std, r


def snr_db_from_std(std_deg):
    """Single-frame amplitude SNR from the phase spread: sigma (radians) is
    noise quadrature over amplitude; with the noise magnitude sqrt(2) times
    the quadrature, SNR = 20 log10(1 / (sqrt(2) sigma)).  Small-angle: a
    spread near uniform only says the harmonic is at or below the floor."""
    sigma = math.radians(std_deg)
    return 20.0 * math.log10(1.0 / (math.sqrt(2.0) * sigma)) if sigma > 0 else float("inf")


def power_envelope(u, sign):
    """Smooth envelope of the unwrapped accumulator curve u on one side: a
    decaying power law final +- A (n+1)^-p fitted in log-log to the corner
    points of the running extremes (seen from the end), the constant raised
    so the curve bounds every corner, clipped to the curve's own extreme."""
    final = u[-1]
    stair, ext = [0.0] * len(u), final
    for i in range(len(u) - 1, -1, -1):
        ext = max(ext, u[i]) if sign > 0 else min(ext, u[i])
        stair[i] = ext
    pts = [(i + 1, sign * (stair[i] - final)) for i in range(len(stair))
           if (i == 0 or stair[i] != stair[i - 1]) and sign * (stair[i] - final) > 1e-6]
    if len(pts) < 2:
        return [final] * len(u)
    lx = [math.log(n) for n, _ in pts]
    ly = [math.log(d) for _, d in pts]
    mx, my = sum(lx) / len(lx), sum(ly) / len(ly)
    sxx = sum((a - mx) ** 2 for a in lx)
    p = -sum((a - mx) * (b - my) for a, b in zip(lx, ly)) / sxx if sxx > 0 else 0.5
    p = max(0.05, p)
    log_a = max(b + p * a for a, b in zip(lx, ly))
    cap = max(u) if sign > 0 else min(u)
    return [(min if sign > 0 else max)(cap, final + sign * math.exp(log_a) * (i + 1) ** (-p))
            for i in range(len(u))]


def thin(count):
    """Indices to draw: every point of the first 300, then every 8th, and
    the last one."""
    return [i for i in range(count) if i < 300 or i % 8 == 0 or i == count - 1]
