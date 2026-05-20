package org.edgo.audio.measure.cli;

import lombok.experimental.UtilityClass;

@UtilityClass
public class SampleRates {

    public static final int[] VALID =
            {44100, 48000, 88200, 96000, 176400, 192000, 352800, 384000, 705600, 768000};

    public boolean isValid(int rate) {
        for (int r : VALID) {
            if (r == rate) {
                return true;
            }
        }
        return false;
    }
}
