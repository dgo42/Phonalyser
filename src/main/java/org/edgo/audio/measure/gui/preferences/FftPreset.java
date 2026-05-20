package org.edgo.audio.measure.gui.preferences;

import lombok.Getter;
import lombok.Setter;
import org.edgo.audio.measure.enums.Channel;

/**
 * Snapshot of every FFT-view control whose value is part of a saved
 * preset: chart range (freqMin/Max, magTop/Bottom, magnitude unit,
 * phase visibility, log/lin), every FFT-tab field, and every THD-tab
 * field.  Field names mirror the top-level {@code fftXxx} fields on
 * {@link Preferences} so apply/capture in {@code FftPane} is a straight
 * assignment per field.
 */
@Getter
@Setter
public class FftPreset {
    private Channel channel               = Channel.L;
    private String  magUnit                      = "DBV";
    private boolean logFreqAxis                  = true;
    private double  freqMinHz                    = 20;
    private double  freqMaxHz                    = 20000;
    private double  magTop                       = 10;
    private double  magBottom                    = -150;
    // Settings tab
    private int     fftLength                    = 65536;
    private double  averages                     = 4;
    private boolean stopAfterNEnabled            = false;
    private int     stopAfterN                   = 10;
    private boolean fundFromGenerator            = false;
    private String  window                       = "HANN";
    private String  overlap                      = "PCT_0";
    private boolean coherentAveraging            = true;
    // THD tab
    private double  distMinHz                    = 20;
    private double  distMaxHz                    = 20000;
    private boolean distMinEnabled               = false;
    private boolean distMaxEnabled               = false;
    private int     thdMaxHarmonic               = 9;
    private int     calcMaxHarmonic              = 9;
    private double  manualFundVrms               = 1.0;
    private String  manualFundUnit               = "V";
    private boolean manualFundEnabled            = false;
}
