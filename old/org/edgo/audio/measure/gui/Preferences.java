package org.edgo.audio.measure.gui;

import lombok.Getter;
import lombok.Setter;
import lombok.extern.log4j.Log4j2;
import org.edgo.audio.measure.sound.AudioBackendType;
import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Process-wide GUI preferences.  Singleton — access via {@link #instance()}.
 *
 * <p>State is persisted as YAML to a file in the application's running
 * directory (see {@link #PREFS_FILE}).  The file is loaded the first time
 * {@code instance()} is called, and rewritten by {@link #save()} after every
 * user-visible change.  When the file is missing or malformed the defaults
 * remain.
 *
 * <p>Per-backend audio settings (input/output device name, sample rate, bit
 * depth) are stored in a {@link BackendPrefs} map keyed by
 * {@link AudioBackendType}, so switching backends preserves each one's
 * selections.  Oscilloscope toolbar state lives at the top level.
 *
 * <p>Devices are persisted by name (a string) rather than as
 * {@link org.edgo.audio.measure.sound.DeviceRef} objects because the
 * concrete implementations are backend-specific and not serialisable on
 * their own.  The dialog re-resolves a name to a live {@code DeviceRef} by
 * matching against the current device list.
 */
@Log4j2
public final class Preferences {

    /** Name of the YAML file in the working directory. */
    private static final String PREFS_FILE = "preferences.yaml";

    private static volatile Preferences instance;

    @Getter @Setter
    private AudioBackendType backend = AudioBackendType.CSJSOUND;

    private final Map<AudioBackendType, BackendPrefs> perBackend =
            new EnumMap<>(AudioBackendType.class);

    // -------------------------------------------------------------------------
    // Oscilloscope toolbar state — not backend-specific.
    // -------------------------------------------------------------------------

    @Getter @Setter private boolean oscLeftChannelEnabled  = true;
    @Getter @Setter private boolean oscRightChannelEnabled = true;
    /** AC display mode per channel: remove the DC mean from the rendered trace. Measurements are unaffected. */
    @Getter @Setter private boolean oscLeftAcMode  = false;
    @Getter @Setter private boolean oscRightAcMode = false;
    @Getter @Setter private int     oscLeftVoltsPerDivIdx  = 15;  // 100 mV/div
    @Getter @Setter private int     oscRightVoltsPerDivIdx = 15;  // 100 mV/div
    @Getter @Setter private int     oscTimePerDivIdx       = 6;   // 1 ms/div
    @Getter @Setter private TriggerChannel oscTriggerChannel = TriggerChannel.L;
    @Getter @Setter private TriggerEdge    oscTriggerEdge    = TriggerEdge.RISE;
    @Getter @Setter private TriggerMode    oscTriggerMode    = TriggerMode.AUTO;
    /** Trigger hysteresis in oscilloscope divisions; 0 disables hysteresis. */
    @Getter @Setter private double         oscTriggerHysteresisDiv = 0.0;
    @Getter @Setter private boolean        oscSincInterpEnabled = true;

    // Per-pane slider state.  All values are fractions of the visible window
    // (independent of V/div and t/div): 0.5 = centred, 0 = top/left edge,
    // 1 = bottom/right edge.  Offset is per-channel; the slider on the scope
    // canvas controls whichever channel is currently selected in the
    // measurement table.
    @Getter @Setter private double oscLeftOffsetFrac      = 0.5;
    @Getter @Setter private double oscRightOffsetFrac     = 0.5;
    @Getter @Setter private double oscTriggerLevelFrac    = 0.5;
    @Getter @Setter private double oscTriggerPositionFrac = 0.5;
    /** Sliding-window duration (seconds) for measurement avg / min / max / σ. */
    @Getter @Setter private double         oscMeasurementAverageSeconds = 5.0;
    /** Trace stroke width (pixels). */
    @Getter @Setter private double         oscLineWidth         = 2.0;
    /** Sample-dot diameter (pixels) when the inter-sample spacing exceeds 10 px. */
    @Getter @Setter private int            oscDotDiameter       = 5;
    /** Packed RGB (0xRRGGBB) of the left and right channel traces. */
    @Getter @Setter private int            oscLeftChannelColor  = 0x28DCF0;  // (40, 220, 240) cyan
    @Getter @Setter private int            oscRightChannelColor = 0xF0DC28;  // (240, 220, 40) yellow

    /** Last screenshot resolution / folder, 0 / null = use the pane's current size / system default. */
    @Getter @Setter private int    screenshotWidth;
    @Getter @Setter private int    screenshotHeight;
    @Getter @Setter private String screenshotFolder;
    /** Channel whose live samples drive the measurement table (auto-flipped to the other when this one is disabled). */
    @Getter @Setter private TriggerChannel oscMeasurementChannel = TriggerChannel.L;

    // -------------------------------------------------------------------------
    // Window geometry — restored on the next launch.  Defaults of 0 / null mean
    // "no saved value yet"; the GUI falls back to its built-in defaults.
    // -------------------------------------------------------------------------

    @Getter @Setter private int   windowWidth;
    @Getter @Setter private int   windowHeight;
    @Getter @Setter private int[] hSplitWeights;   // generator | (osc / fft)
    @Getter @Setter private int[] vSplitWeights;   // oscilloscope / fft

    private Preferences() {
        load();
    }

    public static Preferences instance() {
        Preferences local = instance;
        if (local == null) {
            synchronized (Preferences.class) {
                local = instance;
                if (local == null) {
                    local = new Preferences();
                    instance = local;
                }
            }
        }
        return local;
    }

    /**
     * Returns the saved preferences for {@code type}, lazily creating a
     * default entry on first access.
     */
    public BackendPrefs prefsFor(AudioBackendType type) {
        synchronized (perBackend) {
            BackendPrefs p = perBackend.get(type);
            if (p == null) {
                p = new BackendPrefs();
                perBackend.put(type, p);
            }
            return p;
        }
    }

    /** Shorthand for {@code prefsFor(getBackend())}. */
    public BackendPrefs current() {
        return prefsFor(backend);
    }

    // -------------------------------------------------------------------------
    // YAML persistence
    // -------------------------------------------------------------------------

    /** Writes the current preferences to {@link #PREFS_FILE} in the working dir. */
    public synchronized void save() {
        Map<String, Object> root = toMap();
        DumperOptions opts = new DumperOptions();
        opts.setDefaultFlowStyle(DumperOptions.FlowStyle.BLOCK);
        opts.setIndent(2);
        opts.setPrettyFlow(true);
        try (Writer w = Files.newBufferedWriter(prefsPath())) {
            new Yaml(opts).dump(root, w);
        } catch (IOException e) {
            log.warn("Failed to save preferences to {}: {}", PREFS_FILE, e.getMessage());
        }
    }

    /** Loads preferences from {@link #PREFS_FILE} if present.  No-op if missing or unreadable. */
    public synchronized void load() {
        Path path = prefsPath();
        if (!Files.exists(path)) return;
        try (Reader r = Files.newBufferedReader(path)) {
            Object loaded = new Yaml().load(r);
            if (loaded instanceof Map<?, ?> root) {
                fromMap(root);
                log.info("Preferences loaded from {}", path.toAbsolutePath());
            }
        } catch (IOException | RuntimeException e) {
            log.warn("Failed to load preferences from {}: {}", path, e.getMessage());
        }
    }

    private Path prefsPath() {
        return Paths.get(System.getProperty("user.dir"), PREFS_FILE);
    }

    private Map<String, Object> toMap() {
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("backend",                backend.name());
        root.put("windowWidth",            windowWidth);
        root.put("windowHeight",           windowHeight);
        if (hSplitWeights != null) root.put("hSplitWeights", intArrayToList(hSplitWeights));
        if (vSplitWeights != null) root.put("vSplitWeights", intArrayToList(vSplitWeights));
        root.put("oscLeftChannelEnabled",  oscLeftChannelEnabled);
        root.put("oscRightChannelEnabled", oscRightChannelEnabled);
        root.put("oscLeftAcMode",          oscLeftAcMode);
        root.put("oscRightAcMode",         oscRightAcMode);
        root.put("oscLeftVoltsPerDivIdx",  oscLeftVoltsPerDivIdx);
        root.put("oscRightVoltsPerDivIdx", oscRightVoltsPerDivIdx);
        root.put("oscTimePerDivIdx",       oscTimePerDivIdx);
        root.put("oscTriggerChannel",      oscTriggerChannel.name());
        root.put("oscTriggerEdge",         oscTriggerEdge.name());
        root.put("oscTriggerMode",         oscTriggerMode.name());
        root.put("oscTriggerHysteresisDiv", oscTriggerHysteresisDiv);
        root.put("oscSincInterpEnabled",   oscSincInterpEnabled);
        root.put("oscLeftOffsetFrac",      oscLeftOffsetFrac);
        root.put("oscRightOffsetFrac",     oscRightOffsetFrac);
        root.put("oscTriggerLevelFrac",    oscTriggerLevelFrac);
        root.put("oscTriggerPositionFrac", oscTriggerPositionFrac);
        root.put("oscMeasurementAverageSeconds", oscMeasurementAverageSeconds);
        root.put("oscMeasurementChannel",        oscMeasurementChannel.name());
        root.put("oscLineWidth",                 oscLineWidth);
        root.put("oscDotDiameter",               oscDotDiameter);
        root.put("oscLeftChannelColor",          oscLeftChannelColor);
        root.put("oscRightChannelColor",         oscRightChannelColor);
        if (screenshotWidth  > 0)         root.put("screenshotWidth",  screenshotWidth);
        if (screenshotHeight > 0)         root.put("screenshotHeight", screenshotHeight);
        if (screenshotFolder != null)     root.put("screenshotFolder", screenshotFolder);

        Map<String, Object> perBackendMap = new LinkedHashMap<>();
        synchronized (perBackend) {
            for (Map.Entry<AudioBackendType, BackendPrefs> e : perBackend.entrySet()) {
                Map<String, Object> bp = new LinkedHashMap<>();
                BackendPrefs v = e.getValue();
                bp.put("inputDeviceName",  v.inputDeviceName);
                bp.put("outputDeviceName", v.outputDeviceName);
                bp.put("inputSampleRate",  v.inputSampleRate);
                bp.put("inputBitDepth",    v.inputBitDepth);
                bp.put("outputSampleRate", v.outputSampleRate);
                bp.put("outputBitDepth",   v.outputBitDepth);
                perBackendMap.put(e.getKey().name(), bp);
            }
        }
        root.put("perBackend", perBackendMap);
        return root;
    }

    private void fromMap(Map<?, ?> root) {
        if (root.get("backend") instanceof String s) {
            backend = enumOr(AudioBackendType.class, s, backend);
        }
        if (root.get("windowWidth")            instanceof Integer i) windowWidth            = i;
        if (root.get("windowHeight")           instanceof Integer i) windowHeight           = i;
        if (root.get("hSplitWeights")          instanceof List<?> l) hSplitWeights          = listToIntArray(l);
        if (root.get("vSplitWeights")          instanceof List<?> l) vSplitWeights          = listToIntArray(l);
        if (root.get("oscLeftChannelEnabled")  instanceof Boolean b) oscLeftChannelEnabled  = b;
        if (root.get("oscLeftAcMode")          instanceof Boolean b) oscLeftAcMode          = b;
        if (root.get("oscRightAcMode")         instanceof Boolean b) oscRightAcMode         = b;
        if (root.get("oscRightChannelEnabled") instanceof Boolean b) oscRightChannelEnabled = b;
        if (root.get("oscLeftVoltsPerDivIdx")  instanceof Integer i) oscLeftVoltsPerDivIdx  = i;
        if (root.get("oscRightVoltsPerDivIdx") instanceof Integer i) oscRightVoltsPerDivIdx = i;
        if (root.get("oscTimePerDivIdx")       instanceof Integer i) oscTimePerDivIdx       = i;
        if (root.get("oscTriggerChannel")      instanceof String  s) oscTriggerChannel = enumOr(TriggerChannel.class, s, oscTriggerChannel);
        if (root.get("oscTriggerEdge")         instanceof String  s) oscTriggerEdge    = enumOr(TriggerEdge.class,    s, oscTriggerEdge);
        if (root.get("oscTriggerMode")         instanceof String  s) oscTriggerMode    = enumOr(TriggerMode.class,    s, oscTriggerMode);
        if (root.get("oscTriggerHysteresisDiv") instanceof Number n) oscTriggerHysteresisDiv = n.doubleValue();
        if (root.get("oscSincInterpEnabled")   instanceof Boolean b) oscSincInterpEnabled = b;
        if (root.get("oscLeftOffsetFrac")      instanceof Number n) oscLeftOffsetFrac      = n.doubleValue();
        if (root.get("oscRightOffsetFrac")     instanceof Number n) oscRightOffsetFrac     = n.doubleValue();
        if (root.get("oscTriggerLevelFrac")    instanceof Number n) oscTriggerLevelFrac    = n.doubleValue();
        if (root.get("oscTriggerPositionFrac") instanceof Number n) oscTriggerPositionFrac = n.doubleValue();
        if (root.get("oscMeasurementAverageSeconds") instanceof Number n) oscMeasurementAverageSeconds = n.doubleValue();
        if (root.get("oscMeasurementChannel")        instanceof String s) oscMeasurementChannel = enumOr(TriggerChannel.class, s, oscMeasurementChannel);
        if (root.get("oscLineWidth")                 instanceof Number n) oscLineWidth         = n.doubleValue();
        if (root.get("oscDotDiameter")               instanceof Number n) oscDotDiameter       = n.intValue();
        if (root.get("oscLeftChannelColor")          instanceof Number n) oscLeftChannelColor  = n.intValue();
        if (root.get("oscRightChannelColor")         instanceof Number n) oscRightChannelColor = n.intValue();
        if (root.get("screenshotWidth")              instanceof Number n) screenshotWidth      = n.intValue();
        if (root.get("screenshotHeight")             instanceof Number n) screenshotHeight     = n.intValue();
        if (root.get("screenshotFolder")             instanceof String s) screenshotFolder     = s;

        if (root.get("perBackend") instanceof Map<?, ?> pbm) {
            for (Map.Entry<?, ?> e : pbm.entrySet()) {
                if (!(e.getKey() instanceof String key)) continue;
                AudioBackendType type = enumOr(AudioBackendType.class, key, null);
                if (type == null) continue;
                BackendPrefs bp = prefsFor(type);
                if (e.getValue() instanceof Map<?, ?> bpMap) {
                    if (bpMap.get("inputDeviceName")  instanceof String  s) bp.inputDeviceName  = s;
                    if (bpMap.get("outputDeviceName") instanceof String  s) bp.outputDeviceName = s;
                    if (bpMap.get("inputSampleRate")  instanceof Integer i) bp.inputSampleRate  = i;
                    if (bpMap.get("inputBitDepth")    instanceof Integer i) bp.inputBitDepth    = i;
                    if (bpMap.get("outputSampleRate") instanceof Integer i) bp.outputSampleRate = i;
                    if (bpMap.get("outputBitDepth")   instanceof Integer i) bp.outputBitDepth   = i;
                }
            }
        }
    }

    private <E extends Enum<E>> E enumOr(Class<E> type, String name, E fallback) {
        try {
            return Enum.valueOf(type, name);
        } catch (IllegalArgumentException e) {
            return fallback;
        }
    }

    private List<Integer> intArrayToList(int[] arr) {
        List<Integer> out = new ArrayList<>(arr.length);
        for (int v : arr) out.add(v);
        return out;
    }

    /** SnakeYAML deserialises a YAML list as {@code List<Object>}; pull out integer
     *  entries and copy them into an {@code int[]}, returning {@code null} when
     *  the list is empty or contains nothing usable. */
    private int[] listToIntArray(List<?> list) {
        if (list == null || list.isEmpty()) return null;
        int[] out = new int[list.size()];
        for (int i = 0; i < list.size(); i++) {
            Object v = list.get(i);
            if (v instanceof Integer iv) out[i] = iv;
            else if (v instanceof Number n) out[i] = n.intValue();
        }
        return out;
    }

    /**
     * Per-backend settings.  Devices are stored by name (string) — the
     * concrete {@code DeviceRef} is re-resolved at dialog open time by
     * matching the saved name against the live device list.
     */
    @Getter
    @Setter
    public class BackendPrefs {
        private String inputDeviceName;
        private String outputDeviceName;
        private int    inputSampleRate  = 384000;
        private int    inputBitDepth    = 24;
        private int    outputSampleRate = 384000;
        private int    outputBitDepth   = 24;

        /** Copies all fields from {@code src} into this instance. */
        public void copyFrom(BackendPrefs src) {
            this.inputDeviceName  = src.inputDeviceName;
            this.outputDeviceName = src.outputDeviceName;
            this.inputSampleRate  = src.inputSampleRate;
            this.inputBitDepth    = src.inputBitDepth;
            this.outputSampleRate = src.outputSampleRate;
            this.outputBitDepth   = src.outputBitDepth;
        }

        /** Returns a snapshot of this instance suitable for later restoration via {@link #copyFrom}. */
        public BackendPrefs snapshot() {
            BackendPrefs copy = new BackendPrefs();
            copy.copyFrom(this);
            return copy;
        }
    }
}
