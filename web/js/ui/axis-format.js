/*
 * Phonalyser web — precision audio measurement workbench (browser port).
 * Copyright (C) 2026  Dimitrij Goldstein <https://github.com/dgo42>
 * GNU Affero General Public License v3 or later.
 *
 * Faithful line-for-line port of the shared axis tick-generation and label
 * formatters from the Java desktop source (the single source of truth):
 *   - AbstractMeasurementView.formatVoltsSi              (:1105)
 *   - AbstractMeasurementView.formatMagnitudeWithUnit    (:1133)
 *   - AbstractMeasurementView.adaptiveLogLabels          (:1166)
 *   - AbstractMeasurementView.logMajorTicks / logMinorTicks
 *   - AbstractMeasurementView.niceLinearMajors           (:992)
 *   - AbstractMeasurementView.isSubDecade / isDecadeValue
 *   - AbstractMeasurementView.formatFrequency / formatFreqTick / formatFrequencyInteger
 * The Java methods are quoted in the porting report; do NOT change a constant
 * here without re-reading the Java.
 */

/** MagnitudeUnit.isLog() — Java enum: V(true), V_SQRT_HZ(true), DBV(false), DBFS(false),
 *  DBR(false). Every dB unit (dBr included) keeps the linear-in-dB axis. */
export function unitIsLog(unit) {
  return unit === 'V' || unit === 'V_SQRT_HZ';
}

/* ---- AbstractMeasurementView.formatVoltsSi (:1105) -----------------------
 *   if (v == 0) return "0 ";
 *   double abs = Math.abs(v); ...
 *   if      (abs >= 1e3)  { prefix = "k"; scale = 1e3; }
 *   else if (abs >= 1)    { prefix = "";  scale = 1;   }
 *   else if (abs >= 1e-3) { prefix = "m"; scale = 1e-3; }
 *   else if (abs >= 1e-6) { prefix = "µ"; scale = 1e-6; }
 *   else if (abs >= 1e-9) { prefix = "n"; scale = 1e-9; }
 *   else if (abs >= 1e-12){ prefix = "p"; scale = 1e-12; }
 *   else                  { prefix = "f"; scale = 1e-15; }
 *   double s = v / scale;
 *   if      (Math.abs(s) >= 100) m = "%.0f";
 *   else if (Math.abs(s) >= 10)  m = "%.1f";
 *   else                          m = "%.2f";
 *   strip trailing zeros; return m + " " + prefix;
 */
export function formatVoltsSi(v) {
  if (v === 0) return '0 ';
  const abs = Math.abs(v);
  let prefix, scale;
  if      (abs >= 1e3)   { prefix = 'k'; scale = 1e3; }
  else if (abs >= 1)     { prefix = '';  scale = 1; }
  else if (abs >= 1e-3)  { prefix = 'm'; scale = 1e-3; }
  else if (abs >= 1e-6)  { prefix = 'µ'; scale = 1e-6; }
  else if (abs >= 1e-9)  { prefix = 'n'; scale = 1e-9; }
  else if (abs >= 1e-12) { prefix = 'p'; scale = 1e-12; }
  else                   { prefix = 'f'; scale = 1e-15; }
  const s = v / scale;
  let m;
  if      (Math.abs(s) >= 100) m = s.toFixed(0);
  else if (Math.abs(s) >= 10)  m = s.toFixed(1);
  else                         m = s.toFixed(2);
  if (m.indexOf('.') >= 0) {
    m = m.replace(/0+$/, '');
    if (m.endsWith('.')) m = m.substring(0, m.length - 1);
  }
  return m + ' ' + prefix;
}

/* ---- AbstractMeasurementView.formatMagnitudeWithUnit (:1133) -------------
 *   case DBFS:      return "%.1f dBFS";
 *   case DBV:       return "%.1f dBV";
 *   case V:         return formatVoltsSi(v) + "V";
 *   case V_SQRT_HZ: return formatVoltsSi(v) + "V/√Hz";
 */
export function formatMagnitudeWithUnit(v, unit) {
  if (!Number.isFinite(v)) return '—';
  switch (unit) {
    case 'DBFS':      return v.toFixed(1) + ' dBFS';
    case 'DBR':       return v.toFixed(1) + ' dBr';
    case 'DBV':       return v.toFixed(1) + ' dBV';
    case 'V':         return formatVoltsSi(v) + 'V';
    case 'V_SQRT_HZ': return formatVoltsSi(v) + 'V/√Hz';
    default:          return String(v);
  }
}

/* ---- Magnitude AXIS tick label (applyLabelFormat VOLTS_SI / DB dispatch) -
 * Java drawGrid uses LabelFormat.VOLTS_SI (→ formatVoltsSi, no unit on the
 * tick — the unit caption is painted once) for log V / V√Hz axes, and
 * LabelFormat.DB (→ "%.1f") for the dBFS / dBV linear axes. */
export function formatMagTick(v, unit) {
  if (!Number.isFinite(v)) return '—';
  if (unitIsLog(unit)) return formatVoltsSi(v).trimEnd();   // VOLTS_SI: "100 m" → trim trailing space
  return v.toFixed(1);                                       // DB: "%.1f"
}

/* ---- AbstractMeasurementView.adaptiveLogLabels (:1166) -------------------
 *   double safeMin = Math.max(1e-15, min);
 *   double safeMax = Math.max(safeMin + 1e-9, max);
 *   int loDec = floor(log10(safeMin)); int hiDec = ceil(log10(safeMax));
 *   int decades = hiDec - loDec;
 *   decades<=1 -> {1,2,3,4,5,6,7,8}; <=2 -> {1,2,3,4,5,6,8};
 *   <=3 -> {1,2,3,5,7}; <=5 -> {1,2,5}; else -> {1};
 *   for d in loDec..hiDec: base=10^d; for k in keep: v=base*k; if safeMin<=v<=safeMax add.
 */
export function adaptiveLogLabels(min, max) {
  const safeMin = Math.max(1e-15, min);
  const safeMax = Math.max(safeMin + 1e-9, max);
  const loDec = Math.floor(Math.log10(safeMin));
  const hiDec = Math.ceil(Math.log10(safeMax));
  const decades = hiDec - loDec;
  let keep;
  if      (decades <= 1) keep = [1, 2, 3, 4, 5, 6, 7, 8];
  else if (decades <= 2) keep = [1, 2, 3, 4, 5, 6, 8];
  else if (decades <= 3) keep = [1, 2, 3, 5, 7];
  else if (decades <= 5) keep = [1, 2, 5];
  else                   keep = [1];
  const out = [];
  for (let d = loDec; d <= hiDec; d++) {
    const base = Math.pow(10, d);
    for (const k of keep) {
      const v = base * k;
      if (v < safeMin || v > safeMax) continue;
      out.push(v);
    }
  }
  return out;
}

/* ---- AbstractMeasurementView.logMajorTicks (:916) — decade boundaries ----
 *   safeMin = max(1e-15,min); safeMax = max(safeMin+1e-9,max);
 *   lo = floor(log10(safeMin)); hi = ceil(log10(safeMax));
 *   for e in lo..hi: v=10^e; if safeMin<=v<=safeMax add.
 */
export function logMajorTicks(min, max) {
  const safeMin = Math.max(1e-15, min);
  const safeMax = Math.max(safeMin + 1e-9, max);
  const lo = Math.floor(Math.log10(safeMin));
  const hi = Math.ceil(Math.log10(safeMax));
  const out = [];
  for (let e = lo; e <= hi; e++) {
    const v = Math.pow(10, e);
    if (v >= safeMin && v <= safeMax) out.push(v);
  }
  return out;
}

/* ---- AbstractMeasurementView.logMinorTicks (:936) — 2..9 × 10ⁿ ----------
 *   lo = floor(log10(safeMin))-1; hi = ceil(log10(safeMax))+1;
 *   for e in lo..hi: base=10^e; for k=2..9: v=k*base; if safeMin<v<safeMax add.
 */
export function logMinorTicks(min, max) {
  const safeMin = Math.max(1e-15, min);
  const safeMax = Math.max(safeMin + 1e-9, max);
  const lo = Math.floor(Math.log10(safeMin)) - 1;
  const hi = Math.ceil(Math.log10(safeMax)) + 1;
  const out = [];
  for (let e = lo; e <= hi; e++) {
    const base = Math.pow(10, e);
    for (let k = 2; k <= 9; k++) {
      const v = k * base;
      if (v > safeMin && v < safeMax) out.push(v);
    }
  }
  return out;
}

/* ---- AbstractMeasurementView.niceLinearMajors (:992) --------------------
 *   range = max(1e-9, max-min); rough = range / max(1,targetCount);
 *   pow = 10^floor(log10(rough)); mant = rough/pow;
 *   mant<1.5 -> 1; <3 -> 2; <4 -> 2.5; <7 -> 5; else -> 10  (× pow);
 *   first = ceil(min/step)*step; for f=first; f<=max+step*1e-9; f+=step add.
 */
export function niceLinearMajors(min, max, targetCount) {
  const range = Math.max(1e-9, max - min);
  const rough = range / Math.max(1, targetCount);
  const pow = Math.pow(10, Math.floor(Math.log10(rough)));
  const mant = rough / pow;
  let step;
  if      (mant < 1.5) step = 1 * pow;
  else if (mant < 3)   step = 2 * pow;
  else if (mant < 4)   step = 2.5 * pow;
  else if (mant < 7)   step = 5 * pow;
  else                 step = 10 * pow;
  const first = Math.ceil(min / step) * step;
  const out = [];
  for (let f = first; f <= max + step * 1e-9; f += step) out.push(f);
  return out;
}

/* ---- AbstractMeasurementView.niceLinearMinors (:1012) -------------------
 *   majors = niceLinearMajors(min,max,10);
 *   first = ceil(min/minorStep)*minorStep;
 *   for m=first; m<=max+1e-9; m+=minorStep: skip if <min||>max or coincides with a major.
 */
export function niceLinearMinors(min, max, minorStep) {
  if (minorStep <= 0) return [];
  const majors = niceLinearMajors(min, max, 10);
  const first = Math.ceil(min / minorStep) * minorStep;
  const out = [];
  for (let m = first; m <= max + 1e-9; m += minorStep) {
    if (m < min || m > max) continue;
    let isMajor = false;
    for (const M of majors) { if (Math.abs(m - M) < 1e-6) { isMajor = true; break; } }
    if (!isMajor) out.push(m);
  }
  return out;
}

/* ---- AbstractMeasurementView.subDecadeMinors (:1155) -------------------
 *   majors = niceLinearMajors(min,max,12); majorStep = majors[1]-majors[0];
 *   pow=10^floor(log10(majorStep)); mant=majorStep/pow;
 *   minorStep = majorStep / (|mant-2|<0.1 ? 2 : 5);
 *   first=ceil(min/minorStep)*minorStep; emit v in [min,max] not coinciding a major.
 * The sub-decade minor ticks for a zoomed (<1 decade) frequency axis — Java hardcodes
 * the target count 12 inside this method (do NOT thread SUB_DECADE_TICK_TARGET here). */
export function subDecadeMinors(min, max) {
  const majors = niceLinearMajors(min, max, 12);
  if (majors.length < 2) return [];
  const majorStep = majors[1] - majors[0];
  const pow = Math.pow(10, Math.floor(Math.log10(majorStep)));
  const mant = majorStep / pow;
  const minorStep = majorStep / (Math.abs(mant - 2.0) < 0.1 ? 2 : 5);
  const first = Math.ceil(min / minorStep) * minorStep;
  const out = [];
  for (let v = first; v <= max + minorStep * 1e-9; v += minorStep) {
    if (v < min || v > max) continue;
    let isMajor = false;
    for (const M of majors) { if (Math.abs(v - M) < minorStep * 0.5) { isMajor = true; break; } }
    if (!isMajor) out.push(v);
  }
  return out;
}

/* ---- AbstractMeasurementView.isSubDecade (:855) -------------------------
 *   lo = max(1e-15,min); hi = max(lo+1e-9,max); return log10(hi/lo) < 1.0;
 */
export function isSubDecade(min, max) {
  const lo = Math.max(1e-15, min);
  const hi = Math.max(lo + 1e-9, max);
  return Math.log10(hi / lo) < 1.0;
}

/* ---- AbstractMeasurementView.isDecadeValue (:863) ----------------------- */
export function isDecadeValue(v) {
  if (!(v > 0)) return false;
  const p = Math.pow(10, Math.round(Math.log10(v)));
  return Math.abs(v - p) <= p * 1e-6;
}

/** Smallest gap between consecutive values (Java minSpacing :1060). */
export function minSpacing(vals) {
  let m = Infinity;
  for (let i = 1; i < vals.length; i++) m = Math.min(m, Math.abs(vals[i] - vals[i - 1]));
  return Number.isFinite(m) ? m : 0.0;
}

/* ---- AbstractMeasurementView.formatFrequency (:1038) --------------------
 *   if (f >= 1000) return "%.2f kHz"; return "%.2f Hz";
 */
export function formatFrequency(f) {
  if (!Number.isFinite(f) || f <= 0) return '—';
  if (f >= 1000) return (f / 1000).toFixed(2) + ' kHz';
  return f.toFixed(2) + ' Hz';
}

/* ---- AbstractMeasurementView.formatFrequencyFine (:1352) ----------------
 *   %.4f, kHz only from 10 kHz, Locale.ROOT (period decimal, no thousands sep).
 * The crosshair frequency readout — full precision, so a zoomed cursor reads e.g.
 * "1002.5119 Hz" rather than the coarse axis-label "1.003 kHz". */
export function formatFrequencyFine(f) {
  if (!Number.isFinite(f) || f <= 0) return '—';
  if (f >= 10000) return (f / 1000).toFixed(4) + ' kHz';
  return f.toFixed(4) + ' Hz';
}

/* ---- AbstractMeasurementView.formatFreqTick (:1048) ---------------------
 *   if (v >= 1000) { kStep = step/1000; kd = (kStep<1)?min(4,ceil(-log10(kStep))):2; "%.kd f kHz" }
 *   dec = (step<1)?min(4,ceil(-log10(step))):0; "%.dec f Hz"
 */
export function formatFreqTick(v, step) {
  if (!Number.isFinite(v) || v <= 0) return '—';
  if (v >= 1000) {
    const kStep = step / 1000.0;
    const kd = (kStep > 0 && kStep < 1) ? Math.min(4, Math.ceil(-Math.log10(kStep))) : 2;
    return (v / 1000.0).toFixed(kd) + ' kHz';
  }
  const dec = (step > 0 && step < 1) ? Math.min(4, Math.ceil(-Math.log10(step))) : 0;
  return v.toFixed(dec) + ' Hz';
}

/* ---- AbstractMeasurementView.formatFrequencyInteger (:1077) -------------
 *   if (f >= 1000) { k=f/1000; if(|k-round(k)|<0.05) "%d kHz" else "%.1f kHz" }
 *   return "%d Hz";
 */
export function formatFrequencyInteger(f) {
  if (!Number.isFinite(f)) return '—';
  if (f >= 1000) {
    const k = f / 1000;
    if (Math.abs(k - Math.round(k)) < 0.05) return Math.round(k) + ' kHz';
    return k.toFixed(1) + ' kHz';
  }
  return Math.round(f) + ' Hz';
}
