/*
 * DeviceScanner - standalone audio-device diagnostic.
 * Copyright (C) 2026  Dimitrij Goldstein
 * GNU Affero General Public License v3 or later.
 */
package org.edgo.audio.measure.scanner;

import java.io.IOException;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.ServiceLoader;
import java.util.SortedMap;
import java.util.SortedSet;
import java.util.TreeMap;
import java.util.TreeSet;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.spi.MixerProvider;

import org.edgo.audio.measure.enums.AudioBackendType;
import org.edgo.audio.measure.sound.AudioDeviceManager;
import org.edgo.audio.measure.sound.AudioDeviceManagerProvider;
import org.edgo.audio.measure.sound.DeviceRef;
import org.edgo.audio.measure.sound.javasound.CsjsoundNativePath;

/**
 * Console diagnostic that enumerates every audio backend present on this
 * machine and writes each device with its supported sample rates and bit
 * depths to a human-readable text file - the file a user attaches to a bug
 * report.
 *
 * <p>Deliberately CACHE-FREE: managers are built fresh from their
 * {@link AudioDeviceManagerProvider}s (never through the AudioBackend
 * singleton, which would keep warm format caches), and a fresh manager starts
 * with empty caches, so every figure in the report was probed live by this
 * run.  The tool opens no streams, so a device-list rebuild is always
 * permitted.
 */
public final class DeviceScanner {

    /** Report file name: {@code device-scan-<stamp>.txt} in the working dir. */
    private static final DateTimeFormatter FILE_STAMP =
            DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");
    private static final DateTimeFormatter HEADER_STAMP =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final String INDENT_DEVICE = "    ";
    private static final String INDENT_FORMAT = "          ";
    /** First Windows 11 build number - ProductName in the registry still says
     *  "Windows 10" on upgraded systems, so the build decides. */
    private static final int WINDOWS_11_FIRST_BUILD = 22000;

    /** The report sink; every line also mirrors to the console. */
    private final PrintWriter out;
    /** The truthful OS description, detected once (registry read on Windows). */
    private final String osDescription = describeOs();

    private DeviceScanner(PrintWriter out) {
        this.out = out;
    }

    public static void main(String[] args) throws IOException {
        // Every native rides inside this jar; which one applies is decided
        // here, from the running OS and arch.  The JNA-loaded libraries
        // (portaudio DLLs under win32-*, the macOS dylibs under darwin-*)
        // extract themselves by platform; the csjsound DLL is the exception -
        // System.loadLibrary reads java.library.path only, snapshotted at VM
        // start, so on Windows it must be staged beside the process BEFORE
        // anything triggers the JavaSound SPI scan.
        // No progress line here: the console has to read exactly like the file
        // it writes, and the report's own header already states the OS and the
        // architecture this run was taken on.
        String os = System.getProperty("os.name");
        if (os.toLowerCase(Locale.ROOT).contains("win")) {
            CsjsoundNativePath.installForFatJar();
        }

        Path file = Path.of("device-scan-" + LocalDateTime.now().format(FILE_STAMP) + ".txt");
        try (PrintWriter w = new PrintWriter(Files.newBufferedWriter(file, StandardCharsets.UTF_8))) {
            new DeviceScanner(w).scan();
        }
        System.out.println();
        System.out.println("Report written to " + file.toAbsolutePath());
    }

    private void scan() {
        header();
        mixerProviders();
        for (AudioDeviceManagerProvider provider
                : ServiceLoader.load(AudioDeviceManagerProvider.class)) {
            scanBackend(provider);
        }
    }

    private void header() {
        line("Phonalyser device scanner");
        line("scanned : " + LocalDateTime.now().format(HEADER_STAMP));
        line("os      : " + osDescription);
        line("java    : " + System.getProperty("java.version") + " - "
                + System.getProperty("java.vendor"));
        line("note    : managers built fresh, no caches - every figure below was probed live by this run");
        line("");
    }

    /** The JVM maps every modern Windows to "Windows 10"/10.0; the registry
     *  carries the truth - a build number of 22000 or above is Windows 11,
     *  ProductName the edition, DisplayVersion the marketing release.  Any
     *  registry trouble falls back to the raw JVM properties. */
    private String describeOs() {
        String name = System.getProperty("os.name");
        String arch = System.getProperty("os.arch");
        String raw = name + " " + System.getProperty("os.version") + " (" + arch + ")";
        if (!name.toLowerCase(Locale.ROOT).contains("win")) {
            return raw;
        }
        try {
            String product = registryValue("ProductName");
            String display = registryValue("DisplayVersion");
            String build   = registryValue("CurrentBuildNumber");
            if (product == null || build == null) {
                return raw;
            }
            if (Integer.parseInt(build) >= WINDOWS_11_FIRST_BUILD) {
                product = product.replace("Windows 10", "Windows 11");
            }
            return product + (display != null ? " " + display : "")
                    + " build " + build + " (" + arch + ")";
        } catch (Exception e) {
            return raw;
        }
    }

    /** One value from the Windows version key, via reg.exe (pure-Java has no
     *  registry API); null when absent or unreadable. */
    private String registryValue(String valueName) throws IOException, InterruptedException {
        Process p = new ProcessBuilder("reg", "query",
                "HKLM\\SOFTWARE\\Microsoft\\Windows NT\\CurrentVersion", "/v", valueName)
                .redirectErrorStream(true).start();
        String output = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        p.waitFor();
        int marker = output.indexOf("REG_SZ");
        if (marker < 0) {
            return null;
        }
        String value = output.substring(marker + "REG_SZ".length()).trim();
        int lineEnd = value.indexOf('\r');
        return lineEnd < 0 ? value : value.substring(0, lineEnd).trim();
    }

    /** The JavaSound SPI roster - the line that answers "is csjsound loaded". */
    private void mixerProviders() {
        line("JavaSound mixer providers on the class path:");
        try {
            for (MixerProvider p : ServiceLoader.load(MixerProvider.class)) {
                line("  - " + p.getClass().getName());
            }
        } catch (Throwable t) {
            line("  ERROR enumerating mixer providers: " + describe(t));
        }
        line("");
    }

    private void scanBackend(AudioDeviceManagerProvider provider) {
        AudioBackendType type = provider.backendType();
        // A backend the running OS does not have says nothing about the machine
        // being reported on, and a reader looking for a real device should not
        // have to step over it - it is left out of the report entirely.
        if (!type.isAvailable()) {
            return;
        }
        line("=== " + type.getDisplayName() + " ===");
        if (!provider.available()) {
            line("  module present, but its hardware probe answered unavailable");
            line("");
            return;
        }
        long start = System.nanoTime();
        try {
            AudioDeviceManager manager = provider.create();
            // A read-only scan holds no streams, so the rebuild is always
            // permitted - this drops PortAudio's process-wide snapshot too.
            boolean rebuilt = manager.refreshDeviceList();
            line("  device list: " + (rebuilt ? "re-enumerated for this scan" : "enumerated fresh"));
            scanDirection(manager, false);
            scanDirection(manager, true);
        } catch (Throwable t) {
            line("  ERROR: " + describe(t));
        }
        line(String.format(Locale.ROOT, "  scan time: %.1f s", (System.nanoTime() - start) / 1e9));
        line("");
    }

    private void scanDirection(AudioDeviceManager manager, boolean output) {
        line(output ? "  OUTPUT devices:" : "  INPUT devices:");
        List<DeviceRef> devices;
        try {
            devices = output ? manager.listOutputDevices() : manager.listInputDevices();
        } catch (Throwable t) {
            line(INDENT_DEVICE + "ERROR listing devices: " + describe(t));
            return;
        }
        if (devices.isEmpty()) {
            line(INDENT_DEVICE + "(none)");
            return;
        }
        for (DeviceRef device : devices) {
            // The backend's own rendering, not one composed here: a device that
            // knows how it should read - a card name and a port instead of an
            // ALSA address - would otherwise have that answer ignored.
            // Formats print exactly as the backend reports them - this report
            // shows what the live backends DO, so filtering happens in the
            // backend or not at all (JavaSound itself omits phantom
            // format-less devices from its listing).
            formats(manager, device, output);
        }
    }

    private void formats(AudioDeviceManager manager, DeviceRef device, boolean output) {
        List<AudioFormat> formats;
        try {
            formats = manager.listSupportedFormats(device, output);
        } catch (Throwable t) {
            line(INDENT_DEVICE + device.displayName());
            line(INDENT_FORMAT + "ERROR probing formats: " + describe(t));
            return;
        }
        if (formats.isEmpty()) {
            line(INDENT_DEVICE + device.displayName());
            line(INDENT_FORMAT + "(no formats reported)");
            return;
        }
        line(INDENT_DEVICE + device.displayName());
        // One AudioFormat per (rate, depth) pair - fold into rate -> depths.
        SortedMap<Integer, SortedSet<Integer>> byRate = new TreeMap<>();
        for (AudioFormat f : formats) {
            byRate.computeIfAbsent(Math.round(f.getSampleRate()), r -> new TreeSet<>())
                    .add(f.getSampleSizeInBits());
        }
        for (var entry : byRate.entrySet()) {
            StringBuilder bits = new StringBuilder();
            for (int b : entry.getValue()) {
                bits.append(b).append(" ");
            }
            line(INDENT_FORMAT + String.format(Locale.ROOT, "%6d Hz: %sbits", entry.getKey(), bits));
        }
    }

    /** Exception class + message, verbatim - the message IS the diagnostic. */
    private String describe(Throwable t) {
        return t.getClass().getSimpleName() + (t.getMessage() != null ? ": " + t.getMessage() : "");
    }

    private void line(String text) {
        out.println(text);
        System.out.println(text);
    }
}
