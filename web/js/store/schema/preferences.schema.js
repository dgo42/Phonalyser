/*
 * Phonalyser web - JSON Schema for the preferences document.
 * Copyright (C) 2026  Dimitrij Goldstein <https://github.com/dgo42>
 * GNU Affero General Public License v3 or later.
 *
 * Describes the document that store/preferences.js writes (its _toMap) and reads back
 * (its _fromMap): the key list, the types and enum names each key accepts, and the
 * clamps the reader applies. The JSON configuration editor feeds this schema to
 * CodeMirror, so every `description` below is the help text an operator reads when
 * hovering a key - write them as explanations, not as restatements of the key name.
 * Types and constraints are derived from the reader, never invented: a value the
 * reader would reject must not validate here, and a key this build does not know
 * must still be allowed, because the reader keeps it and writes it back untouched.
 */

export const PREFERENCES_SCHEMA = {
  "$schema": "https://json-schema.org/draft/2020-12/schema",
  "title": "Phonalyser preferences",
  "description": "Everything the workbench remembers between sessions: the selected audio backend and its per-backend device choices, the state of the oscilloscope, generator, FFT and frequency-response panes, the saved presets and calibration lists, and the settings blocks owned by individual components. The whole document is one JSON object held in browser storage. Keys are optional throughout - anything missing simply keeps its built-in default, and any key this build does not recognise is preserved and written back unchanged, so a document from a newer release survives a round trip.",
  "type": "object",
  "additionalProperties": true,
  "properties": {

    // ---- document header -------------------------------------------------
    "formatVersion": {
      "type": "integer",
      "default": 1,
      "description": "Layout version stamped on the document each time it is written. It marks which release wrote the file for a future migration; the current reader does not consult it, so editing it changes nothing."
    },

    // ---- shell / look and feel ------------------------------------------
    "backend": {
      "type": "string",
      "default": "WASAPI",
      "examples": ["WEB_AUDIO", "QA40X", "net:JAVASOUND"],
      "description": "The audio backend measurements run on. In the browser the real choices are WEB_AUDIO (the browser's own audio input and output) and QA40X (a QuantAsylum QA402/QA403 driven over WebUSB); the desktop names WASAPI, WDMKS, COREAUDIO and JAVASOUND stay legal so a document written by the desktop build still loads. A remote bench is stored as net:<the server's own backend name>, because the server, not this client, decides what it offers."
    },
    "uiLanguage": {
      "type": "string",
      "default": "en",
      "examples": ["en", "de", "uk", "zh-TW"],
      "description": "Language tag of the user interface, e.g. en, de, fr, uk or zh-TW. A tag with no translation bundle falls back to English at load time."
    },
    "tabOrientation": {
      "type": "string",
      "enum": ["TOP", "LEFT"],
      "default": "TOP",
      "description": "Where the main tab strip sits: TOP puts the tabs in a horizontal row above the panes, LEFT stacks them down the left edge, which fits more tabs on a narrow window."
    },
    "uiFontNormal": {
      "type": "string",
      "examples": ["Consolas|9|normal"],
      "description": "Font for ordinary interface text, as family|size|style, where size is in points and style is normal or bold. The default is the platform's standard monospace face - Consolas 9 on Windows, Menlo 11 on macOS, DejaVu Sans Mono 11 elsewhere."
    },
    "uiFontBold": {
      "type": "string",
      "examples": ["Consolas|9|bold"],
      "description": "Font for emphasised interface text such as readouts and headings, in the same family|size|style form as uiFontNormal. Defaults to the same face and size in bold."
    },
    "uiFontChannel": {
      "type": "string",
      "examples": ["Consolas|12|bold"],
      "description": "Font for the large left/right channel buttons, in the same family|size|style form as uiFontNormal. Defaults to bold at three points above the base size so the channel labels stay readable at a glance."
    },
    "activeTabIndex": {
      "type": "integer",
      "default": 0,
      "description": "Zero-based index of the main tab that was open when the app last closed, so the next start comes up on the same view."
    },
    "smallIconsInMainTab": {
      "type": "boolean",
      "default": false,
      "description": "Draw the main tab's toolbar with small icons instead of the full-size ones, which frees vertical space on a short screen."
    },
    "checkForUpdatesOnStartup": {
      "type": "boolean",
      "default": false,
      "description": "Ask the project site for a newer release each time the app starts, and report one if it exists. Off means updates are only ever checked when asked for by hand."
    },
    "includeBetaInUpdateChecks": {
      "type": "boolean",
      "default": false,
      "description": "Let the update check offer pre-release builds as well as stable ones. Ignored while checkForUpdatesOnStartup is off and no manual check is run."
    },
    "showTipsAtStartup": {
      "type": "boolean",
      "default": true,
      "description": "Show the tip-of-the-day window when the app opens. Turn it off once the tips are familiar."
    },

    // ---- window geometry / multifunctional layout ------------------------
    "windowWidth": {
      "type": "integer",
      "default": 0,
      "description": "Width in pixels of the application window as it was last sized. 0 means it has never been sized and the browser or window manager picks the width."
    },
    "windowHeight": {
      "type": "integer",
      "default": 0,
      "description": "Height in pixels of the application window as it was last sized. 0 means it has never been sized and the browser or window manager picks the height."
    },
    "genPaneWidth": {
      "type": "integer",
      "description": "Width in pixels of the generator pane on the multifunctional tab, as the divider was last dragged. Omitted entirely until the divider has been moved, in which case the pane opens at its natural width."
    },
    "multiVSplitWeights": {
      "type": "array",
      "items": {
        "type": "integer",
        "description": "One pane's share of the available height, as a weight relative to the other entries."
      },
      "description": "Relative heights of the stacked panes on the multifunctional tab, one weight per pane in top-to-bottom order. The numbers are proportions rather than pixels, so the layout keeps its shape when the window is resized. Omitted until the dividers have been moved."
    },
    "genPaneCollapsed": {
      "type": "boolean",
      "default": false,
      "description": "Whether the generator pane on the multifunctional tab is folded away to its title bar."
    },
    "oscPaneCollapsed": {
      "type": "boolean",
      "default": false,
      "description": "Whether the oscilloscope pane on the multifunctional tab is folded away to its title bar."
    },
    "fftPaneCollapsed": {
      "type": "boolean",
      "default": true,
      "description": "Whether the FFT pane on the multifunctional tab is folded away to its title bar. Collapsed by default, since the FFT has a full tab of its own."
    },

    // ---- oscilloscope ----------------------------------------------------
    "oscLeftChannelEnabled": {
      "type": "boolean",
      "default": true,
      "description": "Draw the left channel on the oscilloscope. A disabled channel is still captured, so its measurements stay available; it is only left off the screen."
    },
    "oscRightChannelEnabled": {
      "type": "boolean",
      "default": true,
      "description": "Draw the right channel on the oscilloscope. A disabled channel is still captured, so its measurements stay available; it is only left off the screen."
    },
    "oscLeftAcMode": {
      "type": "boolean",
      "default": false,
      "description": "AC coupling for the left trace: subtract the mean of the displayed window so a small signal riding on a DC offset stays centred and can be scaled up."
    },
    "oscRightAcMode": {
      "type": "boolean",
      "default": false,
      "description": "AC coupling for the right trace: subtract the mean of the displayed window so a small signal riding on a DC offset stays centred and can be scaled up."
    },
    "oscLeftVoltsPerDiv": {
      "type": "number",
      "default": 0.1,
      "description": "Vertical scale of the left trace in volts per screen division. Smaller values magnify the trace; the graticule always has the same number of divisions."
    },
    "oscRightVoltsPerDiv": {
      "type": "number",
      "default": 0.1,
      "description": "Vertical scale of the right trace in volts per screen division. Smaller values magnify the trace; the graticule always has the same number of divisions."
    },
    "oscTimePerDiv": {
      "type": "number",
      "default": 0.001,
      "description": "Horizontal scale in seconds per screen division. 1e-3 shows one millisecond per division, so a 1 kHz sine spans one division per cycle."
    },
    "scopeScreenshotWidth": {
      "type": "integer",
      "default": 0,
      "description": "Width in pixels last used for an oscilloscope screenshot. 0 means fall back to the pane's own on-screen size. Each pane remembers its screenshot size separately."
    },
    "scopeScreenshotHeight": {
      "type": "integer",
      "default": 0,
      "description": "Height in pixels last used for an oscilloscope screenshot. 0 means fall back to the pane's own on-screen size."
    },
    "oscTriggerChannel": {
      "type": "string",
      "enum": ["L", "R"],
      "default": "L",
      "description": "Which channel the trigger watches: L the left input, R the right one. The other channel is still drawn, aligned to the same trigger instant."
    },
    "oscTriggerEdge": {
      "type": "string",
      "enum": ["RISE", "FALL"],
      "default": "RISE",
      "description": "Slope the trigger fires on: RISE when the signal crosses the level going up, FALL when it crosses going down. For a glitch trigger this selects whether the display anchors on the start or the end of the discontinuity."
    },
    "oscTriggerType": {
      "type": "string",
      "enum": ["EDGE", "GLITCH"],
      "default": "EDGE",
      "description": "What counts as a trigger event. EDGE fires when the signal crosses the trigger level; GLITCH fires on any abrupt change of slope - a step or a splice - anywhere on the waveform, which catches dropouts that never reach the level."
    },
    "oscTriggerMode": {
      "type": "string",
      "enum": ["AUTO", "NORMAL", "SINGLE"],
      "default": "AUTO",
      "description": "How the sweep is armed. AUTO free-runs when no trigger arrives, so an untriggered signal still shows; NORMAL only redraws on a real trigger; SINGLE captures one triggered frame and freezes."
    },
    "oscTriggerHysteresisDiv": {
      "type": "number",
      "default": 0,
      "description": "Noise guard band around the trigger level, in screen divisions. The signal must move this far past the level before a new trigger is accepted, which stops a noisy crossing from firing several times."
    },
    "oscTriggerHysteresisEnabled": {
      "type": "boolean",
      "default": false,
      "description": "Whether the hysteresis band above is applied at all. While off, the trigger fires on the bare level crossing."
    },
    "oscShowReconstructedBeat": {
      "type": "boolean",
      "default": false,
      "description": "With a dual-tone signal, overlay the reconstructed beat envelope at the difference frequency of the two tones. Only drawn while the generator is actually running and both tone frequencies are known."
    },
    "oscLeftSincInterpEnabled": {
      "type": "boolean",
      "default": true,
      "description": "Reconstruct the left trace between samples with band-limited (Lanczos sinc) interpolation instead of straight lines. It only changes the picture when zoomed in far enough that samples are several pixels apart."
    },
    "oscRightSincInterpEnabled": {
      "type": "boolean",
      "default": true,
      "description": "Reconstruct the right trace between samples with band-limited (Lanczos sinc) interpolation instead of straight lines. It only changes the picture when zoomed in far enough that samples are several pixels apart."
    },
    "oscLeftResidualEnabled": {
      "type": "boolean",
      "default": false,
      "description": "Show the left channel's residual - the captured signal minus its best-fit tone - instead of the raw trace, which exposes the distortion, noise and glitches hidden under the fundamental. Trigger and frequency readouts still follow the captured signal."
    },
    "oscRightResidualEnabled": {
      "type": "boolean",
      "default": false,
      "description": "Show the right channel's residual - the captured signal minus its best-fit tone - instead of the raw trace, which exposes the distortion, noise and glitches hidden under the fundamental."
    },
    "oscLeftMainsSuppression": {
      "type": "string",
      "enum": ["NONE", "IIR_COMB", "SYNC_SUBTRACT", "LMS"],
      "default": "NONE",
      "description": "Mains-hum removal on the left trace, tracked live at the measured mains frequency and its harmonics. NONE leaves the signal alone; IIR_COMB notches every harmonic with a comb filter; SYNC_SUBTRACT averages a hum period and subtracts it; LMS adapts a hum estimate and subtracts that. The DC level and the test tone are preserved either way."
    },
    "oscRightMainsSuppression": {
      "type": "string",
      "enum": ["NONE", "IIR_COMB", "SYNC_SUBTRACT", "LMS"],
      "default": "NONE",
      "description": "Mains-hum removal on the right trace. Same options as the left channel: NONE, IIR_COMB (comb notch), SYNC_SUBTRACT (period-averaged subtraction) or LMS (adaptive subtraction)."
    },
    "oscLeftLpf": {
      "type": "string",
      "enum": ["NONE", "HZ_80", "DESPIKE"],
      "default": "NONE",
      "description": "High-frequency cleanup on the left trace. NONE passes everything; HZ_80 applies an 80 kHz Chebyshev low-pass that removes continuous ultrasonic content (and does nothing when 80 kHz is already above Nyquist); DESPIKE is a median filter that removes impulsive glitches without ringing."
    },
    "oscRightLpf": {
      "type": "string",
      "enum": ["NONE", "HZ_80", "DESPIKE"],
      "default": "NONE",
      "description": "High-frequency cleanup on the right trace: NONE, an 80 kHz Chebyshev low-pass, or a de-spiking median filter."
    },
    "oscLeftOffsetFrac": {
      "type": "number",
      "default": 0.5,
      "description": "Vertical position of the left trace's zero line as a fraction of screen height, 0 at the top edge and 1 at the bottom. 0.5 centres it."
    },
    "oscRightOffsetFrac": {
      "type": "number",
      "default": 0.5,
      "description": "Vertical position of the right trace's zero line as a fraction of screen height, 0 at the top edge and 1 at the bottom. 0.5 centres it."
    },
    "oscTriggerLevelFrac": {
      "type": "number",
      "default": 0.5,
      "description": "Trigger level as a fraction of screen height, 0 at the top edge and 1 at the bottom. Storing it as a fraction rather than volts keeps the trigger where the marker sits when the vertical scale changes."
    },
    "oscTriggerPositionFrac": {
      "type": "number",
      "default": 0.5,
      "description": "Where the trigger instant is placed across the screen, 0 at the left edge and 1 at the right. Values above 0 show pre-trigger history - what the signal did before the event."
    },
    "oscMeasurementAverageSeconds": {
      "type": "number",
      "default": 5,
      "description": "Length in seconds of the sliding window behind the average, minimum, maximum and standard-deviation readouts. Longer windows steady the numbers and make them slower to react."
    },
    "oscMeasurementChannel": {
      "type": "string",
      "enum": ["L", "R"],
      "default": "L",
      "description": "Channel the measurement table reports on. Changing it clears the collected statistics, so the new channel starts from a clean window."
    },
    "oscShowStats": {
      "type": "boolean",
      "default": true,
      "description": "Show the live statistics overlay (average, minimum, maximum and sigma) on the oscilloscope canvas."
    },
    "oscShowHistogram": {
      "type": "boolean",
      "default": false,
      "description": "Whether the amplitude-histogram window is open. It plots how often the signal visits each amplitude: a sine draws the bathtub of its two turning points, broadband noise a bell, and clipping a hard spike at the rail."
    },
    "oscHistogramBins": {
      "type": "integer",
      "default": 50,
      "description": "Number of bars the amplitude histogram draws - display resolution only. Changing it re-aggregates the collected micro-bins and never discards a count. The dialog offers 10 to 200 in steps of 5."
    },
    "oscHistogramChannel": {
      "type": "string",
      "enum": ["L", "R"],
      "default": "L",
      "description": "Channel the amplitude histogram collects. Kept separate from the measurement channel on purpose, so picking a histogram channel does not wipe the measurement table's statistics."
    },
    "oscShowMeasurementTable": {
      "type": "boolean",
      "default": true,
      "description": "Show the measurement table beside the oscilloscope, with the per-channel amplitude, frequency and period readouts."
    },

    // ---- generator -------------------------------------------------------
    "genSignalForm": {
      "type": "string",
      "enum": ["SINE", "SINE_COMP", "TRIANGLE", "RECTANGLE", "WHITE_NOISE", "PINK_NOISE",
        "PINK_NOISE_LINEAR", "LINEAR_SWEEP", "LOG_SWEEP", "DUAL_TONE", "DUAL_TONE_COMP"],
      "default": "SINE",
      "description": "Waveform the generator produces. SINE and DUAL_TONE are the plain tones; the _COMP variants apply a measured predistortion file to cancel the DAC's own distortion; TRIANGLE and RECTANGLE take a duty cycle; the noise forms feed broadband signals (PINK_NOISE_LINEAR is pink noise weighted for a linear frequency axis); LINEAR_SWEEP and LOG_SWEEP walk the sweep range set below."
    },
    "genFrequencyHz": {
      "type": "number",
      "default": 1000,
      "description": "Frequency in hertz of the single-tone forms (sine, triangle, rectangle)."
    },
    "genDualToneFreq1Hz": {
      "type": "number",
      "default": 1000,
      "description": "First tone of the dual-tone signal, in hertz. The pair of tones is what drives an intermodulation measurement."
    },
    "genDualToneFreq2Hz": {
      "type": "number",
      "default": 1300,
      "description": "Second tone of the dual-tone signal, in hertz. Its distance from tone 1 sets the beat and the intermodulation product spacing."
    },
    "genDualToneSplitPct": {
      "type": "number",
      "default": 50,
      "description": "Share of the total amplitude given to tone 1, in percent; tone 2 takes the remainder. 50 is the equal-amplitude pair used for the usual IMD tests."
    },
    "genAmplitudeVrms": {
      "type": "number",
      "default": 0.5,
      "description": "Output level in volts RMS, referred to the DAC full-scale calibration. It is the true amplitude of the tone, not a digital fraction."
    },
    "genAmplitudeDbvDisplay": {
      "type": "boolean",
      "default": false,
      "description": "Show the generator amplitude field in dBV instead of volts. Display only - the stored level stays in volts RMS."
    },
    "genDitherBits": {
      "type": "number",
      "default": 0,
      "description": "Depth in bits of the TPDF dither added before quantisation; may be fractional. 0 disables dither. Fewer bits leave more quantisation distortion, more bits raise the noise floor but linearise the converter."
    },
    "genDitherDbvDisplay": {
      "type": "boolean",
      "default": false,
      "description": "Show the dither field in dBV rather than bits, because the value was last entered with an explicit dBV suffix. Display only."
    },
    "genOutputChannels": {
      "type": "string",
      "enum": ["BOTH", "LEFT", "RIGHT"],
      "default": "BOTH",
      "description": "Which output lanes the generator drives. BOTH feeds the signal to both; LEFT or RIGHT drives one lane and writes digital silence to the other, which is how a single-ended device under test is fed without unplugging anything."
    },
    "genDpd": {
      "type": "string",
      "description": "Predistortion data used by the compensated single-tone form. The web build stores the contents of the .dpd file here, not a file path. Absent when no predistortion has been loaded."
    },
    "genDpdDual": {
      "type": "string",
      "description": "Predistortion data used by the compensated dual-tone form, held as the .dpd file's contents. The dual-tone slot is kept apart from the single-tone one because the correction differs."
    },
    "genDpdFolder": {
      "type": "string",
      "description": "Folder the file chooser opens in when picking or saving a predistortion file, so the next visit starts where the last one ended."
    },
    "genDpdName": {
      "type": "string",
      "description": "Original file name of the single-tone predistortion data, kept purely so the corrections row can name it after a reload - the browser never sees the full path."
    },
    "genDpdDualName": {
      "type": "string",
      "description": "Original file name of the dual-tone predistortion data, shown in the corrections row after a reload."
    },
    "predistortionAverages": {
      "type": "integer",
      "default": 64,
      "description": "How many FFT frames the predistortion wizard averages per iteration. More averaging lowers the measurement floor, so the correction can chase smaller residues, at the cost of time per step."
    },
    "predistortionTargetPct": {
      "type": "number",
      "default": 0.000001,
      "description": "Target total harmonic distortion, in percent, at which the predistortion wizard stops iterating. 0 means keep iterating until the improvement stalls."
    },
    "genRectangleDuty": {
      "type": "number",
      "default": 0.5,
      "description": "Duty cycle of the rectangle wave as a fraction of the period, 0.5 being a square wave. The field in the pane shows the same value as a percentage."
    },
    "genTriangleDuty": {
      "type": "number",
      "default": 0.5,
      "description": "Duty cycle of the triangle wave as a fraction of the period: 0.5 is symmetric, values towards 0 or 1 turn it into a sawtooth. Each waveform keeps its own duty setting."
    },
    "genSweepFreqStartHz": {
      "type": "number",
      "default": 20,
      "description": "Frequency in hertz the generator's sweep starts at."
    },
    "genSweepFreqEndHz": {
      "type": "number",
      "default": 20000,
      "description": "Frequency in hertz the generator's sweep ends at. It may be below the start frequency for a downward sweep."
    },
    "genSweepDurationSec": {
      "type": "number",
      "default": 1,
      "description": "Time in seconds the sweep takes to travel from the start frequency to the end frequency."
    },
    "genSweepLoop": {
      "type": "boolean",
      "default": true,
      "description": "Restart the sweep from the beginning as soon as it finishes, so it runs continuously instead of stopping after one pass."
    },
    "genSweepFadeInSec": {
      "type": "number",
      "default": 0.01,
      "description": "Length in seconds of the amplitude ramp at the start of the sweep. It stops the abrupt onset from splattering energy across the spectrum."
    },
    "genSweepFadeOutSec": {
      "type": "number",
      "default": 0.01,
      "description": "Length in seconds of the amplitude ramp at the end of the sweep, for the same reason as the fade-in."
    },
    "genSnapToFftBin": {
      "type": "boolean",
      "default": false,
      "description": "Round the generated tone to the nearest exact FFT bin centre for the current block length and sample rate. A bin-centred tone leaks no energy into its neighbours, so the analysis window needs no correction."
    },
    "genWavDurationSeconds": {
      "type": "number",
      "default": 5,
      "description": "Length in seconds of the audio file the generator writes when exporting its signal."
    },
    "genWavPath": {
      "type": "string",
      "description": "File the generator last exported its signal to, remembered so the name and format (its extension) come back on the next export."
    },
    "genWavFolder": {
      "type": "string",
      "description": "Folder the export dialog opens in when the generator writes a signal file."
    },
    "genPlayFromPath": {
      "type": "string",
      "description": "Audio file the generator last played back instead of synthesising a signal."
    },
    "genPlayFromFolder": {
      "type": "string",
      "description": "Folder the open dialog starts in when picking a file for the generator to play."
    },
    "genPlayFromLoop": {
      "type": "boolean",
      "default": false,
      "description": "Repeat the played-back file from the beginning when it reaches the end, rather than stopping."
    },

    // ---- oscilloscope file capture / playback ----------------------------
    "oscSavePath": {
      "type": "string",
      "description": "File the oscilloscope last wrote a capture to, remembered so its name and format come back next time."
    },
    "oscSaveFolder": {
      "type": "string",
      "description": "Folder the save dialog opens in when the oscilloscope writes a capture."
    },
    "oscSaveDurationSeconds": {
      "type": "number",
      "default": 5,
      "description": "Length in seconds of signal the oscilloscope records when saving a capture to file."
    },
    "oscPlayFromPath": {
      "type": "string",
      "description": "Audio file the oscilloscope last analysed instead of a live input, so a stored capture can be re-examined."
    },
    "oscPlayFromFolder": {
      "type": "string",
      "description": "Folder the open dialog starts in when picking a file for the oscilloscope to analyse."
    },
    "oscPlayFromLoop": {
      "type": "boolean",
      "default": false,
      "description": "Restart the analysed file from the beginning when it ends, so a short capture keeps the display alive."
    },

    // ---- oscilloscope appearance ----------------------------------------
    "oscLineWidth": {
      "type": "number",
      "default": 2,
      "description": "Thickness in pixels of the oscilloscope trace. It is also the pen width used to sweep the digital-phosphor image, so a wider line makes a brighter, softer trace."
    },
    "oscDotDiameter": {
      "type": "integer",
      "default": 5,
      "description": "Diameter in pixels of the individual sample markers drawn when the zoom is deep enough to separate samples."
    },
    "oscPersistenceMode": {
      "type": "string",
      "enum": ["OFF", "S_05", "S_1", "S_2", "S_5", "S_10", "S_15", "S_20", "INFINITE", "MANUAL"],
      "default": "OFF",
      "description": "Digital-phosphor persistence: how long a swept trace lingers before fading. OFF clears every frame; S_05 to S_20 fade with a decay time constant of 0.5 to 20 seconds; INFINITE accumulates forever; MANUAL takes its time from oscPersistenceManualSeconds. Requires the GPU rendering path."
    },
    "oscPersistenceManualSeconds": {
      "type": "number",
      "default": 1,
      "description": "Persistence decay time in seconds used when oscPersistenceMode is MANUAL. Ignored for every other mode."
    },
    "oscLeftChannelColor": {
      "type": "string",
      "pattern": "^#[0-9A-Fa-f]{6}$",
      "default": "#00D7FF",
      "description": "Colour of the left oscilloscope trace, written as #RRGGBB in upper-case hexadecimal. A packed 24-bit integer is also accepted when reading."
    },
    "oscRightChannelColor": {
      "type": "string",
      "pattern": "^#[0-9A-Fa-f]{6}$",
      "default": "#FFD700",
      "description": "Colour of the right oscilloscope trace, written as #RRGGBB in upper-case hexadecimal. A packed 24-bit integer is also accepted when reading."
    },

    // ---- screenshots -----------------------------------------------------
    "screenshotFolder": {
      "type": "string",
      "description": "Folder the screenshot dialog offers when saving an image, so a series of shots lands in one place."
    },
    "screenshotCommentFont": {
      "type": "string",
      "examples": ["Consolas|9|normal"],
      "description": "Font for the optional caption stamped in the corner of a screenshot, in the same family|size|style form as the interface fonts. Absent while the default caption font is in use."
    },

    // ---- oscilloscope presets -------------------------------------------
    "oscPresets": {
      "type": "object",
      "description": "Named oscilloscope setups, keyed by the name the operator typed when saving one. Recalling a preset restores the whole front panel - both channels' scaling, filtering and display mode, plus the timebase and the complete trigger setup. Omitted while no preset has been saved.",
      "additionalProperties": {
        "type": "object",
        "description": "One saved oscilloscope setup, under the name it was stored as.",
        "additionalProperties": true,
        "properties": {
          "leftChannelEnabled": {
            "type": "boolean",
            "default": true,
            "description": "Whether the left trace is drawn in this preset."
          },
          "rightChannelEnabled": {
            "type": "boolean",
            "default": true,
            "description": "Whether the right trace is drawn in this preset."
          },
          "leftAcMode": {
            "type": "boolean",
            "default": false,
            "description": "AC coupling for the left trace: the window mean is subtracted so a small signal on a DC offset stays centred."
          },
          "rightAcMode": {
            "type": "boolean",
            "default": false,
            "description": "AC coupling for the right trace: the window mean is subtracted so a small signal on a DC offset stays centred."
          },
          "leftSincInterpEnabled": {
            "type": "boolean",
            "default": true,
            "description": "Band-limited (sinc) reconstruction between samples on the left trace, rather than straight lines."
          },
          "rightSincInterpEnabled": {
            "type": "boolean",
            "default": true,
            "description": "Band-limited (sinc) reconstruction between samples on the right trace, rather than straight lines."
          },
          "leftResidualEnabled": {
            "type": "boolean",
            "default": false,
            "description": "Show the left channel's residual - the capture minus its best-fit tone - instead of the raw trace."
          },
          "rightResidualEnabled": {
            "type": "boolean",
            "default": false,
            "description": "Show the right channel's residual - the capture minus its best-fit tone - instead of the raw trace."
          },
          "leftMainsSuppression": {
            "type": "string",
            "enum": ["NONE", "IIR_COMB", "SYNC_SUBTRACT", "LMS"],
            "default": "NONE",
            "description": "Mains-hum removal on the left trace: none, a comb notch, a period-averaged subtraction, or an adaptive (LMS) subtraction."
          },
          "rightMainsSuppression": {
            "type": "string",
            "enum": ["NONE", "IIR_COMB", "SYNC_SUBTRACT", "LMS"],
            "default": "NONE",
            "description": "Mains-hum removal on the right trace: none, a comb notch, a period-averaged subtraction, or an adaptive (LMS) subtraction."
          },
          "leftLpf": {
            "type": "string",
            "enum": ["NONE", "HZ_80", "DESPIKE"],
            "default": "NONE",
            "description": "High-frequency cleanup on the left trace: none, an 80 kHz low-pass, or a de-spiking median filter."
          },
          "rightLpf": {
            "type": "string",
            "enum": ["NONE", "HZ_80", "DESPIKE"],
            "default": "NONE",
            "description": "High-frequency cleanup on the right trace: none, an 80 kHz low-pass, or a de-spiking median filter."
          },
          "leftVoltsPerDiv": {
            "type": "number",
            "default": 0.1,
            "description": "Vertical scale of the left trace in volts per screen division."
          },
          "rightVoltsPerDiv": {
            "type": "number",
            "default": 0.1,
            "description": "Vertical scale of the right trace in volts per screen division."
          },
          "leftOffsetFrac": {
            "type": "number",
            "default": 0.5,
            "description": "Vertical position of the left zero line as a fraction of screen height, 0 at the top and 1 at the bottom."
          },
          "rightOffsetFrac": {
            "type": "number",
            "default": 0.5,
            "description": "Vertical position of the right zero line as a fraction of screen height, 0 at the top and 1 at the bottom."
          },
          "timePerDiv": {
            "type": "number",
            "default": 0.001,
            "description": "Timebase in seconds per screen division."
          },
          "triggerPositionFrac": {
            "type": "number",
            "default": 0.5,
            "description": "Where the trigger instant sits across the screen, 0 at the left edge and 1 at the right; above 0 leaves room for pre-trigger history."
          },
          "triggerChannel": {
            "type": "string",
            "enum": ["L", "R"],
            "default": "L",
            "description": "Channel the trigger watches in this preset."
          },
          "triggerEdge": {
            "type": "string",
            "enum": ["RISE", "FALL"],
            "default": "RISE",
            "description": "Slope the trigger fires on: rising or falling through the level."
          },
          "triggerType": {
            "type": "string",
            "enum": ["EDGE", "GLITCH"],
            "default": "EDGE",
            "description": "Trigger event: a level crossing (EDGE) or a discontinuity in slope (GLITCH)."
          },
          "triggerMode": {
            "type": "string",
            "enum": ["AUTO", "NORMAL", "SINGLE"],
            "default": "AUTO",
            "description": "Sweep arming: free-running when untriggered (AUTO), triggered only (NORMAL), or one shot then freeze (SINGLE)."
          },
          "triggerLevelFrac": {
            "type": "number",
            "default": 0.5,
            "description": "Trigger level as a fraction of screen height, 0 at the top and 1 at the bottom."
          }
        }
      }
    },

    // ---- FFT pane --------------------------------------------------------
    "fftLength": {
      "type": "integer",
      "default": 65536,
      "description": "Samples per FFT block. Longer blocks resolve finer detail - the bin spacing is the sample rate divided by this - and take proportionally longer to fill, so the display updates less often."
    },
    "fftAverages": {
      "type": "number",
      "default": 4,
      "description": "How many FFT frames are folded into one displayed spectrum. More averaging lowers the visible noise floor and steadies the trace, at the cost of reaction time."
    },
    "fftThreads": {
      "type": "integer",
      "minimum": 1,
      "maximum": 16,
      "default": 1,
      "description": "Size of the worker pool the browser build spreads FFT work over, 1 to 16. More workers keep up with longer blocks on a multi-core machine; there is nothing to gain past the number of physical cores."
    },
    "fftStopAfterNEnabled": {
      "type": "boolean",
      "default": false,
      "description": "Stop averaging automatically once the frame count below is reached, instead of averaging continuously. Useful for a repeatable measurement of fixed depth."
    },
    "fftStopAfterN": {
      "type": "integer",
      "default": 10,
      "description": "Number of averaged frames after which the analysis stops, when the stop-after switch above is on."
    },
    "fftFundFromGenerator": {
      "type": "boolean",
      "default": false,
      "description": "Take the fundamental frequency from the generator setting rather than finding the strongest peak in the spectrum. It keeps harmonic markers on the right bins when the tone is buried in noise."
    },
    "fftLogFreqAxis": {
      "type": "boolean",
      "default": true,
      "description": "Draw the frequency axis logarithmically, giving each octave equal width. Off gives a linear axis, where harmonics fall at evenly spaced intervals."
    },
    "fftDetectTimeDiscontinuity": {
      "type": "boolean",
      "default": true,
      "description": "Reject blocks whose raw samples break the expected waveform continuity - splices, dropouts, dropped samples - before they enter the average. Turn it off for a small or non-sinusoidal signal that would otherwise trip the gate on every block and stall the analysis; the separate spectral glitch gate stays active either way."
    },
    "fftWindow": {
      "type": "string",
      "enum": ["RECT", "HANN", "BH4", "BH7", "FT", "HFT144D", "HFT248D", "KB24", "KB38",
        "DC150", "DC200", "DC250", "DC300"],
      "default": "HANN",
      "description": "Window applied to each block before the transform, trading main-lobe width against sidelobe suppression. RECT is no window at all (sharpest, worst leakage); HANN is the general-purpose choice; the flat-top and Blackman-Harris families (FT, BH4, BH7, HFT144D, HFT248D) suppress leakage far enough to measure distortion beside a large tone; the Kaiser (KB24, KB38) and Dolph-Chebyshev (DC150 to DC300) families let the sidelobe floor be chosen directly."
    },
    "fftOverlap": {
      "type": "string",
      "enum": ["PCT_0", "PCT_50", "PCT_75", "PCT_87_5", "PCT_93_75"],
      "default": "PCT_0",
      "description": "How much each analysis block overlaps the previous one: none, 50, 75, 87.5 or 93.75 percent. Overlapping reuses samples the window would otherwise attenuate, so averages build faster at the price of more computation per second."
    },
    "fftCoherentAveraging": {
      "type": "boolean",
      "default": true,
      "description": "Average the complex spectra in phase, locked to the measured tone, rather than averaging magnitudes. Coherent averaging cancels uncorrelated noise as the count grows, digging the noise floor down and leaving the tone and its harmonics intact."
    },
    "fftMainsSuppression": {
      "type": "string",
      "enum": ["NONE", "IIR_COMB", "SYNC_SUBTRACT", "LMS"],
      "default": "NONE",
      "description": "Mains-hum removal for the analysis. IIR_COMB corrects at plot time, notching the hum harmonics out of the displayed spectrum and leaving the averaging accumulator raw; SYNC_SUBTRACT and LMS remove the hum from the captured signal before averaging. All three leave the test tone untouched, and the mains frequency is tracked live."
    },
    "fftAlignGenerator": {
      "type": "string",
      "enum": ["NONE", "FLL"],
      "default": "NONE",
      "description": "Whether the generator is retuned to follow the analyser. NONE leaves it free-running; FLL closes a frequency-locked loop that nudges the generator so the tone lands exactly on a bin centre, which keeps coherent averaging in step over long runs when the converter clocks drift apart."
    },
    "fftDistMinHz": {
      "type": "number",
      "default": 20,
      "description": "Lower edge in hertz of the band the distortion figures are integrated over - content below it is ignored, like a high-pass on the analysis. Only applied when the switch below is on."
    },
    "fftDistMaxHz": {
      "type": "number",
      "default": 20000,
      "description": "Upper edge in hertz of the distortion integration band - content above it is ignored, like a low-pass on the analysis. Only applied when its switch is on."
    },
    "fftDistMinEnabled": {
      "type": "boolean",
      "default": false,
      "description": "Whether the lower distortion band edge is applied. Off means the integration starts just above DC."
    },
    "fftDistMaxEnabled": {
      "type": "boolean",
      "default": false,
      "description": "Whether the upper distortion band edge is applied. Off means the integration runs up to Nyquist."
    },
    "fftThdMaxHarmonic": {
      "type": "integer",
      "default": 9,
      "description": "Highest harmonic listed in the THD overlay table, counted from the second (H2). 9 shows H2 to H9."
    },
    "fftCalcMaxHarmonic": {
      "type": "integer",
      "default": 9,
      "description": "Highest harmonic order included in the THD figure itself, which may reach further than the table shows. The dialog accepts 9 to 50."
    },
    "fftStrongToneRelDb": {
      "type": "number",
      "default": 100,
      "description": "How far below the strongest peak, in dB, another peak may sit and still count as a separate tone. Lower it so harmonics and intermodulation products are not mistaken for independent tones, which would push averaging onto the multi-tone path. Changing it restarts the averaging."
    },
    "fftManualFundVrms": {
      "type": "number",
      "default": 1,
      "description": "Fixed fundamental level in volts RMS that distortion is referred to when the manual fundamental is enabled, instead of the level actually measured."
    },
    "fftManualFundDbvDisplay": {
      "type": "boolean",
      "default": false,
      "description": "Show the manual fundamental field in dBV rather than volts. Display only - the value is stored in volts RMS."
    },
    "fftManualFundEnabled": {
      "type": "boolean",
      "default": false,
      "description": "Refer the distortion figures to the fixed level above rather than to the measured fundamental, so readings stay comparable while the input level moves."
    },
    "fftChannel": {
      "type": "string",
      "enum": ["L", "R"],
      "default": "L",
      "description": "Channel the FFT analyses. The calibrated full-scale voltage of that channel is what its dBV readings are referred to."
    },
    "fftMagUnit": {
      "type": "string",
      "enum": ["V", "V_SQRT_HZ", "DBV", "DBFS", "DBR"],
      "default": "DBV",
      "description": "Unit of the magnitude axis. V is linear volts; V_SQRT_HZ is a noise density, volts per root hertz, which divides out the bin bandwidth so the floor no longer moves with the block length; DBV is dB relative to 1 V; DBFS is dB relative to converter full scale; DBR is dB relative to the displayed fundamental, which therefore sits at exactly 0."
    },
    "fftScreenshotWidth": {
      "type": "integer",
      "description": "Width in pixels last used for an FFT screenshot. Omitted until a size has been chosen, in which case the pane's own on-screen size is used."
    },
    "fftScreenshotHeight": {
      "type": "integer",
      "description": "Height in pixels last used for an FFT screenshot. Omitted until a size has been chosen."
    },
    "fftDistortionTableVisible": {
      "type": "boolean",
      "default": true,
      "description": "Show the THD overlay table listing the harmonic levels on top of the spectrum."
    },
    "fftFreqMinHz": {
      "type": "number",
      "default": 20,
      "description": "Left edge of the displayed frequency axis, in hertz. A view setting only - it does not change what is measured."
    },
    "fftFreqMaxHz": {
      "type": "number",
      "default": 20000,
      "description": "Right edge of the displayed frequency axis, in hertz. A view setting only - it does not change what is measured."
    },
    "fftMagTop": {
      "type": "number",
      "default": 10,
      "description": "Top of the magnitude axis, in whichever unit fftMagUnit selects (dB for the dB units, volts for the linear ones)."
    },
    "fftMagBottom": {
      "type": "number",
      "default": -150,
      "description": "Bottom of the magnitude axis, in whichever unit fftMagUnit selects. Set it below the noise floor to see how far the averaging has dug."
    },
    "fftSavePath": {
      "type": "string",
      "description": "File the FFT pane last exported a spectrum to, remembered so the name and format return next time."
    },
    "fftSaveFolder": {
      "type": "string",
      "description": "Folder the save dialog opens in when exporting a spectrum."
    },
    "fftLoadPath": {
      "type": "string",
      "description": "File the FFT pane last loaded a stored spectrum from."
    },
    "fftLoadFolder": {
      "type": "string",
      "description": "Folder the open dialog starts in when loading a stored spectrum."
    },
    "fftCalibrations": {
      "type": "array",
      "description": "Calibration curves applied to the FFT display, in the order they are listed in the pane. Each row names a .frc response file whose inverse is applied to the spectrum, so the measurement shows the device under test rather than the measuring chain. Omitted while the list is empty.",
      "items": {
        "type": "object",
        "description": "One calibration row: the file it came from and whether it is currently applied.",
        "additionalProperties": true,
        "properties": {
          "path": {
            "type": "string",
            "description": "Name of the .frc calibration file this row refers to, as the operator picked it."
          },
          "hash": {
            "type": "string",
            "description": "SHA-256 of the original file contents, in hexadecimal. It identifies the stored copy of the curve, since the browser cannot re-open a file by path."
          },
          "active": {
            "type": "boolean",
            "default": false,
            "description": "Whether this curve is currently applied. Rows can be kept in the list and switched on and off without being re-imported."
          },
          "withNoise": {
            "type": "boolean",
            "default": false,
            "description": "Also apply the noise-floor part of the calibration file, not just its magnitude response - used when the file records the measuring chain's own noise as well as its gain."
          }
        }
      }
    },
    "fftBeforeCalDotColor": {
      "type": ["integer", "string"],
      "minimum": 0,
      "maximum": 16777215,
      "default": 128,
      "description": "Colour of the markers showing where a harmonic sat before calibration was applied, written as a packed 24-bit integer (red in the high byte). The default 128 is navy. A \"#RRGGBB\" string is also accepted when reading."
    },
    "fftCalOverlayColor": {
      "type": ["integer", "string"],
      "minimum": 0,
      "maximum": 16777215,
      "default": 38400,
      "description": "Colour of the calibration curve drawn over the spectrum, as a packed 24-bit integer; the default 38400 is a mid green. A \"#RRGGBB\" string is also accepted when reading."
    },
    "fftLineWidth": {
      "type": "number",
      "default": 1,
      "description": "Thickness in pixels of the FFT spectrum trace."
    },
    "freqRespLineWidth": {
      "type": "number",
      "default": 2,
      "description": "Thickness in pixels of the frequency-response traces - magnitude, phase, the RIAA reference and the compare curve all use it."
    },
    "fftHarmonicDotDiameter": {
      "type": "integer",
      "default": 9,
      "description": "Diameter in pixels of the markers placed on the fundamental and its harmonics."
    },
    "fftLineColor": {
      "type": "string",
      "pattern": "^#[0-9A-Fa-f]{6}$",
      "default": "#0064C8",
      "description": "Colour of the FFT spectrum trace, written as #RRGGBB in upper-case hexadecimal. A packed 24-bit integer is also accepted when reading."
    },
    "fftChartBackgroundColor": {
      "type": "string",
      "pattern": "^#[0-9A-Fa-f]{6}$",
      "default": "#FFFFFF",
      "description": "Background colour of the FFT chart area, written as #RRGGBB in upper-case hexadecimal. Screenshots are taken with this background, so white prints better than a dark theme."
    },
    "fftHarmonicDotColor": {
      "type": "string",
      "pattern": "^#[0-9A-Fa-f]{6}$",
      "default": "#FF0000",
      "description": "Colour of the harmonic and fundamental markers on the spectrum, written as #RRGGBB in upper-case hexadecimal."
    },
    "fftFreqRespColor": {
      "type": "string",
      "pattern": "^#[0-9A-Fa-f]{6}$",
      "default": "#009600",
      "description": "Colour of the filter-response curve overlaid on the spectrum, written as #RRGGBB in upper-case hexadecimal."
    },

    // ---- frequency response ---------------------------------------------
    "freqRespStartHz": {
      "type": "number",
      "default": 1,
      "description": "Frequency in hertz the measurement sweep starts at. Going very low costs sweep time, because the low end needs the most cycles."
    },
    "freqRespStopHz": {
      "type": "number",
      "default": 0,
      "description": "Frequency in hertz the measurement sweep ends at. The stored 0 is a placeholder meaning \"not yet chosen\": on first use it is replaced by the current device's Nyquist frequency (half the sample rate). Any value saved afterwards is used as it stands."
    },
    "freqRespAmplitudeVrms": {
      "type": "number",
      "default": 1,
      "description": "Sweep level in volts RMS. High enough to sit above the noise floor, low enough not to drive the device under test into compression."
    },
    "freqRespAmplitudeDbvDisplay": {
      "type": "boolean",
      "default": false,
      "description": "Show the sweep amplitude field in dBV rather than volts. Display only - the level is stored in volts RMS."
    },
    "freqRespSweepPoints": {
      "type": "integer",
      "default": 0,
      "description": "Number of logarithmically spaced points the deconvolution emits along the response curve. The stored 0 is a placeholder meaning \"not yet chosen\": on first use it becomes half the device sample rate, the FS/2 point count. More points resolve narrow features; fewer smooth the curve and draw faster."
    },
    "freqRespDurationSec": {
      "type": "number",
      "default": 5.5,
      "description": "Length in seconds of the sweep itself. A longer sweep puts more energy into every frequency, which lifts the measurement out of the noise, especially at the low end."
    },
    "freqRespFftSize": {
      "type": "integer",
      "minimum": 65536,
      "maximum": 16777216,
      "default": 4194304,
      "description": "Samples in the transform used to deconvolve the captured sweep. Must be a power of two between 65536 and 16777216; a value in between is rounded up to the next power of two when read. It has to be comfortably longer than the sweep plus the device's decay, or the tail wraps round onto the start."
    },
    "freqRespDitherBits": {
      "type": "integer",
      "default": 0,
      "description": "Depth in bits of the TPDF dither added to the sweep before quantisation. 0 disables it; a few bits linearise the converter at the cost of a slightly higher noise floor."
    },
    "freqRespLeadInSec": {
      "type": "number",
      "default": 0.05,
      "description": "Silence in seconds played before the sweep starts, giving the capture path time to settle so the first samples of the sweep are not lost."
    },
    "freqRespOutputChannels": {
      "type": "string",
      "enum": ["BOTH", "LEFT", "RIGHT"],
      "default": "BOTH",
      "description": "Which output lanes the sweep drives. BOTH feeds both; LEFT or RIGHT drives one lane and writes digital silence to the other. Both input channels are deconvolved regardless, so crosstalk can be measured by driving one lane only."
    },
    "tuneNotchStartHz": {
      "type": "number",
      "default": 900,
      "description": "Lower edge in hertz of the band the notch-tuning wizard sweeps while a twin-T notch is being adjusted. Kept apart from the main pane's range so tuning does not disturb the measurement setup."
    },
    "tuneNotchStopHz": {
      "type": "number",
      "default": 1100,
      "description": "Upper edge in hertz of the notch-tuning wizard's sweep band - wide enough to see the notch move, narrow enough to resolve it."
    },
    "tuneNotchAmplitudeVrms": {
      "type": "number",
      "default": 1,
      "description": "Level in volts RMS the notch-tuning wizard drives while measuring the notch depth."
    },
    "tuneNotchTargetHz": {
      "type": "number",
      "default": 1000,
      "description": "Frequency in hertz the notch is being tuned to - the wizard reports how far the measured null sits from it."
    },
    "tuneNotchOutputChannels": {
      "type": "string",
      "enum": ["BOTH", "LEFT", "RIGHT"],
      "default": "BOTH",
      "description": "Which output lanes the notch-tuning wizard drives: both, or one with silence on the other. Kept separate from the main sweep setting."
    },
    "freqRespLeftVisible": {
      "type": "boolean",
      "default": true,
      "description": "Draw the left channel's magnitude curve in the frequency-response chart."
    },
    "freqRespRightVisible": {
      "type": "boolean",
      "default": false,
      "description": "Draw the right channel's magnitude curve in the frequency-response chart."
    },
    "freqRespPhaseVisible": {
      "type": "boolean",
      "default": false,
      "description": "Draw the phase curve alongside the magnitude, on its own axis."
    },
    "freqRespFreqMinHz": {
      "type": "number",
      "default": 20,
      "description": "Left edge of the displayed frequency axis, in hertz. A view setting only - the sweep range is set separately."
    },
    "freqRespFreqMaxHz": {
      "type": "number",
      "default": 20000,
      "description": "Right edge of the displayed frequency axis, in hertz. A view setting only - the sweep range is set separately."
    },
    "freqRespMagTopDb": {
      "type": "number",
      "default": 20,
      "description": "Top of the magnitude axis in dB."
    },
    "freqRespMagBotDb": {
      "type": "number",
      "default": -140,
      "description": "Bottom of the magnitude axis in dB."
    },
    "freqRespNyquistFraction": {
      "type": "number",
      "minimum": 0.83,
      "maximum": 1,
      "default": 1,
      "description": "Caps the analysed band at this fraction of Nyquist (half the sample rate). 1 keeps the strict Nyquist limit; a lower value trims the very top of the band, where the deconvolution kernel runs out of energy and the curve becomes unreliable. Values below 0.83 are rejected."
    },
    "freqRespCompareSmoothWindow": {
      "type": "integer",
      "minimum": 0,
      "maximum": 100,
      "default": 6,
      "description": "Width of the moving average applied to the compare curve (measured minus reference), in 1/N-octave units. 0 disables smoothing. A small N is a wide window and heavy smoothing (3 is roughly a third of an octave); a large N is narrow and light (24 is the usual audio choice)."
    },
    "freqRespNotchEnabled": {
      "type": "boolean",
      "default": false,
      "description": "Interpolate the displayed response across every harmonic of the mains frequency, which removes hum spikes from the curve without re-measuring anything."
    },
    "freqRespNotchBaseHz": {
      "type": "integer",
      "enum": [50, 60],
      "default": 50,
      "description": "Mains frequency whose harmonics are interpolated out when the notch above is on: 50 removes 50, 100, 150 Hz and so on, 60 removes 60, 120, 180 Hz. Any other value is read as 50."
    },
    "freqRespSignalColor": {
      "type": ["integer", "string"],
      "minimum": 0,
      "maximum": 16777215,
      "default": 25800,
      "description": "Colour of the measured magnitude curve, as a packed 24-bit integer (red in the high byte); the default 25800 is a mid blue. A \"#RRGGBB\" string is also accepted when reading."
    },
    "freqRespPhaseColor": {
      "type": ["integer", "string"],
      "minimum": 0,
      "maximum": 16777215,
      "default": 16711680,
      "description": "Colour of the phase curve, as a packed 24-bit integer; the default 16711680 is pure red. A \"#RRGGBB\" string is also accepted when reading."
    },
    "freqRespReferenceColor": {
      "type": ["integer", "string"],
      "minimum": 0,
      "maximum": 16777215,
      "default": 38400,
      "description": "Colour of the reference curve, such as the RIAA overlay, as a packed 24-bit integer; the default 38400 is a mid green. A \"#RRGGBB\" string is also accepted when reading."
    },
    "freqRespBackgroundColor": {
      "type": ["integer", "string"],
      "minimum": 0,
      "maximum": 16777215,
      "default": 16777215,
      "description": "Background colour of the frequency-response chart, as a packed 24-bit integer; the default 16777215 is white. A \"#RRGGBB\" string is also accepted when reading."
    },
    "freqRespReverseRiaa": {
      "type": "boolean",
      "default": false,
      "description": "Show the RIAA playback (decode) curve rather than the record (encode) curve, which is what a phono stage should follow."
    },
    "freqRespIecAmendment": {
      "type": "boolean",
      "default": false,
      "description": "Include the IEC amendment in the RIAA reference: an extra subsonic high-pass at roughly 20 Hz that suppresses rumble and warp."
    },
    "freqRespCompareMode": {
      "type": "boolean",
      "default": false,
      "description": "Plot the difference between the measured response and the reference instead of the response itself, so a deviation of a fraction of a dB becomes visible. The view auto-zooms to a narrow band around zero when switched on."
    },
    "freqRespFilterCompare": {
      "type": "boolean",
      "default": false,
      "description": "Compare the measured response against the calculated filter overlay, plotting their difference rather than both curves."
    },
    "freqRespFilterType": {
      "type": "string",
      "enum": ["LOW_PASS", "HIGH_PASS", "BAND_PASS", "NOTCH"],
      "default": "LOW_PASS",
      "description": "Kind of filter the calculated overlay draws: a low-pass or high-pass with one corner, a band-pass that keeps a band, or a notch that rejects one."
    },
    "freqRespFilterResponse": {
      "type": "string",
      "enum": ["BESSEL", "BUTTERWORTH", "CHEBYSHEV", "ELLIPTIC", "INV_CHEBYSHEV"],
      "default": "BUTTERWORTH",
      "description": "Approximation used for the filter overlay, each trading passband flatness against roll-off steepness. BESSEL has the most linear phase and the gentlest slope; BUTTERWORTH is maximally flat in the passband; CHEBYSHEV buys a steeper skirt with passband ripple; INV_CHEBYSHEV moves that ripple into the stopband; ELLIPTIC ripples in both and is the steepest for a given order."
    },
    "freqRespUnevenMode": {
      "type": "string",
      "enum": ["OFF", "LEVEL", "RANGE"],
      "default": "OFF",
      "description": "Flatness readout on the response curve. OFF computes and draws nothing; LEVEL reports the deviation over the scanned band; RANGE reports the band edges where the response first falls away by the allowed amount."
    },
    "freqRespUnevenNotch": {
      "type": "boolean",
      "default": false,
      "description": "Treat the curve as a notch for the flatness analysis: search for the lowest point and the shoulders around it, instead of walking down from the peak."
    },
    "freqRespUnevenDb": {
      "type": "number",
      "minimum": 0.001,
      "maximum": 20,
      "default": 3,
      "description": "Allowed deviation in dB for the flatness readout. The band edges are the frequencies where the response first drops this far below its peak; 3 dB is the classical half-power convention."
    },
    "freqRespUnevenStartHz": {
      "type": "number",
      "default": 20,
      "description": "Lower edge in hertz of the range scanned for the flatness readout. If it is not below the stop frequency, both edges fall back to 20 Hz and 20 kHz when read."
    },
    "freqRespUnevenStopHz": {
      "type": "number",
      "default": 20000,
      "description": "Upper edge in hertz of the range scanned for the flatness readout."
    },
    "freqRespApplyCalibration": {
      "type": "boolean",
      "default": true,
      "description": "Apply the active calibration curves below to the measured response, so the plot shows the device under test rather than the measuring chain."
    },
    "freqRespCalibrations": {
      "type": "array",
      "description": "Calibration curves available to the frequency-response pane, in list order. Each row names a .frc response file whose inverse is divided out of the measurement. Omitted while the list is empty.",
      "items": {
        "type": "object",
        "description": "One calibration row: the file it came from and whether it is currently applied.",
        "additionalProperties": true,
        "properties": {
          "path": {
            "type": "string",
            "description": "Name of the .frc calibration file this row refers to, as the operator picked it."
          },
          "hash": {
            "type": "string",
            "description": "SHA-256 of the original file contents, in hexadecimal. It identifies the stored copy of the curve, since the browser cannot re-open a file by path."
          },
          "active": {
            "type": "boolean",
            "default": false,
            "description": "Whether this curve is currently applied. Rows can be kept and switched on and off without being re-imported."
          }
        }
      }
    },
    "freqRespSaveFolder": {
      "type": "string",
      "description": "Folder the save dialog opens in when exporting a measured response."
    },
    "freqRespSavePath": {
      "type": "string",
      "description": "File the frequency-response pane last exported a measurement to, remembered so the name and format return next time."
    },
    "freqRespLoadFolder": {
      "type": "string",
      "description": "Folder the open dialog starts in when loading a stored response."
    },
    "freqRespLoadPath": {
      "type": "string",
      "description": "File the frequency-response pane last loaded a stored measurement from."
    },
    "freqRespActiveTabIndex": {
      "type": "integer",
      "default": 0,
      "description": "Zero-based index of the settings tab that was open in the frequency-response pane, so it reopens on the same group of controls."
    },
    "freqRespScreenshotWidth": {
      "type": "integer",
      "description": "Width in pixels last used for a frequency-response screenshot. Omitted until a size has been chosen, in which case the pane's own on-screen size is used."
    },
    "freqRespScreenshotHeight": {
      "type": "integer",
      "description": "Height in pixels last used for a frequency-response screenshot. Omitted until a size has been chosen."
    },

    // ---- presets ---------------------------------------------------------
    "fftPresets": {
      "type": "object",
      "description": "Named FFT setups, keyed by the name the operator typed when saving one. A preset carries both the analysis settings (block length, window, overlap, averaging, distortion band) and the view settings (axis ranges, unit, channel). Omitted while no preset has been saved.",
      "additionalProperties": {
        "type": "object",
        "description": "One saved FFT setup, under the name it was stored as.",
        "additionalProperties": true,
        "properties": {
          "channel": {
            "type": "string",
            "enum": ["L", "R"],
            "default": "L",
            "description": "Channel this preset analyses."
          },
          "magUnit": {
            "type": "string",
            "enum": ["V", "V_SQRT_HZ", "DBV", "DBFS", "DBR"],
            "default": "DBV",
            "description": "Magnitude unit: linear volts, noise density in volts per root hertz, dB relative to 1 V, dB relative to full scale, or dB relative to the fundamental."
          },
          "logFreqAxis": {
            "type": "boolean",
            "default": true,
            "description": "Logarithmic frequency axis, giving each octave equal width; off gives a linear axis."
          },
          "freqMinHz": {
            "type": "number",
            "default": 20,
            "description": "Left edge of the displayed frequency axis, in hertz."
          },
          "freqMaxHz": {
            "type": "number",
            "default": 20000,
            "description": "Right edge of the displayed frequency axis, in hertz."
          },
          "magTop": {
            "type": "number",
            "default": 10,
            "description": "Top of the magnitude axis, in the unit this preset selects."
          },
          "magBottom": {
            "type": "number",
            "default": -150,
            "description": "Bottom of the magnitude axis, in the unit this preset selects."
          },
          "fftLength": {
            "type": "integer",
            "default": 65536,
            "description": "Samples per FFT block: longer blocks resolve finer detail and take longer to fill."
          },
          "averages": {
            "type": "number",
            "default": 4,
            "description": "How many FFT frames are folded into one displayed spectrum."
          },
          "stopAfterNEnabled": {
            "type": "boolean",
            "default": false,
            "description": "Stop averaging automatically once the frame count below is reached."
          },
          "stopAfterN": {
            "type": "integer",
            "default": 10,
            "description": "Number of averaged frames after which the analysis stops."
          },
          "fundFromGenerator": {
            "type": "boolean",
            "default": false,
            "description": "Take the fundamental from the generator setting rather than from the strongest measured peak."
          },
          "window": {
            "type": "string",
            "enum": ["RECT", "HANN", "BH4", "BH7", "FT", "HFT144D", "HFT248D", "KB24", "KB38",
              "DC150", "DC200", "DC250", "DC300"],
            "default": "HANN",
            "description": "Analysis window, trading main-lobe width against sidelobe suppression - see the top-level fftWindow for what each family is for."
          },
          "overlap": {
            "type": "string",
            "enum": ["PCT_0", "PCT_50", "PCT_75", "PCT_87_5", "PCT_93_75"],
            "default": "PCT_0",
            "description": "Overlap between consecutive analysis blocks: none, 50, 75, 87.5 or 93.75 percent."
          },
          "coherentAveraging": {
            "type": "boolean",
            "default": true,
            "description": "Average the complex spectra in phase, so uncorrelated noise cancels and the floor drops with the average count."
          },
          "distMinHz": {
            "type": "number",
            "default": 20,
            "description": "Lower edge in hertz of the distortion integration band."
          },
          "distMaxHz": {
            "type": "number",
            "default": 20000,
            "description": "Upper edge in hertz of the distortion integration band."
          },
          "distMinEnabled": {
            "type": "boolean",
            "default": false,
            "description": "Whether the lower distortion band edge is applied."
          },
          "distMaxEnabled": {
            "type": "boolean",
            "default": false,
            "description": "Whether the upper distortion band edge is applied."
          },
          "thdMaxHarmonic": {
            "type": "integer",
            "default": 9,
            "description": "Highest harmonic listed in the THD overlay table."
          },
          "calcMaxHarmonic": {
            "type": "integer",
            "default": 9,
            "description": "Highest harmonic order included in the THD figure itself."
          },
          "manualFundVrms": {
            "type": "number",
            "default": 1,
            "description": "Fixed fundamental level in volts RMS that distortion is referred to when the manual fundamental is enabled."
          },
          "manualFundDbvDisplay": {
            "type": "boolean",
            "default": false,
            "description": "Show the manual fundamental field in dBV rather than volts. Display only."
          },
          "manualFundEnabled": {
            "type": "boolean",
            "default": false,
            "description": "Refer distortion to the fixed level above instead of the measured fundamental."
          }
        }
      }
    },
    "freqRespPresets": {
      "type": "object",
      "description": "Named frequency-response setups, keyed by the name the operator typed when saving one. A preset carries the sweep parameters, the reference and filter overlays and the flatness settings, so a repeat measurement is set up in one click. Omitted while no preset has been saved.",
      "additionalProperties": {
        "type": "object",
        "description": "One saved frequency-response setup, under the name it was stored as.",
        "additionalProperties": true,
        "properties": {
          "startHz": {
            "type": "number",
            "default": 20,
            "description": "Frequency in hertz the sweep starts at."
          },
          "stopHz": {
            "type": "number",
            "default": 20000,
            "description": "Frequency in hertz the sweep ends at."
          },
          "amplitudeVrms": {
            "type": "number",
            "default": 0.5,
            "description": "Sweep level in volts RMS."
          },
          "sweepPoints": {
            "type": "integer",
            "default": 65536,
            "description": "Number of logarithmically spaced points the deconvolution emits along the curve."
          },
          "fftSize": {
            "type": "integer",
            "default": 524288,
            "description": "Samples in the transform used to deconvolve the captured sweep; must comfortably exceed the sweep plus the device's decay."
          },
          "leadInSec": {
            "type": "number",
            "default": 0.2,
            "description": "Silence in seconds played before the sweep, so the capture path has settled when it starts."
          },
          "ditherBits": {
            "type": "integer",
            "default": 0,
            "description": "Depth in bits of the TPDF dither added to the sweep; 0 disables it."
          },
          "showRiaa": {
            "type": "boolean",
            "default": false,
            "description": "Overlay the RIAA reference curve, aligned at 1 kHz."
          },
          "reverseRiaa": {
            "type": "boolean",
            "default": false,
            "description": "Show the RIAA playback (decode) curve rather than the record (encode) curve."
          },
          "iecAmendment": {
            "type": "boolean",
            "default": false,
            "description": "Include the IEC subsonic high-pass amendment at roughly 20 Hz in the RIAA reference."
          },
          "compareMode": {
            "type": "boolean",
            "default": false,
            "description": "Plot the difference between measurement and reference instead of the response itself."
          },
          "showFilter": {
            "type": "boolean",
            "default": false,
            "description": "Draw the calculated filter overlay on top of the measured response."
          },
          "filterCompare": {
            "type": "boolean",
            "default": false,
            "description": "Plot the difference between the measurement and the filter overlay rather than both curves."
          },
          "filterType": {
            "type": "string",
            "enum": ["LOW_PASS", "HIGH_PASS", "BAND_PASS", "NOTCH"],
            "default": "LOW_PASS",
            "description": "Kind of filter the overlay draws: low-pass, high-pass, band-pass or notch."
          },
          "filterResponse": {
            "type": "string",
            "enum": ["BESSEL", "BUTTERWORTH", "CHEBYSHEV", "ELLIPTIC", "INV_CHEBYSHEV"],
            "default": "BUTTERWORTH",
            "description": "Filter approximation: linear-phase Bessel, maximally flat Butterworth, passband-ripple Chebyshev, stopband-ripple inverse Chebyshev, or the steepest-for-its-order elliptic."
          },
          "filterParams": {
            "type": "object",
            "description": "The filter overlay's own scalars, saved with the preset so the drawn curve comes back exactly as it was.",
            "additionalProperties": true,
            "properties": {
              "modeOrder": {
                "type": "boolean",
                "default": false,
                "description": "Specify the filter by order rather than by band edges: true uses the order and passband fields, false derives the order from the passband and stopband specification."
              },
              "rippleDb": {
                "type": "number",
                "minimum": 0.001,
                "maximum": 20,
                "default": 1,
                "description": "Allowed passband ripple in dB, for the approximations that trade ripple for steepness."
              },
              "stopAttenDb": {
                "type": "number",
                "minimum": 0,
                "maximum": 200,
                "default": 40,
                "description": "Required stopband attenuation in dB - how far down the rejected band has to be."
              },
              "centerHz": {
                "type": "number",
                "minimum": 0,
                "default": 1000,
                "description": "Centre frequency in hertz of a band-pass or notch."
              },
              "passHz": {
                "type": "number",
                "minimum": 0,
                "default": 1000,
                "description": "Passband edge in hertz - the last frequency still inside the ripple limit."
              },
              "stopHz": {
                "type": "number",
                "minimum": 0,
                "default": 2000,
                "description": "Stopband edge in hertz - the first frequency that must be attenuated by the full stopband figure. It lies above the passband edge for a low-pass and below it for a high-pass."
              },
              "orderPassHz": {
                "type": "number",
                "minimum": 0,
                "default": 1000,
                "description": "Passband edge in hertz used when the filter is specified by order instead of by band edges."
              },
              "orderRippleDb": {
                "type": "number",
                "minimum": 0.001,
                "maximum": 20,
                "default": 1,
                "description": "Passband ripple in dB used when the filter is specified by order."
              },
              "order": {
                "type": "integer",
                "minimum": 1,
                "maximum": 32,
                "default": 4,
                "description": "Filter order, 1 to 32 - the number of poles. Each order roughly adds 6 dB per octave to the final slope."
              },
              "q": {
                "type": "number",
                "minimum": 0.1,
                "maximum": 100,
                "default": 1,
                "description": "Quality factor of a band-pass or notch: centre frequency divided by bandwidth, so a higher Q is a narrower band."
              }
            }
          },
          "unevenMode": {
            "type": "string",
            "enum": ["OFF", "LEVEL", "RANGE"],
            "default": "OFF",
            "description": "Flatness readout: off, deviation over the band (LEVEL), or the band edges at the allowed deviation (RANGE)."
          },
          "unevenNotch": {
            "type": "boolean",
            "default": false,
            "description": "Analyse flatness around a minimum rather than a maximum, for a curve that is a notch."
          },
          "unevenDb": {
            "type": "number",
            "default": 3,
            "description": "Allowed deviation in dB for the flatness readout."
          },
          "unevenStartHz": {
            "type": "number",
            "default": 20,
            "description": "Lower edge in hertz of the range scanned for flatness."
          },
          "unevenStopHz": {
            "type": "number",
            "default": 20000,
            "description": "Upper edge in hertz of the range scanned for flatness."
          }
        }
      }
    },
    "freqRespFilterParamsByType": {
      "type": "object",
      "description": "The filter overlay's scalars remembered separately for each filter type, keyed by LOW_PASS, HIGH_PASS, BAND_PASS or NOTCH. Switching type therefore restores the edges and order last used for that type instead of carrying a low-pass corner into a notch. Keys outside those four are dropped when read.",
      "additionalProperties": {
        "type": "object",
        "description": "The scalars for one filter type.",
        "additionalProperties": true,
        "properties": {
          "modeOrder": {
            "type": "boolean",
            "default": false,
            "description": "Specify this filter by order rather than by band edges: true uses the order and passband fields, false derives the order from the passband and stopband specification."
          },
          "rippleDb": {
            "type": "number",
            "minimum": 0.001,
            "maximum": 20,
            "default": 1,
            "description": "Allowed passband ripple in dB, for the approximations that trade ripple for steepness."
          },
          "stopAttenDb": {
            "type": "number",
            "minimum": 0,
            "maximum": 200,
            "default": 40,
            "description": "Required stopband attenuation in dB - how far down the rejected band has to be."
          },
          "centerHz": {
            "type": "number",
            "minimum": 0,
            "default": 1000,
            "description": "Centre frequency in hertz of a band-pass or notch."
          },
          "passHz": {
            "type": "number",
            "minimum": 0,
            "default": 1000,
            "description": "Passband edge in hertz - the last frequency still inside the ripple limit. The per-type defaults keep a 4:1 ratio to the stopband edge, so a freshly picked type always draws a sensible curve."
          },
          "stopHz": {
            "type": "number",
            "minimum": 0,
            "default": 2000,
            "description": "Stopband edge in hertz. It sits above the passband edge for a low-pass and below it for a high-pass, which is what makes the two types roll off in opposite directions."
          },
          "orderPassHz": {
            "type": "number",
            "minimum": 0,
            "default": 1000,
            "description": "Passband edge in hertz used when the filter is specified by order instead of by band edges."
          },
          "orderRippleDb": {
            "type": "number",
            "minimum": 0.001,
            "maximum": 20,
            "default": 1,
            "description": "Passband ripple in dB used when the filter is specified by order."
          },
          "order": {
            "type": "integer",
            "minimum": 1,
            "maximum": 32,
            "default": 4,
            "description": "Filter order, 1 to 32 - the number of poles. Each order roughly adds 6 dB per octave to the final slope."
          },
          "q": {
            "type": "number",
            "minimum": 0.1,
            "maximum": 100,
            "default": 1,
            "description": "Quality factor of a band-pass or notch: centre frequency divided by bandwidth, so a higher Q is a narrower band."
          }
        }
      }
    },

    // ---- per-backend device selections -----------------------------------
    "perBackend": {
      "type": "object",
      "description": "Device selection and stream format remembered separately for each audio backend, keyed by the backend name (WEB_AUDIO, QA40X, the desktop names WASAPI, WDMKS, COREAUDIO and JAVASOUND, or net:<name> for a remote bench). Switching backend therefore brings back the devices and rates that backend was last used with, rather than resetting them. Keys that are neither a known backend name nor a net: reference are dropped when read.",
      "additionalProperties": {
        "type": "object",
        "description": "One backend's remembered input and output setup.",
        "additionalProperties": true,
        "properties": {
          "inputDeviceName": {
            "type": ["string", "null"],
            "default": null,
            "description": "Which capture device this backend uses. In the browser it is the Web Audio device identifier; on the desktop it was the operating system's device name. Null means nothing has been chosen yet, so the default device is used."
          },
          "outputDeviceName": {
            "type": ["string", "null"],
            "default": null,
            "description": "Which playback device this backend uses, identified the same way as the input device. Null means nothing has been chosen yet."
          },
          "inputSampleRate": {
            "type": "integer",
            "default": 384000,
            "description": "Capture sample rate in hertz. It sets the analysable bandwidth (half of it) and, together with the block length, the FFT bin spacing. Must be an integer, and a fractional value is ignored when read."
          },
          "inputBitDepth": {
            "type": "integer",
            "default": 24,
            "description": "Bits per captured sample. More bits lower the quantisation floor; 24 is what a measurement-grade converter delivers."
          },
          "outputSampleRate": {
            "type": "integer",
            "default": 384000,
            "description": "Playback sample rate in hertz for the generator and the sweep. Some hardware, the QA40x among it, runs one clock for both directions and requires this to equal the input rate."
          },
          "outputBitDepth": {
            "type": "integer",
            "default": 24,
            "description": "Bits per played sample. It sets how far the generator's own quantisation noise sits below full scale."
          }
        }
      }
    },

    // ---- component-owned blocks ------------------------------------------
    "custom": {
      "type": "object",
      "description": "Settings blocks owned by individual components rather than by the preferences themselves. Each entry belongs to whichever component or backend registered it - the QA40x keeps its own hardware options here, and the net backend keeps the list of remembered servers - and its shape is decided entirely by that owner. A block whose owner has not registered this session, because its backend was never selected or because a newer release wrote it, is kept exactly as found and written back untouched, so nothing is lost by editing the document with that component inactive.",
      "additionalProperties": true,
      "properties": {
        "qa40x": {
          "type": "object",
          "description": "Options of the QuantAsylum QA402/QA403 backend, owned by its device manager.",
          "additionalProperties": true,
          "properties": {
            "i2s": {
              "type": "boolean",
              "default": false,
              "description": "Route audio through the analyser's front-panel I2S expansion port instead of its analogue input and output connectors."
            }
          }
        },
        "net": {
          "type": "object",
          "description": "Remote benches this client remembers, owned by the net backend.",
          "additionalProperties": true,
          "properties": {
            "servers": {
              "type": "array",
              "description": "Servers the client can dial without waiting for a discovery round, so the list is populated the moment the window opens. A row with no identifier or no host is dropped when read, because it could never be reached again.",
              "items": {
                "type": "object",
                "description": "One remembered server: enough to reach it again and enough to list it before it has answered.",
                "additionalProperties": true,
                "properties": {
                  "serverId": {
                    "type": "string",
                    "description": "The server's installation identifier, which stays the same when its address or name changes. It is how the entry is recognised again."
                  },
                  "name": {
                    "type": "string",
                    "description": "The operator-visible name of the bench, as last heard from it. Falls back to the identifier when the server has not announced one."
                  },
                  "host": {
                    "type": "string",
                    "description": "Address the server was last reached at - a host name or an IP address."
                  },
                  "port": {
                    "type": "integer",
                    "description": "TCP port the server advertised. A row whose port is not positive is dropped when read."
                  },
                  "manual": {
                    "type": "boolean",
                    "default": false,
                    "description": "True when the operator typed this server in by hand, false when it was learnt from another bench's peer table."
                  }
                }
              }
            },
            "lastConnected": {
              "type": "string",
              "description": "Identifier of the server the client connected to most recently, so the next start can offer that bench first. Absent until a connection has succeeded."
            }
          }
        }
      }
    }
  }
};
