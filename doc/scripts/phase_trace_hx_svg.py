"""Two-panel SVG for one harmonic of the LAST collection in the NEWEST log:
the upper panel shows the single-frame phase relation to the fundamental
per frame, folded to +-180 degrees, in black, with the circular mean as a
red line and no envelope; the lower panel shows the accumulator's relation
after each frame, unwrapped, in black, with the two power-law envelope
curves (50 percent opacity) and no mean line.  Legend bottom right.
Frames linear, only the end number labelled.

usage: python phase_trace_hx_svg.py <harmonic 2..9> [out.svg] [log]
       out.svg "-" or absent: stat/phase_trace_h<harmonic>-<collection start
       YYYY-MM-DD_HH-MM>.svg beside this script; log absent: the newest
"""
import math
import sys

from phase_trace_common import (newest_log, last_collection, circular_stats, snr_db_from_std,
                                unwrap_backwards, power_envelope, thin, output_path)

if len(sys.argv) < 2 or not sys.argv[1].isdigit() or not 2 <= int(sys.argv[1]) <= 9:
    sys.exit("usage: python phase_trace_hx_svg.py <harmonic 2..9> [out.svg] [log]")
HARM = int(sys.argv[1])
LOG = sys.argv[3] if len(sys.argv) > 3 else newest_log()

singles, acc, first_frame, stamp = last_collection(LOG)
OUT = sys.argv[2] if len(sys.argv) > 2 and sys.argv[2] != "-" else output_path("phase_trace_h%d" % HARM, stamp)
single = [(n, v[HARM]) for n, v in singles if HARM in v]
if not single:
    sys.exit("no H%d values in the last collection of %s" % (HARM, LOG))
mean_deg, std_deg, _ = circular_stats([a for _, a in single])
snr_db = snr_db_from_std(std_deg)

acc_n = [n for n, _ in acc]
n_first, n_max = acc_n[0], acc_n[-1]
u = unwrap_backwards([v[HARM] for _, v in acc])
final = u[-1]
upper, lower = power_envelope(u, 1.0), power_envelope(u, -1.0)

# --- geometry: two panels ---------------------------------------------------
W, H = 1000, 820
L, R = 80, 30
PW = W - L - R
panels = {"top": (30, 340), "bottom": (430, 740)}   # y0, y1 of each plot area


def x_of(n):
    return L + PW * (n - n_first) / max(1, n_max - n_first)


def y_maker(y0, y1, lo, hi):
    return lambda a: y0 + (y1 - y0) * (hi - a) / (hi - lo)


def path(xs_n, ys, yf, idx, break_on_wrap=False):
    d, prev = [], None
    for i in idx:
        a = ys[i]
        cmd = "M" if prev is None or (break_on_wrap and abs(a - prev) > 180.0) else "L"
        d.append("%s%.1f %.1f" % (cmd, x_of(xs_n[i]), yf(a)))
        prev = a
    return " ".join(d)


def axes(out, y0, y1, lo, hi, tick, yf, title):
    out.append('<line x1="%d" y1="%d" x2="%d" y2="%d" stroke="#444"/>' % (L, y0, L, y1))
    out.append('<line x1="%d" y1="%.1f" x2="%d" y2="%.1f" stroke="#444"/>' % (L, yf(lo), L + PW, yf(lo)))
    a = lo
    while a <= hi + 1e-9:
        y = yf(a)
        out.append('<line x1="%d" y1="%.1f" x2="%d" y2="%.1f" stroke="#ddd"/>' % (L, y, L + PW, y))
        out.append('<text x="%d" y="%.1f" text-anchor="end" dominant-baseline="middle">%d&#176;</text>'
                   % (L - 8, y, int(round(a))))
        a += tick
    out.append('<text x="%.1f" y="%.1f" text-anchor="middle">%d</text>' % (x_of(n_max), yf(lo) + 20, n_max))
    out.append('<text x="%.1f" y="%.1f" text-anchor="middle" fill="#444">frame</text>' % (L + PW / 2, yf(lo) + 20))
    out.append('<text x="%d" y="%d" text-anchor="start" fill="#444">%s</text>' % (L, y0 - 10, title))


out = ['<svg xmlns="http://www.w3.org/2000/svg" width="%d" height="%d" viewBox="0 0 %d %d" '
       'font-family="sans-serif" font-size="13">' % (W, H, W, H),
       '<rect width="%d" height="%d" fill="white"/>' % (W, H)]

# upper panel: single frames, folded +-180, black, red mean line
y0, y1 = panels["top"]
yf = y_maker(y0, y1, -180.0, 180.0)
axes(out, y0, y1, -180.0, 180.0, 60, yf,
     "H%d single-frame phase to the fundamental, degrees, folded +-180" % HARM)
sn = [n for n, _ in single]
sv = [a for _, a in single]
out.append('<path d="%s" fill="none" stroke="black" stroke-width="0.8"/>'
           % path(sn, sv, yf, list(range(len(single))), True))
out.append('<line x1="%d" y1="%.1f" x2="%d" y2="%.1f" stroke="#d62728" stroke-width="2"/>'
           % (L, yf(mean_deg), L + PW, yf(mean_deg)))

# lower panel: accumulator, unwrapped, black, envelope curves, no mean line
y0, y1 = panels["bottom"]
vals = u + upper + lower
lo = 30.0 * math.floor(min(vals) / 30.0)
hi = 30.0 * math.ceil(max(vals) / 30.0)
if hi - lo < 60.0:
    hi = lo + 60.0
tick = 60 if (hi - lo) >= 360 else 30
yf = y_maker(y0, y1, lo, hi)
axes(out, y0, y1, lo, hi, tick, yf,
     "H%d accumulator phase to the fundamental after each frame, degrees (unwrapped)" % HARM)
keep = thin(len(u))
for env in (upper, lower):
    out.append('<path d="%s" fill="none" stroke="black" stroke-opacity="0.5" stroke-width="1.5"/>'
               % path(acc_n, env, yf, keep))
out.append('<path d="%s" fill="none" stroke="black" stroke-width="1.3"/>' % path(acc_n, u, yf, keep))

# legend, bottom right of the lower panel
rows = [("black", 1.3, "H%d accumulator, final %.2f&#176;" % (HARM, final)),
        ("black", 0.8, "H%d single frames, %d frames, single-frame SNR %d dB" % (HARM, len(single), int(round(snr_db)))),
        ("#d62728", 2.0, "single-frame circular mean %.2f&#176; (std %.1f&#176;)" % (mean_deg, std_deg)),
        ("black", 1.5, "envelope: power-law bound of the running extremes")]
lw, lh = 370, len(rows) * 18 + 8
lx, ly = L + PW - lw - 10, y1 - lh - 10
out.append('<rect x="%d" y="%d" width="%d" height="%d" fill="white" fill-opacity="0.85" stroke="#ccc"/>'
           % (lx, ly, lw, lh))
for i, (colour, width, text) in enumerate(rows):
    y = ly + 14 + i * 18
    opacity = ' stroke-opacity="0.5"' if text.startswith("envelope") else ""
    out.append('<line x1="%d" y1="%d" x2="%d" y2="%d" stroke="%s" stroke-width="%.1f"%s/>'
               % (lx + 8, y, lx + 32, y, colour, width, opacity))
    out.append('<text x="%d" y="%d" dominant-baseline="middle">%s</text>' % (lx + 40, y, text))
out.append('</svg>')

with open(OUT, "w", encoding="utf-8") as f:
    f.write("\n".join(out))
print("log %s: H%d, collection frames %d..%d, %d single frames, mean %.2f std %.1f snr %.1f dB, written %s"
      % (LOG, HARM, n_first, n_max, len(single), mean_deg, std_deg, snr_db, OUT))
