package org.edgo.audio.measure.fft;

import org.edgo.audio.measure.enums.WindowType;
import org.junit.jupiter.api.Test;

/** Temporary probe: what the election makes of the bench geometry. */
class PilotElectionProbeTest {

    @Test
    void printTheElection() {
        int    fftSize = 1 << 21;
        double fs      = 384000.0;
        for (int k0 : new int[] { 5477, 5478, 5484, 5486 }) {
            PilotToneSelector.PilotPair p = new PilotToneSelector(fs, fs,
                    fftSize, new int[] { k0 }, 9, WindowType.BH7, 20000.0).selectPair();
            System.out.printf("k0=%d -> bin1=%d bin2=%d  spacing=%d  reason=%s%n",
                    k0, p.bin1(), p.bin2(), p.bin1() - p.bin2(), p.reason());
        }
    }
}
