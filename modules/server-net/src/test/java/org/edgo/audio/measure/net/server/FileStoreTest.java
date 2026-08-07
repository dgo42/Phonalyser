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

package org.edgo.audio.measure.net.server;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The uploaded-file ownership of spec 3: "owned by nobody until referenced by
 * {@code gen.playFile}, and dropped when the referencing connection closes or on
 * {@code DELETE /files/{id}}".
 *
 * <p>Every sentence of that has teeth.  An upload arrives over HTTP, which
 * carries no session, so nothing may be dropped just because some connection
 * ended.  And a file a connection DID play has to go when that connection does,
 * or a client that vanished mid-measurement leaves 50 MB of the operator's audio
 * in the server's heap for the life of the process, with no handle left anywhere
 * to delete it by.
 */
class FileStoreTest {

    private static final byte[] CONTENT = {1, 2, 3, 4};
    private static final String UNKNOWN_ID = "f-404";

    private final FileStore files = new FileStore();
    /** Stand-ins for two connections' generators - the store only ever compares
     *  owners by identity. */
    private final Object firstOwner = new Object();
    private final Object secondOwner = new Object();

    @Test
    void anUploadedFileIsKeptUnderTheHandleItAnswers() {
        String fileId = files.put(CONTENT);

        assertNotNull(fileId);
        assertArrayEquals(CONTENT, files.get(fileId));
    }

    @Test
    void theDeclaredTypeIsKeptBesideTheBytesAndGoesWithThem() {
        String typed = files.put(CONTENT, "audio/flac");
        String untyped = files.put(CONTENT);

        assertEquals("audio/flac", files.typeOf(typed),
                "the generator stages by what the client said the file IS");
        assertNull(files.typeOf(untyped),
                "and an upload that declared none leaves the sniff to decide");

        files.remove(typed);
        assertNull(files.typeOf(typed),
                "the type is part of the entry, so it leaves with the bytes");
    }

    @Test
    void aBlankTypeIsTheSameAsNone() {
        assertNull(files.typeOf(files.put(CONTENT, "   ")),
                "whitespace is not a declaration");
    }

    @Test
    void aFileNobodyClaimedSurvivesEveryTeardown() {
        String fileId = files.put(CONTENT);

        assertEquals(0, files.releaseAll(firstOwner));

        assertNotNull(files.get(fileId),
                "spec 3: an upload is owned by NOBODY until gen.playFile references "
                        + "it - a client may upload on one connection and play on "
                        + "another");
    }

    @Test
    void aClaimedFileGoesWithTheConnectionThatClaimedIt() {
        String fileId = files.put(CONTENT);
        assertTrue(files.claim(fileId, firstOwner));

        assertEquals(1, files.releaseAll(firstOwner));

        assertNull(files.get(fileId));
    }

    @Test
    void aFileTwoConnectionsPlayGoesWithTheLastOfThem() {
        String fileId = files.put(CONTENT);
        files.claim(fileId, firstOwner);
        files.claim(fileId, secondOwner);

        assertEquals(0, files.releaseAll(firstOwner));
        assertNotNull(files.get(fileId),
                "the other connection is still playing it");

        assertEquals(1, files.releaseAll(secondOwner));
        assertNull(files.get(fileId));
    }

    @Test
    void claimingTwiceIsNotOwningTwice() {
        String fileId = files.put(CONTENT);
        files.claim(fileId, firstOwner);
        files.claim(fileId, firstOwner);

        assertEquals(1, files.releaseAll(firstOwner));

        assertNull(files.get(fileId), "one connection, one claim, one drop");
    }

    @Test
    void anUnknownHandleCannotBeClaimed() {
        assertFalse(files.claim(UNKNOWN_ID, firstOwner),
                "the caller answers NO_SUCH_FILE rather than owning a file that is "
                        + "not there");
    }

    @Test
    void anUploadPastTheStoreWideBoundIsRefusedAndRoomComesBackWhenAFileGoes() {
        FileStore small = new FileStore(CONTENT.length * 2L);
        assertNotNull(small.put(CONTENT));
        String second = small.put(CONTENT);
        assertNotNull(second);

        assertNull(small.put(CONTENT),
                "spec 3 caps ONE upload and the plane has no authentication, so "
                        + "without a bound on the whole store any LAN peer could put "
                        + "50 MB after 50 MB until the server's heap was gone");

        small.remove(second);
        assertNotNull(small.put(CONTENT), "the room a dropped file held comes back");
    }

    @Test
    void deleteDropsTheFileWhoeverClaimedIt() {
        String fileId = files.put(CONTENT);
        files.claim(fileId, firstOwner);

        assertTrue(files.remove(fileId));

        assertNull(files.get(fileId));
        assertEquals(0, files.releaseAll(firstOwner),
                "and the ownership went with it, so a later teardown finds nothing");
        assertFalse(files.remove(UNKNOWN_ID));
    }
}
