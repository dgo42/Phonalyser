/*
 * Phonalyser - precision audio measurement workbench.
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

package org.edgo.audio.measure.preferences;

import java.lang.reflect.Method;
import java.util.Map;

import org.edgo.audio.measure.enums.OutputChannels;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The three output-channel selector preferences - {@code genOutputChannels},
 * {@code freqRespOutputChannels} and {@code tuneNotchOutputChannels}: each
 * defaults to {@link OutputChannels#BOTH}, persists by enum {@code name()} and
 * round-trips through {@code toMap}/{@code fromMap}; an absent key keeps the
 * default (old files stay on {@code BOTH}).
 *
 * <p>Detached, transient copy so the live singleton's store is untouched;
 * {@code toMap}/{@code fromMap} are the private serialisation seam, invoked by
 * reflection.
 */
class OutputChannelsPrefsTest {

    private Preferences detached() {
        Preferences p = Preferences.instance().copyForDialog();
        p.setTransientMode(true);
        return p;
    }

    private Map<String, Object> toMap(Preferences p) {
        return invoke(p, "toMap", null, null);
    }

    private void fromMap(Preferences p, Map<?, ?> root) {
        invoke(p, "fromMap", Map.class, root);
    }

    @SuppressWarnings("unchecked")
    private <T> T invoke(Preferences p, String name, Class<?> argType, Object arg) {
        try {
            Method m = argType == null
                    ? Preferences.class.getDeclaredMethod(name)
                    : Preferences.class.getDeclaredMethod(name, argType);
            m.setAccessible(true);
            return (T) (argType == null ? m.invoke(p) : m.invoke(p, arg));
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(name + " invocation failed", e);
        }
    }

    @Test
    void defaults_areBoth() {
        Preferences p = detached();
        assertEquals(OutputChannels.BOTH, p.getGenOutputChannels());
        assertEquals(OutputChannels.BOTH, p.getFreqRespOutputChannels());
        assertEquals(OutputChannels.BOTH, p.getTuneNotchOutputChannels());
    }

    @Test
    void allThreeSelectors_roundTrip() {
        Preferences src = detached();
        src.setGenOutputChannels(OutputChannels.LEFT);
        src.setFreqRespOutputChannels(OutputChannels.RIGHT);
        src.setTuneNotchOutputChannels(OutputChannels.LEFT);

        Map<String, Object> root = toMap(src);
        assertEquals("LEFT",  root.get("genOutputChannels"),       "persisted by enum name");
        assertEquals("RIGHT", root.get("freqRespOutputChannels"),  "persisted by enum name");
        assertEquals("LEFT",  root.get("tuneNotchOutputChannels"), "persisted by enum name");

        Preferences dst = detached();
        fromMap(dst, root);
        assertEquals(OutputChannels.LEFT,  dst.getGenOutputChannels());
        assertEquals(OutputChannels.RIGHT, dst.getFreqRespOutputChannels());
        assertEquals(OutputChannels.LEFT,  dst.getTuneNotchOutputChannels());
    }

    @Test
    void absentKeys_keepDefaultBoth() {
        Preferences src = detached();
        src.setGenOutputChannels(OutputChannels.RIGHT);
        Map<String, Object> root = toMap(src);
        root.remove("genOutputChannels");
        root.remove("freqRespOutputChannels");
        root.remove("tuneNotchOutputChannels");

        Preferences dst = detached();   // starts at the BOTH defaults
        fromMap(dst, root);
        assertEquals(OutputChannels.BOTH, dst.getGenOutputChannels(),
                "an absent key leaves the default (old files stay on BOTH)");
        assertEquals(OutputChannels.BOTH, dst.getFreqRespOutputChannels());
        assertEquals(OutputChannels.BOTH, dst.getTuneNotchOutputChannels());
    }
}
