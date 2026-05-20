package org.edgo.audio.measure;

import lombok.extern.log4j.Log4j2;
import org.edgo.audio.measure.cli.AnalyzeHistogramMode;
import org.edgo.audio.measure.cli.util.ArgParser;
import org.edgo.audio.measure.cli.DeembedMode;
import org.edgo.audio.measure.cli.util.DeviceSelector;
import org.edgo.audio.measure.cli.FftAnalyzeMode;
import org.edgo.audio.measure.cli.FreqRespMode;
import org.edgo.audio.measure.cli.GenFftMode;
import org.edgo.audio.measure.cli.GenerateMode;
import org.edgo.audio.measure.cli.HistogramMode;
import org.edgo.audio.measure.cli.IterativeCompensateMode;
import org.edgo.audio.measure.cli.ProcessWavMode;
import org.edgo.audio.measure.cli.RecordWavMode;
import org.edgo.audio.measure.cli.RegressCalibrateMode;
import org.edgo.audio.measure.cli.UsagePrinter;
import org.edgo.audio.measure.enums.AudioBackendType;
import org.edgo.audio.measure.sound.AudioBackend;

@Log4j2
public class Main {

    public static void main(String[] args) throws Exception {
        new Main().run(args);
    }

    public void run(String[] args) throws Exception {
        if (ArgParser.hasArg(args, "--help") || args.length == 0) {
            UsagePrinter.printUsage();
            return;
        }
        AudioBackend.instance().setActive(AudioBackendType.fromString(ArgParser.getArgValue(args, "--backend")));
        if (ArgParser.hasArg(args, "--iterative-compensate")) {
            IterativeCompensateMode.run(args);
            return;
        }
        if (ArgParser.hasArg(args, "--gen-fft")) {
            GenFftMode.run(args);
            return;
        }
        if (ArgParser.hasArg(args, "--freq-response")) {
            FreqRespMode.run(args);
            return;
        }
        if (ArgParser.getArgValue(args, "--analyze-histogram") != null) {
            AnalyzeHistogramMode.run(args);
            return;
        }
        if (ArgParser.getArgValue(args, "--fft-analyze") != null) {
            FftAnalyzeMode.run(args);
            return;
        }
        if (ArgParser.hasArg(args, "--deembed")) {
            DeembedMode.run(args);
            return;
        }
        if (ArgParser.getArgValue(args, "--regress-calibrate") != null) {
            RegressCalibrateMode.run(args);
            return;
        }
        if (ArgParser.getArgValue(args, "--process-wav") != null) {
            ProcessWavMode.run(args);
            return;
        }
        DeviceSelector.logProviders();
        if (ArgParser.hasArg(args, "--list")) {
            DeviceSelector.listDevices();
            return;
        }
        if (ArgParser.hasArg(args, "--generate")) {
            GenerateMode.run(args);
            return;
        }
        if (ArgParser.hasArg(args, "--record-wav") || ArgParser.getArgValue(args, "--record-mapped-wav") != null) {
            RecordWavMode.run(args);
            return;
        }
        if (ArgParser.hasArg(args, "--histogram")) {
            HistogramMode.run(args);
            return;
        }
        log.error("No mode flag given. Use --help for the list of modes.");
        System.exit(1);
    }
}
