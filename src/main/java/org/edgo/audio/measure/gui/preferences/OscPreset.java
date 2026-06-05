package org.edgo.audio.measure.gui.preferences;

import lombok.Data;
import org.edgo.audio.measure.enums.Channel;
import org.edgo.audio.measure.enums.LpfMode;
import org.edgo.audio.measure.enums.MainsSuppression;
import org.edgo.audio.measure.enums.TriggerEdge;
import org.edgo.audio.measure.enums.TriggerMode;

/**
 * Snapshot of every oscilloscope control whose value is part of a
 * saved preset: both channels (V/div, offset, AC, sinc, enabled),
 * the horizontal scale + trigger position, and the trigger settings
 * + level.  Field types mirror the corresponding top-level
 * {@link Preferences} fields one-for-one so apply/capture in
 * {@code OscilloscopePane} is a straight assignment per field.
 */
@Data
public class OscPreset {
    private boolean leftChannelEnabled       = true;
    private boolean rightChannelEnabled      = true;
    private boolean leftAcMode               = false;
    private boolean rightAcMode              = false;
    private boolean leftSincInterpEnabled    = true;
    private boolean rightSincInterpEnabled   = true;
    private MainsSuppression leftMainsSuppression  = MainsSuppression.NONE;
    private MainsSuppression rightMainsSuppression = MainsSuppression.NONE;
    private LpfMode          leftLpf               = LpfMode.NONE;
    private LpfMode          rightLpf              = LpfMode.NONE;
    private double  leftVoltsPerDiv          = 0.1;
    private double  rightVoltsPerDiv         = 0.1;
    private double  leftOffsetFrac           = 0.5;
    private double  rightOffsetFrac          = 0.5;
    private double  timePerDiv               = 1e-3;
    private double  triggerPositionFrac      = 0.5;
    private Channel triggerChannel    = Channel.L;
    private TriggerEdge    triggerEdge       = TriggerEdge.RISE;
    private TriggerMode    triggerMode       = TriggerMode.AUTO;
    private double  triggerLevelFrac         = 0.5;
}
