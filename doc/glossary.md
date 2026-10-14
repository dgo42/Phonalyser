# Glossary

The words used for the FFT measurement, its averaging and its phase
alignment - one meaning each, in the measurement's own language, with the
place in the code that owns it.  Every reply about this part of the system
uses these words and no others; a new term is added here before it is used.

## Capture and analysis

- **analysis cycle** - one pass of the analyser: it takes one hop of new samples, transforms the window and hands the result on; a few per second. Runs in [FftAnalyzerWorker.doAnalysis](modules/phonalyser-gui/src/main/java/org/edgo/audio/measure/gui/fft/FftAnalyzerWorker.java#L3671).
- **hop** - the number of new samples between two cycles, set by the overlap.
- **window** - the span of samples one transform covers (the FFT length); at 262144 points and 48 kHz it is 5.46 s long. Held in the worker's sliding buffer.
- **frame** - one transform of the window. While averaging is on, each cycle transforms two fresh frames ([FftAnalyzerWorker.java:3782-3783](modules/phonalyser-gui/src/main/java/org/edgo/audio/measure/gui/fft/FftAnalyzerWorker.java#L3782-L3783)).
- **result** - what one cycle produces: the spectrum, the fundamental, the harmonic ladder, the statistics, the intermodulation products. The type is [FftResult](modules/phonalyser-core/src/main/java/org/edgo/audio/measure/fft/FftResult.java).
- **hand-off slot** - the one parked result the worker offers the display; a newer result replaces an undrained one. Drained by [FftAnalyzerWorker.drainPendingResult](modules/phonalyser-gui/src/main/java/org/edgo/audio/measure/gui/fft/FftAnalyzerWorker.java#L4365), which drops a result from before the last reset.
- **epoch** - the counter that a reset advances; every result carries the epoch it was produced under, so a result from before a reset is recognisable.

## Averaging

- **collection** - everything measured since the last reset: the average and the alignment that serves it. A change of the generated signal, a statistics reset or a Record start begins a new one.
- **accumulator** - the running sum of the frames of a collection, aligned so that the tones add coherently. Lives in the worker (accumRe / accumIm); filled by accumulateIntoForeverBuffer.
- **coherent average** - the accumulator divided by its frame count: tones keep their phase relations and grow with the count, noise does not. What the display shows.
- **averaging depth** - how many frames the accumulator holds. The result's frameCount, stamped by the display rebuild ([FftAnalyzerWorker.overlayAccumulatorOnto](modules/phonalyser-gui/src/main/java/org/edgo/audio/measure/gui/fft/FftAnalyzerWorker.java#L3385)).
- **averages performed** - the count of cycles that averaged into the accumulator since the reset, unbounded; the seed cycle (the first, which starts the average instead of adding to one) is not counted. The worker's completedAnalyses.
- **averages in the window** - what the "N averages" readout states: the averages performed while the averaging is unbounded, the configured window length once a moving average has filled it. The worker's averagesInWindow.
- **moving average** - averaging bounded to the last N frames (a ring); "forever" is the unbounded average.
- **reset** - the accumulator starts empty and a new collection begins.
- **re-sync** - the window is thrown away and rebuilt from fresh samples after a glitch (a discontinuity in the samples) or a ring overrun; the average is kept. [onSignalDiscontinuity](modules/phonalyser-gui/src/main/java/org/edgo/audio/measure/gui/fft/FftAnalyzerWorker.java#L987).
- **realign** - the one-shot correction of the first frame after a re-sync so it adds to the accumulator in phase; logged as "FFT re-sync realigned: N sample one-shot".
- **de-rotation** - turning every bin of a frame back to the accumulator's time origin before adding it: bin k turns by 2 pi k D / N for an advance of D samples. The per-bin angle is theta times k; with a window other than rectangular one angle per tone lobe is used instead.
- **time advance** - the number of samples between the accumulator's reference frame and the frame being added: the D of the de-rotation.

## Phase alignment

- **pilot** - one of two generated lines far above the harmonics (about 18.5 and 19.5 kHz on the bench), one fundamental bin apart, put on the generator's output by the analyser so the time advance can be MEASURED from their phases instead of counted. Elected in [FftAnalyzerWorker.selectPilot](modules/phonalyser-gui/src/main/java/org/edgo/audio/measure/gui/fft/FftAnalyzerWorker.java#L2561); generated in [SignalGenerator](modules/phonalyser-core/src/main/java/org/edgo/audio/measure/generator/SignalGenerator.java#L809).
- **pilot lock** - at switch-on a pilot starts phase-locked to the main tone on its first sample; a re-aim keeps the accumulator running.
- **pair** - the two pilots read together: [PairAdvance](modules/phonalyser-gui/src/main/java/org/edgo/audio/measure/gui/fft/PairAdvance.java).
- **coarse reading** - the pilots' phase difference on a frame, which equals the fundamental's advance modulo one turn because the pilots sit one fundamental bin apart; carries both pilots' noise whole.
- **fine reading** - one pilot's own phase change with its whole turns taken from the correction in force, scaled down to the fundamental's frequency; its noise is divided by the ratio of the bins (about 19.5). [PairAdvance.fine](modules/phonalyser-gui/src/main/java/org/edgo/audio/measure/gui/fft/PairAdvance.java#L423).
- **correction** - the number of samples that reconciles the pilots' own phases with their difference; **in force** while frames fit it, a **candidate** while the next frames must confirm it, **carried** as the last value known when none is in force.
- **miss** - on one frame, the mean of the two fine readings minus the coarse reading, degrees at the fundamental; a correction fits while the miss is inside the admission (5.8 sigma of the reading noise, at most 3 degrees).
- **settled / unsettled** - a frame is settled when a correction is in force and fits it; an unsettled frame is shown but not averaged.
- **decision span** - the stretch of frames (root two transforms long) gathered before a new correction is chosen.
- **seed candidate** - the candidate proposed without a decision span: the value nearest the carried correction that the coarse reading allows, offered at a collection's start and when a correction stops fitting, confirmed by two frames; refused once, the pair gathers decision spans instead ([PairAdvance.java:300-317](modules/phonalyser-gui/src/main/java/org/edgo/audio/measure/gui/fft/PairAdvance.java#L300-L317)).
- **fine advance** - the time advance in samples taken from the fine readings; what the accumulator de-rotates by since the pair returns it ([PairAdvance.java:336](modules/phonalyser-gui/src/main/java/org/edgo/audio/measure/gui/fft/PairAdvance.java#L336)).
- **excess** - the fundamental's phase change on a frame beyond what the time advance explains: the phase the notch adds as it drifts. Taken out of the fundamental's lobe only (F1 and F2 in dual tone), before the frame is added.
- **notch drift** - the slow change of the notch filter's phase at the fundamental with temperature; the cause of the excess.
- **reference channel** - the other input channel, carrying the generator's output without the notch; the alignment mode that reads the fundamental's phase there instead of from the pilots.

## Generator side

- **FLL** - the frequency-lock loop that trims the generator's tone onto the analysis bin grid from the measured tone frequency; it runs in the worker ([FftAnalyzerWorker.applyFrequencyLock](modules/phonalyser-gui/src/main/java/org/edgo/audio/measure/gui/fft/FftAnalyzerWorker.java#L2570)), one loop per tone.
- **trim** - the frequency the FLL publishes for a tone (its target plus the loop's correction); the generator moves the tone and re-aims the pilots by the same ratio.
- **emitted pair** - the two tone frequencies the generator states it is emitting (tone 2 is 0 for a single tone); asked once when a collection starts and held by the worker (heldEmittedHz), updated by the FLL's own trims.
- **re-aim** - the generator taking new pilot frequencies without restarting their phase.
- **pilot re-zero** - the one-shot new zeros for the pilot tracking once the FLL reports tracking: the collection's opening frames stand on the untrimmed tone, so the first settled frame after the lock is on grid becomes the new zero, its advance and excess carried so the accumulator stays continuous ([FftAnalyzerWorker.java](modules/phonalyser-gui/src/main/java/org/edgo/audio/measure/gui/fft/FftAnalyzerWorker.java) pilotRetrackPending).
- **DPD** - the predistortion: harmonic corrections applied in the generator so the measured harmonics cancel; a round collects an average, computes the corrections and applies them.
