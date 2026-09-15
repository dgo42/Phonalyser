"""Draws the accumulator's harmonic phase relations over the frames of the
LAST collection in the NEWEST log as an SVG: one curve per harmonic H2..H9
in its own colour, the angle in degrees on the vertical axis UNWRAPPED so
that no curve folds (the axis range grows to hold every curve), the frame
number linear on the horizontal axis with only the last frame labelled.
Around each curve two independent power-law envelope curves in the same
colour at 50 percent opacity, each bounding its own side's excursions and
converging onto the settled value.  Legend bottom right with the final
accumulator phase and the single-frame SNR of each harmonic.

usage: python phase_trace_svg.py [log] [out.svg]
       log "-" or absent: the newest log; out.svg defaults to
       stat/phase_trace-<collection start YYYY-MM-DD_HH-MM>.svg beside this script
"""
import math
import sys

from phase_trace_common import (newest_log, last_collection, circular_stats, snr_db_from_std,
                                unwrap_backwards, power_envelope, thin, output_path)

LOG = sys.argv[1] if len(sys.argv) > 1 and sys.argv[1] != "-" else newest_log()
COLOURS = {2: "#d62728", 3: "#1f77b4", 4: "#2ca02c", 5: "#ff7f0e",
           6: "#9467bd", 7: "#8c564b", 8: "#e377c2", 9: "#17becf"}

singles, acc, first_frame, stamp = last_collection(LOG)
OUT = sys.argv[2] if len(sys.argv) > 2 else output_path("phase_trace", stamp)
frame_no = [n for n, _ in acc]
n_first, n_max = frame_no[0], frame_no[-1]

snr_db = {}
for h in range(2, 10):
    xs = [v[h] for _, v in singles if h in v]
    if xs:
        snr_db[h] = snr_db_from_std(circular_stats(xs)[1])

series, upper, lower, final = {}, {}, {}, {}
for h in range(2, 10):
    vals = [v.get(h) for _, v in acc]
    if any(x is None for x in vals):
        continue
    u = unwrap_backwards(vals)
    series[h], final[h] = u, u[-1]
    upper[h], lower[h] = power_envelope(u, 1.0), power_envelope(u, -1.0)

# --- geometry -------------------------------------------------------------
W, H = 1000, 700
L, R, T, B = 80, 30, 30, 90
PW, PH = W - L - R, H - T - B
all_vals = [x for h in series for x in upper[h] + lower[h]]
y_lo = 30.0 * math.floor(min(all_vals) / 30.0)
y_hi = 30.0 * math.ceil(max(all_vals) / 30.0)
if y_hi - y_lo < 60.0:
    y_hi = y_lo + 60.0
tick = 60 if (y_hi - y_lo) >= 360 else 30


def x_of(n):
    return L + PW * (n - n_first) / max(1, n_max - n_first)


def y_of(a):
    return T + PH * (y_hi - a) / (y_hi - y_lo)


def polyline(values, idx):
    return " ".join("%s%.1f %.1f" % ("M" if j == 0 else "L", x_of(frame_no[i]), y_of(values[i]))
                    for j, i in enumerate(idx))


keep = thin(len(acc))
out = ['<svg xmlns="http://www.w3.org/2000/svg" width="%d" height="%d" viewBox="0 0 %d %d" '
       'font-family="sans-serif" font-size="13">' % (W, H, W, H),
       '<rect width="%d" height="%d" fill="white"/>' % (W, H),
       '<line x1="%d" y1="%d" x2="%d" y2="%d" stroke="#444"/>' % (L, T, L, T + PH),
       '<line x1="%d" y1="%.1f" x2="%d" y2="%.1f" stroke="#444"/>' % (L, y_of(y_lo), L + PW, y_of(y_lo))]
a = y_lo
while a <= y_hi + 1e-9:
    y = y_of(a)
    out.append('<line x1="%d" y1="%.1f" x2="%d" y2="%.1f" stroke="#ddd"/>' % (L, y, L + PW, y))
    out.append('<text x="%d" y="%.1f" text-anchor="end" dominant-baseline="middle">%d&#176;</text>'
               % (L - 8, y, int(round(a))))
    a += tick
out.append('<text x="%.1f" y="%.1f" text-anchor="middle">%d</text>' % (x_of(n_max), y_of(y_lo) + 20, n_max))
out.append('<text x="%.1f" y="%.1f" text-anchor="middle" fill="#444">frame</text>' % (L + PW / 2, y_of(y_lo) + 20))
out.append('<text x="%d" y="%d" text-anchor="start" fill="#444">accumulator phase to the fundamental, degrees (unwrapped)</text>'
           % (L, T - 10))

for h in series:                                  # envelope curves under the traces
    for env in (upper[h], lower[h]):
        out.append('<path d="%s" fill="none" stroke="%s" stroke-opacity="0.5" stroke-width="1.5"/>'
                   % (polyline(env, keep), COLOURS[h]))
for h in series:
    out.append('<path d="%s" fill="none" stroke="%s" stroke-width="1.3"/>'
               % (polyline(series[h], keep), COLOURS[h]))

# legend, bottom right
rows = len(series)
lw, lh = 300, rows * 18 + 8
lx, ly = L + PW - lw - 10, T + PH - lh - 10
out.append('<rect x="%d" y="%d" width="%d" height="%d" fill="white" fill-opacity="0.85" stroke="#ccc"/>'
           % (lx, ly, lw, lh))
for i, h in enumerate(sorted(series)):
    y = ly + 14 + i * 18
    snr = snr_db.get(h)
    snr_txt = "n/a" if snr is None else "%d dB" % int(round(snr))
    out.append('<line x1="%d" y1="%d" x2="%d" y2="%d" stroke="%s" stroke-width="3"/>'
               % (lx + 8, y, lx + 32, y, COLOURS[h]))
    out.append('<text x="%d" y="%d" dominant-baseline="middle">H%d  final %.2f&#176;   single-frame SNR %s</text>'
               % (lx + 40, y, h, final[h], snr_txt))
out.append('</svg>')

with open(OUT, "w", encoding="utf-8") as f:
    f.write("\n".join(out))
print("log %s: collection frames %d..%d, written %s, axis %d..%d deg" % (LOG, n_first, n_max, OUT, y_lo, y_hi))
