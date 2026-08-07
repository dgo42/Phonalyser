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

import org.edgo.audio.measure.enums.AudioBackendType;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The backend-selection key grammar: the string form a preferences file stores,
 * and the two different questions a selection answers (what the bench IS, and
 * what the dispatcher must be handed to REACH it).
 *
 * <p>The legacy case is the load-bearing one - every preferences.yaml written
 * before remote benches existed holds a bare enum name, and it must keep meaning
 * exactly what it always meant.
 */
class BackendKeyTest {

    private static final String SERVER_ID  = "b7e0f3c2-9a44-4d51-8f2e-0c1d2e3f4a5b";
    private static final String REMOTE_KEY = "net:" + SERVER_ID + ":QA40X";

    @Test
    void localKeyIsTheBareEnumName() {
        BackendKey local = BackendKey.of(AudioBackendType.WASAPI);
        assertEquals("WASAPI", local.key());
        assertFalse(local.remote());
        assertNull(local.serverId());
        assertEquals(AudioBackendType.WASAPI, local.type());
        assertEquals(AudioBackendType.WASAPI, local.carrier(),
                "a local backend is reached as itself");
    }

    @Test
    void remoteKeyNamesTheServerAndTheBackend() {
        BackendKey remote = BackendKey.of(SERVER_ID, AudioBackendType.QA40X);
        assertEquals(REMOTE_KEY, remote.key());
        assertTrue(remote.remote());
        assertEquals(SERVER_ID, remote.serverId());
        assertEquals(AudioBackendType.QA40X, remote.type(),
                "what the bench IS - what a backend-semantic rule keys on");
        assertEquals(AudioBackendType.NET, remote.carrier(),
                "what the dispatcher is handed to reach it");
    }

    @Test
    void keyRoundTripsThroughParse() {
        assertEquals(BackendKey.of(AudioBackendType.QA40X),
                BackendKey.parse(BackendKey.of(AudioBackendType.QA40X).key()));
        assertEquals(BackendKey.of(SERVER_ID, AudioBackendType.QA40X),
                BackendKey.parse(REMOTE_KEY));
    }

    @Test
    void legacyPlainEnumNameParsesAsTheLocalBackendItAlwaysWas() {
        // A preferences.yaml from before the net bridge: "backend: WDMKS".
        BackendKey legacy = BackendKey.parse("WDMKS");
        assertEquals(BackendKey.of(AudioBackendType.WDMKS), legacy);
        assertEquals("WDMKS", legacy.key(), "and it is written back unchanged");
    }

    @Test
    void twoServersSameBackendAreTwoDifferentKeys() {
        // The whole reason the server rides in the key: one entry per bench.
        assertFalse(BackendKey.of("bench-a", AudioBackendType.QA40X)
                .key().equals(BackendKey.of("bench-b", AudioBackendType.QA40X).key()));
    }

    @Test
    void aServerIdThatWouldNotSurviveTheKeyIsRefusedWhereItIsBuilt() {
        // The id comes off the beacon and out of the server's own text file, so
        // it is not this application's to trust.  A colon moves the split point
        // ("net:a:b:QA40X" parses its type out of "b:QA40X" and fails), an empty
        // one loses the field altogether - either way the selection becomes
        // unreadable, which is a null backend in the live session, not a
        // preference that merely looks odd.
        assertThrows(IllegalArgumentException.class,
                () -> BackendKey.of("a:b", AudioBackendType.QA40X));
        assertThrows(IllegalArgumentException.class,
                () -> BackendKey.of("", AudioBackendType.QA40X));
    }

    @Test
    void aCarrierCanNeverBeWhatASelectionIs() {
        // The dual-level rule: a selection is (how it is reached; what it IS),
        // and a dual-level carrier is only ever the first.  A key whose type
        // said NET would be the identity lie the two accessors exist to end -
        // refused where it is built, unreadable where it is parsed.
        assertThrows(IllegalArgumentException.class,
                () -> BackendKey.of(AudioBackendType.NET));
        assertThrows(IllegalArgumentException.class,
                () -> BackendKey.of(SERVER_ID, AudioBackendType.NET));
        assertNull(BackendKey.parse(AudioBackendType.NET.name()),
                "a hand-edited 'backend: NET' line is as unreadable as an unknown name");
        assertNull(BackendKey.parse("net:" + SERVER_ID + ":NET"));
    }

    @Test
    void aRemoteSelectionIsNotJudgedByItsCarriersAvailability() {
        // The trap the launch-time sanitiser fell into: NET.isAvailable() is
        // false BY DESIGN (it owns no local hardware), so a remote bench asked
        // through its carrier answers "not available on this OS" - and gets its
        // saved server key overwritten with a local backend.  What must be asked
        // is whether the SELECTION is remote at all.
        BackendKey remote = BackendKey.of(SERVER_ID, AudioBackendType.QA40X);
        assertTrue(remote.remote());
        assertFalse(remote.carrier().isAvailable(),
                "the carrier is never the question to ask about a saved selection");
    }

    @Test
    void anUnreadableKeyIsRefused() {
        assertNull(BackendKey.parse(null));
        assertNull(BackendKey.parse("SOMETHING_ELSE"), "a backend this build has no enum for");
        assertNull(BackendKey.parse("net:" + SERVER_ID), "a remote key missing its backend");
        assertNull(BackendKey.parse("net::QA40X"), "a remote key missing its server");
        assertNull(BackendKey.parse("net:" + SERVER_ID + ":NOT_A_BACKEND"));
    }
}
