/*
 * Phonalyser — precision audio measurement workbench.
 * Copyright (C) 2026  Dimitrij Goldstein <https://github.com/dgo42>
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package org.edgo.audio.measure.cli;

import org.edgo.audio.measure.preferences.Preferences;
import org.edgo.audio.measure.cli.util.*;

import lombok.Setter;
import lombok.extern.log4j.Log4j2;
import org.edgo.audio.measure.enums.GenSignalForm;
import org.edgo.audio.measure.generator.SignalGenerator;
import org.edgo.audio.measure.sound.AudioBackend;
import org.edgo.audio.measure.sound.AudioPlayback;
import org.edgo.audio.measure.sound.DeviceRef;

import java.util.Locale;

/**
 * {@code --generate} — DAC playback only, no capture.
 *
 * <p>Synthesises one of {@code sine | triangle | rectangle | white_noise |
 * pink_noise | pink_noise_linear | sine_compensated} on the selected output
 * device for {@code --duration} seconds at {@code --amplitude} V RMS,
 * optionally with TPDF dither.  {@code sine_compensated} requires a
 * {@code --harmonics-csv} CSV (from {@code --iterative-compensate} or
 * {@code --deembed --gen-out}) and pre-distorts the sine with the inverted
 * harmonics at calibrated phases to cancel DAC distortion at the analog output.
 *
 * <p>For {@code sine_compensated} the CSV's metadata header (sample rate,
 * bit depth, amplitude, exact frequency) supplies defaults; CLI flags override
 * them.
 *
 * <p>Required: {@code --signal}, {@code --duration}.
 */
@Log4j2
public class GenerateMode {

    /** The CLI's single Preferences instance (transient mode) — injected by Main. */
    @Setter
    private Preferences prefs;

    public void run(String[] args) throws Exception {
        String samplerateArg = ArgParser.getArgValue(args, "--samplerate");
        String bitsArg       = ArgParser.getArgValue(args, "--bits");
        String signalArg     = ArgParser.getArgValue(args, "--signal");
        String freqArg       = ArgParser.getArgValue(args, "--freq");
        String amplitudeArg  = ArgParser.getArgValue(args, "--amplitude");
        String durationArg   = ArgParser.getArgValue(args, "--duration");
        String ditherArg     = ArgParser.getArgValue(args, "--dither");
        String harmonicsCsv  = ArgParser.getArgValue(args, "--harmonics-csv");

        if (signalArg == null) {
            log.error("--signal is required for --generate.");
            System.exit(1);
        }
        if (durationArg == null) {
            log.error("--duration is required for --generate.");
            System.exit(1);
        }

        GenSignalForm form = GenSignalForm.fromString(signalArg);

        // --- For sine_compensated: read generator-parameter metadata from the
        //     CSV (sample rate, bit depth, amplitude, exact frequency).  These
        //     take priority as defaults but CLI flags override them.
        SignalGenerator.Metadata csvMeta = null;
        if (form == GenSignalForm.SINE_COMP && harmonicsCsv != null) {
            csvMeta = SignalGenerator.readAppliedCompensationMetadata(harmonicsCsv);
            if (csvMeta != null) {
                log.info("CSV metadata loaded from {}: sr={} Hz, bits={}, amp={} V RMS, freq={} Hz",
                        harmonicsCsv,
                        csvMeta.getSampleRateHz() > 0 ? csvMeta.getSampleRateHz() : "(absent)",
                        csvMeta.getBitDepth()     > 0 ? csvMeta.getBitDepth()     : "(absent)",
                        csvMeta.getAmplitudeVRms() > 0
                                ? String.format(Locale.US, "%.6f", csvMeta.getAmplitudeVRms()) : "(absent)",
                        csvMeta.getFrequencyHz() > 0
                                ? String.format(Locale.US, "%.6f", csvMeta.getFrequencyHz())   : "(absent)");
            }
        }

        // --- Parameter resolution: CLI > CSV > built-in default --------------
        int sampleRate;
        if (samplerateArg != null) {
            sampleRate = Integer.parseInt(samplerateArg);
        } else if (csvMeta != null && csvMeta.getSampleRateHz() > 0) {
            sampleRate = csvMeta.getSampleRateHz();
        } else {
            log.error("--samplerate is required for --generate (and no sample_rate_hz in CSV metadata).");
            System.exit(1);
            return;
        }
        if (!SampleRates.isValid(sampleRate)) {
            log.error("--samplerate must be one of: 44100, 48000, 88200, 96000, 176400, 192000, 352800, 384000, 705600, 768000");
            System.exit(1);
        }

        int bitDepth;
        if (bitsArg != null) {
            bitDepth = Integer.parseInt(bitsArg);
        } else if (csvMeta != null && csvMeta.getBitDepth() > 0) {
            bitDepth = csvMeta.getBitDepth();
        } else {
            bitDepth = 16;
        }
        if (bitDepth != 8 && bitDepth != 16 && bitDepth != 24 && bitDepth != 32) {
            log.error("--bits must be 8, 16, 24 or 32.");
            System.exit(1);
        }

        double frequency;
        if (freqArg != null) {
            frequency = Double.parseDouble(freqArg);
        } else if (csvMeta != null && csvMeta.getFrequencyHz() > 0) {
            frequency = csvMeta.getFrequencyHz();
        } else {
            frequency = 1000.0;
        }

        double amplitudeVRms;
        if (amplitudeArg != null) {
            amplitudeVRms = Double.parseDouble(amplitudeArg);
        } else if (csvMeta != null && csvMeta.getAmplitudeVRms() > 0) {
            amplitudeVRms = csvMeta.getAmplitudeVRms();
        } else {
            amplitudeVRms = 1.0;
        }

        int    duration      = Integer.parseInt(durationArg);
        int    ditherBits    = (ditherArg    != null) ? Integer.parseInt(ditherArg)      : 0;

        DeviceRef mixer = DeviceSelector.selectOutputMixer(args);
        if (mixer == null) {
            log.error("No suitable audio output device found.");
            System.exit(1);
        }

        log.info("Mode      : generate");
        log.info("Device    : {}", mixer.name());
        log.info("SampleRate: {} Hz", sampleRate);
        log.info("Bits      : {}", bitDepth);
        log.info("Signal    : {}", form);
        log.info("Frequency : {} Hz", frequency);
        log.info("Amplitude : {} V RMS", amplitudeVRms);
        log.info("Duration  : {} second(s)", duration);
        if (ditherBits > 0) {
            log.info("Dither    : TPDF {} bit", ditherBits);
        }

        SignalGenerator generator;
        if (form == GenSignalForm.SINE_COMP) {
            if (harmonicsCsv == null) {
                log.error("--harmonics-csv <file> is required for --signal sine_compensated");
                System.exit(1);
            }
            log.info("Harmonics : {}", harmonicsCsv);
            generator = new SignalGenerator(frequency, sampleRate, amplitudeVRms, prefs.getDacFsVoltageAmpl(), harmonicsCsv);
        } else {
            generator = new SignalGenerator(form, frequency, sampleRate, amplitudeVRms, prefs.getDacFsVoltageAmpl());
        }
        try (AudioPlayback ag = AudioBackend.instance().openPlayback(mixer, sampleRate, bitDepth, ditherBits)) {
            ag.open();
            ag.play(generator, duration);
        }
    }
}
