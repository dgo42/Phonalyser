"""Compare the single-frame harmonic phase relations of the FFT trace with
the accumulator's relation after the last frame of the LAST collection in
the NEWEST log.  Prints, per harmonic H2..H9: the accumulator's relation,
the circular mean and circular std of the single frames, the mean
resultant length R, and the wrapped difference accumulator minus mean.
Degrees.

usage: python phase_trace_stats.py [log]   (default: the newest log)
"""
import sys

from phase_trace_common import newest_log, last_collection, circular_stats, snr_db_from_std

LOG = sys.argv[1] if len(sys.argv) > 1 else newest_log()
singles, accums, first_frame, stamp = last_collection(LOG)
last_frame, last_acc = accums[-1]

print("log: %s" % LOG)
print("collection: started %s, first accumulated frame %d, accumulator line after frame %d"
      % (stamp, first_frame, last_frame))
print("single frames used: %d (frames %d..%d, the first excluded)"
      % (len(singles), first_frame + 1, last_frame))
print()
print("| harmonic | accumulator after frame %d | single-frame circular mean | circular std | R | single-frame SNR | accumulator minus mean |"
      % last_frame)
print("|---|---|---|---|---|---|---|")
for h in range(2, 10):
    xs = [v[h] for _, v in singles if h in v]
    if not xs:
        continue
    mean, std, r = circular_stats(xs)
    acc = last_acc.get(h, float("nan"))
    diff = (acc - mean + 180.0) % 360.0 - 180.0
    print("| H%d | %.2f | %.2f | %.2f | %.3f | %d dB | %.2f |"
          % (h, acc, mean, std, r, int(round(snr_db_from_std(std))), diff))
