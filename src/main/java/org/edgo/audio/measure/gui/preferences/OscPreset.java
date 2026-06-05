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
