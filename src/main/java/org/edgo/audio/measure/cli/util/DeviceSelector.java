package org.edgo.audio.measure.cli.util;

import lombok.experimental.UtilityClass;
import lombok.extern.log4j.Log4j2;
import org.edgo.audio.measure.sound.AudioBackend;
import org.edgo.audio.measure.sound.DeviceRef;

import javax.sound.sampled.spi.MixerProvider;
import java.util.List;
import java.util.ServiceLoader;

@Log4j2
@UtilityClass
public class DeviceSelector {

    public void logProviders() {
        log.info("Registered MixerProviders:");
        for (MixerProvider p : ServiceLoader.load(MixerProvider.class)) {
            log.info("  {}", p.getClass().getName());
        }
    }

    public void listDevices() {
        List<DeviceRef> inputs = AudioBackend.instance().listInputDevices();
        if (inputs.isEmpty()) {
            log.info("No audio input devices found.");
        } else {
            log.info("Available audio input devices ({}):", AudioBackend.instance().active());
            inputs.forEach(d -> {
                log.info("  [{}] {} ({}) — {}", d.index(), d.name(), d.description(), d.vendor());
                AudioBackend.instance().listSupportedInputFormats(d).forEach(f -> log.info("      {}", f));
            });
        }

        List<DeviceRef> outputs = AudioBackend.instance().listOutputDevices();
        if (outputs.isEmpty()) {
            log.info("No audio output devices found.");
        } else {
            log.info("Available audio output devices ({}):", AudioBackend.instance().active());
            outputs.forEach(d -> {
                log.info("  [{}] {} ({}) — {}", d.index(), d.name(), d.description(), d.vendor());
                AudioBackend.instance().listSupportedOutputFormats(d).forEach(f -> log.info("      {}", f));
            });
        }
    }

    public DeviceRef selectMixer(String[] args) {
        String indexArg = ArgParser.getArgValue(args, "--device");
        if (indexArg != null) {
            DeviceRef found = AudioBackend.instance().getDeviceByIndex(Integer.parseInt(indexArg), false);
            if (found == null) {
                log.error("Device not found at index: {}", indexArg);
                listDevices();
            }
            return found;
        }

        List<DeviceRef> devices = AudioBackend.instance().listInputDevices();
        if (devices.isEmpty()) {
            return null;
        }
        if (devices.size() == 1) {
            return AudioBackend.instance().getDeviceByIndex(devices.get(0).index(), false);
        }

        log.error("Multiple audio input devices found. Use --device <index> to select one:");
        devices.forEach(d -> log.error("  {}", d));
        return null;
    }

    public DeviceRef selectOutputMixer(String[] args) {
        String indexArg = ArgParser.getArgValue(args, "--device");
        if (indexArg != null) {
            return AudioBackend.instance().getDeviceByIndex(Integer.parseInt(indexArg), true);
        }
        List<DeviceRef> devices = AudioBackend.instance().listOutputDevices();
        if (devices.isEmpty()) {
            return null;
        }
        if (devices.size() == 1) {
            return AudioBackend.instance().getDeviceByIndex(devices.get(0).index(), true);
        }
        log.error("Multiple audio output devices found. Use --device <index> to select one:");
        devices.forEach(d -> log.error("  {}", d));
        return null;
    }

    public DeviceRef selectMixerByFlag(String[] args, String flag, boolean isOutput) {
        String indexArg = ArgParser.getArgValue(args, flag);
        if (indexArg != null) {
            DeviceRef found = AudioBackend.instance().getDeviceByIndex(Integer.parseInt(indexArg), isOutput);
            if (found == null) {
                log.error("Device not found at index {} ({})", indexArg, flag);
                listDevices();
            }
            return found;
        }
        List<DeviceRef> devices = isOutput
                ? AudioBackend.instance().listOutputDevices()
                : AudioBackend.instance().listInputDevices();
        if (devices.isEmpty())  return null;
        if (devices.size() == 1) return AudioBackend.instance().getDeviceByIndex(devices.get(0).index(), isOutput);
        log.error("Multiple {} devices — use {} <index>:", isOutput ? "output" : "input", flag);
        devices.forEach(d -> log.error("  {}", d));
        return null;
    }
}
