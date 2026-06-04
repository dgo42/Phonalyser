package org.edgo.audio.measure.gui.fft;

import org.edgo.audio.measure.enums.AlignGenerator;

/**
 * Thread-safe singleton factory for {@link FrequencyAligner}s: maps an
 * {@link AlignGenerator} mode to a fresh aligner instance.  Access via
 * {@link #instance()}.  Double-checked locking matches the rest of the GUI's
 * singletons (e.g. {@code MessageBus}, {@code Preferences}).
 */
public final class FrequencyAlignerFactory {

    private static volatile FrequencyAlignerFactory instance;

    private FrequencyAlignerFactory() {}

    public static FrequencyAlignerFactory instance() {
        FrequencyAlignerFactory local = instance;
        if (local != null) {
            return local;
        }
        synchronized (FrequencyAlignerFactory.class) {
            if (instance == null) {
                instance = new FrequencyAlignerFactory();
            }
            return instance;
        }
    }

    /** A fresh aligner for {@code mode}, or {@code null} for {@link AlignGenerator#NONE}. */
    public FrequencyAligner create(AlignGenerator mode) {
        return switch (mode) {
            case PID  -> new FrequencyPid();
            case FLL  -> new FrequencyFll();
            case NONE -> null;
        };
    }
}
